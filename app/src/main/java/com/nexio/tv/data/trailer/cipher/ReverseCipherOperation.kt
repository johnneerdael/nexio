package com.nexio.tv.data.trailer.cipher

internal object ReverseCipherOperation : CipherOperation {
    override fun decipher(input: String): String = input.reversed()
}
