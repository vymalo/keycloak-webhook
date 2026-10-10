package com.vymalo.keycloak.webhook.it

import java.io.File

/** What the integrationTest task hands in; see its `-P` properties in build.gradle.kts. */
object ItSettings {

    /** The shaded core and AMQP jars, exactly as they would be copied into Keycloak's providers folder. */
    val pluginJars: List<File> = System.getProperty("it.pluginJars")
        ?.split(File.pathSeparator)
        ?.map(::File)
        ?.onEach { check(it.isFile) { "Plugin jar missing: $it. Run through ./gradlew integrationTest." } }
        ?: error("it.pluginJars is not set. Run through ./gradlew integrationTest.")

    /** The version the heavy scenarios run on: the one we run in production. */
    val keycloakVersion: String = System.getProperty("it.keycloakVersion") ?: "26.2.3"

    /** Every version the README promises, smoke-tested with default settings. */
    val keycloakVersions: List<String> = System.getProperty("it.keycloakVersions")?.split(',')?.map { it.trim() }
        ?: listOf("21.1.2", "22.0.5", "23.0.7", "24.0.5", "25.0.6", "26.2.3", "26.4.0")

    /** How many events the organic load scenario produces. */
    val events: Int = System.getProperty("it.events")?.toInt() ?: 50_000

    /** Parallel HTTP clients driving that load. */
    val concurrency: Int = System.getProperty("it.concurrency")?.toInt() ?: 32

    /** How long the broker hangs in the long outage scenario. */
    val outageSeconds: Long = System.getProperty("it.outageSeconds")?.toLong() ?: 120

    /**
     * Requests a second during the long outage scenario, the same for every setup. An async buffer
     * must hold this times the outage length to survive it without dropping.
     */
    val outageRate: Int = System.getProperty("it.outageRate")?.toInt() ?: 25
}
