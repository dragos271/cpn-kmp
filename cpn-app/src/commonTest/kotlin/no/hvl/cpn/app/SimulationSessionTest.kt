package no.hvl.cpn.app

import kotlinx.coroutines.test.runTest
import no.hvl.cpn.app.resources.Res
import no.hvl.cpn.io.JsonNetFormat
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationSessionTest {
    private suspend fun example() = JsonNetFormat.load(Res.readBytes(EXAMPLE_MODEL).decodeToString())

    @Test
    fun bundledExampleLoadsWithALayout() =
        runTest {
            val model = example()
            val page = model.document.pages.single()
            assertEquals("SimpleProtocol", model.document.name)
            assertEquals(8, page.places.size)
            assertEquals(5, page.transitions.size)
            assertEquals(13, (page.places.map { it.position } + page.transitions.map { it.position }).toSet().size)
        }

    @Test
    fun overlayPutsTokensAndEnablingOnTheDocumentShapes() =
        runTest {
            val session = SimulationSession(example(), seed = 2026)
            val overlay = session.snapshot().overlay("Protocol")
            assertEquals(setOf("Protocol.SendPacket"), overlay.enabledTransitions)
            assertEquals(6, overlay.tokens.getValue("Protocol.PacketsToSend").count)
            assertEquals(0, overlay.tokens.getValue("Protocol.A").count)
            assertEquals("1`1", overlay.tokens.getValue("Protocol.NextSend").text)
            assertEquals(
                listOf("<d=\"COL\",n=1>"),
                session
                    .snapshot()
                    .bindings
                    .getValue("Protocol.SendPacket")
                    .map { it.binding.toString() },
            )
        }

    @Test
    fun clickingATransitionFiresIt() =
        runTest {
            val session = SimulationSession(example(), seed = 2026)
            assertNull(session.fireTransition("Protocol", "Protocol.ReceiveAck"), "not enabled")
            val occurrence = session.fireTransition("Protocol", "Protocol.SendPacket")!!
            assertEquals("Protocol'SendPacket 1", occurrence.element.transition.name)
            val overlay = session.snapshot().overlay("Protocol")
            assertEquals("1`(1,\"COL\")", overlay.tokens.getValue("Protocol.A").text)
            assertTrue("Protocol.TransmitPacket" in overlay.enabledTransitions)
        }

    @Test
    fun showsExactlyWhatTheEngineComputes() =
        runTest {
            val model = example()
            val session = SimulationSession(model, seed = 2026)
            session.run(25)
            val engine = Simulator(model.net, 2026).apply { run(25) }
            assertEquals(engine.trace.map { it.toString() }, session.snapshot().trace.map { it.toString() })
            assertEquals(StopReason.MAX_STEPS, session.snapshot().lastStop)
        }

    @Test
    fun runningToTheEndAndResetting() =
        runTest {
            val session = SimulationSession(example(), seed = 1)
            session.run(1000)
            assertTrue(session.snapshot().isDead, "the protocol ends once every packet is delivered")
            assertEquals(StopReason.DEAD, session.snapshot().lastStop)
            session.reset(newSeed = 7)
            assertEquals(0, session.snapshot().stepCount)
            assertEquals(7, session.seed)
        }
}
