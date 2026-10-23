package no.hvl.cpn.core

import no.hvl.cpn.core.ColourSets.INT
import no.hvl.cpn.core.ColourSets.UNIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetModelTest {
    /** Producer/consumer: Produce puts 0, 1, 2 into Buffer; Consume moves them to Received. */
    private fun producerConsumer(): Net =
        net("ProducerConsumer") {
            val n = variable("n", INT)
            val m = variable("m", INT)
            page("Main") {
                val ready = place("Ready", UNIT, Multiset.of(Unit))
                val count = place("Count", INT, Multiset.of(0))
                val buffer = place("Buffer", INT)
                val received = place("Received", INT)
                transition("Produce", Guard.of(n, source = "n < 3") { it[n] < 3 }) {
                    both(ready, ArcInscription.Constant(Multiset.of(Unit)))
                    input(count, n)
                    output(count, n) { b -> Multiset.of(b[n] + 1) }
                    output(buffer, n)
                }
                transition("Consume") {
                    input(buffer, m)
                    output(received, m)
                }
            }
        }

    @Test
    fun buildsProducerConsumer() {
        val net = producerConsumer()
        val main = net.page("Main")
        assertEquals(listOf("Ready", "Count", "Buffer", "Received"), main.places.map { it.name })
        assertEquals(listOf("Produce", "Consume"), main.transitions.map { it.name })
        assertEquals(listOf(main), net.primePages)
        assertFalse(net.isHierarchical)
        assertEquals("Main.Count", main.place("Count").id)
        assertEquals(Multiset.of(0), main.place("Count", INT).initialMarking)
    }

    @Test
    fun arcsByDirection() {
        val main = producerConsumer().page("Main")
        val produce = main.transition("Produce")
        assertEquals(listOf("Ready", "Count"), main.inputArcs(produce).map { it.place.name })
        assertEquals(listOf("Ready", "Count", "Buffer"), main.outputArcs(produce).map { it.place.name })
        assertEquals("Buffer -> Consume : m", main.inputArcs(main.transition("Consume")).single().toString())
    }

    @Test
    fun evaluatesInscriptionsAndGuard() {
        val net = producerConsumer()
        val main = net.page("Main")
        val produce = main.transition("Produce")

        @Suppress("UNCHECKED_CAST")
        val n = net.variable("n") as Variable<Int>
        val binding = Binding.of(n to 2)

        assertTrue(produce.guard.evaluate(binding))
        assertFalse(produce.guard.evaluate(Binding.of(n to 3)))
        assertEquals(setOf(n), produce.guard.variables)
        val outputs = main.outputArcs(produce).associate { it.place.name to it.inscription!!.evaluate(binding) }
        assertEquals(Multiset.of(3), outputs["Count"])
        assertEquals(Multiset.of(2), outputs["Buffer"])
        assertEquals(Multiset.of(Unit), outputs["Ready"])
    }

    @Test
    fun bindingRejectsColoursOutsideTheColourSet() {
        val small = Variable("s", ColourSets.intRange("SMALL", 1..3))
        assertEquals("<s=2>", Binding.of(small to 2).toString())
        assertFailsWith<IllegalArgumentException> { Binding.of(small to 4) }
        assertFailsWith<IllegalArgumentException> { Binding.EMPTY[small] }
    }

    @Test
    fun buildsHierarchicalNet() {
        val net =
            net("Protocol") {
                val k = variable("k", INT)
                val sender =
                    page("Sender") {
                        val next = place("Next", INT, Multiset.of(1))
                        val out = port("Out", INT, PortType.OUT)
                        transition("Send") {
                            input(next, k)
                            output(next, k) { b -> Multiset.of(b[k] + 1) }
                            output(out, k)
                        }
                    }
                page("Top") {
                    val channel = place("Channel", INT)
                    substitution("Sender", sender) {
                        assign(channel, sender.place("Out", INT))
                    }
                }
            }
        val top = net.page("Top")
        val substitution = top.transition("Sender")
        assertTrue(net.isHierarchical)
        assertEquals(listOf(top), net.primePages)
        assertEquals(net.page("Sender"), substitution.substitution!!.subpage)
        val socketArc = top.arcsOf(substitution).single()
        assertEquals(ArcDirection.TRANSITION_TO_PLACE, socketArc.direction)
        assertEquals(null, socketArc.inscription)
    }

    @Test
    fun groupsFusionSets() {
        val net =
            net("Fused") {
                page("A") { place("P", INT, fusionSet = "Shared") }
                page("B") { place("Q", INT, fusionSet = "Shared") }
            }
        assertEquals(listOf("P", "Q"), net.fusionSets.getValue("Shared").map { it.name })
    }
}
