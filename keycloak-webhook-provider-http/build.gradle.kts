import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("org.openapi.generator") version "7.12.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.vymalo.keycloak.webhook"
version = "0.8.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(project(":keycloak-webhook-provider-core"))
    
    implementation("org.keycloak", "keycloak-services", "26.1.3")

    implementation("com.squareup.okhttp3", "okhttp", "4.12.0")
    implementation("com.google.code.gson", "gson", "2.12.1")
    implementation("com.squareup.okio", "okio-jvm", "3.10.2")
    implementation("org.slf4j", "slf4j-log4j12", "2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

val generatedSourcesDir = "${layout.buildDirectory.asFile.get()}/generated/openapi"
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
            include(dependency("com.squareup.okhttp3:okhttp"))
            include(dependency("com.squareup.okio:okio-jvm"))
        }
        dependsOn(build)
    }
}