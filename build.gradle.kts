import org.jetbrains.changelog.date

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.changelog") version "2.2.1"
}

group = "com.vymalo.keycloak.webhook"
version = "0.8.3"

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
