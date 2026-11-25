package no.hvl.cpn.io.model

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.ArcDirection.PLACE_TO_TRANSITION
import no.hvl.cpn.core.ArcDirection.TRANSITION_TO_PLACE
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.perf.Monitor
import no.hvl.cpn.sim.perf.Performance
import no.hvl.cpn.sim.perf.RunEnd
import no.hvl.cpn.sim.perf.RunLimits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Performance reports (F14) on queues whose answers are known: a deterministic queue, exactly, and
 * the M/M/1 queue, within the confidence interval of queueing theory.
 */
class PerformanceTest {
    private fun queue(
        arrival: String,
        service: String,
    ) = CpnDocument(
        "Queue",
        """
        colset UNIT = unit timed; colset CUST = int timed; var c : CUST;
        val lambda = 0.01; val mu = 0.02;
        fun delay rate = round (exponential rate);
        """.trimIndent(),
        listOf(
            PageDocument(
                "Q",
                "Q",
                listOf(
                    PlaceNode("Gen", "Gen", "UNIT", "1`()", Point(0.0, 0.0)),
                    PlaceNode("Queue", "Queue", "CUST", "", Point(100.0, 0.0)),
                    PlaceNode("Server", "Server", "UNIT", "1`()", Point(200.0, -100.0)),
                    PlaceNode("Busy", "Busy", "CUST", "", Point(300.0, 0.0)),
                ),
                listOf(
                    TransitionNode("Arrive", "Arrive", "", Point(50.0, 0.0)),
                    TransitionNode("Start", "Start", "", Point(200.0, 0.0)),
                    TransitionNode("Depart", "Depart", "", Point(400.0, 0.0)),
                ),
                listOf(
                    arc("a1", "Gen", "Arrive", PLACE_TO_TRANSITION, "()"),
                    arc("a2", "Gen", "Arrive", TRANSITION_TO_PLACE, "() @+ $arrival"),
                    arc("a3", "Queue", "Arrive", TRANSITION_TO_PLACE, "intTime()"),
                    arc("a4", "Queue", "Start", PLACE_TO_TRANSITION, "c"),
                    arc("a5", "Server", "Start", PLACE_TO_TRANSITION, "()"),
                    arc("a6", "Busy", "Start", TRANSITION_TO_PLACE, "c @+ $service"),
                    arc("a7", "Busy", "Depart", PLACE_TO_TRANSITION, "c"),
                    arc("a8", "Server", "Depart", TRANSITION_TO_PLACE, "()"),
                ),
            ),
        ),
    )

    private fun arc(
        id: String,
        place: String,
        transition: String,
        direction: ArcDirection,
        inscription: String,
    ) = ArcEdge(id, place, transition, direction, inscription)

    private fun monitors(model: CompiledModel): List<Monitor> {
        val sojourn = model.expression("intTime() - c")
        return listOf(
            Monitor.MarkingSize("L", listOf("Queue", "Busy")),
            Monitor.MarkingSize("Utilisation", listOf("Busy")),
            Monitor.Occurrences("Throughput", "Depart"),
            Monitor.Observe("W", "Depart") { b, t -> (sojourn(b, t) as Number).toDouble() },
        )
    }

    @Test
    fun aDeterministicQueueGivesExactValues() {
        // Arrivals at 0, 10, 20, ...; each served in 6: busy 6 of every 10 time units, nobody waits.
        val model = queue("10", "6").compile()
        val net = assertNotNull(model.net)
        val run = Performance.run(net, monitors(model), RunLimits(untilTime = 1000), seed = 1)
        assertEquals(RunEnd.TIME_LIMIT, run.end)
        val m = run.monitors.associateBy { it.monitor }
        assertEquals(0.6, m.getValue("Utilisation").mean, 1e-9)
        assertEquals(0.6, m.getValue("L").mean, 1e-9)
        assertEquals(0.0, m.getValue("L").min)
        assertEquals(1.0, m.getValue("L").max)
        assertEquals(100, m.getValue("Throughput").count, "departures at 6, 16, ..., 996")
        assertEquals(0.1, m.getValue("Throughput").mean, 1e-9)
        assertEquals(6.0, m.getValue("W").mean)
        assertEquals(0.0, m.getValue("W").standardDeviation)
    }

    @Test
    fun theWarmUpIsNotMeasured() {
        // From time 500 on: 50 departures in 500 time units, the same averages as without warm-up.
        val model = queue("10", "6").compile()
        val run = Performance.run(model.net!!, monitors(model), RunLimits(untilTime = 1000, warmup = 500), seed = 1)
        val m = run.monitors.associateBy { it.monitor }
        assertEquals(50, m.getValue("Throughput").count)
        assertEquals(0.6, m.getValue("Utilisation").mean, 1e-9)
        assertEquals(50, m.getValue("W").count)
    }

