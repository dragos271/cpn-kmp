package no.hvl.cpn.app.canvas

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A place (ellipse) or transition (rectangle) on the canvas, in world coordinates. */
data class NodeShape(
    val id: String,
    val isPlace: Boolean,
    val center: Point,
    val halfWidth: Double,
    val halfHeight: Double,
) {
    val left: Double get() = center.x - halfWidth
    val top: Double get() = center.y - halfHeight
    val right: Double get() = center.x + halfWidth
    val bottom: Double get() = center.y + halfHeight

    fun contains(p: Point): Boolean {
        val dx = (p.x - center.x) / halfWidth
        val dy = (p.y - center.y) / halfHeight
        return if (isPlace) dx * dx + dy * dy <= 1.0 else abs(dx) <= 1.0 && abs(dy) <= 1.0
    }

    /** Where the line from the centre towards [target] leaves the shape. */
    fun boundaryToward(target: Point): Point {
        val dx = target.x - center.x
        val dy = target.y - center.y
        if (dx == 0.0 && dy == 0.0) return center
        val scale =
            if (isPlace) {
                1.0 / sqrt((dx / halfWidth) * (dx / halfWidth) + (dy / halfHeight) * (dy / halfHeight))
            } else {
                min(if (dx == 0.0) Double.MAX_VALUE else halfWidth / abs(dx), if (dy == 0.0) Double.MAX_VALUE else halfHeight / abs(dy))
            }
        return Point(center.x + dx * scale, center.y + dy * scale)
    }
}

/**
 * An arc drawn from [start] to [end]; [bothEnds] for CPN Tools double arcs. [labelSide] is the unit
 * direction its inscription is pushed to: away from its siblings for parallel arcs, or null to put
 * it above the line.
 */
data class ArcLine(
    val id: String,
    val start: Point,
    val end: Point,
    val bothEnds: Boolean,
    val labelSide: Point? = null,
) {
    /** Where the inscription goes: beside the middle of the line. */
    fun labelAnchor(offset: Double = 12.0): Point {
        val mx = (start.x + end.x) / 2
        val my = (start.y + end.y) / 2
        labelSide?.let { return Point(mx + it.x * offset, my + it.y * offset) }
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = sqrt(dx * dx + dy * dy).takeIf { it > 0 } ?: return Point(mx, my)
        // Perpendicular, pointing up (or left for vertical lines), so labels sit above the line.
        var nx = -dy / length
        var ny = dx / length
        if (ny > 0 || (ny == 0.0 && nx > 0)) {
            nx = -nx
            ny = -ny
        }
        return Point(mx + nx * offset, my + ny * offset)
    }
}

/** Shape sizes: CPN Tools' defaults, widened to fit the name. */
object ShapeSizes {
    const val PLACE_MIN_WIDTH = 80.0
    const val PLACE_HEIGHT = 50.0
    const val TRANSITION_MIN_WIDTH = 90.0
    const val TRANSITION_HEIGHT = 40.0
    const val PADDING = 26.0

    fun place(nameWidth: Double): Pair<Double, Double> = max(PLACE_MIN_WIDTH, nameWidth + PADDING + 10) / 2 to PLACE_HEIGHT / 2

    fun transition(nameWidth: Double): Pair<Double, Double> = max(TRANSITION_MIN_WIDTH, nameWidth + PADDING) / 2 to TRANSITION_HEIGHT / 2
}

/** Shapes of every place and transition of [page]; [nameWidth] measures a name in world units. */
fun shapesOf(
    page: PageDocument,
    nameWidth: (String) -> Double,
): List<NodeShape> {
    val places =
        page.places.map {
            val (hw, hh) = ShapeSizes.place(nameWidth(it.name))
            NodeShape(it.id, true, it.position, hw, hh)
        }
    val transitions =
        page.transitions.map {
            val (hw, hh) = ShapeSizes.transition(nameWidth(it.name))
            NodeShape(it.id, false, it.position, hw, hh)
        }
    return places + transitions
}

/** Gap between arcs that connect the same place and transition, so they are drawn side by side. */
const val PARALLEL_ARC_GAP = 16.0

/**
 * Arcs as lines between shape boundaries, pointing in the direction tokens move. Several arcs between
 * the same place and transition (e.g. an input and an output arc) are spread apart, as in CPN Tools.
 */
fun arcLines(
    page: PageDocument,
    shapes: Map<String, NodeShape>,
): List<ArcLine> {
    val byPair = page.arcs.groupBy { it.placeId to it.transitionId }
    return page.arcs.mapNotNull { arc ->
        val place = shapes[arc.placeId] ?: return@mapNotNull null
        val transition = shapes[arc.transitionId] ?: return@mapNotNull null
        val siblings = byPair.getValue(arc.placeId to arc.transitionId)
        // The normal is taken from place to transition for every arc of the pair, so input and
        // output arcs are pushed to opposite sides instead of onto each other.
        val dx = transition.center.x - place.center.x
        val dy = transition.center.y - place.center.y
        val length = sqrt(dx * dx + dy * dy).takeIf { it > 0 } ?: 1.0
        val offset = (siblings.indexOf(arc) - (siblings.size - 1) / 2.0) * PARALLEL_ARC_GAP
        val shift = Point(-dy / length * offset, dx / length * offset)
        val placePoint = place.boundaryToward(transition.center + shift) + shift
        val transitionPoint = transition.boundaryToward(place.center + shift) + shift
        val (start, end) =
            if (arc.direction ==
                ArcDirection.TRANSITION_TO_PLACE
            ) {
                transitionPoint to placePoint
            } else {
                placePoint to transitionPoint
            }
        // Parallel arcs put their inscriptions on their own outer side, so the labels do not collide.
        val side = if (offset == 0.0) null else Point(-dy / length * kotlin.math.sign(offset), dx / length * kotlin.math.sign(offset))
        ArcLine(arc.id, start, end, arc.direction == ArcDirection.BIDIRECTIONAL, side)
    }
}

/** The topmost shape under [point], if any (transitions are drawn over places). */
fun hitTest(
    shapes: List<NodeShape>,
    point: Point,
): NodeShape? = shapes.lastOrNull { it.contains(point) }

/** Smallest box around [shapes], or null if there are none. */
fun bounds(shapes: List<NodeShape>): Pair<Point, Point>? {
    if (shapes.isEmpty()) return null
    return Point(shapes.minOf { it.left }, shapes.minOf { it.top }) to Point(shapes.maxOf { it.right }, shapes.maxOf { it.bottom })
}

/** Rounds to the editing grid, so dragged elements line up. */
fun snap(
    p: Point,
    grid: Double = 10.0,
): Point = Point(kotlin.math.round(p.x / grid) * grid, kotlin.math.round(p.y / grid) * grid)
