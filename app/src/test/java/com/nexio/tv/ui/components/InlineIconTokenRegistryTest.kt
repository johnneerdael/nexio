package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineIconTokenRegistryTest {

    @Test
    fun `tokenize parses optional icon scale override`() {
        val segments = InlineIconTokenRegistry.tokenize("Before [[icon:dovi:1.75]] after [[icon:hdr10]]")
        val iconSegments = segments.filterIsInstance<InlineIconSegment.IconSegment>()

        assertEquals(2, iconSegments.size)
        assertEquals("dovi", iconSegments[0].token.id)
        assertEquals(1.75f, iconSegments[0].scaleOverride ?: -1f, 0.0001f)
        assertEquals("hdr10", iconSegments[1].token.id)
        assertNull(iconSegments[1].scaleOverride)
    }

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
    fun `token registry resolves audio and visual inline badges`() {
        assertNotNull(InlineIconTokenRegistry.resolve("atmos"))
        assertNotNull(InlineIconTokenRegistry.resolve("truehd"))
        assertNotNull(InlineIconTokenRegistry.resolve("ddp"))
        assertNotNull(InlineIconTokenRegistry.resolve("dd"))
        assertNotNull(InlineIconTokenRegistry.resolve("dts"))
        assertNotNull(InlineIconTokenRegistry.resolve("dtshd"))
        assertNotNull(InlineIconTokenRegistry.resolve("dtsx"))
        assertNotNull(InlineIconTokenRegistry.resolve("stereo"))
        assertNotNull(InlineIconTokenRegistry.resolve("dovi"))
        assertNotNull(InlineIconTokenRegistry.resolve("hdr10"))
        assertNotNull(InlineIconTokenRegistry.resolve("premiumize"))
        assertNotNull(InlineIconTokenRegistry.resolve("realdebrid"))
        assertNotNull(InlineIconTokenRegistry.resolve("alldebrid"))
        assertNotNull(InlineIconTokenRegistry.resolve("debridlink"))
        assertNotNull(InlineIconTokenRegistry.resolve("torbox"))
        assertNotNull(InlineIconTokenRegistry.resolve("offcloud"))
        assertNotNull(InlineIconTokenRegistry.resolve("putio"))
        assertNotNull(InlineIconTokenRegistry.resolve("easydebrid"))
        assertNotNull(InlineIconTokenRegistry.resolve("debrider"))
        assertNotNull(InlineIconTokenRegistry.resolve("pikpak"))
        assertNotNull(InlineIconTokenRegistry.resolve("seedr"))
        assertNotNull(InlineIconTokenRegistry.resolve("easynews"))
        assertNotNull(InlineIconTokenRegistry.resolve("nzbdav"))
        assertNotNull(InlineIconTokenRegistry.resolve("altmount"))
        assertNotNull(InlineIconTokenRegistry.resolve("stremionntp"))
        assertNotNull(InlineIconTokenRegistry.resolve("stremthrunewz"))
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("atmos")?.scaleClass)
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("dovi")?.scaleClass)
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("premiumize")?.scaleClass)
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("realdebrid")?.scaleClass)
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("alldebrid")?.scaleClass)
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("debridlink")?.scaleClass)
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("easynews")?.scaleClass)
        assertEquals(ScaleClass.INLINE, InlineIconTokenRegistry.resolve("stremionntp")?.scaleClass)
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
