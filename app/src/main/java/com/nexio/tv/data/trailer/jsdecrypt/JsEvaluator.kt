package com.nexio.tv.data.trailer.jsdecrypt

import com.squareup.duktape.Duktape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun captureReturnFromEval(formattedFunction: String): Any? =
    withContext(Dispatchers.IO) {
        val duktape = Duktape.create()
        try {
            duktape.evaluate(formattedFunction)
        } finally {
            duktape.close()
        }
    }

