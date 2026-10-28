package no.hvl.cpn.io.model

import no.hvl.cpn.core.Net
import no.hvl.cpn.io.JsonNetFormat
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.sim.Simulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentCompilerTest {
    /** examples/simple-protocol.json. */
    private val protocol =
        """
        {
          "name": "SimpleProtocol",
          "colsets": [
            { "name": "NO", "type": "int" },
            { "name": "DATA", "type": "string" },
            { "name": "NOxDATA", "type": "product", "of": ["NO", "DATA"] }
          ],
          "variables": { "n": "NO", "k": "NO", "d": "DATA", "data": "DATA", "success": "BOOL" },
          "pages": [
            {
              "name": "Protocol",
              "places": [
                {
                  "name": "PacketsToSend", "x": 100, "y": 110, "colset": "NOxDATA",
                  "init": [[1, "COL"], [2, "OUR"], [3, "ED "], [4, "PET"], [5, "RI "], [6, "NET"]]
                },
                { "name": "A", "x": 530, "y": 110, "colset": "NOxDATA" },
                { "name": "B", "x": 910, "y": 110, "colset": "NOxDATA" },
                { "name": "C", "x": 1110, "y": 450, "colset": "NO" },
                { "name": "D", "x": 600, "y": 450, "colset": "NO" },
                { "name": "NextSend", "x": 340, "y": 280, "colset": "NO", "init": [1] },
                { "name": "NextRec", "x": 1350, "y": 280, "colset": "NO", "init": [1] },
                { "name": "DataReceived", "x": 1350, "y": 110, "colset": "DATA", "init": [""] }
              ],
              "transitions": [
                {
                  "name": "SendPacket", "x": 340, "y": 110,
                  "both": [
                    { "place": "PacketsToSend", "inscription": { "pattern": ["n", "d"] } },
                    { "place": "NextSend", "inscription": "n" }
                  ],
                  "out": [{ "place": "A", "inscription": { "pattern": ["n", "d"] } }]
                },
                {
                  "name": "TransmitPacket", "x": 720, "y": 110,
                  "in": [{ "place": "A", "inscription": { "pattern": ["n", "d"] } }],
                  "out": [{
                    "place": "B",
                    "inscription": { "if": { "var": "success" }, "then": { "pattern": ["n", "d"] }, "else": { "tokens": [] } }
                  }]
                },
                {
                  "name": "ReceivePacket", "x": 1110, "y": 110,
                  "in": [
                    { "place": "B", "inscription": { "pattern": ["n", "d"] } },
                    { "place": "NextRec", "inscription": "k" },
                    { "place": "DataReceived", "inscription": "data" }
                  ],
                  "out": [
                    {
                      "place": "NextRec",
                      "inscription": { "expr": {
                        "if": { "op": "=", "args": [{ "var": "n" }, { "var": "k" }] },
                        "then": { "op": "+", "args": [{ "var": "k" }, 1] },
                        "else": { "var": "k" }
                      } }
                    },
                    {
                      "place": "DataReceived",
                      "inscription": { "expr": {
                        "if": { "op": "=", "args": [{ "var": "n" }, { "var": "k" }] },
                        "then": { "op": "^", "args": [{ "var": "data" }, { "var": "d" }] },
                        "else": { "var": "data" }
                      } }
                    },
                    {
                      "place": "C",
                      "inscription": { "expr": {
                        "if": { "op": "=", "args": [{ "var": "n" }, { "var": "k" }] },
                        "then": { "op": "+", "args": [{ "var": "k" }, 1] },
                        "else": { "var": "k" }
                      } }
                    }
                  ]
                },
                {
                  "name": "TransmitAck", "x": 860, "y": 450,
                  "in": [{ "place": "C", "inscription": "n" }],
                  "out": [{ "place": "D", "inscription": { "if": { "var": "success" }, "then": "n", "else": { "tokens": [] } } }]
                },
                {
                  "name": "ReceiveAck", "x": 340, "y": 450,
                  "in": [
                    { "place": "D", "inscription": "n" },
                    { "place": "NextSend", "inscription": "k" }
                  ],
                  "out": [{ "place": "NextSend", "inscription": "n" }]
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private val pairs =
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

    private val hierarchy =
        """
        {
          "name": "H",
          "variables": { "n": "INT" },
          "pages": [
            {
              "name": "Top",
              "places": [{ "name": "A", "colset": "INT", "init": [7, 8] }, { "name": "B", "colset": "INT" }],
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

    /** The trace and every place's final marking (sorted, since place order may differ between nets). */
    private fun run(
        net: Net,
        seed: Long,
        steps: Int = 300,
    ): String {
        val simulator = Simulator(net, seed)
        simulator.run(steps)
        val marking =
            simulator.flatNet.net.pages
                .flatMap { it.places }
                .map { "${it.name}: ${simulator.marking.tokensOf(it)}" }
                .sorted()
        return (simulator.trace.map { it.toString() } + marking).joinToString("\n")
    }

    private fun compiled(document: CpnDocument): Net {
        val result = document.compile()
        assertEquals(emptyList(), result.problems)
        return assertNotNull(result.net)
    }

    @Test
    fun jsonNetsRebuiltFromCpnMlTextGiveIdenticalTraces() {
        for ((name, json) in listOf("protocol" to protocol, "pairs" to pairs, "hierarchy" to hierarchy)) {
            val loaded = JsonNetFormat.load(json)
            for (seed in listOf(1L, 2026L, 99L)) {
                assertEquals(run(loaded.net!!, seed), run(compiled(loaded.document), seed), "$name, seed $seed")
            }
        }
    }

    @Test
    fun theProtocolDeliversEveryPacket() {
        val net = compiled(JsonNetFormat.load(protocol).document)
        val simulator = Simulator(net, 2026)
        simulator.run(10_000)
        val received =
            net.pages
                .single()
                .places
                .single { it.name == "DataReceived" }
        assertEquals(
            "1`\"COLOURED PETRI NET\"",
            simulator.marking.tokensOf(simulator.flatNet.place(simulator.flatNet.instancesOf(net.pages.single()).single(), received)),
        )
    }

    @Test
    fun editedModelsWithFunctionsDatatypesAndPatternsRun() {
        val document =
            CpnDocument(
                "Messages",
                """
                colset NO = int with 1..3;
                colset MSG = union Data : NO + Stop;
                colset STATE = record count : INT * last : NO;
                var n : NO;
                var s : STATE;
                fun next n = if n = 3 then Stop else Data (n + 1);
                """.trimIndent(),
                listOf(
                    PageDocument(
                        "P",
                        "P",
                        listOf(
                            PlaceNode("P.In", "In", "MSG", "1`(Data 1)", Point(0.0, 0.0)),
                            PlaceNode("P.State", "State", "STATE", "{count = 0, last = 1}", Point(0.0, 0.0)),
                        ),
                        listOf(TransitionNode("P.Step", "Step", "n < 3 orelse #count s < 10", Point(0.0, 0.0))),
                        listOf(
                            ArcEdge("a1", "P.In", "P.Step", no.hvl.cpn.core.ArcDirection.PLACE_TO_TRANSITION, "Data n"),
                            ArcEdge("a2", "P.In", "P.Step", no.hvl.cpn.core.ArcDirection.TRANSITION_TO_PLACE, "next n"),
                            ArcEdge("a3", "P.State", "P.Step", no.hvl.cpn.core.ArcDirection.BIDIRECTIONAL, "s"),
                        ),
                    ),
                ),
            )
        val trace = run(compiled(document), seed = 1, steps = 10)
        assertEquals(
            listOf(
                "1 P'Step 1 <n=1,s={count=0,last=1}>",
                "2 P'Step 1 <n=2,s={count=0,last=1}>",
                "3 P'Step 1 <n=3,s={count=0,last=1}>",
                "P'In 1: 1`Stop",
                "P'State 1: 1`{count=0,last=1}",
            ),
            trace.lines(),
        )
    }

    @Test
    fun evaluationErrorsAreProblemsOfTheElement() {
        val document = ModelFiles.newModel().document
        val page = document.pages.single()
        val broken =
            document.withPage(page.copy(places = listOf(PlaceNode("p", "P", "INT", "1`(1 div 0)", Point(0.0, 0.0)))))
        val result = broken.compile()
        assertNull(result.net)
        assertEquals(listOf(ProblemField.INITIAL_MARKING), result.problems.map { it.field })
        assertTrue("uncaught exception Div" in result.problems.single().message, result.problems.single().message)
    }

    @Test
    fun portAssignmentsSurviveTheDocumentFormat() {
        val document = JsonNetFormat.load(hierarchy).document
        val move =
            document.pages
                .first()
                .transitions
                .single()
        assertEquals(mapOf("Top.A" to "Mover.In", "Top.B" to "Mover.Out"), move.portAssignments)
        val reread =
            no.hvl.cpn.io.CpnDocumentFormat
                .read(
                    no.hvl.cpn.io.CpnDocumentFormat
                        .write(document),
                )
        assertEquals(document, reread)
    }
}
