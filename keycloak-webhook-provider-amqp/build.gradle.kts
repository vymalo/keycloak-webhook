import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.vymalo.keycloak.webhook"
version = "0.10.0-rc.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(project(":keycloak-webhook-provider-core"))

    implementation("org.keycloak", "keycloak-services", "26.4.0")
    
    implementation("com.google.code.gson", "gson", "2.12.1")
    implementation("com.rabbitmq", "amqp-client", "5.25.0")
    implementation("org.slf4j", "slf4j-log4j12", "2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

tasks {
    val shadowJar by existing(ShadowJar::class) {
        dependencies {
            include(dependency("com.rabbitmq:amqp-client"))
        }
        dependsOn(build)
    }
}