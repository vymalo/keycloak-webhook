package com.vymalo.keycloak.webhook

import org.keycloak.events.Event
import org.keycloak.events.admin.AdminEvent

fun hasDeclaredMethod(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Boolean {
    return try {
        clazz.getDeclaredMethod(methodName, *parameterTypes)
        true
    } catch (e: NoSuchMethodException) {
        false
    }
}

fun AdminEvent.getAdminEventRealmName(): String? {
    val exists = hasDeclaredMethod(this::class.java, "getRealmName")
    return if (exists) {
        this.realmName
    } else {
        null
    }
}

fun Event.getEventRealmName(): String? {
    val exists = hasDeclaredMethod(this::class.java, "getRealmName")
    return if (exists) {
        this.realmName
    } else {
        null
    }
}