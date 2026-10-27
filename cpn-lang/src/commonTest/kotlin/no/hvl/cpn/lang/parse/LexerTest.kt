package no.hvl.cpn.lang.parse

import no.hvl.cpn.lang.ast.SourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LexerTest {
    private fun lex(text: String): List<Token> = Lexer(SourceText("t", text)).tokenize().dropLast(1)

    private fun kinds(text: String) = lex(text).map { "${it.kind}:${(it.qualifiers + it.text).joinToString(".")}" }

    @Test
    fun identifiersKeywordsAndSymbols() {
        assertEquals(
            listOf(
                "KEYWORD:val",
                "ALPHA_ID:x'",
                "PUNCT:=",
                "ALPHA_ID:List.map",
                "SYMBOLIC_ID:Int.+",
                "SYMBOLIC_ID:++",
                "SYMBOLIC_ID:`",
                "SYMBOLIC_ID::=",
            ),
            kinds("val x' = List.map Int.+ ++ ` :="),
        )
        assertEquals(
            listOf("TYPE_VAR:'a", "TYPE_VAR:''eq", "PUNCT:->", "PUNCT:=>", "PUNCT:|", "PUNCT:#", "ALPHA_ID:seq"),
            kinds("'a ''eq -> => | #seq"),
        )
    }

    @Test
    fun multisetAndTimeOperators() {
        assertEquals(
            listOf(
                "INT:1",
                "SYMBOLIC_ID:`",
                "PUNCT:(",
                "ALPHA_ID:n",
                "PUNCT:,",
                "ALPHA_ID:d",
                "PUNCT:)",
                "SYMBOLIC_ID:@+",
                "INT:5",
                "SYMBOLIC_ID:++",
                "INT:2",
                "SYMBOLIC_ID:`",
                "ALPHA_ID:x",
            ),
            kinds("1`(n,d)@+5++2`x"),
        )
    }

    @Test
    fun rangesAndEllipsis() {
        assertEquals(listOf("INT:1", "DOT_DOT:..", "ALPHA_ID:n"), kinds("1..n"))
        assertEquals(listOf("PUNCT:{", "ALPHA_ID:a", "PUNCT:,", "PUNCT:...", "PUNCT:}"), kinds("{a, ...}"))
    }

    @Test
    fun numbers() {
        val tokens = lex("42 ~7 0x1F ~0x10 1.5 ~2.0E~3 1e10")
        assertEquals(listOf<Any?>(42L, -7L, 31L, -16L, null, null, null), tokens.map { it.value })
        assertEquals(listOf("INT", "INT", "INT", "INT", "REAL", "REAL", "REAL"), tokens.map { it.kind.name })
        assertEquals("~2.0E~3", tokens[5].text)
    }

    @Test
    fun stringsAndChars() {
        val tokens = lex(""""a\"b\\c\n\t" "\065\u0042\^A" "gap \   \ here" #"x" #"\n"""")
        assertEquals(listOf<Any?>("a\"b\\c\n\t", "AB\u0001", "gap  here", 'x', '\n'), tokens.map { it.value })
    }

    @Test
    fun nestedComments() {
        assertEquals(listOf("ALPHA_ID:a", "ALPHA_ID:b"), kinds("a (* outer (* inner *) still outer *) b"))
    }

    @Test
    fun errorsHavePositions() {
        fun error(text: String) = assertFailsWith<ParseException> { lex(text) }.message

        assertEquals("t:1:3: unterminated comment", error("a (* b"))
        assertEquals("t:2:1: unterminated string literal", error("x\n\"abc"))
        assertEquals("t:1:2: unknown escape \\q", error("\"\\q\""))
        assertEquals("t:1:1: a character literal must contain exactly one character", error("#\"ab\""))
        assertEquals("t:1:3: unexpected character '§'", error("a §"))
        assertEquals("t:1:1: word literals (0w...) are not supported", error("0w12"))
    }
}
