import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.vymalo.keycloak.webhook"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(project(":keycloak-webhook-provider-core"))

    implementation("org.keycloak", "keycloak-services", "26.1.3")
    
    implementation("com.google.code.gson", "gson", "2.12.1")
    implementation("com.cloudbees", "syslog-java-client", "1.1.7")
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
            include(dependency("com.cloudbees:syslog-java-client"))
        }
        dependsOn(build)
    }
}