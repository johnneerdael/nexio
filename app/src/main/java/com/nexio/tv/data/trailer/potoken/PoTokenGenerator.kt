package com.nexio.tv.data.trailer.potoken

import android.content.Context
import java.io.Closeable

interface PoTokenGenerator : Closeable {
    suspend fun generatePoToken(identifier: String): String

    fun isExpired(): Boolean

    interface Factory {
        suspend fun newPoTokenGenerator(context: Context): PoTokenGenerator
    }
}
