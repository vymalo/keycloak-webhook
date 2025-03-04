plugins {
    kotlin("jvm") version "2.1.10"
}

group = "com.vymalo.keycloak.webhook"
version = "0.8.1"

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib"))
    }
}
