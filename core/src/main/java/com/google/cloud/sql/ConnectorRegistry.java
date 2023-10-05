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

package com.google.cloud.sql;

import com.google.cloud.sql.core.InternalConnectorRegistry;

/** The public java api to control the Connector configuration. */
public enum ConnectorRegistry {
  INSTANCE;

  public void register(String name, ConnectionConfig config) {
    InternalConnectorRegistry.getInstance().register(name, config);
  }

  public void close(String name) {
    InternalConnectorRegistry.getInstance().close(name);
  }

  public void shutdown() {
    InternalConnectorRegistry.getInstance().shutdown();
  }

  /**
   * Adds an external application name to the user agent string for tracking. This is known to be
   * used by the spring-cloud-gcp project.
   *
   * @throws IllegalStateException if the SQLAdmin client has already been initialized
   */
  public void addArtifactId(String artifactId) {
    InternalConnectorRegistry.getInstance().addArtifactId(artifactId);
  }
}
