package no.hvl.cpn.lang.eval

import no.hvl.cpn.core.Binding
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.value.formatValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
