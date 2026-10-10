package com.vymalo.keycloak.webhook.it

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Organic client traffic against one realm. Each simulated session does what a real app
 * does: log in, refresh a few times, ask for user info, introspect, log out. Mixed in are
 * failed logins and service accounts. Every successful call makes Keycloak fire one event,
 * and the generator counts them per type, so the scenario knows exactly what should arrive.
 */
class Traffic(private val api: KeycloakApi, private val realm: String, private val users: Int) {

    /** One request: when it started (epoch ms), how long it took, and whether Keycloak answered as expected. */
    class Sample(val startedAtMs: Long, val latencyMs: Long, val ok: Boolean)

    /** What a run produced, as Keycloak saw it. */
    class Report(val expectedByType: Map<String, Int>, val samples: List<Sample>, val tookMs: Long) {
        val expectedEvents get() = expectedByType.values.sum()
        val requests get() = samples.size
        val failedRequests get() = samples.count { !it.ok }

        fun percentile(p: Double): Long = samples.latencyPercentile(p)
        fun perSecond() = if (tookMs == 0L) 0 else expectedEvents * 1000L / tookMs

        /** Latency and failures of the requests that *started* between [fromMs] and [toMs]. */
        fun window(fromMs: Long, toMs: Long): String {
            val inside = samples.filter { it.startedAtMs in fromMs until toMs }
            return "${inside.size} requests, ${inside.count { !it.ok }} failed, " +
                "latency p50=${inside.latencyPercentile(0.5)}ms p99=${inside.latencyPercentile(0.99)}ms " +
                "max=${inside.maxOfOrNull { it.latencyMs } ?: 0}ms"
        }

        override fun toString() =
            "$expectedEvents events from $requests requests in ${tookMs}ms (${perSecond()}/s), " +
                "latency p50=${percentile(0.5)}ms p95=${percentile(0.95)}ms p99=${percentile(0.99)}ms " +
                "max=${samples.maxOfOrNull { it.latencyMs } ?: 0}ms, unexpected failures=$failedRequests; " +
                "by type: $expectedByType"
    }

    private val expected = ConcurrentHashMap<String, AtomicInteger>()
    private val samples = ConcurrentLinkedQueue<Sample>()
    private val produced = AtomicLong()

    /** Keeps [concurrency] clients busy until about [events] events have been produced. */
    fun run(events: Int, concurrency: Int): Report = drive(concurrency) { produced.get() < events }

    /**
     * Sends about [perSecond] requests a second for [durationMs], spread over [concurrency] clients,
     * so every setup gets the same load however fast it answers. A client that was held up doesn't
     * make up for it afterwards, just as users whose login hung don't come back twice.
     */
    fun runFor(durationMs: Long, concurrency: Int, perSecond: Int): Report {
        val until = System.currentTimeMillis() + durationMs
        return runWhile(concurrency, perSecond) { System.currentTimeMillis() < until }
    }

    /** Like [runFor], but for as long as [keepGoing] says, e.g. until a disruption is over. */
    fun runWhile(concurrency: Int, perSecond: Int, keepGoing: () -> Boolean): Report {
        pacing = Pacing(1_000_000_000L / perSecond)
        return drive(concurrency, keepGoing)
    }

    /** Hands out one start time per request, a fixed interval apart. */
    private class Pacing(private val intervalNanos: Long) {
        private var next = System.nanoTime()

        fun awaitTurn() {
            val slot = synchronized(this) {
                val now = System.nanoTime()
                if (next < now - 1_000_000_000L) next = now // fell behind (a hang): don't catch up
                next.also { next += intervalNanos }
            }
            val wait = slot - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }

    @Volatile
    private var pacing: Pacing? = null

    /** Logs in [count] different users at once, and nothing else. */
    fun logins(count: Int, usernames: (Int) -> String): Report {
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(count)
        repeat(count) { i -> pool.execute { call("LOGIN") { api.passwordLogin(realm, usernames(i)) } } }
        pool.shutdown()
        check(pool.awaitTermination(30, TimeUnit.MINUTES)) { "logins did not finish" }
        return report((System.nanoTime() - started) / 1_000_000)
    }

    private fun drive(concurrency: Int, keepGoing: () -> Boolean): Report {
        val sessions = AtomicInteger()
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(concurrency)
        repeat(concurrency) { pool.execute { while (keepGoing()) session(sessions.incrementAndGet()) } }
        pool.shutdown()
        check(pool.awaitTermination(2, TimeUnit.HOURS)) { "traffic did not finish" }
        return report((System.nanoTime() - started) / 1_000_000)
    }

    /** One simulated client session; some sessions add a failed login or a service account call. */
    private fun session(n: Int) {
        val username = "user${n % users + 1}"
        if (n % 10 == 0) call("LOGIN_ERROR", expectStatus = 401) { api.passwordLogin(realm, username, "wrong") }
        if (n % 5 == 0) call("CLIENT_LOGIN") { api.clientCredentials(realm) }

        val login = call("LOGIN") { api.passwordLogin(realm, username) } ?: return
        var refreshToken = login.json()["refresh_token"].asString
        var accessToken = login.json()["access_token"].asString
        repeat(1 + n % 3) {
            val refreshed = call("REFRESH_TOKEN") { api.refresh(realm, refreshToken) } ?: return
            refreshToken = refreshed.json()["refresh_token"].asString
            accessToken = refreshed.json()["access_token"].asString
        }
        call("USER_INFO_REQUEST") { api.userInfo(realm, accessToken) }
        call("INTROSPECT_TOKEN") { api.introspect(realm, accessToken) }
        call("LOGOUT", expectStatus = 204) { api.logout(realm, refreshToken) }
    }

    /**
     * Times one call and, if Keycloak answered as expected, counts the event it fired.
     * A call that fails outright (a timeout, a dropped connection) is a failed sample, not a crash:
     * outage scenarios expect some.
     */
    private fun call(eventType: String, expectStatus: Int? = null, request: () -> KeycloakApi.Response): KeycloakApi.Response? {
        pacing?.awaitTurn()
        val startedAt = System.currentTimeMillis()
        val started = System.nanoTime()
        val response = runCatching(request).getOrNull()
        val asExpected = response != null && (if (expectStatus != null) response.status == expectStatus else response.ok)
        samples += Sample(startedAt, (System.nanoTime() - started) / 1_000_000, asExpected)
        if (!asExpected) {
            if (samples.count { !it.ok } <= 5) {
                System.err.println("Unexpected $eventType response ${response?.status}: ${response?.body?.take(300)}")
            }
            return null
        }
        expected.computeIfAbsent(eventType) { AtomicInteger() }.incrementAndGet()
        produced.incrementAndGet()
        return response
    }

    private fun report(tookMs: Long) = Report(expected.mapValues { it.value.get() }.toSortedMap(), samples.toList(), tookMs)
}

private fun List<Traffic.Sample>.latencyPercentile(p: Double): Long =
    map { it.latencyMs }.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * p).toInt()] }
