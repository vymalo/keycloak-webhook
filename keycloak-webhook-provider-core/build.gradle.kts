import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.vymalo.keycloak.webhook"
version = "0.8.2"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.apache.commons", "commons-lang3", "3.17.0")
    implementation("com.google.code.gson", "gson", "2.12.1")

    implementation("org.keycloak", "keycloak-services", "26.1.3")
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
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
            include(dependency("com.google.code.gson:gson"))
        }
        dependsOn(build)
    }
}
