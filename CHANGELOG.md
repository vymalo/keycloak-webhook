# Changelog

## [next]

### Fixed

- The build was broken on `main`: the HTTP module did not compile (`sessionId` from #67 was missing in the OpenAPI
  schema), and the release jars could not be built with Gradle 9. Both fixes come from #90
- Every Keycloak session opened and closed its own AMQP connection, HTTP client and Syslog sender. Each provider now
  keeps one for the lifetime of the server and closes it on shutdown
- An unreachable RabbitMQ broker made listener creation throw into Keycloak; the event is now logged and dropped
- A missing or malformed setting failed every login in a realm with that listener enabled. It is now logged once,
  and that listener's events are dropped until the config is fixed
- AMQP publisher confirms from concurrent requests could interfere with each other, and confirm mode was lost after a
  reconnect
- A connection dropped by the broker could come back through automatic recovery as a second, leaked connection
- `WEBHOOK_AMQP_VHOST` is optional as documented, defaulting to `/` (leaving it out used to fail)
- Syslog messages carried the Syslog server's hostname in their HOSTNAME field; they now carry
  `WEBHOOK_SYSLOG_HOSTNAME`, as documented. **This changes the HOSTNAME of every Syslog message.**
- Syslog over TCP with the default RFC 5425 framing wrote CRLF after every octet-counted frame. Strict receivers such
  as syslog-ng read it as the next frame's header, dropped the connection and lost messages; frames are now sent back
  to back as RFC 5425 requires (newline-delimited formats keep their newline)

### Changed

- AMQP with the broker down: requests arriving during a reconnect wait for it instead of starting their own, and for
  5 seconds after a failed reconnect, events are dropped without trying, so an outage costs logins one reconnect at a
  time instead of one each
- Missing or malformed settings are reported in one error that names every problem key, instead of a
  `NullPointerException`

### Added

- AMQP opt-ins, each off unless set: `WEBHOOK_AMQP_ADDRESSES` for clusters, `WEBHOOK_AMQP_HEARTBEAT_SECONDS`,
  certificate verification with `WEBHOOK_AMQP_SSL_TRUSTSTORE` (plus `_PASSWORD`, `_TYPE`), persistent messages
  (`WEBHOOK_AMQP_PERSISTENT`), per-message ids (`WEBHOOK_AMQP_MESSAGE_ID`) and declaring the exchange
  (`WEBHOOK_AMQP_DECLARE_EXCHANGE`)
- `WEBHOOK_AMQP_CONNECTION_TIMEOUT` to stop waiting on an unreachable broker sooner than the client's 60 s
- `WEBHOOK_AMQP_PUBLISH_MODE=async`: publishing on a background thread with an in-memory buffer
  (`WEBHOOK_AMQP_BUFFER_CAPACITY`), so logins never wait for RabbitMQ. With publisher confirms, delivery is at least
  once: unconfirmed messages are sent again after a reconnect or a confirm timeout, refused ones are retried 5 times,
  and at most `WEBHOOK_AMQP_INFLIGHT_CAPACITY` wait for a confirm at once
- `WEBHOOK_AMQP_MANDATORY`: publishes with the mandatory flag and logs every message the broker returns because no
  queue is bound to receive it (confirms alone report those as delivered)
- A warning when TLS is on without a truststore, since the broker's certificate is then not verified
- Behaviour tests for every provider, including RabbitMQ through Testcontainers, run by CI on every push
- README: publisher confirm settings, defaults, routing keys, and how delivery behaves when a destination is down

## [0.8.3] - 2025-03-04

### Added

- Support for Keycloak 21 -> 26
- Split the project into multiple modules
- Added dependabot for automatic updates of dependencies

[next]: https://github.com/vymalo/keycloak-webhook/compare/v0.8.3...HEAD
[0.8.3]: https://github.com/vymalo/keycloak-webhook/commits/v0.8.3
