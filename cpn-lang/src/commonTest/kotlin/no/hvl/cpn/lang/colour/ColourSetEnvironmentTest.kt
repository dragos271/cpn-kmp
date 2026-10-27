package no.hvl.cpn.lang.colour

import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlTuple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ColourSetEnvironmentTest {
    private fun declare(text: String): ColourSetEnvironment {
        val source = SourceText("decls", text)
        return ColourSetEnvironment.standard(source = source).declareAll(CpnMlParser().parseDeclarations(source))
    }

    private fun errorOf(text: String): String = assertFailsWith<ColsetDeclarationException> { declare(text) }.message!!

    @Test
    fun standardDeclarations() {
        val env = ColourSetEnvironment.standard()
        assertEquals(setOf("UNIT", "BOOL", "INT", "INTINF", "TIME", "REAL", "STRING"), env.names)
        assertIs<IntColourSet>(env["INT"])
    }

    @Test
    fun simpleProtocol() {
        val env =
            declare(
                """
                colset NO = int;
                colset DATA = string;
                colset NOxDATA = product NO * DATA;
                var n, k : NO;
                """.trimIndent(),
            )
        val noxdata = env["NOxDATA"]!!
        assertEquals("(1,\"COL\")", noxdata.mkstr(MlTuple(listOf(1, "COL"))))
        assertTrue(MlTuple(listOf(1, "COL")) in noxdata)
    }

    @Test
    fun everyColourSetKind() {
        val env =
            declare(
                """
                colset SIGNAL = unit with signal;
                colset ANSWER = bool with (no, yes);
                colset SMALL = int with 1..3;
                colset BIG = intinf with ~10..10;
                colset PROB = real with 0.0..1.0;
                colset CLOCK = time;
                colset LETTERS = string with "a".."z" and 1..10;
                colset COLOUR = with red | green | blue;
                colset PH = index ph with 1..5;
                colset PAIR = product SMALL * COLOUR;
                colset REC = record seq:SMALL * ok:ANSWER;
                colset PACKET = union Data:PAIR + Ack:SMALL + Stop;
                colset QUEUE = list SMALL with 0..2;
                colset PRIME = subset SMALL with [2, 3];
                colset SMALL2 = SMALL;
                colset TPAIR = product SMALL * COLOUR timed;
                """.trimIndent(),
            )
        assertEquals("1`signal", env["SIGNAL"]!!.mkstrMs(env["SIGNAL"]!!.all()))
        assertEquals("yes", env["ANSWER"]!!.mkstr(true))
        assertEquals(3, env["SMALL"]!!.size)
        assertEquals(21, env["BIG"]!!.size)
        assertTrue(0.25 in env["PROB"]!!)
        assertTrue(10L in env["CLOCK"]!!)
        assertTrue("hello" in env["LETTERS"]!!)
        assertEquals(MlCon("blue"), env["COLOUR"]!!.col(2))
        assertEquals("ph(5)", env["PH"]!!.mkstr(env["PH"]!!.col(4)))
        assertEquals(9, env["PAIR"]!!.size)
        assertEquals(6, env["REC"]!!.size)
        assertEquals(9 + 3 + 1, env["PACKET"]!!.size)
        assertEquals("Data(1,red)", env["PACKET"]!!.mkstr(env["PACKET"]!!.col(0)))
        assertEquals(1 + 3 + 9, env["QUEUE"]!!.size)
        assertEquals("1`2++1`3", env["PRIME"]!!.mkstrMs(env["PRIME"]!!.all()))
        assertEquals(3, env["SMALL2"]!!.size)
        assertTrue(env["TPAIR"]!!.timed)
    }

    @Test
    fun errorsPointAtTheDeclaration() {
        assertEquals("decls:1:26: unknown colour set DATA", errorOf("colset P = product INT * DATA;"))
        assertEquals("decls:2:8: colour set NO is already declared", errorOf("colset NO = int;\ncolset NO = string;"))
        assertEquals("decls:1:1: E: empty range 5..1", errorOf("colset E = int with 5..1;"))
        assertEquals("decls:1:1: E: duplicate enumeration constants", errorOf("colset E = with a | a;"))
        assertEquals("decls:1:22: expected a real", errorOf("colset R = real with 0..1;"))
        assertEquals("decls:1:24: 2147483648 does not fit in an int", errorOf("colset S = int with 1..2147483648;"))
        assertEquals("decls:1:24: expected a one-character string", errorOf("colset S = string with \"ab\"..\"z\";"))
        // Range bounds and predicates that are not constants need the evaluator (T2.5).
        assertTrue("until the evaluator exists" in errorOf("val n = 5; colset S = int with 1..n;"))
        assertTrue("needs the evaluator" in errorOf("colset E = subset INT by fn x => x > 0;"))
    }
}
