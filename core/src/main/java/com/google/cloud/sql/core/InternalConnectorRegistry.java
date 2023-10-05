/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.sql.core;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.cloud.sql.ConnectionConfig;
import com.google.cloud.sql.CredentialFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.logging.Logger;

/**
 * InternalConnectorRegistry keeps track of connectors. This class should not be used directly, but
 * only through the JDBC driver specific {@code SocketFactory} implementations.
 *
 * <p>WARNING: This is an internal class. The API is subject to change without notice.
 */
public final class InternalConnectorRegistry {

  static final long DEFAULT_MAX_REFRESH_MS = 30000;
  private static final Logger logger = Logger.getLogger(InternalConnectorRegistry.class.getName());

  static final int DEFAULT_SERVER_PROXY_PORT = 3307;
  private static final int RSA_KEY_SIZE = 2048;
  private static final List<String> userAgents = new ArrayList<>();
  private static final String version = getVersion();
  private static final long MIN_REFRESH_DELAY_MS = 30000; // Minimum 30 seconds between refresh.
  private static InternalConnectorRegistry internalConnectorRegistry;
  private final ListenableFuture<KeyPair> localKeyPair;
  private final ConcurrentHashMap<ConnectorKey, Connector> connectors = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ConnectionConfig> namedConfigs =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Connector> namedConnectors = new ConcurrentHashMap<>();
  private final ListeningScheduledExecutorService executor;
  private final CredentialFactory credentialFactory;
  private final int serverProxyPort;
  private final long refreshTimeoutMs;
  private final ConnectionInfoRepositoryFactory connectionInfoRepositoryFactory;

  /**
   * Property used to set the application name for the underlying SQLAdmin client.
   *
   * @deprecated Use {@link #setApplicationName(String)} to set the application name
   *     programmatically.
   */
  static final String USER_TOKEN_PROPERTY_NAME = "_CLOUD_SQL_USER_TOKEN";

  @VisibleForTesting
  InternalConnectorRegistry(
      ListenableFuture<KeyPair> localKeyPair,
      ConnectionInfoRepositoryFactory connectionInfoRepositoryFactory,
      CredentialFactory credentialFactory,
      int serverProxyPort,
      long refreshTimeoutMs,
      ListeningScheduledExecutorService executor) {
    this.connectionInfoRepositoryFactory = connectionInfoRepositoryFactory;
    this.credentialFactory = credentialFactory;
    this.serverProxyPort = serverProxyPort;
    this.executor = executor;
    this.localKeyPair = localKeyPair;
    this.refreshTimeoutMs = refreshTimeoutMs;
  }

  /** Returns the {@link InternalConnectorRegistry} singleton. */
  public static synchronized InternalConnectorRegistry getInstance() {
    if (internalConnectorRegistry == null) {
      logger.info("First Cloud SQL connection, generating RSA key pair.");

      CredentialFactory credentialFactory = CredentialFactoryProvider.getCredentialFactory();

      ListeningScheduledExecutorService executor = getDefaultExecutor();

      internalConnectorRegistry =
          new InternalConnectorRegistry(
              executor.submit(InternalConnectorRegistry::generateRsaKeyPair),
              new DefaultConnectionInfoRepositoryFactory(getUserAgents()),
              credentialFactory,
              DEFAULT_SERVER_PROXY_PORT,
              InternalConnectorRegistry.DEFAULT_MAX_REFRESH_MS,
              executor);
    }
    return internalConnectorRegistry;
  }

