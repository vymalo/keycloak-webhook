plugins {
    `java-test-fixtures`
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.4.2"
}

group = "com.vymalo.keycloak.webhook"
version = "0.10.0-rc.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.apache.commons", "commons-lang3", "3.17.0")
    implementation("com.google.code.gson", "gson", "2.12.1")

    implementation("org.keycloak", "keycloak-services", "26.4.0")

    // Shared by every provider's tests: sample Keycloak events, their golden
    // payloads and a tiny stand-in for Keycloak's provider lifecycle.
    testFixturesApi(kotlin("test"))
    testFixturesApi("org.keycloak", "keycloak-services", "26.4.0")
    testFixturesApi("com.google.code.gson", "gson", "2.12.1")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks {
    shadowJar {
        dependencies {
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
            include(dependency("com.google.code.gson:gson"))
        }
    }
}
