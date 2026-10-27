package no.hvl.cpn.io

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.io.model.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonDocumentTest {
    /** The textbook simple protocol, as in examples/simple-protocol.json (with a reduced packet list). */
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
          "pages": [{
            "name": "Protocol",
            "places": [
              { "name": "PacketsToSend", "x": 110, "y": 110, "colset": "NOxDATA", "init": [[1, "COL"], [2, "OUR"]] },
              { "name": "A", "x": 460, "y": 110, "colset": "NOxDATA" },
              { "name": "B", "x": 780, "y": 110, "colset": "NOxDATA" },
              { "name": "NextRec", "x": 1130, "y": 260, "colset": "NO", "init": [1] },
              { "name": "DataReceived", "x": 1130, "y": 110, "colset": "DATA", "init": [""] }
            ],
            "transitions": [
              {
                "name": "TransmitPacket", "x": 620, "y": 110,
                "in": [{ "place": "A", "inscription": { "pattern": ["n", "d"] } }],
                "out": [{ "place": "B", "inscription": { "if": { "var": "success" }, "then": { "pattern": ["n", "d"] }, "else": { "tokens": [] } } }]
              },
              {
                "name": "ReceivePacket", "x": 940, "y": 110,
                "guard": { "op": "andalso", "args": [{ "op": ">", "args": [{ "var": "n" }, 0] }, { "op": "not", "args": [false] }] },
                "in": [
                  { "place": "B", "inscription": { "pattern": ["n", "d"] } },
                  { "place": "NextRec", "inscription": "k" },
                  { "place": "DataReceived", "inscription": "data" }
                ],
                "out": [
                  { "place": "NextRec", "inscription": { "expr": {
                    "if": { "op": "=", "args": [{ "var": "n" }, { "var": "k" }] },
                    "then": { "op": "+", "args": [{ "var": "k" }, 1] }, "else": { "var": "k" } } } },
                  { "place": "DataReceived", "inscription": { "expr": {
                    "if": { "op": "=", "args": [{ "var": "n" }, { "var": "k" }] },
                    "then": { "op": "^", "args": [{ "var": "data" }, { "var": "d" }] }, "else": { "var": "data" } } } }
                ]
              }
            ]
          }]
        }
        """.trimIndent()

    @Test
    fun declarationsAsCpnMl() {
        val document = JsonNetFormat.load(protocol).document
        assertEquals(
            """
            colset NO = int;
            colset DATA = string;
            colset NOxDATA = product NO * DATA;
            var n, k : NO;
            var d, data : DATA;
            var success : BOOL;
            """.trimIndent(),
            document.declarations,
        )
    }

    @Test
    fun placesKeepTheirPositionsAndMarkings() {
        val page =
            JsonNetFormat
                .load(protocol)
                .document.pages
                .single()
        val packets = page.place("Protocol.PacketsToSend")!!
        assertEquals(Point(110.0, 110.0), packets.position)
        assertEquals("NOxDATA", packets.colourSet)
        assertEquals("1`(1,\"COL\")++1`(2,\"OUR\")", packets.initialMarking)
        assertEquals("", page.place("Protocol.A")!!.initialMarking)
        assertEquals("1`\"\"", page.place("Protocol.DataReceived")!!.initialMarking)
    }

    @Test
    fun inscriptionsAsCpnMl() {
        val page =
            JsonNetFormat
                .load(protocol)
                .document.pages
                .single()
        val texts =
            page.arcs.associate {
                "${it.transitionId.substringAfter('.')}/${it.placeId.substringAfter('.')}/${it.direction}" to
                    it.inscription
            }
        assertEquals("(n,d)", texts["TransmitPacket/A/PLACE_TO_TRANSITION"])
        assertEquals("if success then (n,d) else empty", texts["TransmitPacket/B/TRANSITION_TO_PLACE"])
        assertEquals("if n = k then k + 1 else k", texts["ReceivePacket/NextRec/TRANSITION_TO_PLACE"])
        assertEquals("if n = k then data ^ d else data", texts["ReceivePacket/DataReceived/TRANSITION_TO_PLACE"])
        assertEquals("(n > 0) andalso (not false)", page.transition("Protocol.ReceivePacket")!!.guard)
        assertEquals("", page.transition("Protocol.TransmitPacket")!!.guard)
    }

    @Test
    fun documentAndNetShareIds() {
        val (net, document) = JsonNetFormat.load(protocol)
        val netPage = net.page("Protocol")
        val docPage = document.pages.single()
        assertEquals(netPage.places.map { it.id }, docPage.places.map { it.id })
        assertEquals(netPage.transitions.map { it.id }, docPage.transitions.map { it.id })
        // One document arc per net arc, between the same place and transition.
        assertEquals(
            netPage.arcs.map { Triple(it.place.id, it.transition.id, it.direction) }.toSet(),
            docPage.arcs.map { Triple(it.placeId, it.transitionId, it.direction) }.toSet(),
        )
    }

    @Test
    fun modelsWithoutPositionsAreLaidOutAlongTheFlow() {
        val chain =
            """
            {
              "name": "Chain",
              "variables": { "n": "INT" },
              "pages": [{
                "name": "Main",
                "places": [
                  { "name": "P1", "colset": "INT", "init": [1] },
                  { "name": "P2", "colset": "INT" },
                  { "name": "P3", "colset": "INT" }
                ],
                "transitions": [
                  { "name": "T2", "in": [{ "place": "P2", "inscription": "n" }], "out": [{ "place": "P3", "inscription": "n" }] },
                  { "name": "T1", "in": [{ "place": "P1", "inscription": "n" }], "out": [{ "place": "P2", "inscription": "n" }] }
                ]
              }]
            }
            """.trimIndent()
        val page =
            JsonNetFormat
                .load(chain)
                .document.pages
                .single()
        val x = (page.places.map { it.name to it.position.x } + page.transitions.map { it.name to it.position.x }).toMap()
        assertTrue(x.getValue("P1") < x.getValue("T1") && x.getValue("T1") < x.getValue("P2"))
        assertTrue(x.getValue("P2") < x.getValue("T2") && x.getValue("T2") < x.getValue("P3"), "tokens flow left to right: $x")
        val positions = page.places.map { it.position } + page.transitions.map { it.position }
        assertEquals(positions.size, positions.toSet().size, "no two elements on top of each other")
    }

    @Test
    fun substitutionTransitionsGetSocketArcs() {
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
        val document = JsonNetFormat.load(json).document
        val top = document.page("Top")!!
        assertEquals("Mover", top.transition("Top.Move")!!.subpageId)
        assertEquals(
            setOf("Top.A" to ArcDirection.PLACE_TO_TRANSITION, "Top.B" to ArcDirection.TRANSITION_TO_PLACE),
            top.arcs.map { it.placeId to it.direction }.toSet(),
        )
        assertEquals(no.hvl.cpn.core.PortType.IN, document.page("Mover")!!.place("Mover.In")!!.port)
    }
}
