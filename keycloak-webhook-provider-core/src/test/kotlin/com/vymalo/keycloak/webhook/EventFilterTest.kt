package com.vymalo.keycloak.webhook

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventFilterTest {

    @Test
    fun `unset takes everything`() {
        assertTrue(EventFilter.parse(null).accepts("LOGIN"))
    }

    @Test
    fun `an empty value takes nothing, unlike unset`() {
        assertFalse(EventFilter.parse("").accepts("LOGIN"))
    }
}
