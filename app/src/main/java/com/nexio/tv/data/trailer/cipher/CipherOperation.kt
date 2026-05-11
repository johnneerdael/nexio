package com.nexio.tv.data.trailer.cipher

internal interface CipherOperation {
    fun decipher(input: String): String
}
