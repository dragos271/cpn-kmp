package no.hvl.cpn.io.model

import no.hvl.cpn.core.ArcDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentEditingTest {
    private val empty =
        CpnDocument(
            name = "New",
            declarations = "colset NO = int;\nvar n : NO;",
            pages = listOf(PageDocument("Main", "Main", emptyList(), emptyList(), emptyList())),
        )

    private fun CpnDocument.withPlaceAndTransition(): Triple<CpnDocument, String, String> {
        val (withPlace, placeId) = addPlace("Main", Point(100.0, 100.0))
        val (withBoth, transitionId) = withPlace.addTransition("Main", Point(300.0, 100.0))
        return Triple(withBoth, placeId, transitionId)
    }

    @Test
    fun newElementsGetFreshIdsAndNames() {
        val (doc, p1) = empty.addPlace("Main", Point(10.0, 20.0))
        val (doc2, p2) = doc.addPlace("Main", Point(30.0, 20.0))
        val (doc3, t1) = doc2.addTransition("Main", Point(50.0, 20.0))
        val page = doc3.page("Main")!!
        assertEquals(listOf("P1", "P2"), page.places.map { it.name })
        assertEquals("T1", page.transition(t1)!!.name)
        assertNotEquals(p1, p2)
        assertEquals(DEFAULT_COLOUR_SET, page.place(p1)!!.colourSet)
        assertEquals(Point(10.0, 20.0), page.place(p1)!!.position)
    }

    @Test
    fun arcsFollowTheDragDirection() {
        val (doc, place, transition) = empty.withPlaceAndTransition()
        val input = assertIs<ConnectResult.Connected>(doc.connect("Main", place, transition))
        assertEquals(
            ArcDirection.PLACE_TO_TRANSITION,
            input.document
                .page("Main")!!
                .arcs
                .single()
                .direction,
        )
        val output = assertIs<ConnectResult.Connected>(input.document.connect("Main", transition, place))
        val arcs = output.document.page("Main")!!.arcs
        assertEquals(listOf(ArcDirection.PLACE_TO_TRANSITION, ArcDirection.TRANSITION_TO_PLACE), arcs.map { it.direction })
        assertTrue(arcs.all { it.placeId == place && it.transitionId == transition })
    }

    @Test
    fun invalidArcsAreRejectedWithAReason() {
        val (doc, place, transition) = empty.withPlaceAndTransition()
        val (doc2, place2) = doc.addPlace("Main", Point(100.0, 300.0))
        assertEquals(
            "Arcs connect a place and a transition, not two places",
            assertIs<ConnectResult.Rejected>(doc2.connect("Main", place, place2)).reason,
        )
        assertIs<ConnectResult.Rejected>(doc2.connect("Main", transition, transition))
        val connected = assertIs<ConnectResult.Connected>(doc2.connect("Main", place, transition)).document
        assertTrue("already an arc" in assertIs<ConnectResult.Rejected>(connected.connect("Main", place, transition)).reason)
    }

    @Test
    fun deletingANodeDeletesItsArcs() {
        val (doc, place, transition) = empty.withPlaceAndTransition()
        val connected = assertIs<ConnectResult.Connected>(doc.connect("Main", place, transition))
        val withoutPlace = connected.document.delete("Main", place).page("Main")!!
        assertTrue(withoutPlace.places.isEmpty())
        assertTrue(withoutPlace.arcs.isEmpty())
        assertEquals(1, withoutPlace.transitions.size)
        val withoutArc = connected.document.delete("Main", connected.arcId).page("Main")!!
        assertEquals(1, withoutArc.places.size)
        assertTrue(withoutArc.arcs.isEmpty())
    }

    @Test
    fun updatesKeepIds() {
        val (doc, place, transition) = empty.withPlaceAndTransition()
        val renamed = doc.updatePlace("Main", place) { it.copy(name = "Buffer", colourSet = "NO", initialMarking = "1`3") }
        val p = renamed.page("Main")!!.place(place)!!
        assertEquals(listOf("Buffer", "NO", "1`3"), listOf(p.name, p.colourSet, p.initialMarking))
        val guarded = renamed.updateTransition("Main", transition) { it.copy(id = "hijack", guard = "n > 0") }
        assertEquals("n > 0", guarded.page("Main")!!.transition(transition)!!.guard, "the id cannot be changed by an update")
    }

    @Test
    fun substitutionTransitionsCreateTheirSubpage() {
        val (doc, id) = empty.addSubstitution("Main", Point(0.0, 0.0))
        assertEquals(listOf("Main", "Page1"), doc.pages.map { it.id })
        assertEquals("Page1", doc.page("Main")!!.transition(id)!!.subpageId)
        val (doc2, _) = doc.addSubstitution("Main", Point(0.0, 0.0))
        assertEquals("Page2", doc2.pages.last().id)
    }

    @Test
    fun structureIgnoresLayout() {
        val (doc, place, _) = empty.withPlaceAndTransition()
        val moved = doc.withPage(doc.page("Main")!!.moved(place, Point(999.0, 1.0)))
        assertEquals(doc.structure(), moved.structure())
        assertNotEquals(doc.structure(), doc.updatePlace("Main", place) { it.copy(name = "X") }.structure())
    }

    @Test
    fun checkReportsSyntaxErrorsAndMissingParts() {
        val (doc, place, transition) = empty.withPlaceAndTransition()
        val arc = assertIs<ConnectResult.Connected>(doc.connect("Main", place, transition))
        assertEquals(
            listOf(Problem(arc.arcId, ProblemField.INSCRIPTION, "An arc needs an inscription")),
            arc.document.check(),
        )
        val broken =
            arc.document
                .updatePlace("Main", place) { it.copy(colourSet = "NOPE", initialMarking = "1`") }
                .updateTransition("Main", transition) { it.copy(guard = "n >") }
                .updateArc("Main", arc.arcId) { it.copy(inscription = "n") }
        val problems = broken.check().associateBy { it.elementId to it.field }
        assertEquals("Unknown colour set NOPE", problems.getValue(place to ProblemField.COLOUR_SET).message)
        assertEquals("1:3: expected an expression, found end of input", problems.getValue(place to ProblemField.INITIAL_MARKING).message)
        assertEquals("1:4: expected an expression, found end of input", problems.getValue(transition to ProblemField.GUARD).message)
        assertEquals(3, problems.size)
    }

    @Test
    fun checkReportsTypeErrors() {
        val (doc, place, transition) = empty.withPlaceAndTransition()
        val arc = assertIs<ConnectResult.Connected>(doc.connect("Main", place, transition))
        val typed =
            arc.document
                .updatePlace("Main", place) { it.copy(colourSet = "NO", initialMarking = "1`\"three\"") }
                .updateTransition("Main", transition) { it.copy(guard = "n + 1") }
                .updateArc("Main", arc.arcId) { it.copy(inscription = "1`(n, m)") }
        val problems = typed.check().associateBy({ it.elementId to it.field }, { it.message })
        assertEquals(
            "1:1: initial marking has type string ms, but the place holds NO: expected int or int ms",
            problems[place to ProblemField.INITIAL_MARKING],
        )
        assertEquals("1:1: a guard must be bool or a list of bools, not int", problems[transition to ProblemField.GUARD])
        assertEquals("1:7: unbound value or constructor: m", problems[arc.arcId to ProblemField.INSCRIPTION])
        val fixed =
            typed
                .updateArc("Main", arc.arcId) {
                    it.copy(inscription = "1`(n, n)")
                }.check()
                .associateBy({ it.elementId to it.field }, { it.message })
        assertEquals(
            "1:1: arc inscription has type (int * int) ms, but the place holds NO: expected int or int ms",
            fixed[arc.arcId to ProblemField.INSCRIPTION],
        )
    }

    @Test
    fun checkReportsDeclarationErrors() {
        val bad = empty.copy(declarations = "colset NO = int;\ncolset P = product NO * DATA;\nvar x : MISSING;")
        assertEquals(
            listOf("2:25: unknown colour set DATA", "3:9: unknown colour set MISSING"),
            bad.check().filter { it.field == ProblemField.DECLARATIONS }.map { it.message },
        )
        assertEquals(
            "1:8: expected a colour set name, found '='",
            empty
                .copy(declarations = "colset = int;")
                .check()
                .single()
                .message,
        )
    }
}
