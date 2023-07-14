/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.sql.core;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.cloud.sql.AuthType;
import com.google.cloud.sql.CredentialFactory;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import dev.failsafe.RateLimiter;
import java.io.IOException;
import java.security.KeyPair;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.net.ssl.SSLSocket;

/**
 * This class manages information on and creates connections to a Cloud SQL instance using the Cloud
 * SQL Admin API. The operations to retrieve information with the API are largely done
 * asynchronously, and this class should be considered threadsafe.
 */
class CloudSqlInstance {

  private static final Logger logger = Logger.getLogger(CloudSqlInstance.class.getName());

  private final ListeningScheduledExecutorService executor;
  private final InstanceDataSupplier instanceDataSupplier;
  private final AuthType authType;
  private final AccessTokenSupplier accessTokenSupplier;
  private final CloudSqlInstanceName instanceName;
  private final ListenableFuture<KeyPair> keyPair;
  private final Object instanceDataGuard = new Object();
  private final Object performRefreshGuard = new Object();

  private final RefreshCalculator refreshCalculator = new RefreshCalculator();
  @GuardedBy("instanceDataGuard")
  private volatile boolean refreshing;

  @GuardedBy("instanceDataGuard")
  private InstanceData currentInstanceData;
  @GuardedBy("instanceDataGuard")
  private Throwable lastThrowable;

  /**
   * Initializes a new Cloud SQL instance based on the given connection name.
   *
   * @param connectionName instance connection name in the format
   *     "PROJECT_ID:REGION_ID:INSTANCE_ID"
   * @param instanceDataSupplier Service class for interacting with the Cloud SQL Admin API
   * @param executor executor used to schedule asynchronous tasks
   * @param keyPair public/private key pair used to authenticate connections
   */
  CloudSqlInstance(
      String connectionName,
      InstanceDataSupplier instanceDataSupplier,
      AuthType authType,
      CredentialFactory tokenSourceFactory,
      ListeningScheduledExecutorService executor,
      ListenableFuture<KeyPair> keyPair) {
    this.instanceName = new CloudSqlInstanceName(connectionName);
    this.instanceDataSupplier = instanceDataSupplier;
    this.authType = authType;
    this.executor = executor;
    this.keyPair = keyPair;

    if (authType == AuthType.IAM) {
      HttpRequestInitializer source = tokenSourceFactory.create();
      this.accessTokenSupplier = new DefaultAccessTokenSupplier(source);
    } else {
      this.accessTokenSupplier = Optional::empty;
    }

    this.scheduleNextRefresh(null, null, Instant.now());
  }

  /**
   * Returns the current data related to the instance from {@link #performRefresh(boolean)}. May
   * block if no valid data is currently available.
   */
  private InstanceData getInstanceData() {
    logger.fine("Getting instance data...");
    try {
      Instant getDataTimeout = Instant.now().plus(10, ChronoUnit.SECONDS);
      synchronized (performRefreshGuard) {
        while ((refreshing == true || !isInstanceDataValid()) && Instant.now()
            .isBefore(getDataTimeout)) {
          performRefreshGuard.wait(1000);
        }
        // wait for any in-progress refresh to complete
        logger.fine("At least one refresh has comleted");
      }
    } catch (InterruptedException e) {
      // Safely ignore the interrupted exception. If this was interrupted, then
      // continue, using the current state of instance data.
    }

    synchronized (instanceDataGuard) {
      if (this.currentInstanceData == null) {
        // if recent requests have failed...
        if (this.lastThrowable != null) {
          String msg = String.format(
              "Certificate refresh failed for %s.",
              instanceName.toString());
          logger.log(Level.WARNING, msg, this.lastThrowable);
          throw new RuntimeException(msg, this.lastThrowable);
        }

        // Otherwise, it was never set.
        String msg = String.format(
            "Certificate refresh for %s has never completed.",
            instanceName.toString());
        logger.warning(msg);
        throw new RuntimeException(msg);
      }

      // if the data has expired
      if (Instant.now().isAfter(this.currentInstanceData.getExpiration().toInstant())) {
        String msg = String.format(
            "Auth token for %s expired before at %s before it could be refreshed.",
            instanceName.toString(), this.currentInstanceData.getExpiration().toString());
        logger.warning(msg);
        throw new RuntimeException(msg);
      }

      // the current data is OK
      return this.currentInstanceData;
    }
  }

  /**
   * Returns an unconnected {@link SSLSocket} using the SSLContext associated with the instance. May
   * block until required instance data is available.
   */
  SSLSocket createSslSocket() throws IOException {
    return (SSLSocket) getInstanceData().getSslContext().getSocketFactory().createSocket();
  }

