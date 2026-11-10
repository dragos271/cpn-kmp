package no.hvl.cpn.io.xml

/*
 * A small XML reader in common code (decision D7), enough for CPN Tools files: elements, attributes,
 * text, CDATA, comments, processing instructions and a DOCTYPE (kept as text, never fetched). It keeps
 * whitespace and the order of attributes and children, so a file can be written back as it was read
 * (T3.1b, T3.1c). Character and the five predefined entity references are decoded; DTD-declared
 * entities are not supported (CPN Tools does not write them).
 */

public sealed interface XmlNode

public data class XmlText(
    val text: String,
    val cdata: Boolean = false,
) : XmlNode

public data class XmlComment(
    val text: String,
) : XmlNode

public data class XmlElement(
    val name: String,
    val attributes: List<Pair<String, String>> = emptyList(),
    val children: List<XmlNode> = emptyList(),
    /** Where the start tag is in the file, 1-based. */
    val line: Int = 0,
) : XmlNode {
    public operator fun get(attribute: String): String? = attributes.firstOrNull { it.first == attribute }?.second

    public val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    public fun child(name: String): XmlElement? = children.firstOrNull { it is XmlElement && it.name == name } as XmlElement?

    public fun children(name: String): List<XmlElement> = elements.filter { it.name == name }

    /** The text directly inside this element (not inside its children). */
    public val text: String get() = children.filterIsInstance<XmlText>().joinToString("") { it.text }

    /** Text of the child [name], or null if there is no such child. */
    public fun childText(name: String): String? = child(name)?.text

    /** Every descendant element called [name], in document order. */
    public fun descendants(name: String): List<XmlElement> {
        val result = mutableListOf<XmlElement>()

        fun visit(e: XmlElement) {
            for (c in e.elements) {
                if (c.name == name) result += c
                visit(c)
            }
        }
        visit(this)
        return result
    }
}

/** A parsed file: what comes before the root element (XML declaration, DOCTYPE, comments) as raw text, and the root. */
public data class XmlDocument(
    val prolog: String,
    val root: XmlElement,
)

public class XmlException(
    public val line: Int,
    public val column: Int,
    public val reason: String,
) : IllegalArgumentException("$line:$column: $reason")

public object Xml {
    public fun parse(text: String): XmlDocument = XmlParser(text).document()

    /**
     * [bytes] decoded by the encoding in the XML declaration: ISO-8859-1 (what CPN Tools writes) or
     * UTF-8 (the default, also used without a declaration). A UTF-8 byte order mark is skipped.
     */
    public fun decode(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val head =
            bytes.copyOfRange(start, minOf(bytes.size, start + 200)).let { b ->
                CharArray(b.size) { (b[it].toInt() and 0xFF).toChar() }.concatToString()
            }
        val encoding =
            Regex("""^<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""")
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return when (encoding) {
            "iso-8859-1", "latin1", "latin-1", "iso8859-1", "us-ascii", "ascii" ->
                CharArray(bytes.size - start) { (bytes[start + it].toInt() and 0xFF).toChar() }.concatToString()
            else -> bytes.decodeToString(start, bytes.size)
        }
    }
}

