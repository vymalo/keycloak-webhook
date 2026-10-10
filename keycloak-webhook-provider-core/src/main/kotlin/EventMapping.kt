package com.vymalo.keycloak.webhook

import org.keycloak.events.Event
import org.keycloak.events.admin.AdminEvent
import java.math.BigDecimal

/*
 * Keycloak events to webhook payloads. Pure functions on purpose: no config, no I/O,
 * so the golden-payload tests pin them exactly and every transport sends the same thing.
 */

/** A user-facing event (login, logout, register, ...). */
fun Event.toPayload() = WebhookPayload(
    type = type.toString(),
    realmId = realmId,
    realmName = if (hasRealmName.get(javaClass)) realmName else null,
    id = id,
    time = BigDecimal(time),
    clientId = clientId,
    userId = userId,
    sessionId = sessionId,
    ipAddress = ipAddress,
    error = error,
    details = details,
)

/**
 * An admin action. The type is `<RESOURCE>-<OPERATION>` (e.g. `USER-CREATE`), the actor
 * comes from the auth details, and the representation is always included; Keycloak's
 * own `includeRepresentation` switch has never been applied here.
 */
fun AdminEvent.toPayload() = WebhookPayload(
    type = "$resourceType-$operationType",
    realmId = realmId,
    realmName = if (hasRealmName.get(javaClass)) realmName else null,
    id = id,
    time = BigDecimal(time),
    clientId = authDetails?.clientId,
    userId = authDetails?.userId,
    ipAddress = authDetails?.ipAddress,
    error = error,
    resourcePath = resourcePath,
    representation = representation,
)

/**
 * `getRealmName()` only exists since Keycloak 25; calling it on an older server throws
 * NoSuchMethodError. A class either has it or not, so reflection runs once per class.
 */
private val hasRealmName = object : ClassValue<Boolean>() {
    override fun computeValue(type: Class<*>) = runCatching { type.getDeclaredMethod("getRealmName") }.isSuccess
}
