package no.hvl.cpn.sim

import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.InvalidNetException
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.Page
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.PortType
import no.hvl.cpn.core.Substitution
import no.hvl.cpn.core.Transition
import no.hvl.cpn.core.flatten
import no.hvl.cpn.core.net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlatteningTest {
    private val no = Examples.no

    /** The same net flattened by hand, using the names flattening should produce. */
    private fun handFlattened(): Net =
        net("Hand") {
            val n = variable("n", no)
            val success = variable("success", ColourSets.BOOL)
            page("Flat") {
                val a = place("Top'A 1", no)
                val c = place("Top'C 1", no)
                val delivered = place("Top'Delivered 1", no)
                val next = place("Sender'Next 1", no, Multiset.of(1))
                val mid = place("Channel'Mid 1", no)
                transition("Sender'Send 1", Guard.of(n) { it[n] <= 2 }) {
                    input(next, n)
                    output(next, n) { Multiset.of(it[n] + 1) }
                    output(a, n)
                }
                transition("Transmit'Transmit 1") {
                    input(a, n)
                    output(mid, n, success) { if (it[success]) Multiset.of(it[n]) else Multiset.empty() }
                }
                transition("Transmit'Transmit 2") {
                    input(mid, n)
                    output(c, n, success) { if (it[success]) Multiset.of(it[n]) else Multiset.empty() }
                }
                transition("Receiver'Receive 1") {
                    input(c, n)
                    output(delivered, n)
                }
            }
        }

    @Test
    fun namesFollowCpnToolsInstanceNotation() {
        val flat = hierarchical().flatten()
        val page = flat.net.pages.single()
        assertEquals(setOf("Top'A 1", "Top'C 1", "Top'Delivered 1", "Sender'Next 1", "Channel'Mid 1"), page.places.map { it.name }.toSet())
        assertEquals(
            listOf("Sender'Send 1", "Transmit'Transmit 1", "Transmit'Transmit 2", "Receiver'Receive 1"),
            page.transitions.map { it.name },
        )
        assertEquals(
            listOf("Top (1)", "Sender (1)", "Channel (1)", "Transmit (1)", "Transmit (2)", "Receiver (1)"),
            flat.instances.map {
                it.toString()
            },
        )
        assertTrue(flat.net.validate().isEmpty())
        assertTrue(!flat.net.isHierarchical && flat.net.fusionSets.isEmpty())
    }

    @Test
    fun portsAreGluedToTheirSocketsThroughEveryLevel() {
        val original = hierarchical()
        val flat = original.flatten()
        val transmit = original.page("Transmit")
        val (first, second) = flat.instancesOf(transmit)
        assertEquals("Top'A 1", flat.place(first, transmit.place("In", no)).name)
        assertEquals("Channel'Mid 1", flat.place(first, transmit.place("Out", no)).name)
        assertEquals("Channel'Mid 1", flat.place(second, transmit.place("In", no)).name)
        assertEquals("Top'C 1", flat.place(second, transmit.place("Out", no)).name)

        val a =
            flat.net.pages
                .single()
                .place("Top'A 1")
        assertEquals(
            setOf("Top (1).A", "Sender (1).Out", "Channel (1).In", "Transmit (1).In"),
            flat.originsOf(a).map { (instance, place) -> "$instance.${place.name}" }.toSet(),
        )
    }

    @Test
    fun fusedPlacesBecomeOnePlace() {
        val original = hierarchical()
        val flat = original.flatten()
        val receiver = flat.instancesOf(original.page("Receiver")).single()
        val top = flat.instancesOf(original.page("Top")).single()
        assertEquals(
            flat.place(top, original.page("Top").place("Delivered", no)),
            flat.place(receiver, original.page("Receiver").place("Received", no)),
        )
    }

    @Test
    fun behavesLikeTheHandFlattenedNet() {
        val viaFlatten = reachableMarkings(hierarchical().flatten().net)
        val byHand = reachableMarkings(handFlattened())
        assertTrue(viaFlatten.size > 10, "expected a non-trivial state space, got ${viaFlatten.size}")
        assertEquals(byHand, viaFlatten)
    }

    @Test
    fun flatNetNamesEvenWithoutHierarchy() {
        val simple = net("Simple") { page("Main") { place("P", no, Multiset.of(1)) } }
        val flat = simple.flatten()
        assertEquals(
            "Main'P 1",
            flat.net.pages
                .single()
                .places
                .single()
                .name,
        )
        assertEquals(
            Multiset.of(1),
            flat.net.pages
                .single()
                .place("Main'P 1", no)
                .initialMarking,
        )
    }

    @Test
    fun invalidNetsAreRejected() {
        // Built without the DSL, which would already reject it: the subpage port is never assigned.
        val sub = Page("Sub", "Sub", listOf(Place("Sub.In", "In", no, portType = PortType.IN)), emptyList(), emptyList())
        val top =
            Page("Top", "Top", emptyList(), listOf(Transition("Top.S", "S", substitution = Substitution(sub, emptyMap()))), emptyList())
        val error = assertFailsWith<InvalidNetException> { Net("Bad", listOf(sub, top), emptyList()).flatten() }
        assertTrue(error.problems.any { "port 'In' of page 'Sub' is not assigned" in it }, error.problems.toString())
    }

    private fun hierarchical(): Net = Examples.hierarchical()

    private fun reachableMarkings(net: Net): Set<Map<String, String>> {
        val places = net.pages.flatMap { it.places }
        return Examples
            .reachableByBruteForce(Semantics(net))
            .map { m ->
                places.associate {
                    it.name to
                        m.format(listOf(it)).substringAfter(": ")
                }
            }.toSet()
    }
}