  /**
   * Returns the first IP address for the instance, in order of the preference supplied by
   * preferredTypes.
   *
   * @param preferredTypes Preferred instance IP types to use. Valid IP types include "Public"
   *     and "Private".
   * @return returns a string representing the IP address for the instance
   * @throws IllegalArgumentException If the instance has no IP addresses matching the provided
   *     preferences.
   */
  String getPreferredIp(List<String> preferredTypes) {
    Map<String, String> ipAddrs = getInstanceData().getIpAddrs();
    for (String ipType : preferredTypes) {
      String preferredIp = ipAddrs.get(ipType);
      if (preferredIp != null) {
        return preferredIp;
      }
    }
    throw new IllegalArgumentException(
        String.format(
            "[%s] Cloud SQL instance  does not have any IP addresses matching preferences (%s)",
            instanceName.getConnectionName(), String.join(", ", preferredTypes)));
  }

  /**
   * Attempts to force a new refresh of the instance data. May fail if called too frequently or if a
   * new refresh is already in progress. If successful, other methods will block until refresh has
   * been completed.
   */
  void forceRefresh() {
    submitPerformRefresh(true);
  }


  private void scheduleNextRefresh(InstanceData data, Throwable error, Instant nextCycle) {
    logger.fine(String.format("Scheduling refresh for %s at %s", this.instanceName, nextCycle));
    synchronized (instanceDataGuard) {
      // Always set/clear the lastThrowable error
      this.lastThrowable = error;

      // only update currentInstanceData on success
      if (error == null) {
        this.currentInstanceData = data;
      }

      // schedule a task that will submitPerformRefresh() in the future
      if (Instant.now().isBefore(nextCycle)) {
        Duration d = Duration.between(Instant.now(), nextCycle);
        long inMillis = d.toMillis();
        ListenableScheduledFuture<?> t = executor.schedule(
            () -> this.submitPerformRefresh(false), inMillis,
            TimeUnit.MILLISECONDS);
      } else {
        submitPerformRefresh(false);
      }
    }
    logger.fine(
        String.format("Finished scheduling refresh for %s at %s", this.instanceName, nextCycle));
  }

  private void submitPerformRefresh(boolean force) {
    synchronized (instanceDataGuard) {
      if (force || this.isRefreshRequired()) {
        ListenableFuture<InstanceData> t = executor.submit(() -> performRefresh(force));
      }
    }
  }

  private boolean isInstanceDataValid() {
    synchronized (instanceDataGuard) {
      // when there is no valid data
      return this.currentInstanceData != null &&
          Instant.now().isBefore(this.currentInstanceData.getExpiration().toInstant());
    }

  }

  private boolean isRefreshRequired() {
    synchronized (instanceDataGuard) {
      // when there is no valid data
      if (this.currentInstanceData == null) {
        return true;
      }

      // when the certificate or access token is about to expire
      if (refreshCalculator.needsRefresh(Instant.now(),
          this.currentInstanceData.getExpiration().toInstant())) {
        return true;
      }

      return false;
    }
  }

  /**
   * Triggers an update of internal information obtained from the Cloud SQL Admin API. Replaces the
   * value of currentInstanceData and schedules the next refresh shortly before the information
   * would expire.
   */
  private InstanceData performRefresh(boolean force)
      throws InterruptedException, ExecutionException {
    logger.fine("Refresh Operation: Attempting refresh...");

    synchronized (performRefreshGuard) {
      // if this is not forced refresh, and currentInstanceData is set, and currentInstanceData doesn't
      // need to be refreshed, then balk and do nothing.
      synchronized (instanceDataGuard) {
        this.refreshing = true;
        if (!force && !isRefreshRequired()) {
          logger.fine(
              "Refresh Operation: Ignoring refresh attempt, token refresh is not required.");
          return this.currentInstanceData;
        }
      }

      logger.fine("Refresh Operation: Acquiring rate limiter permit.");
      // To avoid unreasonable SQL Admin API usage, use a rate limit to throttle our usage.
      // forcedRenewRateLimiter.acquirePermit();
      logger.fine("Refresh Operation: Acquired rate limiter permit. Starting refresh...");

      try {
        InstanceData data =
            instanceDataSupplier.getInstanceData(
                this.instanceName, this.accessTokenSupplier, this.authType, executor, keyPair);

        logger.fine(
            String.format(
                "Refresh Operation: Completed refresh with new certificate expiration at %s.",
                data.getExpiration().toInstant().toString()));
        long secondsToRefresh =
            refreshCalculator.calculateSecondsUntilNextRefresh(
                Instant.now(), data.getExpiration().toInstant());

        logger.fine(
            String.format(
                "Refresh Operation: Next operation scheduled at %s.",
                Instant.now()
                    .plus(secondsToRefresh, ChronoUnit.SECONDS)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .toString()));
        scheduleNextRefresh(data, null, Instant.now().plus(secondsToRefresh, ChronoUnit.SECONDS));
        return data;
      } catch (Exception e) {
        logger.log(
            Level.FINE, "Refresh Operation: Failed! Starting next refresh operation immediately.",
            e);
        scheduleNextRefresh(null, e, Instant.now());
        throw e;
      } finally {
        synchronized (instanceDataGuard) {
          refreshing = false;
        }
        performRefreshGuard.notifyAll();
      }
    }

  }

  SslData getSslData() {
    return getInstanceData().getSslData();
  }
}
