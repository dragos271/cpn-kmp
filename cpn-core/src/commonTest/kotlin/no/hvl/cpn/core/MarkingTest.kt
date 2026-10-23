package no.hvl.cpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkingTest {
    private val p = Place("p", "P", ColourSets.INT, Multiset.of(1, 2))
    private val q = Place("q", "Q", ColourSets.STRING)

    @Test
    fun unmarkedPlacesAreEmptyAndNotStored() {
        val m = Marking.EMPTY.with(p, Multiset.of(1)).with(q, Multiset.empty())
        assertEquals(setOf<Place<*>>(p), m.markedPlaces)
        assertTrue(m[q].isEmpty())
        assertEquals(m, Marking.EMPTY.with(p, Multiset.of(1)))
    }

    @Test
    fun removingAllTokensRestoresEquality() {
        val m = Marking.EMPTY.with(p, Multiset.of(1)).with(p, Multiset.empty())
        assertEquals(Marking.EMPTY, m)
        assertEquals(Marking.EMPTY.hashCode(), m.hashCode())
    }

    @Test
    fun equalityIgnoresPlaceOrder() {
        val pq = Marking.EMPTY.with(p, Multiset.of(1)).with(q, Multiset.of("x"))
        val qp = Marking.EMPTY.with(q, Multiset.of("x")).with(p, Multiset.of(1))
        assertEquals(pq, qp)
        assertEquals(pq.hashCode(), qp.hashCode())
    }

    @Test
    fun formatsInColourSetOrder() {
        val m = Marking.EMPTY.with(p, Multiset.of(3, 1, 3)).with(q, Multiset.of("b", "a"))
        assertEquals("P: 1`1++2`3\nQ: 1`\"a\"++1`\"b\"", m.toString())
        assertEquals("Q: 1`\"a\"++1`\"b\"\nP: 1`1++2`3", m.format(listOf(q, p)))
    }
}
