# Webhooks from Keycloak

This plugin call a webhook whenever an event is created from within Keycloak.


## Configuration
- `WEBHOOK_EVENTS_TAKEN (optional)` is the list of events created from Keycloak, that are listened by the plugin. Example: `"LOGIN,REGISTER,LOGOUT"`. If not specified, will take all.

### Using the http client
- `WEBHOOK_HTTP_BASE_PATH` is the endpoint where the webhook request is going to be sent. Example: https://localhost:3000
- `WEBHOOK_HTTP_AUTH_USERNAME (optional)` is the basic auth username. Example "admin".
- `WEBHOOK_HTTP_AUTH_PASSWORD (optional)` is the basic auth password. Example "password".

### Using the AMQP client
This part is heavily inspired from the [keycloak-event-listener-rabbitmq](https://github.com/aznamier/keycloak-event-listener-rabbitmq) plugin.
- `WEBHOOK_AMQP_HOST` is the host url of the rabbitmq server. This key will indicate that the amqp client is being used.
- `WEBHOOK_AMQP_USERNAME` is the username of the rabbitmq server 
- `WEBHOOK_AMQP_PASSWORD` is the password of the rabbitmq server
- `WEBHOOK_AMQP_PORT` is the port of the rabbitmq server
- `WEBHOOK_AMQP_VHOST (optional)` is the vhost of the rabbitmq server
- `WEBHOOK_AMQP_EXCHANGE` is the exchange of the rabbitmq server
- `WEBHOOK_AMQP_SSL (optional)` is to indicate if we're using SSL or not. Values are "yes" or "no"

### Enable listener-webhook in Keycloak
Go to Realm Settings -> Events -> Event listeners and add "listener-webhook" to the list.
