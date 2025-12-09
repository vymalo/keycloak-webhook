plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = "com.vymalo.keycloak.webhook"
version = "0.10.0-rc.1"

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib"))
    }
}

changelog {
    unreleasedTerm.set("next")
    groups.empty()
    repositoryUrl.set("https://github.com/vymalo/keycloak-webhook")
}
