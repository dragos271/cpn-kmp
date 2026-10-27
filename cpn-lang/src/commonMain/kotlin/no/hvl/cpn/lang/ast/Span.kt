package no.hvl.cpn.lang.ast

/**
 * A half-open character range `[start, end)` in a [SourceText]. Every AST node carries one so
 * diagnostics can point at the exact inscription text. [NONE] is used for synthesized nodes and
 * when comparing trees modulo positions ([withoutSpans]).
 */
public data class Span(
    val start: Int,
    val end: Int,
) {
    init {
        require((start == -1 && end == -1) || start in 0..end) { "Invalid span [$start, $end)" }
    }

    val length: Int get() = if (isNone) 0 else end - start

    public val isNone: Boolean get() = start == -1

    /** The smallest span covering both. [NONE] is neutral. */
    public operator fun plus(other: Span): Span =
        when {
            isNone -> other
            other.isNone -> this
            else -> Span(minOf(start, other.start), maxOf(end, other.end))
        }

    override fun toString(): String = if (isNone) "<none>" else "[$start,$end)"

    public companion object {
        public val NONE: Span = Span(-1, -1)
    }
}

/** A 1-based line and column, as editors and CPN Tools show them. */
public data class Position(
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "$line:$column"
}

/**
 * The text an AST was parsed from, e.g. a declaration block or one arc inscription.
 * [name] identifies it in diagnostics, e.g. `Protocol'SendPacket 1: guard`.
 */
public class SourceText(
    public val name: String,
    public val text: String,
) {
    private val lineStarts: IntArray =
        buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()

    public val lineCount: Int get() = lineStarts.size

    /** Line and column of [offset]; an offset at the very end maps to just past the last character. */
    public fun position(offset: Int): Position {
        require(offset in 0..text.length) { "Offset $offset outside 0..${text.length}" }
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return Position(low + 1, offset - lineStarts[low] + 1)
    }

    /** Text of 1-based [line], without its line break. */
    public fun line(line: Int): String {
        require(line in 1..lineCount) { "Line $line outside 1..$lineCount" }
        val start = lineStarts[line - 1]
        val end = if (line < lineCount) lineStarts[line] - 1 else text.length
        return text.substring(start, end)
    }

    public fun slice(span: Span): String = if (span.isNone) "" else text.substring(span.start, span.end)

    /** `name:line:column`, e.g. `decls:3:14`. */
    public fun describe(span: Span): String = if (span.isNone) name else "$name:${position(span.start)}"

    override fun toString(): String = name
}