    @Test
    fun theMm1QueueMatchesQueueingTheory() {
        // λ = 0.01, μ = 0.02, ρ = 0.5: utilisation ρ = 0.5, L = ρ/(1-ρ) = 1, W = 1/(μ-λ) = 100, throughput λ.
        val model = queue("delay lambda", "delay mu").compile()
        val report =
            Performance.replicate(model.net!!, monitors(model), RunLimits(untilTime = 100_000, warmup = 5_000), replications = 20, seed = 1)
        val expected = mapOf("Utilisation" to 0.5, "L" to 1.0, "W" to 100.0, "Throughput" to 0.01)
        for (s in report.summaries) {
            val value = expected.getValue(s.monitor)
            assertTrue(value in s.low..s.high, "${s.monitor}: $value should lie in the 95 % interval ${s.low}..${s.high}")
            assertTrue(abs(s.mean - value) / value < 0.05, "${s.monitor}: ${s.mean} within 5 % of $value")
        }
    }

    @Test
    fun replicationsUseDifferentRandomNumbersAndRepeatExactly() {
        val model = queue("delay lambda", "delay mu").compile()
        val limits = RunLimits(untilTime = 20_000)
        val a = Performance.run(model.net!!, monitors(model), limits, seed = 1)
        val b = Performance.run(model.net!!, monitors(model), limits, seed = 2)
        val again = Performance.run(model.net!!, monitors(model), limits, seed = 1)
        assertNotEquals(a.monitors, b.monitors, "seed 2 draws other arrival and service times")
        assertEquals(a, again, "the same seed gives the same run")
    }

    @Test
    fun theSimulationSeedReachesRandomFunctionsInInscriptions() {
        // Before F14 every seed drew the same delays: the interpreter's generator was always seeded with 0.
        val net = queue("delay lambda", "delay mu").compile().net!!

        fun times(seed: Long) = Simulator(net, seed).apply { run(20) }.trace.map { it.time }
        assertEquals(times(3), times(3))
        assertNotEquals(times(3), times(4))
    }

    @Test
    fun untimedNetsAreMeasuredPerStep() {
        // Flip and Flop alternate: each occurs every second step; the token is on A half the steps.
        val doc =
            CpnDocument(
                "FlipFlop",
                "colset U = unit;",
                listOf(
                    PageDocument(
                        "P",
                        "P",
                        listOf(PlaceNode("A", "A", "U", "1`()", Point(0.0, 0.0)), PlaceNode("B", "B", "U", "", Point(100.0, 0.0))),
                        listOf(TransitionNode("Flip", "Flip", "", Point(50.0, 0.0)), TransitionNode("Flop", "Flop", "", Point(50.0, 50.0))),
                        listOf(
                            arc("a1", "A", "Flip", PLACE_TO_TRANSITION, "()"),
                            arc("a2", "B", "Flip", TRANSITION_TO_PLACE, "()"),
                            arc("a3", "B", "Flop", PLACE_TO_TRANSITION, "()"),
                            arc("a4", "A", "Flop", TRANSITION_TO_PLACE, "()"),
                        ),
                    ),
                ),
            )
        val run =
            Performance.run(
                doc.compile().net!!,
                listOf(Monitor.MarkingSize("On A", listOf("A")), Monitor.Occurrences("Flip rate", "Flip")),
                RunLimits(maxSteps = 1000),
                seed = 1,
            )
        assertEquals(RunEnd.STEP_LIMIT, run.end)
        assertEquals(0.5, run.monitors[0].mean, 1e-9)
        assertEquals(0.5, run.monitors[1].mean, 1e-9)
    }

    @Test
    fun confidenceIntervalsUseStudentsT() {
        val s = Performance.summarise("x", listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(3.0, s.mean)
        // sd = sqrt(2.5); t(4) = 2.776; half-width = 2.776 * sqrt(2.5) / sqrt(5) = 1.963
        assertEquals(1.963, s.halfWidth, 1e-3)
        assertEquals(0.0, Performance.summarise("one", listOf(7.0)).halfWidth)
    }

    @Test
    fun unknownNamesAreReported() {
        val model = queue("10", "6").compile()
        val error =
            runCatching { Performance.run(model.net!!, listOf(Monitor.Occurrences("x", "Leave")), RunLimits(untilTime = 10), 1) }
                .exceptionOrNull()
        assertTrue(error is IllegalArgumentException && "No transition 'Leave'" in error.message!!, "$error")
    }
}
