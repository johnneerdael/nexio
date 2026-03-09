package com.nexio.tv.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SafeApiCallTest {

    @Test
    fun `safeApiCall rethrows cancellation exceptions`() = runTest {
        try {
            safeApiCall<Any> {
                throw CancellationException("cancelled")
            }
            fail("Expected CancellationException")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
    }
}
