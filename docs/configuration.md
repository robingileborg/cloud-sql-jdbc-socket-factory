# Connector Configuration Reference

## Unnamed Connectors

The Cloud SQL Java Connector manages the configuration of connectors internally.
Most applications use unnamed connectors configured using JDBC connection 
properties. The Java Connector handles the lifecycle of unnamed connectors
without requiring intervention from the application.

## Named Connectors

The Cloud SQL Java Connector allows applications to configure multiple Cloud SQL
connectors with distinct configuration. 

Your application can use named connectors if:

- It needs to connect to the Cloud SQL Admin API using credentials 
  other than the Application Default Credentials.
- It needs to connect to multiple Cloud SQL instances using different
  credentials.
- It uses a non-standard Cloud SQL Admin API service URL.
- It needs to precisely control when connectors start and stop. 
- It needs to reset the entire connector configuration without restarting 
  the application.

### Registering and Using a Named Connector

The application calls `ConnectorRegistry.register()` to register the named
connector configuration.

```java
GoogleCredentials myCredentials = GoogleCredentials.create(authToken);

ConnectorConfig config = new ConnectorConfig.Builder()
  .withTargetPrincipal("example@project.iam.googleapis.com")
  .withDelegates(Arrays.asList("delegate@project.iam.googleapis.com"))
  .withGoogleCredentials(myCredentials)
  .build();

ConnectorRegistry.register("my-connector",config);
```

Then the application tells a database connection to use a named connector by 
adding the `cloudSqlNamedConnector` to the JDBC connection properties. 

It can do this by adding `cloudSqlNamedConnector` to the JDBC URL:

```java
String jdbcUrl = "jdbc:mysql:///<DATABASE_NAME>?"+
    +"cloudSqlInstance=project:region:instance"
    +"&cloudSqlNamedConnector=my-connector"
    +"&socketFactory=com.google.cloud.sql.mysql.SocketFactory"
    +"&user=<DB_USER>&password=<PASSWORD>";
```

Or by adding `cloudSqlNamedConnector` to the JDBC connection properties:

```java
// Set up URL parameters
Properties connProps = new Properties();

connProps.setProperty("user","<DB_USER>");
connProps.setProperty("password","<PASSWORD>");
connProps.setProperty("sslmode","disable");
connProps.setProperty("socketFactory","<DRIVER_CLASS>");
connProps.setProperty("cloudSqlInstance","project:region:instance");

connProps.setProperty("cloudSqlNamedConnector","my-connector");

// Initialize connection pool
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql:///<DB_NAME>");
config.setDataSourceProperties(connProps);
config.setConnectionTimeout(10000); // 10s

HikariDataSource connectionPool = new HikariDataSource(config);
```

In R2DBC, use the `NAMED_CONNECTOR` ConnectionFactory option.

