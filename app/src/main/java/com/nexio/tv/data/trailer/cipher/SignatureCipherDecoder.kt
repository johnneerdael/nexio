package com.nexio.tv.data.trailer.cipher

import android.net.Uri
import java.net.URLDecoder

/**
 * Decodes a YouTube `signatureCipher` field into a playable URL.
 *
 * The `signatureCipher` field is URL-encoded key-value pairs of the
 * form `s=<encrypted-signature>&sp=<sig-param-name>&url=<URL-encoded-base-URL>`.
 *
 * After deciphering `s` with the given [CipherManifest] and setting it
 * as the `sp`-named query parameter on the base URL, the result is a
 * directly fetchable googlevideo URL.
 *
 * Port of YoutubeExplode's StreamClient cipher-application logic.
 */
internal object SignatureCipherDecoder {

    fun decode(signatureCipher: String, manifest: CipherManifest): String? {
        val pairs = signatureCipher
            .split('&')
            .mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq < 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
            }
            .toMap()

        val encryptedSig = pairs["s"] ?: return null
        val sigParam = pairs["sp"] ?: "sig"
        val urlEncoded = pairs["url"] ?: return null

        val sigDecoded = runCatching { URLDecoder.decode(encryptedSig, "UTF-8") }
            .getOrNull() ?: return null
        val deciphered = manifest.decipher(sigDecoded)

        val baseUrl = runCatching { URLDecoder.decode(urlEncoded, "UTF-8") }
            .getOrNull() ?: return null

        val baseUri = runCatching { Uri.parse(baseUrl) }.getOrNull() ?: return null

        val rebuilt = Uri.Builder()
            .scheme(baseUri.scheme)
            .authority(baseUri.authority)
            .path(baseUri.path)
        for (key in baseUri.queryParameterNames) {
            if (key == sigParam) continue
            rebuilt.appendQueryParameter(key, baseUri.getQueryParameter(key).orEmpty())
        }
        rebuilt.appendQueryParameter(sigParam, deciphered)
        return rebuilt.build().toString()
    }
}
