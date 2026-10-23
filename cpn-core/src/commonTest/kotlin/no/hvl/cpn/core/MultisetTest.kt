package no.hvl.cpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MultisetTest {
    private val ab2 = Multiset.of("a") + Multiset.of("b", 2)

    @Test
    fun emptyMultiset() {
        val empty = Multiset.empty<String>()
        assertTrue(empty.isEmpty())
        assertEquals(0, empty.size)
        assertEquals("empty", empty.toString())
        assertEquals(empty, Multiset.of("x", 0))
        assertEquals(empty, Multiset.fromCounts(mapOf("x" to 0)))
    }

    @Test
    fun countsAndSize() {
        assertEquals(3, ab2.size)
        assertEquals(1, ab2.count("a"))
        assertEquals(2, ab2.count("b"))
        assertEquals(0, ab2.count("c"))
        assertTrue("b" in ab2)
        assertFalse("c" in ab2)
        assertEquals(setOf("a", "b"), ab2.distinct)
    }

    @Test
    fun varargOfAddsRepeats() {
        assertEquals(ab2, Multiset.of("b", "a", "b"))
        assertEquals(ab2, listOf("b", "b", "a").toMultiset())
    }

    @Test
    fun equalityIgnoresInsertionOrder() {
        val ba2 = Multiset.of("b", 2) + Multiset.of("a")
        assertEquals(ab2, ba2)
        assertEquals(ab2.hashCode(), ba2.hashCode())
        assertNotEquals(ab2, Multiset.of("a", "b"))
    }

    @Test
    fun subtraction() {
        assertEquals(Multiset.of("b"), ab2 - Multiset.of("a", "b"))
        assertEquals(Multiset.empty(), ab2 - ab2)
        assertFalse("a" in ab2 - Multiset.of("a"))
    }

    @Test
    fun subtractionOfNonSubMultisetFails() {
        assertFailsWith<IllegalArgumentException> { ab2 - Multiset.of("a", 2) }
        assertFailsWith<IllegalArgumentException> { ab2 - Multiset.of("c") }
    }

    @Test
    fun scalarMultiplication() {
        assertEquals(Multiset.of("a", 3) + Multiset.of("b", 6), 3 * ab2)
        assertEquals(ab2 * 3, 3 * ab2)
        assertEquals(Multiset.empty(), ab2 * 0)
        assertFailsWith<IllegalArgumentException> { ab2 * -1 }
    }

    @Test
    fun subsetOf() {
        assertTrue(Multiset.of("b", 2) subsetOf ab2)
        assertTrue(Multiset.empty<String>() subsetOf ab2)
        assertFalse(Multiset.of("b", 3) subsetOf ab2)
        assertFalse(ab2 subsetOf Multiset.of("b", 2))
    }

    @Test
    fun iteratesWithMultiplicity() {
        assertEquals(listOf("a", "b", "b"), ab2.toList())
    }

    @Test
    fun mapMergesCounts() {
        val lengths = (Multiset.of("aa", "bb") + Multiset.of("c")).map { it.length }
        assertEquals(Multiset.of(2, 2, 1), lengths)
    }

    @Test
    fun filterKeepsMultiplicity() {
        assertEquals(Multiset.of("b", 2), ab2.filter { it == "b" })
    }

    @Test
    fun formatsInCpnNotation() {
        assertEquals("1`a++2`b", ab2.toString())
        assertEquals("2`b++1`a", ab2.format(order = reverseOrder()))
        assertEquals("1`\"a\"++2`\"b\"", ab2.format { "\"$it\"" })
    }

    @Test
    fun countOverflowIsDetected() {
        val big = Multiset.of("a", Int.MAX_VALUE)
        assertFailsWith<IllegalStateException> { big + Multiset.of("a") }
        assertFailsWith<IllegalStateException> { big * 2 }
    }
}
