package com.nexio.tv.data.trailer.cipher

internal data class SpliceCipherOperation(val index: Int) : CipherOperation {
    override fun decipher(input: String): String = input.substring(index)
}