When using a named connector, the JDBC connection will ignore all connector
configuration properties in the JDBC connection properties, using the named
connector configuration instead. See the full list
of [connector configuration properties](#connector-configuration) below.

The
test [JdbcPostgresNamedConnectorIntegrationTest.java](../jdbc/postgres/src/test/java/com/google/cloud/sql/postgres/JdbcPostgresNamedConnectorIntegrationTests.java)
contains a full example showing how to use a named connector.

### Closing Named Connectors


You can close a named connector by calling `ConnectorRegistry.close()`. This
will stop the certificate refresh process for that connector. It will also
cause all subsequent attempts connect using this named connector to fail.

```java
ConnectorRegistry.close("my-connector");
```

Existing open database `Connection` instances will continue work until they
are closed.

### Updating a Named Connector's Configuration

If you wish to update a named connector's configuration, the application
first calls `ConnectorRegistry.close()` and then `ConnectorRegistry.register()`
with the new configuration. This creates a new connector with the new
credentials.

Existing open database `Connection` instances will continue work until they
are closed. Connections opened after the configuration is updated will use
the new configuration.

#### Example

First, register a named connector called "my-connector", and create
a database connection pool using the named connector.

```java

// Define the ConnectorConfig
GoogleCredentials c1 = GoogleCredentials.create(authToken);
ConnectorConfig config = new ConnectorConfig.Builder()
  .withTargetPrincipal("example@project.iam.googleapis.com")
  .withDelegates(Arrays.asList("delegate@project.iam.googleapis.com"))
  .withGoogleCredentials(c1)
  .build();

// Register it with the name "my-connector"
ConnectorRegistry.register("my-connector", config);
    
// Configure the datbase connection pool.
String jdbcUrl = "jdbc:mysql:///<DATABASE_NAME>?"+
    +"cloudSqlInstance=project:region:instance"
    +"&cloudSqlNamedConnector=my-connector"
    +"&socketFactory=com.google.cloud.sql.mysql.SocketFactory"
    +"&user=<DB_USER>&password=<PASSWORD>";

HikariConfig config = new HikariConfig();
config.setJdbcUrl(jdbcURL);
config.setConnectionTimeout(10000); // 10s
this.connectionPool = new HikariDataSource(config);
```

When your application needs to update the connector configuration, create
the updated ConnectorConfig. Then close the existing connector and register
a new connector with the same name.

```java
// Update the named connector configuration with new credentials.
GoogleCredentials c2 = GoogleCredentials.create(newAuthToken);
ConnectorConfig config2 = new ConnectorConfig.Builder()
    .withTargetPrincipal("application@project.iam.googleapis.com")
    .withGoogleCredentials(c2)
    .build();

// Replace the old connector named "my-connector" with a new connector
// using the new config.
ConnectorRegistry.close("my-connector");
ConnectorRegistry.register("my-connector", config2);
```

No updates to the Database connection pool are required.
Existing open connections in the pool will continue to work until they are
closed. New connections will be established using the new configuration.

### Shutdown The Connector Registry

The application may shutdown the ConnectorRegistry, preventing any new
connections and stopping all connector threads.

```java
ConnectorRegistry.shutdown();
```

After calling `ConnectorRegistry.shutdown()`, the next attempt to connect to a
database using a SocketFactory or R2DBC ConnectionFactory, or
to `ConnectorRegistry.register()` will start a new connector registry.

## Configuring Google Credentials

By default, connectors will use the Google Application Default credentials to
connect to Google Cloud SQL Admin API. The application can set specific
Google Credentials in the connector configuration.

For unnamed connectors, the application can set the JDBC connection property
`cloudSqlGoogleCredentialsPath`. This should hold the path to a file containing
GoogleCredentials JSON. When the application first opens a database connection,
the connector will load the credentials will load from this file.

```java
// Set up URL parameters
String jdbcURL = String.format("jdbc:postgresql:///%s", DB_NAME);
Properties connProps = new Properties();

// Configure Postgres driver properties
connProps.setProperty("user", DB_USER);
connProps.setProperty("password", "password");
connProps.setProperty("socketFactory", "com.google.cloud.sql.postgres.SocketFactory");

// Configure Cloud SQL connector properties
connProps.setProperty("cloudSqlInstance", CONNECTION_NAME);
connProps.setProperty("enableIamAuth", "true");

// Configure path to the credentials file
connProps.setProperty("cloudSqlGoogleCredentialsPath", "/var/secrets/application.json");

// Initialize connection pool
HikariConfig config = new HikariConfig();
config.setJdbcUrl(jdbcURL);
config.setDataSourceProperties(connProps);
config.setConnectionTimeout(10000); // 10s

HikariDataSource connectionPool = new HikariDataSource(config);
```

For named connectors configured registered by calling
`ConnectorRegistry.register()`, there are multiple ways to supply
a `GoogleCredentials` instance to the connector:

- `withGoogleCredentialsPath(String path)` - Configure the connector to load the
  the credentials from the file.
- `withGoogleCredentialsSupplier(Supplier<GoogleCredentials> s)` - Configure the
  connector to load GoogleCredentials from the supplier.
- `withGoogleCredentials(GoogleCredentials c)` - Configure the connector with
  an instance of GoogleCredentials.

Users may only set exactly one of these fields. If more than one field is set,
`ConnectorConfig.Builder.build()` will throw an IllegalStateException.

The credentials are loaded exactly once when the ConnectorConfig is
registered with `ConnectorRegistry.register()`.

## Configuration Property Reference

### Connector Configuration Properties

These properties configure the connector which loads Cloud SQL instance 
configuration using the Cloud SQL Admin API. 

| JDBC Connection Property      | R2DBC Property Name     | Description                                                                                                                                                                                                                                                                                                                | Example                                                                                      |
|-------------------------------|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| cloudSqlTargetPrincipal       | TARGET_PRINCIPAL        | The service account to impersonate when connecting to the database and database admin API.                                                                                                                                                                                                                                 | `db-user@my-project.iam.gserviceaccount.com`                                                 |
| cloudSqlDelegates             | DELEGATES               | A comma-separated list of service accounts delegates. See [Delegated Service Account Impersonation](#delegated-service-account-impersonation)                                                                                                                                                                              | `application@my-project.iam.gserviceaccount.com,services@my-project.iam.gserviceaccount.com` |
| cloudSqlGoogleCredentialsPath | GOOGLE_CREDENTIALS_PATH | A file path to a JSON file containing a GoogleCredentials oauth token.                                                                                                                                                                                                                                                     | `/home/alice/secrets/my-credentials.json`                                                    |
| cloudSqlAdminRootUrl          | ADMIN_ROOT_URL          | Override the default API root url for the Cloud SQL admin API. Must end in '/' See [rootUrl](https://github.com/googleapis/google-api-java-client/blob/4a12a5e0901d6fca0afbf9290e06ab39a182451c/google-api-client/src/main/java/com/google/api/client/googleapis/services/AbstractGoogleClient.java#L49)                   | `https://googleapis.example.com/`                                                            |
| cloudSqlAdminServicePath      | ADMIN_SERVICE_PATH      | An alternate path to the SQL Admin API endpoint. Must not begin with '/'. Must end with '/'. See [servicePath](https://github.com/googleapis/google-api-java-client/blob/4a12a5e0901d6fca0afbf9290e06ab39a182451c/google-api-client/src/main/java/com/google/api/client/googleapis/services/AbstractGoogleClient.java#L52) | `sqladmin/v1beta1/`                                                                          |

### Connection Configuration Properties

These properties configure the connection to a specific Cloud SQL instance.

| JDBC Property Name          | R2DBC Property Name | Description                                                                                                                                                                                                                                | Default Value    | Example                           |
|-----------------------------|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|-----------------------------------|
| cloudSqlInstance (required) | HOST                | Identifies the instance to connect                                                                                                                                                                                                         |                  | `projectname:region:instancename` |
| cloudSqlNamedConnector      | NAMED_CONNECTOR     | References a connector configuration created using `ConnectorRegistry.register()`                                                                                                                                                          |                  | `my-configuration`                |
| unixSocketPath              | UNIX_SOCKET         | The path to the unix socket to connect to the database, when using the connector with the [Cloud SQL Auth Proxy](https://github.com/GoogleCloudPlatform/cloud-sql-proxy/)                                                                  |                  | `/var/db/my-db-instance`          |
| enableIamAuth               | ENABLE_IAM_AUTH     | Enable IAM Authentication to authenticate to the database. Valid values: `true` - authenticate with the IAM principal,  `false` - authenticate with a database user and password.                                                          | false            | `true`                            |
| ipTypes                     | IP_TYPES            | A comma-separated list of IP types, ordered by preference. Value values: `PUBLIC` - connect to the instance's public IP, `PRIVATE` - connect to the instances private IP, `PSC` - connect to the instance through Private Service Connect. | `PUBLIC,PRIVATE` | `PSC,PRIVATE,PUBLIC`              |

