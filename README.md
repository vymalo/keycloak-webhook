# Keycloak Webhook Plugin

A modular Keycloak event listener plugin that triggers webhooks whenever specific events (like login, registration, or
logout) occur in Keycloak. This project leverages a multi-module design so you can choose which transport provider (HTTP,
AMQP, or Syslog) to deploy based on your needs.

| Keycloak Version | Plugin Version |
| ---------------- | -------------- |
| 24               | ✅ 0.9.1       |
| 25               | ✅ 0.9.1       |
| 26               | ✅ 0.9.1       |

---

## 1. What It Is

The Keycloak Webhook Plugin consists of four modules:

- **Core Module (`keycloak-webhook-provider-core`)**  
  Contains common SPI interfaces, shared models, and helper utilities.

- **AMQP Provider (`keycloak-webhook-provider-amqp`)**  
  Implements webhook notifications over AMQP (e.g., RabbitMQ). If the AMQP dependency is present on the classpath, this
  provider is loaded automatically.

- **HTTP Provider (`keycloak-webhook-provider-http`)**  
  Implements webhook notifications over HTTP. This provider uses OpenAPI-generated clients to ensure compliance with the
  target API.

- **Syslog Provider (`keycloak-webhook-provider-syslog`)**  
  Implements webhook notifications over Syslog (TCP/UDP). Supports RFC 3164 and RFC 5424 message formats.

Keycloak uses Java's `ServiceLoader` mechanism to conditionally load these providers at runtime if their JARs (and
dependencies) are available.

---

## 2. How to Use It

### Downloading the Plugins

Download the latest release artifacts (shaded JARs) from the GitHub releases page. For example, using `curl`:

```bash
# Replace <version> with the desired release version.

VERSION=<version>; curl -L -o keycloak-webhook-provider-core.jar https://github.com/vymalo/keycloak-webhook/releases/download/v${VERSION}/keycloak-webhook-provider-core-${VERSION}-all.jar; \
curl -L -o keycloak-webhook-provider-amqp.jar https://github.com/vymalo/keycloak-webhook/releases/download/v${VERSION}/keycloak-webhook-provider-amqp-${VERSION}-all.jar; \
curl -L -o keycloak-webhook-provider-http.jar https://github.com/vymalo/keycloak-webhook/releases/download/v${VERSION}/keycloak-webhook-provider-http-${VERSION}-all.jar; \
curl -L -o keycloak-webhook-provider-syslog.jar https://github.com/vymalo/keycloak-webhook/releases/download/v${VERSION}/keycloak-webhook-provider-syslog-${VERSION}-all.jar; \
```

### a. Docker

