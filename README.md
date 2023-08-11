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

### Example
When using the docker-compose file, the following containers are created:
- keycloak: a keycloak server
- watcher: a simple container that will print the webhook request. It's used to test the plugin. Upon user login, you should see a request printed in the web console at http://localhost:3000 like this:
  ```json
  {
    "type": "LOGIN",
    "realmId": "b1a70c8a-8656-4ae9-85f1-524e5c7f6294",
    "time": 1691758188038,
    "clientId": "account-console",
    "userId": "a032b425-60b5-462a-b156-8d178b86fa71",
    "ipAddress": "172.18.0.1",
    "details": {
      "auth_method": "openid-connect",
      "response_type": "code",
      "redirect_uri": "http://localhost:9100/realms/main/account/#/",
      "remember_me": "false",
      "consent": "no_consent_required",
      "code_id": "81be1792-8e07-4bff-a6f4-aca1971b1ea3",
      "response_mode": "fragment",
      "username": "selastlambou@gmail.com"
    }
  }
  ```
- rabbitmq: a rabbitmq server. This is for testing AMQP client.