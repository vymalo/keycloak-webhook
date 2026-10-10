package com.vymalo.keycloak.webhook.helper

/**
 * Where settings come from. Keeping this a plain function turns config parsing into
 * a pure `(ConfigSource) -> Config` step that tests can feed from a map.
 */
fun interface ConfigSource {
    operator fun get(key: String): String?

    companion object {
        /** Env vars first, then JVM system properties (`-D` flags): the lookup order the plugin has always used. */
        val Environment = ConfigSource { key -> System.getenv(key) ?: System.getProperty(key) }
    }
}

/** Every missing or malformed setting at once, so a broken deployment is fixed in one pass. */
class ConfigException(val problems: List<String>) :
    IllegalStateException("Invalid webhook configuration: ${problems.joinToString("; ")}")

/**
 * Reads typed settings and collects problems instead of failing on the first one.
 *
 * Getters return a harmless placeholder for a bad value; [read] throws before the
 * placeholder could ever be used. Only ever used through [read].
 */
class ConfigReader internal constructor(private val source: ConfigSource) {
    internal val problems = mutableListOf<String>()

    /** Must be set. An empty value still counts as set: an empty AMQP exchange name is the broker's default exchange. */
    fun required(key: String): String = source[key] ?: "".also { problems += "$key is required" }

    /** The raw value, or null when unset. */
    fun optional(key: String): String? = source[key]

    /** Unset and empty both fall back to [default]. */
    fun orDefault(key: String, default: String): String = source[key].orEmpty().ifEmpty { default }

    /** On only for the exact string `true`, as flags have always been read. */
    fun flag(key: String): Boolean = source[key] == "true"

    /** A whole number; required unless a [default] is given. */
    fun int(key: String, default: Int? = null): Int = number(key, default, String::toIntOrNull) ?: 0

    /** A whole number; required unless a [default] is given. */
    fun long(key: String, default: Long? = null): Long = number(key, default, String::toLongOrNull) ?: 0

    /**
     * One of [values] by constant name, e.g. `RFC_5424`. Required unless a [default] is
     * given; names must match exactly unless [ignoreCase] is set.
     */
    fun <E : Enum<E>> enum(key: String, values: Array<E>, default: E? = null, ignoreCase: Boolean = false): E {
        val raw = source[key].orEmpty()
        if (raw.isEmpty()) return default ?: values.first().also { problems += "$key is required" }
        return values.firstOrNull { it.name.equals(raw, ignoreCase) }
            ?: values.first().also { problems += "$key must be one of ${values.joinToString { it.name }}, got '$raw'" }
    }

    private fun <N : Number> number(key: String, default: N?, parse: (String) -> N?): N? {
        val raw = source[key].orEmpty()
        if (raw.isEmpty()) return default ?: null.also { problems += "$key is required" }
        return parse(raw) ?: null.also { problems += "$key must be a whole number, got '$raw'" }
    }
}

/** Builds a config with [block], throwing one [ConfigException] that lists everything wrong. */
fun <T> ConfigSource.read(block: ConfigReader.() -> T): T {
    val reader = ConfigReader(this)
    val config = reader.block()
    if (reader.problems.isNotEmpty()) throw ConfigException(reader.problems)
    return config
}
