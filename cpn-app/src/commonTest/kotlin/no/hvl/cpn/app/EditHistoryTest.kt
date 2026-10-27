package no.hvl.cpn.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditHistoryTest {
    @Test
    fun undoAndRedoStepThroughEdits() {
        val history = EditHistory("a")
        history.edit { "b" }
        history.edit { "c" }
        assertTrue(history.undo())
        assertEquals("b", history.current)
        assertTrue(history.undo())
        assertEquals("a", history.current)
        assertFalse(history.undo())
        assertTrue(history.redo())
        assertTrue(history.redo())
        assertEquals("c", history.current)
        assertFalse(history.canRedo)
    }

    @Test
    fun aNewEditClearsRedo() {
        val history = EditHistory(0)
        history.edit { 1 }
        history.undo()
        history.edit { 5 }
        assertFalse(history.canRedo)
        history.undo()
        assertEquals(0, history.current)
    }

    @Test
    fun editsWithTheSameKeyAreOneStep() {
        val history = EditHistory("")
        for (text in listOf("P", "Pa", "Pac", "Pack")) history.edit("name:p1") { text }
        assertEquals("Pack", history.current)
        history.undo()
        assertEquals("", history.current, "typing a word is undone at once")
    }

    @Test
    fun breakingCoalescingStartsANewStep() {
        val history = EditHistory(0)
        history.edit("move:p1") { 10 }
        history.edit("move:p1") { 20 }
        history.breakCoalescing()
        history.edit("move:p1") { 30 }
        history.undo()
        assertEquals(20, history.current, "the second drag is undone, the first stays")
        history.undo()
        assertEquals(0, history.current)
    }

    @Test
    fun unchangedEditsRecordNothing() {
        val history = EditHistory(1)
        history.edit { it }
        assertFalse(history.canUndo)
    }

    @Test
    fun historyIsBounded() {
        val history = EditHistory(0, limit = 3)
        for (i in 1..10) history.edit { i }
        var steps = 0
        while (history.undo()) steps++
        assertEquals(3, steps)
        assertEquals(7, history.current)
    }
}
