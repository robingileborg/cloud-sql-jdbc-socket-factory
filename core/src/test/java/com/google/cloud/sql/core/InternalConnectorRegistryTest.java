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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.api.client.http.BasicAuthentication;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.cloud.sql.AuthType;
import com.google.cloud.sql.ConnectorConfig;
import com.google.cloud.sql.CredentialFactory;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

// TODO(berezv): add multithreaded test
@RunWith(JUnit4.class)
public class InternalConnectorRegistryTest extends CloudSqlCoreTestingBase {
  private final long TEST_MAX_REFRESH_MS = 5000L;

  ListeningScheduledExecutorService defaultExecutor;

  @Before
  public void setUp() throws Exception {
    super.setup();
    defaultExecutor = InternalConnectorRegistry.getDefaultExecutor();
  }

  @After
  public void tearDown() throws Exception {
    defaultExecutor.shutdownNow();
  }

  @Test
  public void create_throwsErrorForInvalidInstanceName() throws IOException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeSuccessHttpTransport(Duration.ofSeconds(0)));
    InternalConnectorRegistry internalConnectorRegistry =
        new InternalConnectorRegistry(
            clientKeyPair,
            factory,
            stubCredentialFactoryProvider,
            3307,
            TEST_MAX_REFRESH_MS,
            defaultExecutor);
    try {
      internalConnectorRegistry.connect(
          new ConnectionConfig.Builder()
              .withCloudSqlInstance("myProject")
              .withIpTypes("PRIMARY")
              .build());
      fail();
    } catch (IllegalArgumentException | InterruptedException e) {
      assertThat(e).hasMessageThat().contains("Cloud SQL connection name is invalid");
    }

    try {
      internalConnectorRegistry.connect(
          new ConnectionConfig.Builder()
              .withCloudSqlInstance("myProject:myRegion")
              .withIpTypes("PRIMARY")
              .build());
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Cloud SQL connection name is invalid");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void create_throwsErrorForInvalidInstanceRegion() throws IOException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeSuccessHttpTransport(Duration.ofSeconds(0)));
    InternalConnectorRegistry internalConnectorRegistry =
        new InternalConnectorRegistry(
            clientKeyPair,
            factory,
            stubCredentialFactoryProvider,
            3307,
            TEST_MAX_REFRESH_MS,
            defaultExecutor);
    try {
      internalConnectorRegistry.connect(
          new ConnectionConfig.Builder()
              .withCloudSqlInstance("myProject:notMyRegion:myInstance")
              .withIpTypes("PRIMARY")
              .build());
      fail();
    } catch (RuntimeException e) {
      assertThat(e)
          .hasMessageThat()
          .contains("The region specified for the Cloud SQL instance is incorrect");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Start an SSL server on the private IP, and verifies that specifying a preference for private IP
   * results in a connection to the private IP.
   */
  @Test
  public void create_successfulPrivateConnection() throws IOException, InterruptedException {
    InternalConnectorRegistry internalConnectorRegistry =
        createRegistry(PRIVATE_IP, stubCredentialFactoryProvider);
    Socket socket =
        internalConnectorRegistry.connect(
            new ConnectionConfig.Builder()
                .withCloudSqlInstance("myProject:myRegion:myInstance")
                .withIpTypes("PRIVATE")
                .build());

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_failOnEmptyTargetPrincipal() throws IOException, InterruptedException {
    InternalConnectorRegistry internalConnectorRegistry =
        createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);
    try {

      internalConnectorRegistry.connect(
          new ConnectionConfig.Builder()
              .withCloudSqlInstance("myProject:myRegion:myInstance")
              .withIpTypes("PRIMARY")
              .withConnectorConfig(
                  new ConnectorConfig.Builder()
                      .withDelegates(
                          Collections.singletonList("delegate-service-principal@example.com"))
                      .build())
              .build());
      fail("IllegalArgumentException expected.");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).contains(ConnectionConfig.CLOUD_SQL_TARGET_PRINCIPAL_PROPERTY);
    }
  }

  @Test
  public void create_successfulConnection() throws IOException, InterruptedException {
    InternalConnectorRegistry internalConnectorRegistry =
        createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);

    Socket socket =
        internalConnectorRegistry.connect(
            new ConnectionConfig.Builder()
                .withCloudSqlInstance("myProject:myRegion:myInstance")
                .withIpTypes("PRIMARY")
                .build());

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_successfulDomainScopedConnection() throws IOException, InterruptedException {
    InternalConnectorRegistry internalConnectorRegistry =
        createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);
    Socket socket =
        internalConnectorRegistry.connect(
            new ConnectionConfig.Builder()
                .withCloudSqlInstance("example.com:myProject:myRegion:myInstance")
                .withIpTypes("PRIMARY")
                .build());
    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_adminApiNotEnabled() throws IOException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeNotConfiguredException());
    InternalConnectorRegistry internalConnectorRegistry =
        new InternalConnectorRegistry(
            clientKeyPair,
            factory,
            stubCredentialFactoryProvider,
            3307,
            TEST_MAX_REFRESH_MS,
            defaultExecutor);
    try {
      // Use a different project to get Api Not Enabled Error.
      internalConnectorRegistry.connect(
          new ConnectionConfig.Builder()
              .withCloudSqlInstance("NotMyProject:myRegion:myInstance")
              .withIpTypes("PRIMARY")
              .build());
      fail("Expected RuntimeException");
    } catch (RuntimeException | InterruptedException e) {
      assertThat(e)
          .hasMessageThat()
          .contains(
              String.format(
                  "[%s] The Google Cloud SQL Admin API is not enabled for the project",
                  "NotMyProject:myRegion:myInstance"));
    }
  }

  @Test
  public void create_notAuthorized() throws IOException {
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(fakeNotAuthorizedException());
    InternalConnectorRegistry internalConnectorRegistry =
        new InternalConnectorRegistry(
            clientKeyPair,
            factory,
            stubCredentialFactoryProvider,
            3307,
            TEST_MAX_REFRESH_MS,
            defaultExecutor);
    try {
      // Use a different instance to simulate incorrect permissions.
      internalConnectorRegistry.connect(
          new ConnectionConfig.Builder()
              .withCloudSqlInstance("myProject:myRegion:NotMyInstance")
              .withIpTypes("PRIMARY")
              .build());
      fail();
    } catch (RuntimeException e) {
      assertThat(e)
          .hasMessageThat()
          .contains(
              String.format(
                  "[%s] The Cloud SQL Instance does not exist or your account is not authorized",
                  "myProject:myRegion:NotMyInstance"));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void supportsCustomCredentialFactoryWithIAM() throws InterruptedException, IOException {
    CredentialFactoryProvider credentialFactoryProvider =
        new CredentialFactoryProvider(
            new StubCredentialFactory("foo", Instant.now().plusSeconds(3600).toEpochMilli()));

    InternalConnectorRegistry internalConnectorRegistry =
        createRegistry(PUBLIC_IP, credentialFactoryProvider);
    Socket socket =
        internalConnectorRegistry.connect(
            new ConnectionConfig.Builder()
                .withCloudSqlInstance("myProject:myRegion:myInstance")
                .withIpTypes("PRIMARY")
                .withAuthType(AuthType.IAM)
                .build());

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void supportsCustomCredentialFactoryWithNoExpirationTime()
      throws InterruptedException, IOException {
    CredentialFactoryProvider credentialFactoryProvider =
        new CredentialFactoryProvider(new StubCredentialFactory("foo", null));

    InternalConnectorRegistry internalConnectorRegistry =
        createRegistry(PUBLIC_IP, credentialFactoryProvider);
    Socket socket =
        internalConnectorRegistry.connect(
            new ConnectionConfig.Builder()
                .withCloudSqlInstance("myProject:myRegion:myInstance")
                .withIpTypes("PRIMARY")
                .withAuthType(AuthType.IAM)
                .build());

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void doesNotSupportNonGoogleCredentialWithIAM() throws InterruptedException {
    class BasicAuthStubCredentialFactory implements CredentialFactory {

      @Override
      public HttpRequestInitializer create() {
        return new BasicAuthentication("user", "password");
      }
    }

    CredentialFactory stubCredentialFactory = new BasicAuthStubCredentialFactory();
    CredentialFactoryProvider credentialFactoryProvider =
        new CredentialFactoryProvider(stubCredentialFactory);

    InternalConnectorRegistry internalConnectorRegistry =
        createRegistry(PUBLIC_IP, credentialFactoryProvider);
    assertThrows(
        RuntimeException.class,
        () ->
            internalConnectorRegistry.connect(
                new ConnectionConfig.Builder()
                    .withCloudSqlInstance("myProject:myRegion:myInstance")
                    .withIpTypes("PRIMARY")
                    .withAuthType(AuthType.IAM)
                    .build()));
  }

  @Test
  public void testGetApplicationNameWithApplicationName() {
    InternalConnectorRegistry.resetUserAgent();
    InternalConnectorRegistry.setApplicationName("sample-app");
    InternalConnectorRegistry.addArtifactId("unit-test");
    InternalConnectorRegistry.getInstance();
    assertThat(InternalConnectorRegistry.getUserAgents()).startsWith("unit-test/");
    assertThat(InternalConnectorRegistry.getUserAgents()).endsWith(" sample-app");
  }

  @Test
  public void testGetApplicationNameFailsAfterInitialization() {
    InternalConnectorRegistry.resetUserAgent();
    InternalConnectorRegistry.getInstance();
    assertThrows(
        IllegalStateException.class,
        () -> InternalConnectorRegistry.setApplicationName("sample-app"));
  }

  @Test
  public void registerConnection() throws IOException, InterruptedException {

    String adminRootUrl = "https://googleapis.example.com/";
    String adminServicePath = "sqladmin/";
    String baseUrl = adminRootUrl + adminServicePath;

    // The mock AdminApi only responds to requests where the URL starts with a custom admin api
    // root url: https://googleapis.example.com/sqladmin/
    InternalConnectorRegistry registry =
        createRegistry(PUBLIC_IP, stubCredentialFactoryProvider, baseUrl);

    // Register a ConnectionConfig named "my-connection" that uses the custom admin api root url.
    ConnectorConfig configWithDetails =
        new ConnectorConfig.Builder()
            .withAdminRootUrl(adminRootUrl)
            .withAdminServicePath(adminServicePath)
            .build();
    registry.register("my-connection", configWithDetails);

    // Assert that when the named connection config is used, the socket opens correctly.
    Properties goodProps = new Properties();
    goodProps.setProperty(ConnectionConfig.CLOUD_SQL_NAMED_CONNECTOR_PROPERTY, "my-connection");
    goodProps.setProperty(
        ConnectionConfig.CLOUD_SQL_INSTANCE_PROPERTY, "myProject:myRegion:myInstance");
    ConnectionConfig goodConfig = ConnectionConfig.fromConnectionProperties(goodProps);

    Socket socket = registry.connect(goodConfig);
    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);

    // Assert that when the named connection config is not used, the socket fails to open.
    Properties badProps = new Properties();
    badProps.setProperty(
        ConnectionConfig.CLOUD_SQL_INSTANCE_PROPERTY, "myProject:myRegion:myInstance");
    ConnectionConfig badConfig = ConnectionConfig.fromConnectionProperties(badProps);

    assertThrows(RuntimeException.class, () -> registry.connect(badConfig));
  }

  @Test
  public void registerConnectionFailsWithDuplicateName() throws InterruptedException {
    InternalConnectorRegistry registry = createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);
    // Register a ConnectionConfig named "my-connection"
    ConnectorConfig configWithDetails = new ConnectorConfig.Builder().build();
    registry.register("my-connection", configWithDetails);

    // Assert that you can't register a connection with a duplicate name
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register("my-connection", configWithDetails));
  }

  @Test
  public void registerConnectionFailsWithDuplicateNameAndDifferentConfig()
      throws InterruptedException {
    InternalConnectorRegistry registry = createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);

    ConnectorConfig config =
        new ConnectorConfig.Builder().withTargetPrincipal("joe@test.com").build();
    registry.register("my-connection", config);

    ConnectorConfig config2 =
        new ConnectorConfig.Builder().withTargetPrincipal("jane@test.com").build();

    // Assert that you can't register a connection with a duplicate name
    assertThrows(IllegalArgumentException.class, () -> registry.register("my-connection", config2));
  }

  @Test
  public void closeNamedConnectionFailsWhenNotFound() throws InterruptedException {
    InternalConnectorRegistry registry = createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);
    // Assert that you can't close a connection that doesn't exist
    assertThrows(IllegalArgumentException.class, () -> registry.close("my-connection"));
  }

  @Test
  public void connectFailsOnClosedNamedConnection() throws InterruptedException {
    InternalConnectorRegistry registry = createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);
    // Register a ConnectionConfig named "my-connection"
    ConnectorConfig configWithDetails = new ConnectorConfig.Builder().build();
    registry.register("my-connection", configWithDetails);

    // Close the named connection.
    registry.close("my-connection");

    // Attempt and fail to connect using the cloudSqlNamedConnection connection property
    Properties connProps = new Properties();
    connProps.setProperty(ConnectionConfig.CLOUD_SQL_NAMED_CONNECTOR_PROPERTY, "my-connection");
    ConnectionConfig nameOnlyConfig = ConnectionConfig.fromConnectionProperties(connProps);

    // Assert that no connection is possible because the connector is closed.
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> registry.connect(nameOnlyConfig));
    assertThat(ex).hasMessageThat().contains("Named connection my-connection does not exist.");
  }

  @Test
  public void connectFailsOnUnknownNamedConnection() throws InterruptedException {
    InternalConnectorRegistry registry = createRegistry(PUBLIC_IP, stubCredentialFactoryProvider);

    // Attempt and fail to connect using the cloudSqlNamedConnection connection property
    Properties connProps = new Properties();
    connProps.setProperty(ConnectionConfig.CLOUD_SQL_NAMED_CONNECTOR_PROPERTY, "my-connection");
    ConnectionConfig nameOnlyConfig = ConnectionConfig.fromConnectionProperties(connProps);
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> registry.connect(nameOnlyConfig));
    assertThat(ex).hasMessageThat().contains("Named connection my-connection does not exist.");
  }

  private InternalConnectorRegistry createRegistry(
      String ipType, CredentialFactoryProvider credentialFactory) throws InterruptedException {
    return createRegistry(ipType, credentialFactory, null);
  }

  private InternalConnectorRegistry createRegistry(
      String ipType, CredentialFactoryProvider credentialFactory, String baseUrl)
      throws InterruptedException {
    FakeSslServer sslServer = new FakeSslServer();
    int port = sslServer.start(ipType);
    ConnectionInfoRepositoryFactory factory =
        new StubConnectionInfoRepositoryFactory(
            fakeSuccessHttpTransport(Duration.ofSeconds(0), baseUrl));
    return new InternalConnectorRegistry(
        clientKeyPair, factory, credentialFactory, port, TEST_MAX_REFRESH_MS, defaultExecutor);
  }

  private String readLine(Socket socket) throws IOException {
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
    return bufferedReader.readLine();
  }
}
