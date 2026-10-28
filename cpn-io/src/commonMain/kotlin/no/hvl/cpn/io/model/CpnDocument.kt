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

public data class PlaceNode(
    val id: String,
    val name: String,
    val colourSet: String,
    /** Initial marking as CPN ML text, e.g. ``1`1++1`2``; empty for no tokens. */
    val initialMarking: String,
    val position: Point,
    val port: PortType? = null,
    val fusionSet: String? = null,
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
)

/** An arc between a place and a transition on the same page. */
public data class ArcEdge(
    val id: String,
    val placeId: String,
    val transitionId: String,
    val direction: ArcDirection,
    /** Arc inscription as CPN ML text; empty for socket arcs of substitution transitions. */
    val inscription: String,
    val bends: List<Point> = emptyList(),
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

    /** Moves a place or transition. */
    public fun moved(
        nodeId: String,
        position: Point,
    ): PageDocument =
        copy(
            places = places.map { if (it.id == nodeId) it.copy(position = position) else it },
            transitions = transitions.map { if (it.id == nodeId) it.copy(position = position) else it },
        )
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
