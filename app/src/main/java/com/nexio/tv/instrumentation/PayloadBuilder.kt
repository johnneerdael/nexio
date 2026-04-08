package com.nexio.tv.instrumentation

/**
 * Thin allocation-free wrapper around the [TraceRecord.payloadBuffer]
 * `StringBuilder`. Each `put*` call appends a leading `,"k":...` JSON fragment
 * suitable for direct splicing into the common envelope written by
 * [SessionWriter].
 */
@JvmInline
value class PayloadBuilder(private val record: TraceRecord) {

    fun putString(key: String, value: String?) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":")
        if (value == null) {
            sb.append("null")
        } else {
            sb.append('"')
            appendEscaped(sb, value)
            sb.append('"')
        }
    }

    fun putInt(key: String, value: Int) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(value)
    }

    fun putLong(key: String, value: Long) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(value)
    }

    fun putBool(key: String, value: Boolean) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(if (value) "true" else "false")
    }

    fun putDouble(key: String, value: Double) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(value)
    }

    private fun appendEscaped(sb: StringBuilder, value: String) {
        var i = 0
        val n = value.length
        while (i < n) {
            val c = value[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            i++
        }
    }
}
