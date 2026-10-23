package no.hvl.cpn.sim

import no.hvl.cpn.core.Binding
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
import no.hvl.cpn.core.Variable
import no.hvl.cpn.core.flatten
import no.hvl.cpn.core.net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlatteningTest {
    private val no = ColourSets.intRange("NO", 1..3)

    /**
     * Top ── Sender ──▶ A ── Channel ──▶ C ── Receiver
     *                        Channel = In ─ Transmit ─▶ Mid ─ Transmit ─▶ Out   (two instances of Transmit)
     * Receiver's Received is fused with Top's Delivered.
     */
    private fun hierarchical(): Net =
        net("Hierarchical") {
            val n = variable("n", no)
            val success = variable("success", ColourSets.BOOL)
            val sender =
                page("Sender") {
                    val out = port("Out", no, PortType.OUT)
                    val next = place("Next", no, Multiset.of(1))
                    transition("Send", Guard.of(n) { it[n] <= 2 }) {
                        input(next, n)
                        output(next, n) { Multiset.of(it[n] + 1) }
                        output(out, n)
                    }
                }
            val transmit =
                page("Transmit") {
                    val input = port("In", no, PortType.IN)
                    val output = port("Out", no, PortType.OUT)
                    transition("Transmit") {
                        input(input, n)
                        output(output, n, success) { if (it[success]) Multiset.of(it[n]) else Multiset.empty() }
                    }
                }
            val channel =
                page("Channel") {
                    val input = port("In", no, PortType.IN)
                    val output = port("Out", no, PortType.OUT)
                    val mid = place("Mid", no)
                    substitution("Hop1", transmit) {
                        assign(input, transmit.place("In", no))
                        assign(mid, transmit.place("Out", no))
                    }
                    substitution("Hop2", transmit) {
                        assign(mid, transmit.place("In", no))
                        assign(output, transmit.place("Out", no))
                    }
                }
            val receiver =
                page("Receiver") {
                    val input = port("In", no, PortType.IN)
                    val received = place("Received", no, fusionSet = "Log")
                    transition("Receive") {
                        input(input, n)
                        output(received, n)
                    }
                }
            page("Top") {
                val a = place("A", no)
                val c = place("C", no)
                place("Delivered", no, fusionSet = "Log")
                substitution("Sender", sender) { assign(a, sender.place("Out", no)) }
                substitution("Channel", channel) {
                    assign(a, channel.place("In", no))
                    assign(c, channel.place("Out", no))
                }
                substitution("Receiver", receiver) { assign(c, receiver.place("In", no)) }
            }
        }

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

    /** Every reachable marking, as place name → CPN Tools text, by brute-force binding enumeration. */
    private fun reachableMarkings(net: Net): Set<Map<String, String>> {
        val semantics = Semantics(net)
        val places = net.pages.flatMap { it.places }
        val bindings = semantics.transitions.associateWith { allBindings(semantics.variablesOf(it).toList()) }
        val seen = mutableSetOf(semantics.initialMarking)
        val queue = ArrayDeque(seen)
        while (queue.isNotEmpty()) {
            val marking = queue.removeFirst()
            for ((transition, candidates) in bindings) {
                for (binding in candidates) {
                    val element = BindingElement(transition, binding)
                    if (semantics.isEnabled(element, marking)) {
                        val next = semantics.fire(element, marking)
                        if (seen.add(next)) queue += next
                    }
                }
            }
        }
        return seen.map { m -> places.associate { it.name to m.format(listOf(it)).substringAfter(": ") } }.toSet()
    }

    private fun allBindings(variables: List<Variable<*>>): List<Binding> =
        variables.fold(listOf(Binding.EMPTY)) { partial, variable ->
            @Suppress("UNCHECKED_CAST")
            val v = variable as Variable<Any?>
            partial.flatMap { b -> v.colourSet.values!!.map { b.with(v, it) } }
        }
}
