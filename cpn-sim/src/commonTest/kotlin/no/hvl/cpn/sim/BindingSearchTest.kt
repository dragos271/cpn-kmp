package no.hvl.cpn.sim

import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.flatten
import no.hvl.cpn.core.net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BindingSearchTest {
    private val p = SimpleProtocol()
    private val semantics = Semantics(p.net)
    private val search = BindingSearch(semantics)
    private val m0 = semantics.initialMarking

    private fun names(elements: List<BindingElement>) = elements.map { it.toString() }.toSet()

    @Test
    fun onlyTheFirstPacketCanBeSentInitially() {
        // Jensen & Kristensen (2009), sec. 2.3: SendPacket is enabled in M0 only with <n=1,d="COL">.
        assertEquals(setOf("(SendPacket, <d=\"COL\",n=1>)"), names(search.enabledBindingElements(m0)))
    }

    @Test
    fun unboundVariableIsEnumeratedOverItsFiniteColourSet() {
        // `success` is on no input arc; it is bound to both colours of BOOL (sec. 2.3).
        val m1 = semantics.fire(search.enabledBindings(p.sendPacket, m0).single(), m0)
        assertEquals(
            setOf("(TransmitPacket, <d=\"COL\",n=1,success=false>)", "(TransmitPacket, <d=\"COL\",n=1,success=true>)"),
            names(search.enabledBindings(p.transmitPacket, m1)),
        )
        assertEquals(3, search.enabledBindingElements(m1).size, "SendPacket stays enabled too")
    }

    @Test
    fun receivePacketBindsVariablesFromThreePlaces() {
        var m = semantics.fire(search.enabledBindings(p.sendPacket, m0).single(), m0)
        m = semantics.fire(BindingElement(p.transmitPacket, Binding.of(p.n to 1, p.d to "COL", p.success to true)), m)
        assertEquals(
            setOf("(ReceivePacket, <d=\"COL\",data=\"\",k=1,n=1>)"),
            names(search.enabledBindings(p.receivePacket, m)),
        )
    }

    @Test
    fun guardRemovesCandidates() {
        val flat = Examples.hierarchical().flatten().net
        val search = BindingSearch(Semantics(flat))
        val send = flat.pages.single().transition("Sender'Send 1")
        var m = search.semantics.initialMarking
        repeat(2) { m = search.semantics.fire(search.enabledBindings(send, m).single(), m) }
        assertTrue(search.enabledBindings(send, m).isEmpty(), "Next is 3 and the guard requires n <= 2")
    }

    @Test
    fun tokensOutsideTheVariableColourSetDoNotMatch() {
        lateinit var place: no.hvl.cpn.core.Place<Int>
        val net =
            net("Narrow") {
                val small = variable("small", ColourSets.intRange("SMALL", 1..3))
                page("Main") {
                    place = place("P", ColourSets.INT, Multiset.of(1, 5))
                    transition("T") { input(place, small) }
                }
            }
        val search = BindingSearch(Semantics(net))
        assertEquals(setOf("(T, <small=1>)"), names(search.enabledBindingElements(search.semantics.initialMarking)))
    }

    @Test
    fun multiplicityOfThePatternIsRespected() {
        val net =
            net("Twice") {
                val x = variable("x", ColourSets.STRING)
                page("Main") {
                    val p = place("P", ColourSets.STRING, Multiset.of("a", "b", "b"))
                    transition("T") { input(p, x, count = 2) }
                }
            }
        val search = BindingSearch(Semantics(net))
        assertEquals(setOf("(T, <x=\"b\">)"), names(search.enabledBindingElements(search.semantics.initialMarking)))
    }

    @Test
    fun aVariableOnTwoArcsMustMatchBoth() {
        val net =
            net("Join") {
                val x = variable("x", ColourSets.INT)
                page("Main") {
                    val p = place("P", ColourSets.INT, Multiset.of(1, 2))
                    val q = place("Q", ColourSets.INT, Multiset.of(2, 3))
                    transition("T") {
                        input(p, x)
                        input(q, x)
                    }
                }
            }
        val search = BindingSearch(Semantics(net))
        assertEquals(setOf("(T, <x=2>)"), names(search.enabledBindingElements(search.semantics.initialMarking)))
    }

    @Test
    fun twoPatternsOnOnePlaceNeedTwoTokens() {
        fun twoFrom(tokens: Multiset<Int>): Set<String> {
            val net =
                net("Pair") {
                    val x = variable("x", ColourSets.INT)
                    val y = variable("y", ColourSets.INT)
                    page("Main") {
                        val p = place("P", ColourSets.INT, tokens)
                        transition("T") {
                            input(p, x)
                            input(p, y)
                        }
                    }
                }
            val search = BindingSearch(Semantics(net))
            return names(search.enabledBindingElements(search.semantics.initialMarking))
        }
        assertEquals(emptySet(), twoFrom(Multiset.of(1)))
        assertEquals(setOf("(T, <x=1,y=1>)"), twoFrom(Multiset.repeated(1, 2)))
        assertEquals(setOf("(T, <x=1,y=2>)", "(T, <x=2,y=1>)"), twoFrom(Multiset.of(1, 2)))
    }

    @Test
    fun variablesThatCannotBeBoundAreReportedUpFront() {
        val infinite =
            net("Infinite") {
                val n = variable("n", ColourSets.INT)
                page("Main") {
                    val p = place("P", ColourSets.INT)
                    transition("T") { output(p, n) }
                }
            }
        val error = assertFailsWith<UnboundVariableException> { BindingSearch(Semantics(infinite)) }
        assertTrue("'n' of transition 'T'" in error.message!! && "is infinite" in error.message!!, error.message)

        val large =
            net("Large") {
                val n = variable("n", ColourSets.intRange("BIG", 1..500))
                page("Main") {
                    val p = place("P", ColourSets.intRange("BIG", 1..500))
                    transition("T") { output(p, n) }
                }
            }
        assertFailsWith<UnboundVariableException> { BindingSearch(Semantics(large)) }
        assertEquals(
            500,
            BindingSearch(Semantics(large), maxEnumeration = 1000).enabledBindingElements(Semantics(large).initialMarking).size,
        )
    }

    @Test
    fun transitionWithoutVariablesHasTheEmptyBinding() {
        val net =
            net("Plain") {
                page("Main") {
                    val p = place("P", ColourSets.UNIT, Multiset.of(Unit))
                    transition("T") { input(p, Multiset.of(Unit)) }
                }
            }
        val search = BindingSearch(Semantics(net))
        val m0 = search.semantics.initialMarking
        assertEquals(setOf("(T, <>)"), names(search.enabledBindingElements(m0)))
        val m1 = search.semantics.fire(search.enabledBindingElements(m0).single(), m0)
        assertTrue(search.isDead(m1))
    }

    @Test
    fun matchesBruteForceOnEveryReachableMarking() {
        for (net in listOf(Examples.hierarchical().flatten().net, opaqueArcs())) {
            val semantics = Semantics(net)
            val search = BindingSearch(semantics)
            val markings = Examples.reachableByBruteForce(semantics)
            assertTrue(markings.size > 5, "${net.name}: only ${markings.size} markings")
            for (marking in markings) {
                assertEquals(
                    Examples.enabledByBruteForce(semantics, marking),
                    search.enabledBindingElements(marking).toSet(),
                    "${net.name} in marking\n$marking",
                )
            }
        }
    }

    /** Input arcs written as Kotlin code, which the search cannot match and must enumerate instead. */
    private fun opaqueArcs(): Net =
        net("Opaque") {
            val no = Examples.no
            val x = variable("x", no)
            val y = variable("y", no)
            page("Main") {
                val p = place("P", no, Multiset.of(1, 2, 3))
                val q = place("Q", no)
                transition("Move", Guard.of(x, y) { it[x] < it[y] }) {
                    input(p, x) { Multiset.of(it[x]) }
                    input(p, y)
                    output(q, x, y) { Multiset.of(it[y] - it[x]) }
                }
                transition("Back") {
                    input(q, x)
                    output(p, x)
                }
            }
        }
}
