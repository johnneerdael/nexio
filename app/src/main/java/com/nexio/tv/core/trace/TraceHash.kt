package com.nexio.tv.core.trace

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TraceHash {
    fun of(salt: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(value.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }.take(12)
    }
}
