package no.hvl.cpn.io

import no.hvl.cpn.core.InvalidNetException
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.PortType
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonNetFormatTest {
    private val counter =
        """
        {
          "name": "Counter",
          "colsets": [{ "name": "N", "type": "int", "range": [0, 5] }],
          "variables": { "n": "N", "flag": "BOOL" },
          "pages": [{
            "name": "Main",
            "places": [
              { "name": "Count", "colset": "N", "init": [0] },
              { "name": "Log", "colset": "N", "init": [{ "value": 0, "count": 2 }] }
            ],
            "transitions": [{
              "name": "Inc",
              "guard": { "op": "<", "args": [{ "var": "n" }, 3] },
              "in": [{ "place": "Count", "inscription": "n" }],
              "out": [
                { "place": "Count", "inscription": { "expr": { "op": "+", "args": [{ "var": "n" }, 1] } } },
                { "place": "Log", "inscription": { "if": { "var": "flag" }, "then": "n", "else": { "tokens": [] } } }
              ]
            }]
          }]
        }
        """.trimIndent()

    @Test
    fun readsPlacesMarkingsAndVariables() {
        val net = JsonNetFormat.parse(counter)
        val main = net.page("Main")
        assertEquals("Counter", net.name)
        assertEquals(listOf("n", "flag"), net.variables.map { it.name })
        assertEquals(Multiset.of(0), main.place("Count").initialMarking)
        assertEquals(Multiset.repeated(0, 2), main.place("Log").initialMarking)
        assertEquals("N", main.place("Count").colourSet.name)
    }

    @Test
    fun simulatesUntilTheGuardStopsIt() {
        val sim = Simulator(JsonNetFormat.parse(counter), seed = 3)
        val result = sim.run(100)
        assertEquals(StopReason.DEAD, result.reason)
        assertEquals(3, result.stepsTaken)
        val count =
            sim.flatNet.net.pages
                .single()
                .place("Main'Count 1")
        assertEquals("1`3", result.marking.tokensOf(count))
        // flag is unbound on input arcs and enumerated over BOOL: two bindings per step.
        sim.reset()
        assertEquals(2, sim.enabled().size)
    }

    @Test
    fun readsProductsPatternsAndSums() {
        val json =
            """
            {
              "name": "Pairs",
              "colsets": [
                { "name": "C", "type": "enum", "values": ["red", "green"] },
                { "name": "NxC", "type": "product", "of": ["INT", "C"] }
              ],
              "variables": { "n": "INT", "c": "C" },
              "pages": [{
                "name": "Main",
                "places": [
                  { "name": "In", "colset": "NxC", "init": [[1, "red"], [2, "green"]] },
                  { "name": "Out", "colset": "NxC" }
                ],
                "transitions": [{
                  "name": "Copy",
                  "in": [{ "place": "In", "inscription": { "pattern": ["n", "c"] } }],
                  "out": [{ "place": "Out", "inscription": { "sum": [
                    { "pattern": ["n", "c"], "count": 2 },
                    { "expr": { "tuple": [{ "op": "*", "args": [{ "var": "n" }, 10] }, "red"] } }
                  ] } }]
                }]
              }]
            }
            """.trimIndent()
        val sim = Simulator(JsonNetFormat.parse(json), seed = 1)
        val copy = sim.enabled().single { it.binding.toString() == "<c=green,n=2>" }
        sim.fire(copy)
        val out =
            sim.flatNet.net.pages
                .single()
                .place("Main'Out 1")
        assertEquals("2`(2,green)++1`(20,red)", sim.marking.tokensOf(out))
    }

    @Test
    fun readsHierarchyInAnyPageOrder() {
        val json =
            """
            {
              "name": "H",
              "variables": { "n": "INT" },
              "pages": [
                {
                  "name": "Top",
                  "places": [{ "name": "A", "colset": "INT", "init": [7] }, { "name": "B", "colset": "INT" }],
                  "substitutions": [{ "name": "Move", "subpage": "Mover", "assign": { "A": "In", "B": "Out" } }]
                },
                {
                  "name": "Mover",
                  "places": [{ "name": "In", "colset": "INT", "port": "in" }, { "name": "Out", "colset": "INT", "port": "out" }],
                  "transitions": [{ "name": "T", "in": [{ "place": "In", "inscription": "n" }], "out": [{ "place": "Out", "inscription": "n" }] }]
                }
              ]
            }
            """.trimIndent()
        val net = JsonNetFormat.parse(json)
        assertEquals(PortType.IN, net.page("Mover").place("In").portType)
        val sim = Simulator(net)
        assertEquals("1 Mover'T 1 <n=7>", sim.step().toString())
        assertEquals(
            "1`7",
            sim.marking.tokensOf(
                sim.flatNet.net.pages
                    .single()
                    .place("Top'B 1"),
            ),
        )
    }

    @Test
    fun errorsPointToTheOffendingElement() {
        fun errorOf(json: String): String = assertFailsWith<ModelFormatException> { JsonNetFormat.parse(json) }.message!!

        assertTrue("not valid JSON" in errorOf("{"))
        assertEquals("pages: a net needs at least one page", errorOf("""{"name": "X", "pages": []}"""))
        assertEquals(
            "pages[0].places[0].colset: unknown colour set 'NOPE'; defined: [UNIT, BOOL, INT, STRING]",
            errorOf("""{"name": "X", "pages": [{"name": "P", "places": [{"name": "A", "colset": "NOPE"}]}]}"""),
        )
        assertEquals(
            "pages[0].transitions[0].in[0].inscription: undeclared variable 'x'",
            errorOf(
                """{"name": "X", "pages": [{"name": "P", "places": [{"name": "A", "colset": "INT"}],
                   "transitions": [{"name": "T", "in": [{"place": "A", "inscription": "x"}]}]}]}""",
            ),
        )
        assertEquals(
            "pages[0].places[0].init[0]: 9 is not in SMALL",
            errorOf(
                """{"name": "X", "colsets": [{"name": "SMALL", "type": "int", "range": [1, 3]}],
                   "pages": [{"name": "P", "places": [{"name": "A", "colset": "SMALL", "init": [9]}]}]}""",
            ),
        )
        assertEquals(
            "pages[0].substitutions[0].subpage: no page named 'Missing'",
            errorOf("""{"name": "X", "pages": [{"name": "P", "substitutions": [{"name": "S", "subpage": "Missing"}]}]}"""),
        )
        assertTrue(
            "contains itself" in
                errorOf("""{"name": "X", "pages": [{"name": "P", "substitutions": [{"name": "S", "subpage": "P"}]}]}"""),
        )
    }

    @Test
    fun structuralProblemsComeFromTheValidator() {
        val json =
            """
            {"name": "X", "pages": [
              {"name": "Sub", "places": [{"name": "In", "colset": "INT", "port": "in"}]},
              {"name": "Top", "substitutions": [{"name": "S", "subpage": "Sub"}]}
            ]}
            """.trimIndent()
        val error = assertFailsWith<InvalidNetException> { JsonNetFormat.parse(json) }
        assertTrue(error.problems.any { "port 'In' of page 'Sub' is not assigned" in it }, error.problems.toString())
    }
}
