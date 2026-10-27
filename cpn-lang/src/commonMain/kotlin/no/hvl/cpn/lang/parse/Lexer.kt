package no.hvl.cpn.lang.parse

import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span

/** A syntax error, located in its source. */
public class ParseException(
    public val source: SourceText,
    public val span: Span,
    public val reason: String,
) : IllegalArgumentException("${source.describe(span)}: $reason")

public enum class TokenKind {
    /** Alphanumeric identifier, possibly qualified (`x`, `List.map`). */
    ALPHA_ID,

    /** Symbolic identifier (`+`, `++`, `` ` ``, `::`, `@+`), possibly qualified (`Int.+`). */
    SYMBOLIC_ID,

    /** `'a`, `''a`. */
    TYPE_VAR,
    INT,
    REAL,
    STRING,
    CHAR,

    /** A reserved word: `val`, `fun`, `andalso`, ... */
    KEYWORD,

    /** Reserved punctuation: `( ) [ ] { } , ; ... _ | = => -> # :`. */
    PUNCT,

    /** `..` in CPN colour set ranges. */
    DOT_DOT,
    EOF,
}

/**
 * A token. [text] is the identifier, keyword or punctuation text, without qualifiers; [qualifiers]
 * holds `List` in `List.map`; [value] is the decoded literal (`Long`, `String`, `Char`), and REAL
 * keeps its digits in [text].
 */
public data class Token(
    val kind: TokenKind,
    val text: String,
    val span: Span,
    val qualifiers: List<String> = emptyList(),
    val value: Any? = null,
) {
    public fun isKeyword(word: String): Boolean = kind == TokenKind.KEYWORD && text == word

    public fun isPunct(symbol: String): Boolean = kind == TokenKind.PUNCT && text == symbol

    public val isIdentifier: Boolean get() = kind == TokenKind.ALPHA_ID || kind == TokenKind.SYMBOLIC_ID

    override fun toString(): String =
        when (kind) {
            TokenKind.EOF -> "end of input"
            TokenKind.STRING -> "string literal"
            TokenKind.CHAR -> "character literal"
            else -> "'" + (qualifiers + text).joinToString(".") + "'"
        }
}

/** SML'97 reserved words (core and module language; the latter are rejected by the parser). */
internal val KEYWORDS: Set<String> =
    setOf(
        "abstype",
        "and",
        "andalso",
        "as",
        "case",
        "datatype",
        "do",
        "else",
        "end",
        "exception",
        "fn",
        "fun",
        "handle",
        "if",
        "in",
        "infix",
        "infixr",
        "let",
        "local",
        "nonfix",
        "of",
        "op",
        "open",
        "orelse",
        "raise",
        "rec",
        "then",
        "type",
        "val",
        "with",
        "withtype",
        "while",
        "eqtype",
        "functor",
        "include",
        "sharing",
        "sig",
        "signature",
        "struct",
        "structure",
        "where",
    )

/** Reserved symbolic tokens; other runs of symbol characters are identifiers. */
private val RESERVED_SYMBOLS = setOf("=", "=>", "->", ":", "|", "#", ":>")

private const val SYMBOL_CHARS = "!%&$#+-/:<=>?@\\~`^|*"

private fun isSymbolChar(c: Char) = c in SYMBOL_CHARS

private fun isAlphaStart(c: Char) = c.isLetter()

private fun isAlphaChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '\''

