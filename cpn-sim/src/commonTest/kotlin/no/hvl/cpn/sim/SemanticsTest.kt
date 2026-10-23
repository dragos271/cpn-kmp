package no.hvl.cpn.sim

import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Marking
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.PortType
import no.hvl.cpn.core.net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walks through the simple protocol occurrences described in Jensen & Kristensen (2009), sec. 2.3–2.4. */
class SemanticsTest {
    private val p = SimpleProtocol()
    private val semantics = Semantics(p.net)
    private val m0 = semantics.initialMarking

    private fun sendPacket(
        n: Int,
        d: String,
    ) = BindingElement(p.sendPacket, Binding.of(p.n to n, p.d to d))

    private fun transmitPacket(
        n: Int,
        d: String,
        success: Boolean,
    ) = BindingElement(p.transmitPacket, Binding.of(p.n to n, p.d to d, p.success to success))

    @Test
    fun initialMarking() {
        assertEquals(6, m0[p.packetsToSend].size)
        assertEquals(Multiset.of(1), m0[p.nextSend])
        assertEquals(Multiset.of(""), m0[p.dataReceived])
        assertTrue(m0[p.a].isEmpty())
        assertEquals(
            """
            NextSend: 1`1
            NextRec: 1`1
            DataReceived: 1`""
            """.trimIndent(),
            m0.format(listOf(p.nextSend, p.nextRec, p.dataReceived)),
        )
    }

    @Test
    fun sendPacketIsEnabledOnlyForTheNextPacket() {
        assertTrue(semantics.isEnabled(sendPacket(1, "COL"), m0))
        assertFalse(semantics.isEnabled(sendPacket(2, "OUR"), m0), "NextSend holds 1, not 2")
        assertFalse(semantics.isEnabled(sendPacket(1, "OUR"), m0), "no token (1,\"OUR\") on PacketsToSend")
    }

    @Test
    fun sendPacketCopiesThePacketToA() {
        val m1 = semantics.fire(sendPacket(1, "COL"), m0)
        assertEquals(Multiset.of(1 to "COL"), m1[p.a])
        assertEquals(m0[p.packetsToSend], m1[p.packetsToSend], "double arc puts the packet back")
        assertEquals(Multiset.of(1), m1[p.nextSend])
    }

    @Test
    fun transmitPacketCanLoseThePacket() {
        val m1 = semantics.fire(sendPacket(1, "COL"), m0)
        val delivered = semantics.fire(transmitPacket(1, "COL", success = true), m1)
        assertEquals(Multiset.of(1 to "COL"), delivered[p.b])
        assertTrue(delivered[p.a].isEmpty())
        val lost = semantics.fire(transmitPacket(1, "COL", success = false), m1)
        assertTrue(lost[p.a].isEmpty())
        assertTrue(lost[p.b].isEmpty())
    }

    @Test
    fun fullRoundTripDeliversFirstPacket() {
        var m = semantics.fire(sendPacket(1, "COL"), m0)
        m = semantics.fire(transmitPacket(1, "COL", success = true), m)
        m = semantics.fire(BindingElement(p.receivePacket, Binding.of(p.n to 1, p.d to "COL", p.k to 1, p.data to "")), m)
        assertEquals(Multiset.of("COL"), m[p.dataReceived])
        assertEquals(Multiset.of(2), m[p.nextRec])
        assertEquals(Multiset.of(2), m[p.c])
        m = semantics.fire(BindingElement(p.transmitAck, Binding.of(p.n to 2, p.success to true)), m)
        assertEquals(Multiset.of(2), m[p.dPlace])
        m = semantics.fire(BindingElement(p.receiveAck, Binding.of(p.n to 2, p.k to 1)), m)
        assertEquals(Multiset.of(2), m[p.nextSend])
        assertTrue(semantics.isEnabled(sendPacket(2, "OUR"), m))
    }

