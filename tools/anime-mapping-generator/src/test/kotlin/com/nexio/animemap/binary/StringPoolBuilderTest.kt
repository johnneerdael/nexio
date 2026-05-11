package com.nexio.animemap.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StringPoolBuilderTest {
    @Test
    fun `intern returns same offset for duplicate strings`() {
        val pool = StringPoolBuilder()
        val first = pool.intern("hello")
        val second = pool.intern("hello")
        assertEquals(first, second)
    }

    @Test
    fun `intern returns different offsets for different strings`() {
        val pool = StringPoolBuilder()
        val a = pool.intern("foo")
        val b = pool.intern("bar")
        assertNotEquals(a, b)
    }

    @Test
    fun `toByteArray contains varint-length-prefixed utf8`() {
        val pool = StringPoolBuilder()
        val helloOffset = pool.intern("hi")
        val bytes = pool.toByteArray()
        // "hi" -> varint length=2 then 'h' 'i'
        assertEquals(0, helloOffset)
        assertEquals(3, bytes.size)
        assertEquals(0x02, bytes[0].toInt())
        assertEquals('h'.code, bytes[1].toInt())
        assertEquals('i'.code, bytes[2].toInt())
    }

    @Test
    fun `empty string returns sentinel offset uint32 max`() {
        val pool = StringPoolBuilder()
        val offset = pool.intern("")
        assertEquals(0xFFFF_FFFFL.toInt(), offset)
    }

    @Test
    fun `null returns sentinel offset uint32 max`() {
        val pool = StringPoolBuilder()
        val offset = pool.intern(null)
        assertEquals(0xFFFF_FFFFL.toInt(), offset)
    }
}
