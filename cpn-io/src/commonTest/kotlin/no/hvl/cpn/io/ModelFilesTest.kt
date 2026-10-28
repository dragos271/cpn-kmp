package no.hvl.cpn.io

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.PortType
import no.hvl.cpn.io.model.ConnectResult
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.addPlace
import no.hvl.cpn.io.model.addTransition
import no.hvl.cpn.io.model.connect
import no.hvl.cpn.io.model.updateArc
import no.hvl.cpn.sim.Simulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelFilesTest {
    private val runnable =
        """
        {
          "name": "Counter",
          "variables": { "n": "INT" },
          "pages": [{
            "name": "Main",
            "places": [{ "name": "P", "colset": "INT", "init": [0], "x": 100, "y": 100 }, { "name": "Q", "colset": "INT" }],
            "transitions": [{
              "name": "Inc",
              "in": [{ "place": "P", "inscription": "n" }],
              "out": [{ "place": "Q", "inscription": "n" }]
            }]
          }]
        }
        """.trimIndent()

    @Test
    fun movingShapesKeepsTheModelRunnable() {
        val model = ModelFiles.open(runnable)
        val page = model.document.pages.single()
        val moved = model.document.withPage(page.moved("Main.P", Point(40.0, 60.0)).moved("Main.Inc", Point(250.5, 60.0)))
        val saved = ModelFiles.save(model, moved)
        assertEquals(SavedFormat.RUNNABLE_MODEL, saved.format)

        // Still the runnable format: the engine reads it, with the new layout.
        val reopened = ModelFiles.open(saved.text)
        assertNotNull(reopened.net)
        assertEquals(
            Point(40.0, 60.0),
            reopened.document
                .page("Main")!!
                .place("Main.P")!!
                .position,
        )
        assertEquals(
            Point(250.5, 60.0),
            reopened.document
                .page("Main")!!
                .transition("Main.Inc")!!
                .position,
        )
        assertEquals("1 Main'Inc 1 <n=0>", Simulator(reopened.net).step().toString())
        // Q had no position; it got one from the layout and keeps it now.
        assertTrue("\"x\"" in saved.text.substringAfter("\"Q\""))
    }

    @Test
    fun structuralEditsAreSavedAsADocument() {
        val model = ModelFiles.open(runnable)
        val (withPlace, placeId) = model.document.addPlace("Main", Point(300.0, 300.0))
        val saved = ModelFiles.save(model, withPlace)
        assertEquals(SavedFormat.DOCUMENT, saved.format)
        val reopened = ModelFiles.open(saved.text)
        // The document is compiled on opening, so it runs like the original (T2.5).
        assertEquals("1 Main'Inc 1 <n=0>", Simulator(assertNotNull(reopened.net)).step().toString())
        assertEquals(withPlace, reopened.document)
        assertEquals(
            "P1",
            reopened.document
                .page("Main")!!
                .place(placeId)!!
                .name,
        )
    }

    @Test
    fun documentsRoundTripEverything() {
        val (doc1, place) = ModelFiles.newModel("Shop").document.addPlace("Page1", Point(10.0, 20.0))
        val (doc2, transition) = doc1.addTransition("Page1", Point(110.5, 20.0))
        val connected = assertIs<ConnectResult.Connected>(doc2.connect("Page1", transition, place))
        val document =
            connected.document
                .updateArc(
                    "Page1",
                    connected.arcId,
                ) { it.copy(inscription = "if ok then 1`x else empty", bends = listOf(Point(60.0, 0.0))) }
                .copy(declarations = "colset X = with a | b;\nvar x : X;")
                .let { doc ->
                    doc.withPage(
                        doc
                            .page(
                                "Page1",
                            )!!
                            .copy(places = doc.page("Page1")!!.places.map { it.copy(port = PortType.IN_OUT, fusionSet = "F") }),
                    )
                }
        val text = CpnDocumentFormat.write(document)
        assertTrue("\"format\": \"cpn-document\"" in text)
        assertEquals(document, CpnDocumentFormat.read(text))
        assertEquals(
            ArcDirection.TRANSITION_TO_PLACE,
            CpnDocumentFormat
                .read(text)
                .page("Page1")!!
                .arcs
                .single()
                .direction,
        )
    }

    @Test
    fun newModelsAreEmptyDocuments() {
        val model = ModelFiles.newModel()
        assertNull(model.net)
        assertEquals(listOf("Page1"), model.document.pages.map { it.id })
        assertEquals(SavedFormat.DOCUMENT, ModelFiles.save(model, model.document).format)
    }

    @Test
    fun badFilesSayWhatIsWrong() {
        assertTrue("not valid JSON" in assertFailsWith<ModelFormatException> { ModelFiles.open("{") }.message!!)
        assertEquals(
            "version: unsupported version 9; this app reads version 1",
            assertFailsWith<ModelFormatException> {
                ModelFiles.open(
                    """{"format": "cpn-document", "version": 9, "name": "X", "pages": []}""",
                )
            }.message,
        )
        val danglingArc =
            """{"format": "cpn-document", "version": 1, "name": "X", "pages": [{"id": "P", "name": "P",
               "arcs": [{"id": "a", "place": "P.nope", "transition": "P.t", "direction": "in"}]}]}"""
        assertEquals(
            "pages[0].arcs[0].place: no place 'P.nope' on this page",
            assertFailsWith<ModelFormatException> {
                ModelFiles.open(danglingArc)
            }.message,
        )
    }
}
