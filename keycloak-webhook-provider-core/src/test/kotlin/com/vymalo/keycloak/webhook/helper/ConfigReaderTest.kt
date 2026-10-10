package com.vymalo.keycloak.webhook.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigReaderTest {

    private fun source(vararg entries: Pair<String, String>) = ConfigSource(mapOf(*entries)::get)

    @Test
    fun `reports every problem in one exception`() {
        val error = assertFailsWith<ConfigException> {
            source("PORT" to "abc").read {
                required("HOST")
                int("PORT")
                int("TIMEOUT")
            }
        }
        assertEquals(
            listOf("HOST is required", "PORT must be a whole number, got 'abc'", "TIMEOUT is required"),
            error.problems,
        )
    }

    @Test
    fun `empty required value counts as set`() {
        assertEquals("", source("EXCHANGE" to "").read { required("EXCHANGE") })
    }

    @Test
    fun `defaults apply to unset and empty values alike`() {
        val config = source("VHOST" to "", "TIMEOUT" to "").read {
            orDefault("VHOST", "/") to long("TIMEOUT", 5_000)
        }
        assertEquals("/" to 5_000L, config)
    }

    @Test
    fun `flags are on only for the exact string true`() {
        val flags = source("A" to "true", "B" to "yes", "C" to "TRUE").read {
            listOf(flag("A"), flag("B"), flag("C"), flag("D"))
        }
        assertEquals(listOf(true, false, false, false), flags)
    }

    private enum class Format { RFC_3164, RFC_5424 }

    @Test
    fun `enums match constant names exactly and list the choices when wrong`() {
        assertEquals(Format.RFC_3164, source().read { enum("FORMAT", Format.values(), Format.RFC_3164) })
        val error = assertFailsWith<ConfigException> {
            source("FORMAT" to "rfc_5424").read { enum("FORMAT", Format.values(), Format.RFC_3164) }
        }
        assertEquals(listOf("FORMAT must be one of RFC_3164, RFC_5424, got 'rfc_5424'"), error.problems)
    }

    @Test
    fun `enums can ignore case and be required`() {
        assertEquals(Format.RFC_5424, source("FORMAT" to "rfc_5424").read { enum("FORMAT", Format.values(), ignoreCase = true) })
        val error = assertFailsWith<ConfigException> { source().read { enum("FORMAT", Format.values()) } }
        assertEquals(listOf("FORMAT is required"), error.problems)
    }
}
