plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "keycloak-webhook"

include("keycloak-webhook-provider-core")
include("keycloak-webhook-provider-amqp")
include("keycloak-webhook-provider-http")
include("keycloak-webhook-provider-syslog")
