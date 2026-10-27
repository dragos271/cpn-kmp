package no.hvl.cpn.io.model

import no.hvl.cpn.core.ArcDirection

/*
 * Editing operations on a CpnDocument. Each returns a new document, so an editor can keep old ones
 * for undo. Element ids never change once created (renaming keeps the id), so selections, undo
 * history and the mapping to a running simulation stay valid.
 */

/** The result of adding an element: the new document and the new element's id. */
public data class Added(
    val document: CpnDocument,
    val id: String,
)

/** Why an arc could not be drawn, or the document with the new arc. */
public sealed interface ConnectResult {
    public data class Connected(
        val document: CpnDocument,
        val arcId: String,
    ) : ConnectResult

    public data class Rejected(
        val reason: String,
    ) : ConnectResult
}

/** Colour set given to new places; part of the standard declarations of every CPN Tools net. */
public const val DEFAULT_COLOUR_SET: String = "UNIT"

public fun CpnDocument.updatePage(
    pageId: String,
    change: (PageDocument) -> PageDocument,
): CpnDocument = withPage(change(page(pageId) ?: throw NoSuchElementException("No page '$pageId'")))

public fun CpnDocument.addPlace(
    pageId: String,
    at: Point,
): Added {
    val page = requirePage(pageId)
    val id = freshId(page, "place")
    val name = freshName(page, "P")
    return Added(withPage(page.copy(places = page.places + PlaceNode(id, name, DEFAULT_COLOUR_SET, "", at))), id)
}

public fun CpnDocument.addTransition(
    pageId: String,
    at: Point,
): Added {
    val page = requirePage(pageId)
    val id = freshId(page, "transition")
    val name = freshName(page, "T")
    return Added(withPage(page.copy(transitions = page.transitions + TransitionNode(id, name, "", at))), id)
}

/**
 * A substitution transition together with the new, empty page it stands for (CPN Tools' "move to
 * subpage" starts the same way). Ports and socket assignments are added on the subpage later.
 */
public fun CpnDocument.addSubstitution(
    pageId: String,
    at: Point,
): Added {
    val page = requirePage(pageId)
    val subpageName =
        generateSequence(1) { it + 1 }.map { "Page$it" }.first { candidate ->
            pages.none {
                it.id == candidate ||
                    it.name == candidate
            }
        }
    val id = freshId(page, "substitution")
    val name = freshName(page, "Sub")
    val withSubpage = copy(pages = pages + PageDocument(subpageName, subpageName, emptyList(), emptyList(), emptyList()))
    return Added(
        withSubpage.withPage(page.copy(transitions = page.transitions + TransitionNode(id, name, "", at, subpageId = subpageName))),
        id,
    )
}

/**
 * Draws an arc from [fromId] to [toId]: place to transition makes an input arc, transition to place
 * an output arc. Arcs always join a place and a transition on the same page, as in every Petri net.
 */
public fun CpnDocument.connect(
    pageId: String,
    fromId: String,
    toId: String,
): ConnectResult {
    val page = requirePage(pageId)
    val fromPlace = page.place(fromId)
    val toPlace = page.place(toId)
    val fromTransition = page.transition(fromId)
    val toTransition = page.transition(toId)
    val (placeId, transitionId, direction) =
        when {
            fromId == toId -> return ConnectResult.Rejected("An arc needs two different elements")
            fromPlace != null && toTransition != null -> Triple(fromId, toId, ArcDirection.PLACE_TO_TRANSITION)
            fromTransition != null && toPlace != null -> Triple(toId, fromId, ArcDirection.TRANSITION_TO_PLACE)
            fromPlace != null && toPlace != null -> return ConnectResult.Rejected("Arcs connect a place and a transition, not two places")
            fromTransition != null && toTransition != null -> return ConnectResult.Rejected(
                "Arcs connect a place and a transition, not two transitions",
            )
            else -> return ConnectResult.Rejected("Both ends of an arc must be on this page")
        }
    if (page.arcs.any { it.placeId == placeId && it.transitionId == transitionId && it.direction == direction }) {
        return ConnectResult.Rejected("There is already an arc in that direction; edit its inscription instead")
    }
    val id = freshId(page, "arc")
    return ConnectResult.Connected(withPage(page.copy(arcs = page.arcs + ArcEdge(id, placeId, transitionId, direction, ""))), id)
}

/** Deletes a place, transition or arc. Deleting a place or transition also deletes its arcs. */
public fun CpnDocument.delete(
    pageId: String,
    elementId: String,
): CpnDocument {
    val page = requirePage(pageId)
    return withPage(
        page.copy(
            places = page.places.filter { it.id != elementId },
            transitions = page.transitions.filter { it.id != elementId },
            arcs = page.arcs.filter { it.id != elementId && it.placeId != elementId && it.transitionId != elementId },
        ),
    )
}

public fun CpnDocument.updatePlace(
    pageId: String,
    id: String,
    change: (PlaceNode) -> PlaceNode,
): CpnDocument = updatePage(pageId) { page -> page.copy(places = page.places.map { if (it.id == id) change(it).copy(id = id) else it }) }

public fun CpnDocument.updateTransition(
    pageId: String,
    id: String,
    change: (TransitionNode) -> TransitionNode,
): CpnDocument =
    updatePage(pageId) { page ->
        page.copy(
            transitions =
                page.transitions.map {
                    if (it.id ==
                        id
                    ) {
                        change(it).copy(id = id)
                    } else {
                        it
                    }
                },
        )
    }

public fun CpnDocument.updateArc(
    pageId: String,
    id: String,
    change: (ArcEdge) -> ArcEdge,
): CpnDocument = updatePage(pageId) { page -> page.copy(arcs = page.arcs.map { if (it.id == id) change(it).copy(id = id) else it }) }

/** The document with every position cleared: two documents with equal structure differ only in layout. */
public fun CpnDocument.structure(): CpnDocument {
    val origin = Point(0.0, 0.0)
    return copy(
        pages =
            pages.map { page ->
                page.copy(
                    places = page.places.map { it.copy(position = origin) },
                    transitions = page.transitions.map { it.copy(position = origin) },
                    arcs = page.arcs.map { it.copy(bends = emptyList()) },
                )
            },
    )
}

private fun CpnDocument.requirePage(pageId: String): PageDocument = page(pageId) ?: throw NoSuchElementException("No page '$pageId'")

private fun PageDocument.allIds(): Set<String> = (places.map { it.id } + transitions.map { it.id } + arcs.map { it.id }).toSet()

private fun freshId(
    page: PageDocument,
    kind: String,
): String {
    val used = page.allIds()
    return generateSequence(1) { it + 1 }.map { "${page.id}.$kind$it" }.first { it !in used }
}

private fun freshName(
    page: PageDocument,
    prefix: String,
): String {
    val used = (page.places.map { it.name } + page.transitions.map { it.name }).toSet()
    return generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in used }
}
