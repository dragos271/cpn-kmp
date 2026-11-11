package no.hvl.cpn.io.model

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason
import no.hvl.cpn.statespace.StateSpace
import no.hvl.cpn.statespace.StateSpaceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Timed nets (T3.2), written in CPN ML and compiled from a document like a model opened in the app. */
class TimedNetTest {
    private fun arc(
        id: String,
        place: String,
        transition: String,
        direction: ArcDirection,
        inscription: String,
    ) = ArcEdge(id, place, transition, direction, inscription)

    /**
     * Count fires every 4 time units while its counter is below 3; Go waits for a token that is only
     * available at time 5, and logs the time it fired at on an untimed place.
     */
    private val document =
        CpnDocument(
            "Clock",
            "colset T = int timed;\ncolset U = unit timed;\ncolset N = int;\nvar n : T;\n",
            listOf(
                PageDocument(
                    "P",
                    "P",
                    listOf(
                        PlaceNode("Start", "Start", "U", "1`()@+5", Point(0.0, 0.0)),
                        PlaceNode("Tick", "Tick", "T", "1`0", Point(0.0, 100.0)),
                        PlaceNode("Out", "Out", "T", "", Point(200.0, 0.0)),
                        PlaceNode("Log", "Log", "N", "", Point(200.0, 50.0)),
                    ),
                    listOf(
                        TransitionNode("Go", "Go", "", Point(100.0, 0.0), time = "@+3"),
                        TransitionNode("Count", "Count", "n < 3", Point(100.0, 100.0), time = "@+4"),
                    ),
                    listOf(
                        arc("a1", "Start", "Go", ArcDirection.PLACE_TO_TRANSITION, "()"),
                        arc("a2", "Out", "Go", ArcDirection.TRANSITION_TO_PLACE, "1 @+ 2"),
                        arc("a3", "Log", "Go", ArcDirection.TRANSITION_TO_PLACE, "time()"),
                        arc("a4", "Tick", "Count", ArcDirection.PLACE_TO_TRANSITION, "n"),
                        arc("a5", "Tick", "Count", ArcDirection.TRANSITION_TO_PLACE, "n + 1"),
                    ),
                ),
            ),
        )

    @Test
    fun theClockMovesToTheNextEnablingTime() {
        assertEquals(emptyList(), document.check())
        val simulator = Simulator(assertNotNull(document.compile().net), seed = 1)
        val result = simulator.run(100)
        assertEquals(StopReason.DEAD, result.reason)
        // Count at 0, 4, 8 (delay 4); Go at 5, when its start token becomes available.
        assertEquals(
            listOf("0 Count", "4 Count", "5 Go", "8 Count"),
            simulator.trace.map {
                "${it.time} ${it.element.transition.name.substringAfter('\'').substringBefore(' ')}"
            },
        )
        val places =
            simulator.flatNet.net.pages
                .single()
                .places
                .associateBy { it.name.substringAfter('\'').substringBefore(' ') }
        assertEquals("1`3@12", result.marking.tokensOf(places.getValue("Tick")), "8 + delay 4")
        assertEquals("1`1@10", result.marking.tokensOf(places.getValue("Out")), "5 + transition delay 3 + arc delay 2")
        assertEquals("1`5", result.marking.tokensOf(places.getValue("Log")), "time() is the model time; untimed places have no timestamps")
        assertEquals(8, result.marking.time)
    }

    @Test
    fun theTimedStateSpaceIncludesTheClock() {
        // Worked out by hand: Count at 0, wait until 4, Count at 4, Go at 5, Count at 8, then dead.
        val ss = StateSpace.build(assertNotNull(document.compile().net))
        assertEquals(StateSpaceStatus.FULL, ss.status)
        assertEquals(5, ss.nodeCount)
        assertEquals(4, ss.arcCount)
        assertEquals(listOf(5), ss.deadMarkings)
        assertEquals(listOf(0L, 0L, 4L, 5L, 8L), ss.nodes.map { ss.marking(it).time })
    }

    @Test
    fun theTraceShowsTheTime() {
        val simulator = Simulator(assertNotNull(document.compile().net), seed = 1)
        simulator.run(2)
        assertEquals("2 @4 P'Count 1 <n=1>", simulator.trace[1].toString())
    }

    @Test
    fun aDelayMustBeAnInt() {
        val bad = document.updateTransition("P", "Go") { it.copy(time = "@+\"soon\"") }
        assertTrue(bad.check().any { it.field == ProblemField.TIME && "a delay must be an int" in it.message }, "${bad.check()}")
        val broken = document.updateTransition("P", "Go") { it.copy(time = "@+(") }
        assertTrue(broken.check().any { it.field == ProblemField.TIME })
    }

    @Test
    fun delaysDoNothingInUntimedNets() {
        // The same net with no timed colour sets: delays have no effect and there is no clock.
        val untimed = document.copy(declarations = "colset T = int;\ncolset U = unit;\ncolset N = int;\nvar n : T;\n")
        val simulator = Simulator(assertNotNull(untimed.compile().net), seed = 1)
        simulator.run(100)
        assertTrue(simulator.trace.all { it.time == null })
        assertEquals(0, simulator.time)
    }
}
