package no.hvl.cpn.statespace

/**
 * Strongly connected components of a graph with nodes `0 until n` (T3.4).
 *
 * [component] gives each node's component; components are numbered in reverse topological order, so
 * a component only has arcs to components with smaller numbers (Tarjan's algorithm). [nontrivial]
 * marks the components that contain a cycle: more than one node, or a node with an arc to itself.
 */
internal class Components(
    val component: IntArray,
    val count: Int,
    val nontrivial: BooleanArray,
) {
    val hasCycle: Boolean get() = nontrivial.any { it }
}

/**
 * Tarjan's algorithm without recursion, so large state spaces cannot overflow the stack (JS and
 * native stacks are small). [successors] lists the targets of each node's arcs; nodes for which
 * [include] is false are left out of the graph, together with their arcs.
 */
internal fun components(
    n: Int,
    successors: (Int) -> IntArray,
    include: (Int) -> Boolean = { true },
): Components {
    val index = IntArray(n) { -1 }
    val low = IntArray(n)
    val onStack = BooleanArray(n)
    val component = IntArray(n) { -1 }
    val stack = IntArray(n)
    var stackSize = 0
    val nontrivial = mutableListOf<Boolean>()
    var counter = 0
    // The depth-first search's own stack: node, and how far through its successors it is.
    val callNode = IntArray(n)
    val callNext = IntArray(n)
    val callSuccessors = arrayOfNulls<IntArray>(n)

    for (root in 0 until n) {
        if (index[root] >= 0 || !include(root)) continue
        var depth = 0
        callNode[0] = root
        callNext[0] = 0
        callSuccessors[0] = successors(root)
        index[root] = counter
        low[root] = counter++
        stack[stackSize++] = root
        onStack[root] = true
        while (depth >= 0) {
            val v = callNode[depth]
            val next = callSuccessors[depth]!!
            if (callNext[depth] < next.size) {
                val w = next[callNext[depth]++]
                if (!include(w)) continue
                if (index[w] < 0) {
                    index[w] = counter
                    low[w] = counter++
                    stack[stackSize++] = w
                    onStack[w] = true
                    depth++
                    callNode[depth] = w
                    callNext[depth] = 0
                    callSuccessors[depth] = successors(w)
                } else if (onStack[w]) {
                    low[v] = minOf(low[v], index[w])
                }
                continue
            }
            // All successors of v done: v roots a component if nothing below reached further up.
            if (low[v] == index[v]) {
                val id = nontrivial.size
                var size = 0
                while (true) {
                    val w = stack[--stackSize]
                    onStack[w] = false
                    component[w] = id
                    size++
                    if (w == v) break
                }
                nontrivial += size > 1 || next.contains(v)
            }
            callSuccessors[depth] = null
            depth--
            if (depth >= 0) {
                val parent = callNode[depth]
                low[parent] = minOf(low[parent], low[v])
            }
        }
    }
    return Components(component, nontrivial.size, nontrivial.toBooleanArray())
}
