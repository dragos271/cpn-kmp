package no.hvl.cpn.lang.eval

import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.value.formatValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** External functions (F13), with a Kotlin stand-in for Python: arguments, results, types and errors. */
class ExternalFunctionTest {
    private val parser = CpnMlParser()

    private val declarations =
        """
        colset INT = int;
        colset SPEC = with biopsy | resection;
        colset STAFF = index w with 1..3;
        colset JOB = product SPEC * STAFF;
        colset INFO = record kind : SPEC * hours : INT;
        colset RESULT = union done : INFO + failed;
        fun delay (s : SPEC, n : int) : int = 30;
        fun pick () : STAFF = w(1);
        fun job (s : SPEC) : JOB = (s, w(1));
        fun info (s : SPEC) : INFO = {kind = s, hours = 0};
        fun outcome (s : SPEC) : RESULT = failed;
        fun curried (a : int) (b : int) : int = 0;
        fun twice (s : SPEC, n : int) = 2 * delay (s, n);
        fun untyped x = 1;
        """.trimIndent()

    /** What the stand-in received, by function name. */
    private val calls = mutableMapOf<String, MutableList<List<Any?>>>()

    private fun interpreter(results: Map<String, (List<Any?>) -> Any?>): Interpreter {
        val source = SourceText("declarations", declarations)
        return Interpreter(
            externals =
                ExternalFunctions { name ->
                    results[name]?.let { result ->
                        ExternalFunction { args ->
                            calls.getOrPut(name) { mutableListOf() } += args
                            result(args)
                        }
                    }
                },
        ).apply { declare(parser.parseDeclarations(source), source) }
    }

    private fun Interpreter.eval(text: String): String = formatValue(evaluate(parser.parseExpression(SourceText("e", text))))

    @Test
    fun argumentsArriveAsPythonWouldTakeThem() {
        val i =
            interpreter(
                mapOf(
                    "delay" to { args -> if (args[0] == "biopsy") 45L else (args[1] as Long) * 10 },
                    "pick" to { _ -> mapOf("tag" to "w", "value" to 3L) },
                    "curried" to { args -> (args[0] as Long) - (args[1] as Long) },
                ),
            )
        assertEquals("45", i.eval("delay (biopsy, 7)"))
        assertEquals("70", i.eval("delay (resection, 7)"))
        assertEquals<List<List<Any?>>?>(listOf(listOf("biopsy", 7L), listOf("resection", 7L)), calls["delay"], "a tuple argument is spread")
        assertEquals("w(3)", i.eval("pick ()"))
        assertEquals<List<List<Any?>>?>(listOf(emptyList()), calls["pick"], "unit means no arguments")
        assertEquals("6", i.eval("curried 10 4"), "curried arguments are collected")
        assertEquals("140", i.eval("twice (resection, 7)"), "CPN ML functions declared later call the external one")
    }

    @Test
    fun resultsAreConvertedToTheDeclaredType() {
        val i =
            interpreter(
                mapOf(
                    "job" to { args -> listOf(args[0], mapOf("tag" to "w", "value" to 2L)) },
                    "info" to { args -> mapOf("kind" to args[0], "hours" to 5.0) },
                    "outcome" to { args -> mapOf("tag" to "done", "value" to mapOf("kind" to args[0], "hours" to 2L)) },
                ),
            )
        assertEquals("(resection,w(2))", i.eval("job resection"))
        assertEquals("{kind=biopsy,hours=5}", i.eval("info biopsy"), "5.0 is a whole number, so it is an int")
        assertEquals("done({kind=biopsy,hours=2})", i.eval("outcome biopsy"))
    }

    @Test
    fun wrongResultsAreReportedWithTheFunctionAndTheDeclaration() {
        val i =
            interpreter(
                mapOf(
                    "delay" to { _ -> "soon" },
                    "pick" to { _ -> mapOf("tag" to "w", "value" to 9L) },
                    "job" to { _ -> throw ExternalFunctionException("delay_job raised ValueError: no data") },
                    "untyped" to { _ -> 1L },
                ),
            )
        val wrongType = assertFailsWith<EvaluationException> { i.eval("delay (biopsy, 1)") }
        assertTrue("external function delay: returned soon, expected an int" in wrongType.message!!, wrongType.message)
        assertTrue(wrongType.message!!.startsWith("declarations:7:"), "located at the declaration: ${wrongType.message}")
        val outOfRange = assertFailsWith<EvaluationException> { i.eval("pick ()") }
        assertTrue("expected a colour of STAFF" in outOfRange.message!!, outOfRange.message)
        val raised = assertFailsWith<EvaluationException> { i.eval("job biopsy") }
        assertTrue("ValueError: no data" in raised.message!!, raised.message)
        val untyped = assertFailsWith<EvaluationException> { i.eval("untyped 1") }
        assertTrue("needs a result type" in untyped.message!!, untyped.message)
    }

    @Test
    fun withoutAnImplementationTheCpnMlBodyIsUsed() {
        // As in CPN Tools, and on platforms where Python cannot run.
        val i = interpreter(emptyMap())
        assertEquals("30", i.eval("delay (biopsy, 1)"))
        assertEquals("60", i.eval("twice (biopsy, 1)"))
    }

    @Test
    fun externalFunctionsAreNeverCached() {
        val i = interpreter(mapOf("delay" to { _ -> 1L }))
        assertFalse(i.isPure(parser.parseExpression(SourceText("e", "delay (biopsy, 1)"))), "Python may draw random numbers")
        assertFalse(i.isPure(parser.parseExpression(SourceText("e", "twice (biopsy, 1)"))), "nor anything that calls it")
        assertTrue(interpreter(emptyMap()).isPure(parser.parseExpression(SourceText("e", "twice (biopsy, 1)"))))
    }

    @Test
    fun valuesBecomeJsonData() {
        val i = interpreter(emptyMap())
        val v = i.evaluate(parser.parseExpression(SourceText("e", "(biopsy, w(2), {kind = resection, hours = 3}, [1.5], ())")))
        assertEquals(
            listOf("biopsy", mapOf("tag" to "w", "value" to 2L), mapOf("kind" to "resection", "hours" to 3L), listOf(1.5), null),
            toForeign(v),
        )
    }
}
