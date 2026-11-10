package no.hvl.cpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TimedMultisetTest {
    // 1`a@0 +++ 2`a@5 +++ 1`b@3
    private val tokens = TimedMultiset.of("a", 1, 0) + TimedMultiset.of("a", 2, 5) + TimedMultiset.of("b", 1, 3)

    @Test
    fun tokensAreAvailableFromTheirTimestampOn() {
        assertEquals(Multiset.of("a"), tokens.available(0))
        assertEquals(Multiset.of("a", "b"), tokens.available(4))
        assertEquals(Multiset.of("a", "a", "a", "b"), tokens.available(5))
        assertEquals(Multiset.of("a", "a", "a", "b"), tokens.untimed)
        assertEquals(4, tokens.size)
    }

    @Test
    fun theEarliestTimeADemandIsAvailable() {
        assertEquals(0, tokens.earliest(Multiset.of("a")))
        assertEquals(5, tokens.earliest(Multiset.of("a", "a")), "the second a is only there at 5")
        assertEquals(5, tokens.earliest(Multiset.of("a", "b", "a")))
        assertNull(tokens.earliest(Multiset.repeated("a", 4)), "there are only three")
        assertNull(tokens.earliest(Multiset.of("c")))
    }

    @Test
    fun removingTakesTheLatestAvailableTokens() {
        // At time 6 all three a's are available; the two with the largest timestamps (5, 5) go.
        assertEquals(listOf(0L), tokens.minus(Multiset.of("a", "a"), 6).timestamps("a"))
        // At time 2 only a@0 is available.
        assertEquals(listOf(5L, 5L), tokens.minus(Multiset.of("a"), 2).timestamps("a"))
        assertFailsWith<IllegalArgumentException> { tokens.minus(Multiset.of("a", "a"), 2) }
    }

    @Test
    fun delaysShiftAndAddUp() {
        val delays = TimedMultiset.of("x", 1, 2) + TimedMultiset.of("y", 2, 0)
        val fired = delays.shifted(10)
        assertEquals(listOf(12L), fired.timestamps("x"))
        assertEquals(listOf(10L, 10L), fired.timestamps("y"))
        assertEquals("1`x@12+++2`y@10", fired.format())
        assertEquals("empty", TimedMultiset.empty<String>().format())
        assertEquals(TimedMultiset.of("a", 1, 3), TimedMultiset.of("a", 1, 3), "equal by tokens and timestamps")
    }

    @Test
    fun aMarkingKeepsTimestampsAndTheClock() {
        val place = Place("p", "P", ColourSets.STRING, timed = true)
        val marking = Marking.of(mapOf(place to tokens))
        assertEquals(Multiset.of("a", "a", "a", "b"), marking[place], "untimed view")
        assertEquals(tokens, marking.timed(place))
        assertEquals(0, marking.time)
        assertEquals(7, marking.at(7).time)
        assertEquals(marking, marking.at(0))
        assertEquals(false, marking == marking.at(1), "the time is part of the state")
    }
}
