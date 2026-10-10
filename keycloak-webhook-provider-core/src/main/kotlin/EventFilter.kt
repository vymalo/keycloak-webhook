package com.vymalo.keycloak.webhook

/** Decides which event types are sent at all. */
fun interface EventFilter {
    fun accepts(type: String): Boolean

    companion object {
        val ALL = EventFilter { true }

        /**
         * Parses `WEBHOOK_EVENTS_TAKEN`: comma-separated types, spaces around names ignored.
         * Unset means everything. An *empty* value is not unset, though: it only matches an
         * empty type, so nothing gets through. Odd, but that is how it has always behaved.
         */
        fun parse(raw: String?): EventFilter =
            if (raw == null) ALL else OnlyTypes(raw.trim().split(",").map { it.trim() }.toSet())
    }
}

/** A data class so debug logs show which types are taken. */
private data class OnlyTypes(val types: Set<String>) : EventFilter {
    override fun accepts(type: String) = type in types
}
