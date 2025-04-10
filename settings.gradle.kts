plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
rootProject.name = "keycloak-webhook"

include("keycloak-webhook-provider-core")
include("keycloak-webhook-provider-amqp")
include("keycloak-webhook-provider-http")
