package no.hvl.cpn.sim

import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatorTest {
    private val protocol = SimpleProtocol()

    private fun simulator(seed: Long = 0) = Simulator(protocol.net, seed)

    @Test
    fun splitMix64MatchesTheReferenceSequence() {
        // Reference outputs of SplitMix64 for seed 0.
        val random = SplitMix64(0)
        assertEquals(
            listOf("e220a8397b1dcdaf", "6e789e6aa1b965f4", "6c45d188009454f"),
            List(3) { random.nextLong().toULong().toString(16) },
        )
    }

    @Test
    fun nextIntStaysInBoundsAndCoversAllValues() {
        val random = SplitMix64(7)
        for (bound in listOf(1, 2, 3, 7, 8, 100)) {
            val seen = List(2000) { random.nextInt(bound) }
            assertTrue(seen.all { it in 0 until bound })
            assertEquals((0 until bound).toSet(), seen.toSet())
        }
        assertFailsWith<IllegalArgumentException> { random.nextInt(0) }
    }

    @Test
    fun startsInTheInitialMarkingWithFlatNames() {
        val sim = simulator()
        assertEquals(listOf("(Protocol'SendPacket 1, <d=\"COL\",n=1>)"), sim.enabled().map { it.toString() })
        assertEquals(0, sim.stepCount)
    }

    @Test
    fun stepFiresAndRecordsOccurrences() {
        val sim = simulator()
        val first = sim.step()!!
        assertEquals("1 Protocol'SendPacket 1 <d=\"COL\",n=1>", first.toString())
        sim.step()
        assertEquals(listOf(1, 2), sim.trace.map { it.step })
        assertEquals(2, sim.stepCount)
    }

    @Test
    fun sameSeedGivesSameTraceAndResetReplaysIt() {
        // Separate simulators flatten into separate (identity-compared) elements, so compare the text.
        val a = simulator(seed = 42).apply { run(200) }
        val b = simulator(seed = 42).apply { run(200) }
        assertEquals(a.trace.map { it.toString() }, b.trace.map { it.toString() })
        assertEquals(a.marking.toString(), b.marking.toString())

        val replay = a.trace
        a.reset()
        assertEquals(0, a.stepCount)
        a.run(200)
        assertEquals(replay, a.trace)

        val other = simulator(seed = 43).apply { run(200) }
        assertNotEquals(replay.map { it.toString() }, other.trace.map { it.toString() })
    }

    @Test
    fun runStopsWhenTheConditionHolds() {
        val sim = simulator(seed = 1)
        val page = protocol.net.page("Protocol")
        val dataReceived = sim.flatNet.place(sim.flatNet.instancesOf(page).single(), protocol.dataReceived)
        val result = sim.run(100_000) { it[dataReceived] == Multiset.of("COLOURED PETRI NET") }
        assertEquals(StopReason.CONDITION, result.reason)
        assertEquals(result.stepsTaken, sim.stepCount)
        assertTrue(result.stepsTaken >= 30, "needs at least 5 transitions per packet, took ${result.stepsTaken}")
    }

    @Test
    fun runStopsAtTheStepLimit() {
        val result = simulator().run(50)
        assertEquals(RunResult(50, StopReason.MAX_STEPS, result.marking), result)
    }

    @Test
    fun runStopsInADeadMarking() {
        val net =
            net("Countdown") {
                val n = variable("n", ColourSets.intRange("N", 0..3))
                page("Main") {
                    val p = place("P", ColourSets.intRange("N", 0..3), Multiset.of(3))
                    transition(
                        "Dec",
                        no.hvl.cpn.core.Guard
                            .of(n) { it[n] > 0 },
                    ) {
                        input(p, n)
                        output(p, n) { Multiset.of(it[n] - 1) }
                    }
                }
            }
        val sim = Simulator(net)
        val result = sim.run(10)
        assertEquals(StopReason.DEAD, result.reason)
        assertEquals(3, result.stepsTaken)
        assertNull(sim.step())
        assertTrue(sim.isDead())
    }

    @Test
    fun hierarchicalNetsAreFlattenedAutomatically() {
        val sim = Simulator(Examples.hierarchical(), seed = 5)
        val result = sim.run(1000)
        assertEquals(StopReason.DEAD, result.reason)
        assertTrue(
            sim.trace
                .first()
                .element.transition.name == "Sender'Send 1",
        )
    }

    @Test
    fun fireRandomPicksABindingOfTheGivenTransition() {
        val sim = simulator(seed = 3)
        sim.step() // SendPacket
        val transmit = sim.transition("Protocol'TransmitPacket 1")
        val occurrence = sim.fireRandom(transmit)!!
        assertEquals(transmit, occurrence.element.transition)
        assertNull(sim.fireRandom(sim.transition("Protocol'ReceiveAck 1")), "not enabled")
        assertEquals(2, sim.stepCount)
        val again = simulator(seed = 3).apply { step() }
        assertEquals(occurrence.toString(), again.fireRandom(again.transition("Protocol'TransmitPacket 1")).toString(), "seeded")
    }

    @Test
    fun manualFiringChecksEnabling() {
        val sim = simulator()
        val notEnabled = BindingElement(sim.transition("Protocol'SendPacket 1"), Binding.of(protocol.n to 2, protocol.d to "OUR"))
        assertFailsWith<NotEnabledException> { sim.fire(notEnabled) }
        val enabled = sim.enabled().single()
        assertEquals(1, sim.fire(enabled).step)
    }

    /**
     * The same seed must give this exact trace on every platform (T1.7). If the simulator's choice
     * order changes on purpose, regenerate it from the JVM run and check JS, Wasm and native agree.
     */
    @Test
    fun goldenTraceIsIdenticalOnEveryPlatform() {
        val sim = simulator(seed = 2026)
        sim.run(25)
        val actual = sim.trace.joinToString("\n")
        assertEquals(GOLDEN_TRACE, actual)
    }

    private companion object {
        /** Seed 2026, first 25 steps, generated on the JVM. */
        val GOLDEN_TRACE =
            """
            1 Protocol'SendPacket 1 <d="COL",n=1>
            2 Protocol'TransmitPacket 1 <d="COL",n=1,success=false>
            3 Protocol'SendPacket 1 <d="COL",n=1>
            4 Protocol'TransmitPacket 1 <d="COL",n=1,success=true>
            5 Protocol'SendPacket 1 <d="COL",n=1>
            6 Protocol'ReceivePacket 1 <d="COL",data="",k=1,n=1>
            7 Protocol'TransmitAck 1 <n=2,success=false>
            8 Protocol'TransmitPacket 1 <d="COL",n=1,success=false>
            9 Protocol'SendPacket 1 <d="COL",n=1>
            10 Protocol'TransmitPacket 1 <d="COL",n=1,success=true>
            11 Protocol'ReceivePacket 1 <d="COL",data="COL",k=2,n=1>
            12 Protocol'TransmitAck 1 <n=2,success=false>
            13 Protocol'SendPacket 1 <d="COL",n=1>
            14 Protocol'TransmitPacket 1 <d="COL",n=1,success=true>
            15 Protocol'ReceivePacket 1 <d="COL",data="COL",k=2,n=1>
            16 Protocol'SendPacket 1 <d="COL",n=1>
            17 Protocol'TransmitAck 1 <n=2,success=true>
            18 Protocol'SendPacket 1 <d="COL",n=1>
            19 Protocol'TransmitPacket 1 <d="COL",n=1,success=true>
            20 Protocol'ReceiveAck 1 <k=1,n=2>
            21 Protocol'SendPacket 1 <d="OUR",n=2>
            22 Protocol'TransmitPacket 1 <d="COL",n=1,success=true>
            23 Protocol'SendPacket 1 <d="OUR",n=2>
            24 Protocol'ReceivePacket 1 <d="COL",data="COL",k=2,n=1>
            25 Protocol'TransmitAck 1 <n=2,success=false>
            """.trimIndent()
    }
}
