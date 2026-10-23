package no.hvl.cpn.core

import no.hvl.cpn.core.ColourSets.BOOL
import no.hvl.cpn.core.ColourSets.INT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetValidatorTest {
    private fun problemsOf(block: NetBuilder.() -> Unit): List<String> = assertFailsWith<InvalidNetException> { net("Bad", block) }.problems

    private fun assertProblem(
        expected: String,
        block: NetBuilder.() -> Unit,
    ) {
        val problems = problemsOf(block)
        assertTrue(problems.any { expected in it }, "Expected a problem containing '$expected', got $problems")
    }

    @Test
    fun initialMarkingOutsideColourSet() =
        assertProblem("has colours not in SMALL: [7]") {
            page("Main") { place("P", ColourSets.intRange("SMALL", 0..5), Multiset.of(1, 7)) }
        }

    @Test
    fun duplicatePlaceNames() =
        assertProblem("Duplicate id 'Main.P'") {
            page("Main") {
                place("P", INT)
                place("P", INT)
            }
        }

    @Test
    fun undeclaredVariable() =
        assertProblem("uses undeclared variable 'x'") {
            val x = Variable("x", INT)
            page("Main") {
                val p = place("P", INT)
                transition("T") { input(p, x) }
            }
        }

    @Test
    fun fusionSetsMustAgree() {
        val problems =
            problemsOf {
                page("A") { place("P", INT, Multiset.of(1), fusionSet = "F") }
                page("B") { place("Q", BOOL, fusionSet = "F") }
            }
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.any { "different colour sets" in it })
        assertTrue(problems.any { "different initial markings" in it })
    }

    @Test
    fun everyPortMustBeAssigned() =
        assertProblem("port 'In' of page 'Sub' is not assigned") {
            val sub =
                page("Sub") {
                    port("In", INT, PortType.IN)
                    port("Out", INT, PortType.OUT)
                }
            page("Top") {
                val a = place("A", INT)
                substitution("S", sub) { assign(a, sub.place("Out", INT)) }
            }
        }

    @Test
    fun socketMustConnectToAPortOfTheSubpage() =
        assertProblem("port is not a port of page 'Sub'") {
            val sub =
                page("Sub") {
                    port("In", INT, PortType.IN)
                    place("Local", INT)
                }
            page("Top") {
                val a = place("A", INT)
                val b = place("B", INT)
                substitution("S", sub) {
                    assign(a, sub.place("In", INT))
                    assign(b, sub.place("Local", INT))
                }
            }
        }

    @Test
    fun wellFormedNetHasNoProblems() {
        val net = net("Ok") { page("Main") { place("P", INT, Multiset.of(1)) } }
        assertEquals(emptyList(), net.validate())
    }
}
