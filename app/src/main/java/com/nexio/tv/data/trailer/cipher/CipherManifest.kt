package com.nexio.tv.data.trailer.cipher

internal data class CipherManifest(
    val signatureTimestamp: String,
    val operations: List<CipherOperation>
) {
    fun decipher(input: String): String =
        operations.fold(input) { acc, op -> op.decipher(acc) }
}
