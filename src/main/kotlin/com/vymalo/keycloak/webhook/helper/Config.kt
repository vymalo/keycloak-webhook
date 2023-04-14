package com.vymalo.keycloak.webhook.helper

fun getConfig(key: String): String? = System.getenv(key) ?: System.getProperties().getProperty(key)