package no.hvl.cpn.lang.ast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpanTest {
    private val source = SourceText("decls", "colset NO = int;\nvar n : NO;\n\nval x = 1;")

    @Test
    fun positionsAreOneBasedLinesAndColumns() {
        assertEquals(Position(1, 1), source.position(0))
        assertEquals(Position(1, 8), source.position(7))
        assertEquals(Position(2, 1), source.position(17))
        assertEquals(Position(3, 1), source.position(29))
        assertEquals(Position(4, 5), source.position(34))
        assertEquals(Position(4, 11), source.position(source.text.length))
        assertFailsWith<IllegalArgumentException> { source.position(source.text.length + 1) }
    }

    @Test
    fun linesAndSlices() {
        assertEquals(4, source.lineCount)
        assertEquals("var n : NO;", source.line(2))
        assertEquals("", source.line(3))
        assertEquals("NO", source.slice(Span(7, 9)))
        assertEquals("decls:2:5", source.describe(Span(21, 22)))
        assertEquals("decls", source.describe(Span.NONE))
    }

    @Test
    fun spansCombine() {
        assertEquals(Span(2, 9), Span(2, 4) + Span(7, 9))
        assertEquals(Span(2, 4), Span.NONE + Span(2, 4))
        assertEquals(Span(2, 4), Span(2, 4) + Span.NONE)
        assertTrue(Span.NONE.isNone)
        assertEquals(0, Span.NONE.length)
        assertFailsWith<IllegalArgumentException> { Span(5, 3) }
        assertFailsWith<IllegalArgumentException> { Span(-2, 4) }
    }
}
