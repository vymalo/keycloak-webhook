package com.vymalo.keycloak.webhook.testing

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertTrue

/**
 * Collects what [owner] logs at error level while [block] runs, and keeps it off the
 * console. Keycloak's dependencies put Quarkus' JBoss LogManager behind SLF4J here,
 * the same path production logs take, and it is a java.util.logging LogManager, so a
 * plain JUL handler sees every record.
 */
fun errorsLoggedBy(owner: Class<*>, block: () -> Unit): List<LogRecord> {
    val logger = Logger.getLogger(owner.name)
    val records = mutableListOf<LogRecord>()
    val capture = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.level.intValue() >= Level.SEVERE.intValue()) synchronized(records) { records += record }
        }
        override fun flush() {}
        override fun close() {}
    }
    val inheritedHandlers = logger.useParentHandlers
    logger.addHandler(capture)
    logger.useParentHandlers = false
    try {
        block()
    } finally {
        logger.removeHandler(capture)
        logger.useParentHandlers = inheritedHandlers
    }
    assertTrue(records.isNotEmpty(), "expected ${owner.simpleName} to log an error")
    return records
}
