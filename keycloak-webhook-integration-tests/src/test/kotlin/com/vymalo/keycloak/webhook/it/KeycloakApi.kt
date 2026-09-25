package com.vymalo.keycloak.webhook.it

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Just the Keycloak calls the scenarios need, over plain HTTP: the admin API to set things up,
 * and the OIDC endpoints a real client uses, each of which makes Keycloak fire one event.
 */
class KeycloakApi(private val baseUrl: String) {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val gson = Gson()

    class Response(val status: Int, val body: String) {
        val ok get() = status in 200..299
        fun json(): JsonObject = JsonParser.parseString(body).asJsonObject
    }

    // ---- admin ----

    /** A fresh admin token per call: they expire after a minute, and a load run lasts longer. */
    private fun adminToken(): String =
        form("/realms/master/protocol/openid-connect/token", "grant_type" to "password", "client_id" to "admin-cli",
            "username" to "admin", "password" to "admin")
            .also { check(it.ok) { "admin login failed: ${it.status} ${it.body}" } }
            .json()["access_token"].asString

    fun createRealm(realm: Map<String, Any>) =
        send(json("/admin/realms", realm, adminToken())).also { check(it.status == 201) { "realm: ${it.status} ${it.body}" } }

    /** An admin action, so Keycloak fires an admin event (USER-CREATE). */
    fun createUser(realm: String, username: String) = send(
        json("/admin/realms/$realm/users", testUser(username), adminToken())
    ).also { check(it.status == 201) { "user: ${it.status} ${it.body}" } }

    fun serverInfo(): JsonObject = send(
        HttpRequest.newBuilder(uri("/admin/serverinfo")).header("Authorization", "Bearer ${adminToken()}").GET().build()
    ).json()

    fun realmId(realm: String): String = send(
        HttpRequest.newBuilder(uri("/admin/realms/$realm")).header("Authorization", "Bearer ${adminToken()}").GET().build()
    ).json()["id"].asString

    // ---- what a client does (one event each) ----

    fun passwordLogin(realm: String, username: String, password: String = TEST_PASSWORD) =
        token(realm, "grant_type" to "password", "username" to username, "password" to password, "scope" to "openid")

    fun refresh(realm: String, refreshToken: String) =
        token(realm, "grant_type" to "refresh_token", "refresh_token" to refreshToken)

    fun clientCredentials(realm: String) = token(realm, "grant_type" to "client_credentials")

    fun userInfo(realm: String, accessToken: String) = send(
        HttpRequest.newBuilder(uri("/realms/$realm/protocol/openid-connect/userinfo"))
            .header("Authorization", "Bearer $accessToken").GET().build()
    )

    fun introspect(realm: String, accessToken: String) =
        form("/realms/$realm/protocol/openid-connect/token/introspect",
            "token" to accessToken, "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET)

    fun logout(realm: String, refreshToken: String) =
        form("/realms/$realm/protocol/openid-connect/logout",
            "refresh_token" to refreshToken, "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET)

    private fun token(realm: String, vararg fields: Pair<String, String>) =
        form("/realms/$realm/protocol/openid-connect/token", "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET, *fields)

    // ---- plumbing ----

    private fun uri(path: String) = URI.create(baseUrl + path)

    private fun form(path: String, vararg fields: Pair<String, String>): Response = send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }))
            .build()
    )

    private fun json(path: String, body: Any, token: String) = HttpRequest.newBuilder(uri(path))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        .build()

    /** Generous timeout: in sync mode a hanging broker holds every request for the confirm timeout, one after another. */
    private fun send(request: HttpRequest): Response {
        val timed = HttpRequest.newBuilder(request) { _, _ -> true }.timeout(Duration.ofMinutes(5)).build()
        val response = http.send(timed, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body())
    }

    companion object {
        const val CLIENT_ID = "webhook-it"
        const val CLIENT_SECRET = "webhook-it-secret"
        const val TEST_PASSWORD = "pw"

        /**
         * A realm that sends its events to the plugin. The password policy makes hashing cheap,
         * so logins cost what a real login costs minus the deliberate slowness; events aren't
         * stored in Keycloak's own database, which the plugin doesn't need and a load run would bloat.
         */
        fun testRealm(name: String, users: Int, listeners: List<String> = listOf("webhook-amqp")) = mapOf(
            "realm" to name,
            "enabled" to true,
            "eventsEnabled" to false,
            "eventsListeners" to listeners,
            "adminEventsEnabled" to true,
            "adminEventsDetailsEnabled" to true,
            "passwordPolicy" to "hashIterations(1)",
            "clients" to listOf(
                mapOf(
                    "clientId" to CLIENT_ID,
                    "enabled" to true,
                    "publicClient" to false,
                    "secret" to CLIENT_SECRET,
                    "directAccessGrantsEnabled" to true,
                    "serviceAccountsEnabled" to true,
                    "standardFlowEnabled" to false,
                )
            ),
            "users" to (1..users).map { testUser("user$it") },
        )

        /** Complete enough that Keycloak 24+'s user profile doesn't ask for more before letting them log in. */
        fun testUser(username: String) = mapOf(
            "username" to username,
            "enabled" to true,
            "email" to "$username@example.com",
            "emailVerified" to true,
            "firstName" to "Test",
            "lastName" to username,
            "credentials" to listOf(mapOf("type" to "password", "value" to TEST_PASSWORD, "temporary" to false)),
        )
    }
}
