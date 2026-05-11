package com.nexio.tv.data.trailer.cipher

internal data class SwapCipherOperation(val index: Int) : CipherOperation {
    override fun decipher(input: String): String {
        val chars = input.toCharArray()
        val tmp = chars[0]
        chars[0] = chars[index]
        chars[index] = tmp
        return String(chars)
    }
}
