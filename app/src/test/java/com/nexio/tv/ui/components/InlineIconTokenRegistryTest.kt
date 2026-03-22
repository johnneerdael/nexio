package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineIconTokenRegistryTest {

    @Test
    fun `token registry marks title badges as prominent`() {
        val registryClass = Class.forName("com.nexio.tv.ui.components.InlineIconTokenRegistry")
        val resolveMethod = registryClass.methods.firstOrNull {
            it.name == "resolve" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: error("resolve(String) method missing")
        val registryInstance = runCatching {
            registryClass.getField("INSTANCE").get(null)
        }.getOrElse {
            runCatching {
                registryClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrNull()
        }
        val token = resolveMethod.invoke(registryInstance, "4k")
            ?: error("resolve(4k) returned null")
        val scaleClassValue = token.javaClass.methods.firstOrNull {
            it.name == "getScaleClass" && it.parameterCount == 0
        }?.invoke(token) ?: run {
            val field = token.javaClass.getDeclaredField("scaleClass")
            field.isAccessible = true
            field.get(token)
        }

        assertEquals("TITLE_PROMINENT", scaleClassValue.toString())
    }
}
