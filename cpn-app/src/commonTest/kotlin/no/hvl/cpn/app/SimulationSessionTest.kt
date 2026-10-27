package no.hvl.cpn.app

import kotlinx.coroutines.test.runTest
import no.hvl.cpn.app.resources.Res
import no.hvl.cpn.io.JsonNetFormat
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationSessionTest {
    private suspend fun example() = JsonNetFormat.parse(Res.readBytes("files/simple-protocol.json").decodeToString())

    @Test
    fun bundledExampleLoads() =
        runTest {
            val session = SimulationSession(example(), seed = 2026)
            val snapshot = session.snapshot()
            assertEquals("SimpleProtocol", session.model.name)
            assertEquals(8, snapshot.marking.size)
            assertEquals(listOf("(Protocol'SendPacket 1, <d=\"COL\",n=1>)"), snapshot.enabled.map { it.toString() })
        }

    @Test
    fun showsExactlyWhatTheEngineComputes() =
        runTest {
            val net = example()
            val session = SimulationSession(net, seed = 2026)
            session.run(25)
            val engine = Simulator(net, 2026).apply { run(25) }
            assertEquals(engine.trace.map { it.toString() }, session.snapshot().trace.map { it.toString() })
            assertEquals(StopReason.MAX_STEPS, session.snapshot().lastStop)
        }

    @Test
    fun firingStepsAndResetting() =
        runTest {
            val session = SimulationSession(example(), seed = 1)
            val before = session.snapshot()
            session.fire(before.enabled.single())
            val after = session.snapshot()
            assertEquals(1, after.stepCount)
            assertTrue(after.revision > before.revision)
            assertEquals("1`(1,\"COL\")", after.marking.single { it.name == "Protocol'A 1" }.tokens)

            session.run(1000)
            assertTrue(session.snapshot().isDead, "the protocol ends once every packet is delivered")
            assertEquals(StopReason.DEAD, session.snapshot().lastStop)

            session.reset(newSeed = 7)
            assertEquals(0, session.snapshot().stepCount)
            assertEquals(7, session.seed)
        }
}
