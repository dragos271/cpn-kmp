package no.hvl.cpn.app.canvas

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.io.model.ArcEdge
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.PlaceNode
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.TransitionNode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {
    private val place = NodeShape("p", isPlace = true, center = Point(100.0, 100.0), halfWidth = 40.0, halfHeight = 25.0)
    private val transition = NodeShape("t", isPlace = false, center = Point(300.0, 100.0), halfWidth = 45.0, halfHeight = 20.0)

    private fun near(
        expected: Point,
        actual: Point,
    ) = assertTrue(abs(expected.x - actual.x) < 1e-9 && abs(expected.y - actual.y) < 1e-9, "expected $expected, got $actual")

    @Test
    fun arcsStartAndEndOnTheShapeBoundaries() {
        near(Point(140.0, 100.0), place.boundaryToward(transition.center))
        near(Point(255.0, 100.0), transition.boundaryToward(place.center))
        near(Point(100.0, 75.0), place.boundaryToward(Point(100.0, 0.0)))
        near(Point(300.0, 120.0), transition.boundaryToward(Point(300.0, 500.0)))
        // On the ellipse: (dx/a)^2 + (dy/b)^2 = 1 for a diagonal direction too.
        val diagonal = place.boundaryToward(Point(200.0, 200.0))
        val dx = (diagonal.x - 100.0) / 40.0
        val dy = (diagonal.y - 100.0) / 25.0
        assertTrue(abs(dx * dx + dy * dy - 1.0) < 1e-9)
    }

    @Test
    fun hitTestingUsesTheRealShape() {
        val shapes = listOf(place, transition)
        assertEquals("p", hitTest(shapes, Point(100.0, 100.0))?.id)
        assertNull(hitTest(shapes, Point(138.0, 122.0)), "inside the bounding box, outside the ellipse")
        assertEquals("t", hitTest(shapes, Point(340.0, 118.0))?.id, "rectangle corners count")
        assertFalse(place.contains(Point(141.0, 100.0)))
    }

    @Test
    fun arcLinesFollowTokenFlow() {
        val page =
            PageDocument(
                "P",
                "P",
                listOf(PlaceNode("p", "p", "INT", "", Point(100.0, 100.0))),
                listOf(TransitionNode("t", "t", "", Point(300.0, 100.0))),
                listOf(
                    ArcEdge("in", "p", "t", ArcDirection.PLACE_TO_TRANSITION, "n"),
                    ArcEdge("out", "p", "t", ArcDirection.TRANSITION_TO_PLACE, "n"),
                    ArcEdge("both", "p", "t", ArcDirection.BIDIRECTIONAL, "n"),
                ),
            )
        val lines = arcLines(page, mapOf("p" to place, "t" to transition)).associateBy { it.id }
        assertTrue(lines.getValue("in").start.x < lines.getValue("in").end.x, "input arcs point at the transition")
        assertTrue(lines.getValue("out").start.x > lines.getValue("out").end.x, "output arcs point at the place")
        assertTrue(lines.getValue("both").bothEnds)
        // Three arcs between the same pair are drawn side by side, not on top of each other.
        assertEquals(
            3,
            lines.values
                .map { it.start.y }
                .toSet()
                .size,
        )

        fun midY(line: ArcLine) = (line.start.y + line.end.y) / 2
        assertTrue(
            abs(midY(lines.getValue("in")) - midY(lines.getValue("out"))) > PARALLEL_ARC_GAP * 0.8,
            "neighbouring arcs are clearly apart",
        )
        // Outer arcs of a parallel group put their labels outside the group; the middle one goes above.
        assertTrue(lines.getValue("in").labelAnchor().y < lines.getValue("in").start.y)
        assertTrue(lines.getValue("both").labelAnchor().y > lines.getValue("both").start.y)
        val single = arcLines(page.copy(arcs = page.arcs.take(1)), mapOf("p" to place, "t" to transition)).single()
        assertTrue(single.labelAnchor().y < single.start.y, "a lone horizontal arc has its label above")
    }

    @Test
    fun arcsCanBeClickedNearTheirLine() {
        val horizontal = ArcLine("h", Point(0.0, 0.0), Point(100.0, 0.0), bothEnds = false)
        val vertical = ArcLine("v", Point(50.0, 10.0), Point(50.0, 100.0), bothEnds = false)
        assertEquals(3.0, distanceToArc(horizontal, Point(40.0, 3.0)), 1e-9)
        assertEquals(5.0, distanceToArc(horizontal, Point(-3.0, 4.0)), 1e-9, "beyond the end, distance to the end point")
        assertEquals("h", arcAt(listOf(horizontal, vertical), Point(20.0, 4.0), tolerance = 6.0)?.id)
        assertEquals("v", arcAt(listOf(horizontal, vertical), Point(52.0, 60.0), tolerance = 6.0)?.id)
        assertNull(arcAt(listOf(horizontal, vertical), Point(20.0, 30.0), tolerance = 6.0))
    }

    @Test
    fun snappingAndBounds() {
        assertEquals(Point(120.0, 50.0), snap(Point(123.0, 47.0)))
        assertEquals(Point(60.0, 75.0) to Point(345.0, 125.0), bounds(listOf(place, transition)))
        assertNull(bounds(emptyList()))
    }
}