When running Keycloak in Docker, mount the downloaded JARs into Keycloak's providers directory. For example, in your
`docker-compose.yaml`:

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.1.3
    ports:
      - "9100:9100"
    environment:
      # HTTP Provider Configuration
      WEBHOOK_HTTP_BASE_PATH: "http://prism:4010"
      WEBHOOK_HTTP_AUTH_USERNAME: "admin"
      WEBHOOK_HTTP_AUTH_PASSWORD: "password"
      # AMQP Provider (Cluster-aware)
      # Preferred: comma-separated host[:port] list
      WEBHOOK_AMQP_ADDRESSES: "rabbitmq1:5672,rabbitmq2:5672,rabbitmq3:5672"
      # Fallbacks (used only if ADDRESSES is empty)
      WEBHOOK_AMQP_HOST: "rabbitmq1"
      WEBHOOK_AMQP_PORT: "5672"
      WEBHOOK_AMQP_USERNAME: "username"
      WEBHOOK_AMQP_PASSWORD: "password"
      WEBHOOK_AMQP_VHOST: "/"
      WEBHOOK_AMQP_EXCHANGE: "keycloak"
      WEBHOOK_AMQP_SSL: "false"
      # Optional AMQP tuning (with defaults shown)
      WEBHOOK_AMQP_HEARTBEAT_SECONDS: "30"
      WEBHOOK_AMQP_NETWORK_RECOVERY_MILLISECONDS: "5000"
      WEBHOOK_AMQP_WH_HANDLER_BUFFER_CAPACITY: "1000"
      # Syslog Provider Configuration
      WEBHOOK_SYSLOG_PROTOCOL: udp
      WEBHOOK_SYSLOG_HOSTNAME: keycloak
      WEBHOOK_SYSLOG_APP_NAME: Keycloak
      WEBHOOK_SYSLOG_FACILITY: USER
      WEBHOOK_SYSLOG_SEVERITY: INFORMATIONAL
      WEBHOOK_SYSLOG_SERVER_HOSTNAME: syslog-ng
      WEBHOOK_SYSLOG_SERVER_PORT: 5514
      WEBHOOK_SYSLOG_MESSAGE_FORMAT: RFC_5425
      # Keycloak Admin Credentials
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: password
      KC_HTTP_PORT: 9100
      KC_METRICS_ENABLED: "true"
      KC_LOG_CONSOLE_COLOR: "true"
      KC_HEALTH_ENABLED: "true"
    entrypoint: /bin/sh
    command:
      - -c
      - |
        set -ex
        # Copy all plugin JARs from the mounted volume into Keycloak's providers folder
        cp /tmp/plugins/*.jar /opt/keycloak/providers
        /opt/keycloak/bin/kc.sh start-dev --import-realm
    volumes:
      - ./plugins:/tmp/plugins:ro # Place your downloaded JARs in this folder
      - ./.docker/keycloak-config/:/opt/keycloak/data/import/:ro
```

### b. Kubernetes

In Kubernetes, you can use an init container to download the plugin JARs from GitHub artifacts and copy them into
Keycloak's providers folder. For example:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
spec:
  replicas: 1
  selector:
    matchLabels:
      app: keycloak
  template:
    metadata:
      labels:
        app: keycloak
    spec:
      volumes:
        - name: providers-volume
          emptyDir: {}
      initContainers:
        - name: download-plugins
          image: curlimages/curl:8.1.2
          command:
            - sh
            - -c
            - |
              mkdir -p /plugins
              # Download the plugins from GitHub releases (update the URLs accordingly)
              curl -L -o /plugins/keycloak-webhook-provider-core.jar https://github.com/vymalo/keycloak-webhook/releases/download/v<version>/keycloak-webhook-provider-core-<version>-all.jar
              curl -L -o /plugins/keycloak-webhook-provider-amqp.jar https://github.com/vymalo/keycloak-webhook/releases/download/v<version>/keycloak-webhook-provider-amqp-<version>-all.jar
              curl -L -o /plugins/keycloak-webhook-provider-http.jar https://github.com/vymalo/keycloak-webhook/releases/download/v<version>/keycloak-webhook-provider-http-<version>-all.jar
              curl -L -o /plugins/keycloak-webhook-provider-syslog.jar https://github.com/vymalo/keycloak-webhook/releases/download/v<version>/keycloak-webhook-provider-syslog-<version>-all.jar
              cp /plugins/*.jar /providers/
          volumeMounts:
            - name: providers-volume
              mountPath: /providers
      containers:
        - name: keycloak
          image: quay.io/keycloak/keycloak:26.1.3
          env:
            - name: WEBHOOK_HTTP_BASE_PATH
              value: "http://prism:4010"
            - name: WEBHOOK_HTTP_AUTH_USERNAME
              value: "admin"
            - name: WEBHOOK_HTTP_AUTH_PASSWORD
              value: "password"
            - name: WEBHOOK_AMQP_ADDRESSES
              value: "rabbitmq1:5672,rabbitmq2:5672,rabbitmq3:5672"
            - name: WEBHOOK_AMQP_USERNAME
              value: "username"
            - name: WEBHOOK_AMQP_PASSWORD
              value: "password"
            - name: WEBHOOK_AMQP_VHOST
              value: "/"
            - name: WEBHOOK_AMQP_EXCHANGE
              value: "keycloak"
            - name: WEBHOOK_AMQP_SSL
              value: "no"
            - name: WEBHOOK_SYSLOG_PROTOCOL
              value: "udp"
            - name: WEBHOOK_SYSLOG_SERVER_HOSTNAME
              value: "syslog-ng"
            - name: WEBHOOK_SYSLOG_SERVER_PORT
              value: "5514"
          volumeMounts:
            - name: providers-volume
              mountPath: /opt/keycloak/providers
```

---

## 3. Environment Variables

### HTTP Provider

- **`WEBHOOK_HTTP_BASE_PATH`**  
  The endpoint URL where webhook requests are sent.

- **`WEBHOOK_HTTP_AUTH_USERNAME` (optional)**  
  Basic auth username.

- **`WEBHOOK_HTTP_AUTH_PASSWORD` (optional)**  
  Basic auth password.

### AMQP Provider (cluster-aware)

- **`WEBHOOK_AMQP_ADDRESSES` (preferred)**  
  Comma-separated list of `host[:port]` for RabbitMQ nodes (e.g., `rabbitmq1:5672,rabbitmq2:5672,rabbitmq3:5672`).  
  When set, this is used instead of `WEBHOOK_AMQP_HOST`/`WEBHOOK_AMQP_PORT`.

- **`WEBHOOK_AMQP_HOST`** *(fallback)*  
  RabbitMQ server hostname (used only if `WEBHOOK_AMQP_ADDRESSES` is empty/unset).

- **`WEBHOOK_AMQP_PORT`** *(fallback)*  
  RabbitMQ server port (default `5672` if omitted; used only if `WEBHOOK_AMQP_ADDRESSES` is empty/unset).

- **`WEBHOOK_AMQP_USERNAME`**  
  Username for RabbitMQ.

- **`WEBHOOK_AMQP_PASSWORD`**  
  Password for RabbitMQ.

- **`WEBHOOK_AMQP_VHOST` (optional)**  
  Virtual host (default `/`).

- **`WEBHOOK_AMQP_EXCHANGE`**  
  Exchange name (declared/verified as a durable `topic`).

- **`WEBHOOK_AMQP_SSL` (optional)**  
  `true` or `false` to enable TLS.

- **`WEBHOOK_AMQP_HEARTBEAT_SECONDS` (optional)**  
  Connection heartbeat in seconds (default `30`).

- **`WEBHOOK_AMQP_NETWORK_RECOVERY_MILLISECONDS` (optional)**  
  Auto-recovery backoff interval in milliseconds (default `5000`).

- **`WEBHOOK_AMQP_WH_HANDLER_BUFFER_CAPACITY` (optional)**  
  In-memory LRU buffer size for pending messages (default `1000`; drops oldest when full).

### Syslog Provider

- **`WEBHOOK_SYSLOG_PROTOCOL`**  
  `"TCP"` or `"UDP"` protocol for Syslog communication.

- **`WEBHOOK_SYSLOG_HOSTNAME`**  
  Hostname of the Keycloak instance.

- **`WEBHOOK_SYSLOG_APP_NAME`**  
  Application name for Syslog messages.

- **`WEBHOOK_SYSLOG_FACILITY`**  
  Syslog facility (e.g., USER, DAEMON, AUTH).

- **`WEBHOOK_SYSLOG_SEVERITY`**  
  Syslog severity level (e.g., INFORMATIONAL, WARNING, ERROR).

- **`WEBHOOK_SYSLOG_SERVER_HOSTNAME`**  
  Hostname of the Syslog server.

- **`WEBHOOK_SYSLOG_SERVER_PORT`**  
  Port of the Syslog server.

- **`WEBHOOK_SYSLOG_MESSAGE_FORMAT`**  
  `"RFC_3164"`, `"RFC_5424"` or `"RFC_5425"` message format.

- **`WEBHOOK_EVENTS_TAKEN` (optional)**  
  A comma-separated list of Keycloak events (e.g., `"LOGIN,REGISTER,LOGOUT"`) that should trigger webhooks. If not
  specified, all events are processed.

---

## 4. Architecture

The architecture of the Keycloak Webhook Plugin is illustrated using a Mermaid diagram below:

```mermaid
graph TD
    A["Keycloak (Event Source)"]
    B["ServiceLoader (SPI)"]
    C[Core Module]
    D[HTTP Provider]
    E[AMQP Provider]
    F[Syslog Provider]
    G[External HTTP Server]
    H[RabbitMQ Broker]
    I[Syslog Server]
    A --> B
    B --> C
    C --> D
    C --> E
    C --> F
    D --> G
    E --> H
    F --> I
```

- **Core Module:**  
  Provides common interfaces, models, and utilities.

- **Provider Modules:**  
  Implement specific webhook delivery mechanisms (HTTP, AMQP, or Syslog) and are conditionally loaded if their JARs are present.

- **ServiceLoader:**  
  Uses Java's SPI to discover and load the providers.

- **External Systems:**  
  Webhook notifications are sent to an HTTP server, published to a RabbitMQ broker, or forwarded to a Syslog server.

---

## 5. Contribute

We welcome contributions! To get started:

1. **Fork the Repository:**  
   Create your own fork of the project on GitHub.

2. **Set Up Your Development Environment:**

- Clone your fork locally.
- Ensure you have JDK 17 and Gradle installed.
- Build the project using:
  ```bash
  ./gradlew clean shadow
  ```

3. **Follow Code Conventions:**

- Keep the code style consistent with the existing modules.
- Write tests where applicable.
- Update the README and documentation if your changes require it.

4. **Submit a Pull Request:**  
   Open a pull request with your proposed changes. Please include a detailed description and reference any related
   issues.

5. **Join Discussions:**  
   Use GitHub issues to discuss ideas, report bugs, or ask for help.

---

This modular and flexible design allows you to deploy only the providers you need while keeping the project maintainable
and extensible. Happy coding!
