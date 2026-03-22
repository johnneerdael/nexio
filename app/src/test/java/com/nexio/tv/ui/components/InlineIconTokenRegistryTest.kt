package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `token registry strips unknown tokens to plain text fallback`() {
        val registryClass = Class.forName("com.nexio.tv.ui.components.InlineIconTokenRegistry")
        val tokenizeMethod = registryClass.methods.firstOrNull {
            it.name == "tokenize" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: error("tokenize(String) method missing")
        val registryInstance = runCatching {
            registryClass.getField("INSTANCE").get(null)
        }.getOrElse {
            runCatching {
                registryClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrNull()
        }

        @Suppress("UNCHECKED_CAST")
        val segments = tokenizeMethod.invoke(
            registryInstance,
            "Title [[icon:4k]] and [[icon:unknown_badge]]"
        ) as? List<Any?> ?: error("tokenize(String) did not return a List")

        assertTrue("expected at least one icon segment", segments.any { segment ->
            segment?.javaClass?.methods?.any { it.name == "getToken" && it.parameterCount == 0 } == true
        })

        val flattenedText = segments.joinToString(separator = "") { segment ->
            when {
                segment == null -> ""
                segment.javaClass.methods.any { it.name == "getText" && it.parameterCount == 0 } -> {
                    segment.javaClass.methods.first { it.name == "getText" && it.parameterCount == 0 }
                        .invoke(segment)
                        ?.toString()
                        .orEmpty()
                }
                segment.javaClass.methods.any { it.name == "getToken" && it.parameterCount == 0 } -> {
                    val token = segment.javaClass.methods.first { it.name == "getToken" && it.parameterCount == 0 }
                        .invoke(segment)
                    token?.javaClass?.methods?.firstOrNull { it.name == "getFallbackLabel" && it.parameterCount == 0 }
                        ?.invoke(token)
                        ?.toString()
                        .orEmpty()
                }
                else -> ""
            }
        }

        assertEquals("Title 4K and unknown_badge", flattenedText)
    }
}