/** Splits CPN ML text into tokens, following the SML'97 lexical rules plus CPN's `..`. */
public class Lexer(
    private val source: SourceText,
) {
    private val text = source.text
    private var pos = 0

    public fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            val token = next()
            tokens += token
            if (token.kind == TokenKind.EOF) return tokens
        }
    }

    private fun fail(
        start: Int,
        message: String,
        end: Int = pos,
    ): Nothing = throw ParseException(source, Span(start, maxOf(start, end).coerceAtMost(text.length)), message)

    private fun peek(offset: Int = 0): Char = if (pos + offset < text.length) text[pos + offset] else '\u0000'

    private fun next(): Token {
        skipWhitespaceAndComments()
        val start = pos
        if (pos >= text.length) return Token(TokenKind.EOF, "", Span(text.length, text.length))
        val c = peek()
        return when {
            c == '\'' -> typeVar(start)
            c.isDigit() || (c == '~' && peek(1).isDigit()) -> number(start)
            c == '"' -> string(start)
            c == '#' && peek(1) == '"' -> char(start)
            isAlphaStart(c) -> identifier(start)
            c == '.' && peek(1) == '.' && peek(2) == '.' -> punct(start, "...")
            c == '.' && peek(1) == '.' -> {
                pos += 2
                Token(TokenKind.DOT_DOT, "..", Span(start, pos))
            }
            c in "()[]{},;" -> punct(start, c.toString())
            c == '_' -> punct(start, "_")
            isSymbolChar(c) -> symbolic(start, emptyList())
            else -> fail(start, "unexpected character '$c'", start + 1)
        }
    }

    private fun punct(
        start: Int,
        symbol: String,
    ): Token {
        pos += symbol.length
        return Token(TokenKind.PUNCT, symbol, Span(start, pos))
    }

    private fun skipWhitespaceAndComments() {
        while (pos < text.length) {
            when {
                peek().isWhitespace() -> pos++
                peek() == '(' && peek(1) == '*' -> skipComment()
                else -> return
            }
        }
    }

    /** `(* ... *)`, which nest in SML. */
    private fun skipComment() {
        val start = pos
        var depth = 0
        while (pos < text.length) {
            when {
                peek() == '(' && peek(1) == '*' -> {
                    depth++
                    pos += 2
                }
                peek() == '*' && peek(1) == ')' -> {
                    depth--
                    pos += 2
                    if (depth == 0) return
                }
                else -> pos++
            }
        }
        fail(start, "unterminated comment", start + 2)
    }

    private fun typeVar(start: Int): Token {
        while (peek() == '\'') pos++
        if (!isAlphaChar(peek()) || peek() == '\'') fail(start, "expected a type variable name after '")
        while (isAlphaChar(peek())) pos++
        return Token(TokenKind.TYPE_VAR, text.substring(start, pos), Span(start, pos))
    }

    /** Alphanumeric identifiers and keywords; `A.B.x` and `Int.+` are qualified identifiers. */
    private fun identifier(start: Int): Token {
        val qualifiers = mutableListOf<String>()
        while (true) {
            val partStart = pos
            while (isAlphaChar(peek())) pos++
            val part = text.substring(partStart, pos)
            // A '.' directly followed by an identifier makes `part` a structure qualifier.
            if (peek() == '.' && peek(1) != '.' && (isAlphaStart(peek(1)) || isSymbolChar(peek(1)))) {
                if (part in KEYWORDS) fail(partStart, "reserved word '$part' cannot be a structure name")
                qualifiers += part
                pos++
                if (isSymbolChar(peek())) return symbolic(start, qualifiers)
                continue
            }
            if (qualifiers.isEmpty() && part in KEYWORDS) return Token(TokenKind.KEYWORD, part, Span(start, pos))
            if (part in KEYWORDS) fail(partStart, "reserved word '$part' cannot be qualified")
            return Token(TokenKind.ALPHA_ID, part, Span(start, pos), qualifiers.toList())
        }
    }

    private fun symbolic(
        start: Int,
        qualifiers: List<String>,
    ): Token {
        val symStart = pos
        while (isSymbolChar(peek())) pos++
        val symbol = text.substring(symStart, pos)
        if (qualifiers.isEmpty() && symbol in RESERVED_SYMBOLS) return Token(TokenKind.PUNCT, symbol, Span(start, pos))
        return Token(TokenKind.SYMBOLIC_ID, symbol, Span(start, pos), qualifiers)
    }

    /**
     * Integers (`42`, `~7`, `0x1F`) and reals (`1.5`, `~2.0E~3`, `1e10`). SML writes negative
     * numbers and exponents with `~`.
     */
    private fun number(start: Int): Token {
        val negative = peek() == '~'
        if (negative) pos++
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X') && peek(2).isHexDigit()) {
            pos += 2
            val digitsStart = pos
            while (peek().isHexDigit()) pos++
            val magnitude = text.substring(digitsStart, pos).toLongOrNull(16) ?: fail(start, "integer literal too large")
            return Token(TokenKind.INT, text.substring(start, pos), Span(start, pos), value = if (negative) -magnitude else magnitude)
        }
        if (peek() == '0' && peek(1) == 'w') fail(start, "word literals (0w...) are not supported", pos + 2)
        while (peek().isDigit()) pos++
        var isReal = false
        if (peek() == '.' && peek(1).isDigit()) {
            isReal = true
            pos++
            while (peek().isDigit()) pos++
        }
        if ((peek() == 'e' || peek() == 'E') && (peek(1).isDigit() || (peek(1) == '~' && peek(2).isDigit()))) {
            isReal = true
            pos++
            if (peek() == '~') pos++
            while (peek().isDigit()) pos++
        }
        val literal = text.substring(start, pos)
        if (isReal) return Token(TokenKind.REAL, literal, Span(start, pos))
        val value = literal.replace('~', '-').toLongOrNull() ?: fail(start, "integer literal too large")
        return Token(TokenKind.INT, literal, Span(start, pos), value = value)
    }

    private fun Char.isHexDigit() = this.isDigit() || this.lowercaseChar() in 'a'..'f'

    private fun string(start: Int): Token {
        pos++ // opening quote
        val value = StringBuilder()
        while (true) {
            if (pos >= text.length || peek() == '\n') fail(start, "unterminated string literal")
            val c = peek()
            when (c) {
                '"' -> {
                    pos++
                    return Token(TokenKind.STRING, value.toString(), Span(start, pos), value = value.toString())
                }
                '\\' -> escape(value)
                else -> {
                    value.append(c)
                    pos++
                }
            }
        }
    }

    /** `#"a"`: exactly one character. */
    private fun char(start: Int): Token {
        pos++ // '#'
        val token = string(pos)
        if (token.text.length != 1) fail(start, "a character literal must contain exactly one character")
        return Token(TokenKind.CHAR, token.text, Span(start, pos), value = token.text[0])
    }

    /** SML escapes: `\n \t \\ \" \a \b \v \f \r`, `\^C`, `\ddd`, `\uXXXX`, and `\ <whitespace> \` gaps. */
    private fun escape(out: StringBuilder) {
        val start = pos
        pos++ // backslash
        val c = peek()
        pos++
        when (c) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'a' -> out.append('\u0007')
            'b' -> out.append('\b')
            'v' -> out.append('\u000B')
            'f' -> out.append('\u000C')
            'r' -> out.append('\r')
            '\\' -> out.append('\\')
            '"' -> out.append('"')
            '^' -> {
                val control = peek()
                if (control.code !in 64..95) fail(start, "invalid control escape \\^$control", pos + 1)
                out.append((control.code - 64).toChar())
                pos++
            }
            'u' -> {
                val hex = text.substring(pos, minOf(pos + 4, text.length))
                if (hex.length != 4 || !hex.all { it.isHexDigit() }) fail(start, "\\u needs four hex digits", pos + hex.length)
                out.append(hex.toInt(16).toChar())
                pos += 4
            }
            in '0'..'9' -> {
                val digits = text.substring(pos - 1, minOf(pos + 2, text.length))
                if (digits.length != 3 ||
                    !digits.all { it.isDigit() } ||
                    digits.toInt() > 255
                ) {
                    fail(start, "\\ddd needs three decimal digits up to 255", pos + 2)
                }
                out.append(digits.toInt().toChar())
                pos += 2
            }
            else ->
                if (c.isWhitespace()) {
                    while (peek().isWhitespace()) pos++
                    if (peek() != '\\') fail(start, "unterminated string gap: expected '\\'", pos)
                    pos++
                } else {
                    fail(start, "unknown escape \\$c", pos)
                }
        }
    }
}
