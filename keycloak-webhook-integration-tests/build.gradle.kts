plugins {
    kotlin("jvm")
}

group = "com.vymalo.keycloak.webhook"
version = "0.10.0-rc.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter", "junit-jupiter-params", "5.10.1")
    // Gradle 9 no longer brings the launcher along for a custom Test task.
    testRuntimeOnly("org.junit.platform", "junit-platform-launcher", "1.10.1")
    testImplementation("org.testcontainers", "testcontainers", "2.0.5")
    testImplementation("com.rabbitmq", "amqp-client", "5.25.0")
    testImplementation("com.google.code.gson", "gson", "2.12.1")
    // Gives Testcontainers somewhere to log, so a Keycloak that fails to start explains why.
    testRuntimeOnly("org.slf4j", "slf4j-simple", "2.0.17")
}

kotlin {
    jvmToolchain(17)
}

// The jars under test are the shaded ones we'd deploy, not this build's classes.
evaluationDependsOn(":keycloak-webhook-provider-core")
evaluationDependsOn(":keycloak-webhook-provider-amqp")
val pluginJars = listOf(":keycloak-webhook-provider-core", ":keycloak-webhook-provider-amqp")
    .map { project(it).tasks.named<Jar>("shadowJar") }

tasks.test {
    // Declared even though disabled: the Kotlin plugin picks kotlin-test's JUnit 5 flavour from it.
    useJUnitPlatform()
    // These tests start Keycloak many times and take a while; they only run through integrationTest.
    enabled = false
}

tasks.register<Test>("integrationTest") {
    description = "Runs the plugin inside real Keycloak containers against RabbitMQ (needs Docker, takes minutes)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "2g"
    // A fresh test JVM per class: each class starts its own containers anyway, and this hands its
    // memory back before the next one. run-sequentially.sh goes further, with one Gradle run per class.
    forkEvery = 1

    dependsOn(pluginJars)
    doFirst {
        systemProperty("it.pluginJars", pluginJars.joinToString(File.pathSeparator) { it.get().archiveFile.get().asFile.path })
    }
    // -PkeycloakVersion=26.2.3 -PkeycloakVersions=21.1.2,26.2.3 -Pevents=50000 -Pconcurrency=32
    // -PoutageSeconds=120 -PoutageRate=25
    listOf("keycloakVersion", "keycloakVersions", "events", "concurrency", "outageSeconds", "outageRate").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty("it.$key", it) }
    }

    // Always a fresh run: this task is started by hand to learn something, not to be skipped as up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true // throughput and latency reports
    }
}
