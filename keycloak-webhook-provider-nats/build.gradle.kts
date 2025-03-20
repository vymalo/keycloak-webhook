import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.vymalo.keycloak.webhook"
version = "0.8.3"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(project(":keycloak-webhook-provider-core"))

    implementation("org.keycloak", "keycloak-services", "26.1.3")
    
    implementation("com.google.code.gson", "gson", "2.12.1")
    implementation("io.nats", "jnats", "2.20.5")
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
            include(dependency("io.nats:jnats"))
            include(dependency("com.google.code.gson:gson"))
            include(dependency("org.slf4j:slf4j-log4j12"))
            include(dependency("org.slf4j:slf4j-api"))
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
            include(dependency("org.jetbrains:annotations"))
        }
        mergeServiceFiles()
        relocate("com.google.gson", "com.vymalo.keycloak.webhook.shaded.com.google.gson")
        dependsOn(build)
    }
}