  // TODO(kvg): Figure out better executor to use for testing
  @VisibleForTesting
  // Returns a listenable, scheduled executor that exits upon shutdown.
  static ListeningScheduledExecutorService getDefaultExecutor() {

    // During refresh, each instance consumes 2 threads from the thread pool. By using 8 threads,
    // there should be enough free threads so that there will not be a deadlock. Most users
    // configure 3 or fewer instances, requiring 6 threads during refresh. By setting
    // this to 8, it's enough threads for most users, plus a safety factor of 2.

    ScheduledThreadPoolExecutor executor =
        (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(8);

    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return MoreExecutors.listeningDecorator(
        MoreExecutors.getExitingScheduledExecutorService(executor));
  }

  /**
   * Creates a socket representing a connection to a Cloud SQL instance.
   *
   * <p>Depending on the given properties, it may return either a SSL Socket or a Unix Socket.
   *
   * @param config used to configure the connection.
   * @return the newly created Socket.
   * @throws IOException if error occurs during socket creation.
   */
  public Socket connect(ConnectionConfig config) throws IOException, InterruptedException {
    config = resolveConfig(config);

    // Validate parameters
    Preconditions.checkArgument(
        config.getCloudSqlInstance() != null,
        "cloudSqlInstance property not set. Please specify this property in the JDBC URL or the "
            + "connection Properties with value in form \"project:region:instance\"");

    return getConnector(config).connect(config);
  }

  public ConnectionMetadata getConnectionMetadata(ConnectionConfig config) throws IOException {
    config = resolveConfig(config);
    return getConnector(config).getConnection(config).getConnectionMetadata(refreshTimeoutMs);
  }

  private static KeyPair generateRsaKeyPair() {
    KeyPairGenerator generator;
    try {
      generator = KeyPairGenerator.getInstance("RSA");
    } catch (NoSuchAlgorithmException err) {
      throw new RuntimeException(
          "Unable to initialize Cloud SQL socket factory because no RSA implementation is "
              + "available.");
    }
    generator.initialize(RSA_KEY_SIZE);
    return generator.generateKeyPair();
  }

  private static String getVersion() {
    try {
      Properties packageInfo = new Properties();
      packageInfo.load(
          InternalConnectorRegistry.class
              .getClassLoader()
              .getResourceAsStream("com.google.cloud.sql/project.properties"));
      return packageInfo.getProperty("version", "unknown");
    } catch (IOException e) {
      return "unknown";
    }
  }

  /**
   * Internal use only: Sets the default string which is appended to the SQLAdmin API client
   * User-Agent header.
   *
   * <p>This is used by the specific database connector socket factory implementations to append
   * their database name to the user agent.
   */
  public static void addArtifactId(String artifactId) {
    String userAgent = artifactId + "/" + version;
    if (!userAgents.contains(userAgent)) {
      userAgents.add(userAgent);
    }
  }

  /** Resets the values of User Agent fields for unit tests. */
  @VisibleForTesting
  static void resetUserAgent() {
    internalConnectorRegistry = null;
    userAgents.clear();
    setApplicationName("");
  }

  /** Returns the default string which is appended to the SQLAdmin API client User-Agent header. */
  static String getUserAgents() {
    String ua = String.join(" ", userAgents);
    String appName = getApplicationName();
    if (!Strings.isNullOrEmpty(appName)) {
      ua = ua + " " + appName;
    }
    return ua;
  }

  /** Returns the current User-Agent header set for the underlying SQLAdmin API client. */
  private static String getApplicationName() {
    return System.getProperty(USER_TOKEN_PROPERTY_NAME, "");
  }

  /**
   * Adds an external application name to the user agent string for tracking. This is known to be
   * used by the spring-cloud-gcp project.
   *
   * @throws IllegalStateException if the SQLAdmin client has already been initialized
   */
  public static void setApplicationName(String applicationName) {
    if (internalConnectorRegistry != null) {
      throw new IllegalStateException(
          "Unable to set ApplicationName - SQLAdmin client already initialized.");
    }
    System.setProperty(USER_TOKEN_PROPERTY_NAME, applicationName);
  }

  private Connector getConnector(ConnectionConfig config) {
    if (config.getNamedConnection() != null) {
      ConnectionConfig realConfig = resolveConfig(config);
      return namedConnectors.computeIfAbsent(
          config.getNamedConnection(), k -> createConnector(realConfig));
    }
    return connectors.computeIfAbsent(new ConnectorKey(config), k -> createConnector(config));
  }

  private Connector createConnector(ConnectionConfig config) {

    final CredentialFactory instanceCredentialFactory;
    if (config.getTargetPrincipal() != null && !config.getTargetPrincipal().isEmpty()) {
      instanceCredentialFactory =
          new ServiceAccountImpersonatingCredentialFactory(
              credentialFactory, config.getTargetPrincipal(), config.getDelegates());
    } else {
      if (config.getDelegates() != null && !config.getDelegates().isEmpty()) {
        throw new IllegalArgumentException(
            String.format(
                "Connection property %s must be when %s is set.",
                ConnectionConfig.CLOUD_SQL_TARGET_PRINCIPAL_PROPERTY,
                ConnectionConfig.CLOUD_SQL_DELEGATES_PROPERTY));
      }
      instanceCredentialFactory = credentialFactory;
    }

    HttpRequestInitializer credential = instanceCredentialFactory.create();
    DefaultConnectionInfoRepository adminApi =
        connectionInfoRepositoryFactory.create(credential, config);

    return new Connector(
        adminApi,
        instanceCredentialFactory,
        executor,
        localKeyPair,
        MIN_REFRESH_DELAY_MS,
        refreshTimeoutMs,
        serverProxyPort);
  }

  private ConnectionConfig resolveConfig(ConnectionConfig config) {
    if (config.getNamedConnection() != null) {
      ConnectionConfig namedConfig = namedConfigs.get(config.getNamedConnection());
      if (namedConfig == null) {
        throw new IllegalArgumentException(
            "No named connection named " + config.getNamedConnection());
      }
      return namedConfig;
    }
    return config;
  }

  /** Register the configuration for a named connector. */
  public void register(String name, ConnectionConfig config) {
    ConnectionConfig oldConfig = this.namedConfigs.putIfAbsent(name, config);
    if (oldConfig != null) {
      throw new IllegalArgumentException("Named configuration " + name + " already exists.");
    }
  }

  /** Close a named connector, stopping the refresh process and removing it from the registry. */
  public void close(String name) {
    this.namedConnectors.remove(name);
    Connector oldConnector = this.namedConnectors.remove(name);
    ConnectionConfig oldConfig = this.namedConfigs.remove(name);
    if (oldConnector == null && oldConfig == null) {
      throw new IllegalArgumentException("Named configuration " + name + " does not exist.");
    }
    if (oldConnector != null) {
      // oldInstance == null when a config is registered but never used.
      oldConnector.close();
    }
  }

  /** Shutdown all connectors. */
  public void shutdown() {
    this.namedConnectors.forEach(
        (key, c) -> {
          c.close();
        });
    this.namedConnectors.clear();
    this.connectors.forEach(
        (key, c) -> {
          c.close();
        });
    this.connectors.clear();
    this.executor.shutdown();
  }
}