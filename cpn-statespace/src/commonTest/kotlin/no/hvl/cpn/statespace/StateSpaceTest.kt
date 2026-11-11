package no.hvl.cpn.statespace

import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateSpaceTest {
    /** A counter from 0 to 3: a chain of 4 markings. */
    private val counter =
        net("Counter") {
            val n = variable("n", ColourSets.INT)
            page("Main") {
                val count = place("Count", ColourSets.INT, Multiset.of(0))
                transition("Inc", Guard.of(n) { it[n] < 3 }) {
                    input(count, n)
                    output(count, n) { b -> Multiset.of(b[n] + 1) }
                }
            }
        }

    /** Two independent one-shot transitions: the diamond of interleavings, 4 markings and 4 arcs. */
    private val diamond =
        net("Diamond") {
            page("Main") {
                val a = place("A", ColourSets.UNIT, Multiset.of(Unit))
                val b = place("B", ColourSets.UNIT, Multiset.of(Unit))
                val doneA = place("DoneA", ColourSets.UNIT)
                val doneB = place("DoneB", ColourSets.UNIT)
                transition("TA") {
                    input(a, Multiset.of(Unit))
                    output(doneA, Multiset.of(Unit))
                }
                transition("TB") {
                    input(b, Multiset.of(Unit))
                    output(doneB, Multiset.of(Unit))
                }
            }
        }

    /** A counter without limit: an infinite state space. */
    private val unbounded =
        net("Unbounded") {
            val n = variable("n", ColourSets.INT)
            page("Main") {
                val count = place("Count", ColourSets.INT, Multiset.of(0))
                transition("Inc") {
                    input(count, n)
                    output(count, n) { b -> Multiset.of(b[n] + 1) }
                }
            }
        }

    @Test
    fun aChain() {
        val ss = StateSpace.build(counter)
        assertEquals(StateSpaceStatus.FULL, ss.status)
        assertEquals(4, ss.nodeCount)
        assertEquals(3, ss.arcCount)
        assertEquals(listOf(4), ss.deadMarkings)
        val count =
            ss.flatNet.net.pages
                .single()
                .places
                .single()
        assertEquals("1`0", ss.marking(1).tokensOf(count), "node 1 is the initial marking")
        assertEquals("1`3", ss.marking(4).tokensOf(count))
        assertEquals(listOf(2), ss.successors(1))
        assertEquals(1, ss.node(ss.marking(1)))
    }

    @Test
    fun interleavingsMeetInOneMarking() {
        val ss = StateSpace.build(diamond)
        assertEquals(4, ss.nodeCount)
        assertEquals(4, ss.arcCount)
        assertEquals(listOf(4), ss.deadMarkings, "both orders reach the same final marking")
        assertEquals(2, ss.inArcs(4).size)
        assertEquals(setOf("Main'TA 1", "Main'TB 1"), ss.outArcs(1).map { it.element.transition.name }.toSet())
    }

    @Test
    fun limitsMakeAPartialStateSpace() {
        val ss = StateSpace.build(unbounded, StateSpaceLimits(maxNodes = 100))
        assertEquals(StateSpaceStatus.PARTIAL, ss.status)
        assertTrue(ss.stoppedBy!!.contains("100 nodes"))
        assertTrue(ss.nodeCount in 101..102)
        assertEquals(emptyList(), ss.deadMarkings, "unexplored nodes are not dead markings")
        assertTrue(!ss.explored(ss.nodeCount))
        val byArcs = StateSpace.build(unbounded, StateSpaceLimits(maxArcs = 10))
        assertEquals(StateSpaceStatus.PARTIAL, byArcs.status)
        assertNull(StateSpace.build(counter).stoppedBy)
    }
}