private class XmlParser(
    private val s: String,
) {
    private var i = 0

    fun document(): XmlDocument {
        val prologStart = i
        while (true) {
            skipWhitespace()
            when {
                s.startsWith("<?", i) -> skipPast("?>", "processing instruction")
                s.startsWith("<!--", i) -> skipPast("-->", "comment")
                s.startsWith("<!DOCTYPE", i) -> skipDoctype()
                s.startsWith("<", i) -> break
                i >= s.length -> fail("no root element")
                else -> fail("text before the root element")
            }
        }
        val prolog = s.substring(prologStart, i)
        val root = element()
        while (true) {
            skipWhitespace()
            when {
                i >= s.length -> break
                s.startsWith("<!--", i) -> skipPast("-->", "comment")
                s.startsWith("<?", i) -> skipPast("?>", "processing instruction")
                else -> fail("content after the root element")
            }
        }
        return XmlDocument(prolog, root)
    }

    private fun element(): XmlElement {
        val line = lineAt(i)
        expect('<')
        val name = name()
        val attributes = mutableListOf<Pair<String, String>>()
        while (true) {
            skipWhitespace()
            when {
                s.startsWith("/>", i) -> {
                    i += 2
                    return XmlElement(name, attributes, emptyList(), line)
                }
                s.startsWith(">", i) -> {
                    i++
                    break
                }
                i >= s.length -> fail("unterminated start tag <$name>")
                else -> {
                    val attribute = name()
                    skipWhitespace()
                    expect('=')
                    skipWhitespace()
                    val quote = s.getOrNull(i)
                    if (quote != '"' && quote != '\'') fail("expected a quoted value for $attribute")
                    i++
                    val end = s.indexOf(quote, i).takeIf { it >= 0 } ?: fail("unterminated value of $attribute")
                    if (attributes.any { it.first == attribute }) fail("attribute $attribute repeated on <$name>")
                    attributes += attribute to decodeEntities(s.substring(i, end))
                    i = end + 1
                }
            }
        }
        val children = mutableListOf<XmlNode>()
        while (true) {
            when {
                i >= s.length -> fail("<$name> (line $line) is not closed")
                s.startsWith("</", i) -> {
                    i += 2
                    val closing = name()
                    if (closing != name) fail("</$closing> closes <$name> from line $line")
                    skipWhitespace()
                    expect('>')
                    return XmlElement(name, attributes, children, line)
                }
                s.startsWith("<!--", i) -> {
                    val end = s.indexOf("-->", i + 4).takeIf { it >= 0 } ?: fail("unterminated comment")
                    children += XmlComment(s.substring(i + 4, end))
                    i = end + 3
                }
                s.startsWith("<![CDATA[", i) -> {
                    val end = s.indexOf("]]>", i + 9).takeIf { it >= 0 } ?: fail("unterminated CDATA section")
                    children += XmlText(s.substring(i + 9, end), cdata = true)
                    i = end + 3
                }
                s.startsWith("<?", i) -> skipPast("?>", "processing instruction")
                s.startsWith("<", i) -> children += element()
                else -> {
                    val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    children += XmlText(decodeEntities(s.substring(i, end)))
                    i = end
                }
            }
        }
    }

    private fun name(): String {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_-.:")) i++
        if (i == start) fail("expected a name")
        return s.substring(start, i)
    }

    private fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            if (c != '&') {
                out.append(c)
                k++
                continue
            }
            val end = raw.indexOf(';', k)
            if (end < 0) fail("unterminated entity reference")
            val entity = raw.substring(k + 1, end)
            when {
                entity == "lt" -> out.append('<')
                entity == "gt" -> out.append('>')
                entity == "amp" -> out.append('&')
                entity == "quot" -> out.append('"')
                entity == "apos" -> out.append('\'')
                entity.startsWith("#x") ->
                    out.appendCodePoint(
                        entity.substring(2).toIntOrNull(16) ?: fail("bad character reference &$entity;"),
                    )
                entity.startsWith(
                    "#",
                ) -> out.appendCodePoint(entity.substring(1).toIntOrNull() ?: fail("bad character reference &$entity;"))
                else -> fail("unknown entity &$entity;")
            }
            k = end + 1
        }
        return out.toString()
    }

    private fun StringBuilder.appendCodePoint(code: Int) {
        if (code < 0x10000) {
            append(code.toChar())
        } else {
            val v = code - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    private fun skipDoctype() {
        // <!DOCTYPE name PUBLIC "..." "..." [ internal subset ]>: skip, honouring quotes and brackets.
        var depth = 0
        i += 2
        while (i < s.length) {
            when (s[i]) {
                '"', '\'' -> {
                    val end = s.indexOf(s[i], i + 1).takeIf { it >= 0 } ?: fail("unterminated DOCTYPE")
                    i = end
                }
                '[' -> depth++
                ']' -> depth--
                '>' ->
                    if (depth == 0) {
                        i++
                        return
                    }
            }
            i++
        }
        fail("unterminated DOCTYPE")
    }

    private fun skipPast(
        end: String,
        what: String,
    ) {
        val at = s.indexOf(end, i).takeIf { it >= 0 } ?: fail("unterminated $what")
        i = at + end.length
    }

    private fun skipWhitespace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun expect(c: Char) {
        if (s.getOrNull(i) != c) fail("expected '$c'")
        i++
    }

    /** Index of the first character of each line. */
    private val lineStarts: List<Int> by lazy {
        val starts = mutableListOf(0)
        for (k in s.indices) if (s[k] == '\n') starts += k + 1
        starts
    }

    private fun lineAt(index: Int): Int {
        val found = lineStarts.binarySearch(index)
        return if (found >= 0) found + 1 else -found - 1
    }

    private fun fail(reason: String): Nothing {
        val line = lineAt(i)
        throw XmlException(line, i - lineStarts[line - 1] + 1, reason)
    }
}
