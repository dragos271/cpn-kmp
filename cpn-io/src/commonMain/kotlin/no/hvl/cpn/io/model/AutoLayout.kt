package no.hvl.cpn.io.model

/**
 * Positions for pages whose elements have none (JSON models written by hand, or models from tools
 * that store no layout). A simple layered layout: tokens flow left to right in columns, and each
 * column is ordered by the average position of its neighbours to reduce crossings. Elements that
 * already have a position keep it.
 */
public object AutoLayout {
    public const val COLUMN_SPACING: Double = 170.0
    public const val ROW_SPACING: Double = 120.0
    public const val MARGIN: Double = 90.0

    /** [page] with every element in [unplaced] given a position. */
    public fun layout(
        page: PageDocument,
        unplaced: Set<String>,
    ): PageDocument {
        if (unplaced.isEmpty()) return page
        val nodes = page.places.map { it.id } + page.transitions.map { it.id }
        val successors = nodes.associateWith { mutableListOf<String>() }
        for (arc in page.arcs) {
            if (arc.direction.isInput) successors.getValue(arc.placeId) += arc.transitionId
            if (arc.direction.isOutput) successors.getValue(arc.transitionId) += arc.placeId
        }

        // Columns by breadth-first distance from where the tokens start.
        val column = LinkedHashMap<String, Int>()
        val marked = page.places.filter { it.initialMarking.isNotBlank() }.map { it.id }
        val roots = marked.ifEmpty { nodes.filter { n -> successors.values.none { n in it } } }.ifEmpty { nodes.take(1) }

        fun bfs(
            starts: List<String>,
            base: Int,
        ) {
            val queue = ArrayDeque(starts.filter { it !in column }.onEach { column[it] = base })
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (next in successors.getValue(node)) {
                    if (next !in column) {
                        column[next] = column.getValue(node) + 1
                        queue += next
                    }
                }
            }
        }
        bfs(roots, 0)
        for (node in nodes) if (node !in column) bfs(listOf(node), (column.values.maxOrNull() ?: -1) + 1)

        // Order each column by the average row of its predecessors (one barycentre sweep).
        val columns =
            column.entries
                .groupBy({ it.value }, { it.key })
                .entries
                .sortedBy { it.key }
                .map { it.key to it.value }
        val row = HashMap<String, Double>()
        for ((_, members) in columns) {
            val ordered =
                members.sortedBy { node ->
                    val neighbours = nodes.filter { node in successors.getValue(it) && it in row }
                    if (neighbours.isEmpty()) nodes.indexOf(node).toDouble() else neighbours.map { row.getValue(it) }.average()
                }
            ordered.forEachIndexed { i, node -> row[node] = i.toDouble() }
        }

        // Unplaced elements go below anything that already has a position.
        val placedBottom =
            (
                page.places.filter { it.id !in unplaced }.map { it.position.y } +
                    page.transitions.filter { it.id !in unplaced }.map { it.position.y }
            ).maxOrNull()
        val top = if (placedBottom == null) MARGIN else placedBottom + ROW_SPACING

        fun position(id: String) = Point(MARGIN + column.getValue(id) * COLUMN_SPACING, top + row.getValue(id) * ROW_SPACING)

        return page.copy(
            places = page.places.map { if (it.id in unplaced) it.copy(position = position(it.id)) else it },
            transitions = page.transitions.map { if (it.id in unplaced) it.copy(position = position(it.id)) else it },
        )
    }
}
