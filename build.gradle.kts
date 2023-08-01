import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar


plugins {
    kotlin("jvm") version "1.9.0"
    id("org.openapi.generator") version "6.5.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("groovy")
}

group = "com.vymalo.keycloak.webhook"
version = "0.3.0"

val gsonVersion = "2.10.1"
val amqpVersion = "5.17.0"
val okhttp3Version = "4.10.0"
val okioVersion = "3.0.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    api("org.apache.commons", "commons-lang3", "3.12.0")

    implementation(kotlin("stdlib"))

    implementation("org.keycloak", "keycloak-services", "22.0.1")
    implementation("org.keycloak", "keycloak-server-spi", "22.0.1")
    implementation("org.keycloak", "keycloak-server-spi-private", "22.0.1")

    api("com.squareup.okhttp3", "okhttp", okhttp3Version)
    api("com.rabbitmq", "amqp-client", amqpVersion)
    api("com.google.code.gson", "gson", gsonVersion)

    api("org.slf4j", "slf4j-log4j12", "1.7.36")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

val generatedSourcesDir = "$buildDir/generated/openapi"
val openapiPackageName = "com.vymalo.keycloak.openapi.client"

openApiGenerate {
    generatorName.set("kotlin")

    inputSpec.set("$rootDir/openapi/webhook.open-api.yml")
    outputDir.set(generatedSourcesDir)

    packageName.set(openapiPackageName)
    apiPackage.set("$openapiPackageName.handler")
    invokerPackage.set("$openapiPackageName.invoker")
    modelPackage.set("$openapiPackageName.model")

    httpUserAgent.set("Keycloak/Kotlin")

    configOptions.set(
        mutableMapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "gson"
        )
    )
}

sourceSets {
    getByName("main") {
        kotlin {
            srcDir("$generatedSourcesDir/src/main/kotlin")
        }
    }
}

tasks {
    val openApiGenerate by getting

    val compileKotlin by getting {
        dependsOn(openApiGenerate)
    }

    val shadowJar by existing(ShadowJar::class) {
        dependencies {
            include(dependency("com.squareup.okhttp3:okhttp:$okhttp3Version"))
            include(dependency("com.squareup.okio:okio-jvm:$okioVersion"))
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib:1.9.0"))
            include(dependency("org.jetbrains.kotlin:kotlin-reflect:1.9.0"))
            include(dependency("com.rabbitmq:amqp-client:$amqpVersion"))
            include(dependency("com.google.code.gson:gson:$gsonVersion"))
        }
        dependsOn(build)
    }
}
