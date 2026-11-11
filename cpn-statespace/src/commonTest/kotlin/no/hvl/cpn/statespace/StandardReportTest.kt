package no.hvl.cpn.statespace

import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Report properties on nets small enough to know the answer by construction. Each net is a state
 * machine: one place `S` holding the current state, and transitions `from -> to` between states.
 */
class StandardReportTest {
    private fun machine(vararg moves: Triple<String, Int, Int>): Net =
        net("Machine") {
            val n = variable("n", ColourSets.INT)
            page("M") {
                val s = place("S", ColourSets.INT, Multiset.of(0))
                for ((name, from, to) in moves) {
                    transition(name, Guard.of(n) { it[n] == from }) {
                        input(s, n)
                        output(s, n) { Multiset.of(to) }
                    }
                }
            }
        }

    private fun report(vararg moves: Triple<String, Int, Int>) = StateSpace.build(machine(*moves)).standardReport()

    private fun fairnessOf(
        report: StandardReport,
        transition: String,
    ) = report.fairness!!.getValue("M'$transition 1")

    @Test
    fun componentsOfAGraph() {
        // 0 -> 1 -> 2 -> 1, 2 -> 3, 3 -> 3: components {0}, {1,2}, {3 with a self-loop}.
        val succ = arrayOf(intArrayOf(1), intArrayOf(2), intArrayOf(1, 3), intArrayOf(3))
        val c = components(4, { succ[it] })
        assertEquals(3, c.count)
        assertEquals(c.component[1], c.component[2])
        assertTrue(c.nontrivial[c.component[1]] && c.nontrivial[c.component[3]])
        assertTrue(!c.nontrivial[c.component[0]])
        assertTrue(c.component[3] < c.component[1] && c.component[1] < c.component[0], "reverse topological order")
    }

    @Test
    fun aLongChainDoesNotOverflowTheStack() {
        val n = 200_000
        val c = components(n, { if (it + 1 < n) intArrayOf(it + 1) else intArrayOf(0) })
        assertEquals(1, c.count, "one big cycle")
    }

    @Test
    fun aChainHasNoInfiniteOccurrenceSequences() {
        val r = report(Triple("A", 0, 1), Triple("B", 1, 2))
        assertEquals(3, r.sccNodes)
        assertEquals(2, r.sccArcs)
        assertEquals(listOf(3), r.homeMarkings)
        assertEquals(listOf(3), r.deadMarkings)
        assertEquals(emptyList(), r.liveTransitions)
        assertNull(r.fairness)
        assertTrue("No infinite occurrence sequences." in r.format())
    }

    @Test
    fun twoEndsMeanNoHomeMarking() {
        val r = report(Triple("Left", 0, 1), Triple("Right", 0, 2), Triple("Never", 9, 9))
        assertEquals(emptyList(), r.homeMarkings)
        assertEquals(listOf(2, 3), r.deadMarkings)
        assertEquals(listOf("M'Never 1"), r.deadTransitions)
    }

    @Test
    fun aCycleIsLiveAndHome() {
        val r = report(Triple("Up", 0, 1), Triple("Down", 1, 0))
        assertEquals(1, r.sccNodes)
        assertEquals(listOf(1, 2), r.homeMarkings)
        assertEquals(listOf("M'Up 1", "M'Down 1"), r.liveTransitions)
        assertEquals(Fairness.IMPARTIAL, fairnessOf(r, "Up"))
        assertEquals(IntegerBound("M'S 1", 1, 1), r.integerBounds.single())
        assertEquals(MultisetBound("M'S 1", "1`0++1`1", "empty"), r.multisetBounds.single())
    }

    @Test
    fun fairnessClasses() {
        // Loop avoids T and Go, which are never enabled on it: both fair. Every cycle uses Loop: impartial.
        val fair = report(Triple("Go", 0, 1), Triple("Loop", 1, 1), Triple("T", 0, 2))
        assertEquals(Fairness.IMPARTIAL, fairnessOf(fair, "Loop"))
        assertEquals(Fairness.FAIR, fairnessOf(fair, "Go"))
        assertEquals(Fairness.FAIR, fairnessOf(fair, "T"))

        // Flip 0 <-> 1 forever avoids T, enabled in 0 but not in 1: just, not fair.
        val just = report(Triple("Flip0", 0, 1), Triple("Flip1", 1, 0), Triple("T", 0, 2))
        assertEquals(Fairness.JUST, fairnessOf(just, "T"))

        // Stay in 0 forever avoids T, enabled all the time: no fairness.
        val none = report(Triple("Stay", 0, 0), Triple("T", 0, 1))
        assertEquals(Fairness.NO_FAIRNESS, fairnessOf(none, "T"))
        assertEquals(Fairness.IMPARTIAL, fairnessOf(none, "Stay"))
    }

    @Test
    fun theReportFollowsCpnToolsSections() {
        val text = report(Triple("Up", 0, 1), Triple("Down", 1, 0)).format()
        val sections = listOf("Statistics", "Boundedness Properties", "Home Properties", "Liveness Properties", "Fairness Properties")
        assertEquals(sections, sections.sortedBy { text.indexOf(" $it\n") })
        assertTrue(sections.all { " $it\n" in text })
    }
}
