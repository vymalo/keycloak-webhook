# Webhooks from Keycloak

This plugin call a webhook whenever an event is created from within Keycloak.


## Configuration

- `WEBHOOK_BASE_PATH` is the endpoint where the webhook request is going to be sent. Example: https://localhost:3000
- `WEBHOOK_TAKE_EVENTS (optional)` is the list of events created from Keycloak, that are listened by the plugin. Example: `"LOGIN,REGISTER,LOGOUT"`
- `WEBHOOK_AUTH_USERNAME (optional)` is the basic auth username. Example "admin".
- `WEBHOOK_AUTH_PASSWORD (optional)` is the basic auth password. Example "password".