    @Test
    fun duplicatePacketIsIgnoredByReceiver() {
        // NextRec is 2 after the first delivery; a retransmitted packet 1 leaves the data unchanged.
        var m = semantics.fire(sendPacket(1, "COL"), m0)
        m = semantics.fire(sendPacket(1, "COL"), m)
        m = semantics.fire(transmitPacket(1, "COL", true), m)
        m = semantics.fire(transmitPacket(1, "COL", true), m)
        val receive = {
            k: Int,
            data: String,
            ->
            BindingElement(p.receivePacket, Binding.of(p.n to 1, p.d to "COL", p.k to k, p.data to data))
        }
        m = semantics.fire(receive(1, ""), m)
        m = semantics.fire(receive(2, "COL"), m)
        assertEquals(Multiset.of("COL"), m[p.dataReceived])
        assertEquals(Multiset.of(2), m[p.nextRec])
        assertEquals(Multiset.repeated(2, 2), m[p.c])
    }

    @Test
    fun concurrentStepsNeedEnoughTokensForAllBindingElements() {
        var m = semantics.fire(sendPacket(1, "COL"), m0)
        m = semantics.fire(sendPacket(1, "COL"), m)
        assertEquals(Multiset.repeated(1 to "COL", 2), m[p.a])

        val both = listOf(transmitPacket(1, "COL", true), transmitPacket(1, "COL", false))
        assertTrue(semantics.isEnabled(both, m))
        assertFalse(semantics.isEnabled(both + transmitPacket(1, "COL", true), m), "only two packets on A")

        val stepResult = semantics.fire(both, m)
        val sequential = both.fold(m) { acc, element -> semantics.fire(element, acc) }
        assertEquals(sequential, stepResult)
        assertEquals(Multiset.of(1 to "COL"), stepResult[p.b])
    }

    @Test
    fun doubleArcsPreventAutoConcurrency() {
        // Each SendPacket occurrence needs its own token on NextSend, and there is only one.
        assertFalse(semantics.isEnabled(listOf(sendPacket(1, "COL"), sendPacket(1, "COL")), m0))
    }

    @Test
    fun firingADisabledElementFails() {
        assertFailsWith<NotEnabledException> { semantics.fire(sendPacket(2, "OUR"), m0) }
    }

    @Test
    fun bindingMustCoverExactlyTheTransitionVariables() {
        val incomplete = BindingElement(p.sendPacket, Binding.of(p.n to 1))
        val error = assertFailsWith<IllegalArgumentException> { semantics.isEnabled(incomplete, m0) }
        assertTrue("missing [d]" in error.message!!, error.message)
        val extra = BindingElement(p.sendPacket, Binding.of(p.n to 1, p.d to "COL", p.k to 1))
        assertFailsWith<IllegalArgumentException> { semantics.isEnabled(extra, m0) }
        assertEquals(setOf(p.n, p.d), semantics.variablesOf(p.sendPacket))
    }

    @Test
    fun outputsOutsideTheColourSetAreRejected() {
        val small = ColourSets.intRange("SMALL", 0..1)
        lateinit var counter: no.hvl.cpn.core.Place<Int>
        val bad =
            net("Overflow") {
                val i = variable("i", small)
                page("Main") {
                    counter = place("Counter", small, Multiset.of(1))
                    transition("Inc") {
                        input(counter, i)
                        output(counter, i) { Multiset.of(it[i] + 1) }
                    }
                }
            }
        val semantics = Semantics(bad)
        val inc = BindingElement(bad.page("Main").transition("Inc"), Binding.of(bad.variable("i") to 1))
        assertFailsWith<IllegalStateException> { semantics.fire(inc, semantics.initialMarking) }
        assertEquals(Marking.initial(bad)[counter], Multiset.of(1))
    }

    @Test
    fun hierarchicalNetsMustBeFlattenedFirst() {
        val hierarchical =
            net("H") {
                val sub = page("Sub") { port("In", ColourSets.INT, PortType.IN) }
                page("Top") {
                    val a = place("A", ColourSets.INT)
                    substitution("S", sub) { assign(a, sub.place("In", ColourSets.INT)) }
                }
            }
        assertFailsWith<IllegalArgumentException> { Semantics(hierarchical) }
    }
}
