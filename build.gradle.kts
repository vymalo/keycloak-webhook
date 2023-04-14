import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.8.0"
    id("org.openapi.generator") version "6.5.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("groovy")
}

group = "com.vymalo.keycloak.webhook"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    api("org.apache.commons", "commons-lang3", "3.12.0")

    implementation("org.keycloak", "keycloak-services", "21.0.2")
    implementation("org.keycloak", "keycloak-server-spi", "21.0.2")
    implementation("org.keycloak", "keycloak-server-spi-private", "21.0.2")

    api("com.squareup.moshi", "moshi-kotlin", "1.13.0")
    api("com.squareup.moshi", "moshi-adapters", "1.13.0")
    api("com.squareup.okhttp3", "okhttp", "4.10.0")

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
            "dateLibrary" to "java8"
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
            include(dependency("com.squareup.moshi:.*"))
            include(dependency("com.squareup.okhttp3:.*"))
            include(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
        }
    }
}
