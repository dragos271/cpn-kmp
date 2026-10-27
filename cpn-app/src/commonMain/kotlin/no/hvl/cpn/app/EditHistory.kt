package no.hvl.cpn.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Undo and redo over immutable values (here: CpnDocument). Compose observes [current], [canUndo]
 * and [canRedo].
 *
 * Edits with the same coalescing key in a row become one undo step: a drag sends a move per pixel
 * and a text field an edit per keystroke, but undo should take back the whole drag or the whole word.
 * Call [breakCoalescing] when such a gesture ends (drag released, another element selected).
 */
class EditHistory<T>(
    initial: T,
    private val limit: Int = 200,
) {
    var current: T by mutableStateOf(initial)
        private set

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var lastKey: String? = null

    // Stack sizes as state, so buttons enable and disable themselves.
    private var undoDepth by mutableIntStateOf(0)
    private var redoDepth by mutableIntStateOf(0)

    val canUndo: Boolean get() = undoDepth > 0
    val canRedo: Boolean get() = redoDepth > 0

    /** Applies [change]; a no-op change records nothing. */
    fun edit(
        coalesceKey: String? = null,
        change: (T) -> T,
    ) {
        val next = change(current)
        if (next == current) return
        if (coalesceKey == null || coalesceKey != lastKey) {
            undoStack.addLast(current)
            if (undoStack.size > limit) undoStack.removeFirst()
        }
        current = next
        redoStack.clear()
        lastKey = coalesceKey
        syncDepths()
    }

    fun breakCoalescing() {
        lastKey = null
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current)
        current = previous
        lastKey = null
        syncDepths()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current)
        current = next
        lastKey = null
        syncDepths()
        return true
    }

    private fun syncDepths() {
        undoDepth = undoStack.size
        redoDepth = redoStack.size
    }
}
