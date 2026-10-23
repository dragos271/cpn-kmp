package no.hvl.cpn.sim

import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.Marking
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.PortType
import no.hvl.cpn.core.Variable
import no.hvl.cpn.core.net

/** Example nets and a brute-force reference for the binding search, shared by the tests. */
internal object Examples {
    val no = ColourSets.intRange("NO", 1..3)

    /**
     * Top ── Sender ──▶ A ── Channel ──▶ C ── Receiver
     *                        Channel = In ─ Transmit ─▶ Mid ─ Transmit ─▶ Out   (two instances of Transmit)
     * Receiver's Received is fused with Top's Delivered.
     */
    fun hierarchical(): Net =
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

    /** Every binding of [variables] over their (finite) colour sets. */
    fun allBindings(variables: List<Variable<*>>): List<Binding> =
        variables.fold(listOf(Binding.EMPTY)) { partial, variable ->
            @Suppress("UNCHECKED_CAST")
            val v = variable as Variable<Any?>
            partial.flatMap { b -> v.colourSet.values!!.map { b.with(v, it) } }
        }

    /** Enabled binding elements found by trying every binding: the reference the binding search must match. */
    fun enabledByBruteForce(
        semantics: Semantics,
        marking: Marking,
    ): Set<BindingElement> =
        semantics.transitions
            .flatMap { t -> allBindings(semantics.variablesOf(t).toList()).map { BindingElement(t, it) } }
            .filter { semantics.isEnabled(it, marking) }
            .toSet()

    /** Every reachable marking, exploring with brute-force binding enumeration. */
    fun reachableByBruteForce(semantics: Semantics): Set<Marking> {
        val seen = mutableSetOf(semantics.initialMarking)
        val queue = ArrayDeque(seen)
        while (queue.isNotEmpty()) {
            val marking = queue.removeFirst()
            for (element in enabledByBruteForce(semantics, marking)) {
                val next = semantics.fire(element, marking)
                if (seen.add(next)) queue += next
            }
        }
        return seen
    }
}
