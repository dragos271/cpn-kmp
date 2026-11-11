package no.hvl.cpn.io.model

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.PortType

/*
 * The editable model: what a CPN editor shows and changes, as opposed to the executable
 * no.hvl.cpn.core.Net the engine runs. It holds the layout (positions) and every inscription as
 * CPN ML text, exactly as CPN Tools stores a .cpn file. The JSON reader, the .cpn importer (T3.1)
 * and the app's editor all produce it; compiling it to a Net goes through cpn-lang (T2.5).
 *
 * Immutable: edits make new values, which makes undo and redo cheap.
 */

/** A point on the canvas, in pixels at zoom 1; y grows downwards. */
public data class Point(
    val x: Double,
    val y: Double,
) {
    public operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)

    public operator fun minus(other: Point): Point = Point(x - other.x, y - other.y)
}

/** A node's width and height in pixels, as CPN Tools stores them; null in the model means the editor's default size. */
public data class Size(
    val width: Double,
    val height: Double,
)

/*
 * Label positions (`...Label` fields) are where CPN Tools draws an inscription: the centre of its text,
 * in canvas coordinates like [Point]. Null means the editor places the label itself (models made in
 * the app); `.cpn` files store one for every inscription.
 */

public data class PlaceNode(
    val id: String,
    val name: String,
    val colourSet: String,
    /** Initial marking as CPN ML text, e.g. ``1`1++1`2``; empty for no tokens. */
    val initialMarking: String,
    val position: Point,
    val port: PortType? = null,
    val fusionSet: String? = null,
    val size: Size? = null,
    val colourSetLabel: Point? = null,
    val markingLabel: Point? = null,
)

public data class TransitionNode(
    val id: String,
    val name: String,
    /** Guard as CPN ML text; empty for none. */
    val guard: String,
    val position: Point,
    /** Set for substitution transitions: the page they stand for. */
    val subpageId: String? = null,
    /**
     * For substitution transitions: socket place id (on this page) to port place id (on the
     * subpage). A socket without an entry is glued to the port with the same name.
     */
    val portAssignments: Map<String, String> = emptyMap(),
    /** CPN Tools' time region, e.g. `@+9`: the transition's delay in timed nets (T3.2); empty for none. */
    val time: String = "",
    /** CPN Tools' priority region, e.g. `P_HIGH`; empty for the default. Not used by the simulator yet. */
    val priority: String = "",
    /** CPN Tools' code segment; empty for none. Not supported: [check] reports it. */
    val code: String = "",
    val size: Size? = null,
    val guardLabel: Point? = null,
    val timeLabel: Point? = null,
)

/** CPN Tools' arc kinds. Inhibitor and reset arcs are outside the CPN definition (Jensen & Kristensen) and not supported yet. */
public enum class ArcKind { NORMAL, INHIBITOR, RESET }

/** An arc between a place and a transition on the same page. */
public data class ArcEdge(
    val id: String,
    val placeId: String,
    val transitionId: String,
    val direction: ArcDirection,
    /** Arc inscription as CPN ML text; empty for socket arcs of substitution transitions. */
    val inscription: String,
    val bends: List<Point> = emptyList(),
    val kind: ArcKind = ArcKind.NORMAL,
    val label: Point? = null,
)

public data class PageDocument(
    val id: String,
    val name: String,
    val places: List<PlaceNode>,
    val transitions: List<TransitionNode>,
    val arcs: List<ArcEdge>,
) {
    public fun place(id: String): PlaceNode? = places.firstOrNull { it.id == id }

    public fun transition(id: String): TransitionNode? = transitions.firstOrNull { it.id == id }

    public fun arcsOf(nodeId: String): List<ArcEdge> = arcs.filter { it.placeId == nodeId || it.transitionId == nodeId }

    /**
     * Moves a place or transition. Its labels move with it, and the labels of its arcs move half as
     * far, as the middle of the arc does.
     */
    public fun moved(
        nodeId: String,
        position: Point,
    ): PageDocument {
        val old = place(nodeId)?.position ?: transition(nodeId)?.position ?: return this
        val delta = position - old
        val half = Point(delta.x / 2, delta.y / 2)
        return copy(
            places =
                places.map {
                    if (it.id != nodeId) {
                        it
                    } else {
                        it.copy(
                            position = position,
                            colourSetLabel = it.colourSetLabel?.plus(delta),
                            markingLabel = it.markingLabel?.plus(delta),
                        )
                    }
                },
            transitions =
                transitions.map {
                    if (it.id != nodeId) {
                        it
                    } else {
                        it.copy(position = position, guardLabel = it.guardLabel?.plus(delta), timeLabel = it.timeLabel?.plus(delta))
                    }
                },
            arcs = arcs.map { if (it.placeId == nodeId || it.transitionId == nodeId) it.copy(label = it.label?.plus(half)) else it },
        )
    }
}

public data class CpnDocument(
    val name: String,
    /** Global declarations as CPN ML text (`colset`, `var`, `val`, `fun`, ...). */
    val declarations: String,
    val pages: List<PageDocument>,
) {
    public fun page(id: String): PageDocument? = pages.firstOrNull { it.id == id }

    public fun withPage(page: PageDocument): CpnDocument = copy(pages = pages.map { if (it.id == page.id) page else it })
}
