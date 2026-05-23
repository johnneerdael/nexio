package com.nexio.tv.data.trailer.potoken

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)
    val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        JSONArray(descramble(scrambled.getString(1)))
    } else {
        scrambled.getJSONArray(0)
    }

    fun firstStringInNestedArray(index: Int): Any {
        val array = challengeData.optJSONArray(index) ?: return JSONObject.NULL
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            if (value is String) return value
        }
        return JSONObject.NULL
    }

    val interpreter = JSONObject().apply {
        put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstStringInNestedArray(1))
        put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstStringInNestedArray(2))
    }

    return JSONObject().apply {
        put("messageId", challengeData.getString(0))
        put("interpreterJavascript", interpreter)
        put("interpreterHash", challengeData.getString(3))
        put("program", challengeData.getString(4))
        put("globalName", challengeData.getString(5))
        put("clientExperimentsStateBlob", challengeData.getString(7))
    }.toString()
}

fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JSONArray(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

fun stringToU8(identifier: String): String =
    newUint8Array(identifier.toByteArray())

fun u8ToBase64(poToken: String): String {
    return poToken.split(",")
        .filter { it.isNotBlank() }
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")
}

private fun descramble(scrambledChallenge: String): String {
    return base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()
}

private fun base64ToU8(base64: String): String =
    newUint8Array(base64ToByteString(base64))

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" +
        contents.joinToString(separator = ",") { it.toUByte().toString() } +
        "])"
}

private fun base64ToByteString(base64: String): ByteArray {
    val standardized = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return (standardized.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
