package io.github.neojozapk.trackers

import java.util.logging.Logger

/** Machine-readable log: one finding per line, `TPR|1|` + compact JSON. */
internal object TprLog {
    private val logger: Logger = Logger.getLogger("TPR")

    fun str(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Builds `{"k":v,...}` from pairs; values may be String?, Boolean, Number. */
    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (k, v) ->
            val value = when (v) {
                null -> "null"
                is Boolean, is Number -> v.toString()
                else -> str(v.toString())
            }
            "${str(k)}:$value"
        }

    fun emit(vararg fields: Pair<String, Any?>) = logger.info("TPR|1|" + obj(*fields))
}
