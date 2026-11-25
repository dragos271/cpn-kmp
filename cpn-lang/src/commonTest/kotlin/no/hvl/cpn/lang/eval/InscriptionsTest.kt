package no.hvl.cpn.lang.eval

import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.PatternInscription
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.value.formatValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Both tests were added after deliberately breaking the code showed that no existing test noticed
 * (docs/ai-process.md, mutations M5 and M6).
 */
class InscriptionsTest {
    private val parser = CpnMlParser()

    private fun inscriptions(declarations: String): CpnMlInscriptions {
        val source = SourceText("d", declarations)
        return CpnMlInscriptions(Interpreter().apply { declare(parser.parseDeclarations(source), source) })
    }

    @Test
    fun aGuardListHoldsOnlyIfEveryConditionHolds() {
        val inscriptions = inscriptions("colset NO = int with 1..5; var n : NO;")
        val guard = inscriptions.guard(parser.parseExpression(SourceText("g", "[n > 1, n < 4]")))
        val n = inscriptions.variables.getValue("n")
        assertFalse(guard.evaluate(Binding.of(n to 1)), "n > 1 fails")
        assertTrue(guard.evaluate(Binding.of(n to 2)))
        assertFalse(guard.evaluate(Binding.of(n to 5)), "n < 4 fails")
    }

    @Test
    fun aListIsOneTokenOnAListPlaceAndAMultisetElsewhere() {
        val inscriptions = inscriptions("colset NO = int; colset NOS = list NO;")
        val no = inscriptions.interpreter.colourSets["NO"]!!
        val nos = inscriptions.interpreter.colourSets["NOS"]!!
        val list = parser.parseExpression(SourceText("m", "[1, 2, 2]"))
        assertEquals("1`[1,2,2]", inscriptions.multiset(list, nos).format(element = ::formatValue), "a token of NOS")
        assertEquals("1`1++2`2", inscriptions.multiset(list, no).format(element = ::formatValue), "three tokens of NO")
    }

    @Test
    fun aListVariableOnAPlaceOfElementsIsEvaluatedNotMatched() {
        // Garvik's two-phase commit: `workers : Workers` removes every acknowledgement in the list
        // (reference/garvik-2022/README.md). Matching it against one Worker token never succeeds.
        val inscriptions = inscriptions("colset W = int; colset WS = list W; var w : W; var workers : WS;")
        val w = inscriptions.interpreter.colourSets["W"]!!
        val ws = inscriptions.interpreter.colourSets["WS"]!!
        val expr = parser.parseExpression(SourceText("a", "workers"))
        val onElements = inscriptions.arc(expr, w)
        assertFalse(onElements is PatternInscription<*>, "bound elsewhere, then evaluated")
        val workers = inscriptions.variables.getValue("workers")
        assertEquals("1`1++1`2", onElements.evaluate(Binding.of(workers to listOf(1, 2))).format(element = ::formatValue))
        assertTrue(inscriptions.arc(expr, ws) is PatternInscription<*>, "on a place of lists it binds from a token")
        assertTrue(inscriptions.arc(parser.parseExpression(SourceText("a", "w")), w) is PatternInscription<*>)
    }

    @Test
    fun pureExpressionsAreRecognised() {
        val interpreter =
            inscriptions(
                """
                val n = 5;
                colset PH = index ph with 1..n; colset CS = index cs with 1..n; colset DIE = int with 1..6;
                var p : PH; var d : DIE;
                fun Chopsticks(ph(i)) = 1`cs(i) ++ 1`cs(if i=n then 1 else i+1);
                fun fact 0 = 1 | fact k = k * fact (k - 1);
                fun roll () = discrete(1, 6);
                fun later () = roll () + 1;
                globref counter = 0;
                """.trimIndent(),
            ).interpreter

        fun pure(text: String) = interpreter.isPure(parser.parseExpression(SourceText("e", text)))
        assertTrue(pure("Chopsticks(p)"), "constructors, arithmetic and user functions")
        assertTrue(pure("fact 5 + d"), "a recursive function")
        assertTrue(pure("let val x = d + 1 in case x of 2 => 1`p | _ => empty end"))
        assertTrue(pure("List.map (fn x => x + 1) [d]"))
        assertFalse(pure("discrete(1, 6)"), "random")
        assertFalse(pure("later ()"), "random, two calls down")
        assertFalse(pure("DIE.ran ()"), "a colour set's random colour")
        assertFalse(pure("time ()"), "model time")
        assertFalse(pure("!counter"), "a reference")
        assertFalse(pure("ref 1"), "a new reference each time")
    }

    @Test
    fun pureArcsAreCachedAndRandomOnesAreNot() {
        val inscriptions = inscriptions("colset DIE = int with 1..6; var d : DIE;")
        val die = inscriptions.interpreter.colourSets["DIE"]!!
        val d = inscriptions.variables.getValue("d")
        val pure = inscriptions.arc(parser.parseExpression(SourceText("a", "1`(d + 0)")), die)
        val binding = Binding.of(d to 3)
        assertSame(pure.evaluate(binding), pure.evaluate(binding), "the second evaluation comes from the cache")
        assertEquals("1`4", pure.evaluate(Binding.of(d to 4)).format(element = ::formatValue), "one result per value of d")

        val random = inscriptions.arc(parser.parseExpression(SourceText("a", "1`(discrete(1, 6) + d - d)")), die)
        val results = (1..50).map { random.evaluate(binding).format(element = ::formatValue) }.toSet()
        assertTrue(results.size > 1, "a random arc is evaluated every time: $results")
    }

    @Test
    fun intTokensOnIntinfPlacesBecomeLongs() {
        val inscriptions = inscriptions("colset BIG = intinf; colset NO = int; colset PAIR = product BIG * NO;")
        val big = inscriptions.interpreter.colourSets["BIG"]!!
        val marking = inscriptions.multiset(parser.parseExpression(SourceText("m", "1`5 ++ 2`7")), big)
        assertEquals(listOf(5L, 7L), marking.distinct.toList())
        assertTrue(marking.distinct.all { it in big })

        val pair = inscriptions.interpreter.colourSets["PAIR"]!!
        val pairs = inscriptions.multiset(parser.parseExpression(SourceText("m", "1`(3, 4)")), pair)
        assertTrue(pairs.distinct.all { it in pair }, "the intinf component is converted inside a product: $pairs")
    }
}
