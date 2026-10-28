package no.hvl.cpn.lang.eval

import no.hvl.cpn.core.Multiset
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.types.TypeChecker
import no.hvl.cpn.lang.types.TypeEnvironment
import no.hvl.cpn.lang.value.formatValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InterpreterTest {
    /** Type-checks and runs [declarations], then evaluates [expr]; the value as CPN ML text. */
    private fun eval(
        expr: String,
        declarations: String = "",
        bindings: Map<String, Any?> = emptyMap(),
    ): String {
        val (parser, interpreter) = interpreter(declarations)
        val source = SourceText("e", expr)
        val parsed = parser.parseExpression(source)
        return show(interpreter.evaluate(parsed, bindings, source))
    }

    private fun interpreter(declarations: String): Pair<CpnMlParser, Interpreter> {
        val parser = CpnMlParser()
        val source = SourceText("d", declarations)
        val parsed = parser.parseDeclarations(source)
        val errors = TypeChecker().checkDeclarations(parsed, source)
        assertEquals(emptyList(), errors.map { it.message }, "declarations should type-check")
        return parser to Interpreter().apply { declare(parsed, source) }
    }

    private fun show(value: Any?): String =
        if (value is Multiset<*>) (value as Multiset<Any?>).format(element = ::formatValue) else formatValue(value)

    private fun failure(
        expr: String,
        declarations: String = "",
    ): String = assertFailsWith<EvaluationException> { eval(expr, declarations) }.reason

    @Test
    fun arithmetic() {
        assertEquals("7", eval("1 + 2 * 3"))
        assertEquals("~3", eval("~7 div 2 + 1"))
        assertEquals("1", eval("~7 mod 2"), "mod rounds towards negative infinity, as in SML")
        assertEquals("2.5", eval("5.0 / 2.0"))
        assertEquals("3", eval("round 2.5 + floor 0.9 + ceil 0.1"), "round is to even")
        assertEquals("true", eval("1 < 2 andalso \"a\" < \"b\" andalso #\"a\" <= #\"a\""))
        assertEquals("uncaught exception Div", failure("1 div 0"))
        assertEquals("uncaught exception Overflow", failure("2147483647 + 1"))
    }

    @Test
    fun stringsAndLists() {
        assertEquals("\"ab3\"", eval("\"a\" ^ \"b\" ^ Int.toString 3"))
        assertEquals("[2,4,6]", eval("map (fn x => 2 * x) [1, 2, 3]"))
        assertEquals("6", eval("foldl (op +) 0 [1, 2, 3]"))
        assertEquals("[1,2,3,4]", eval("[1, 2] @ 3 :: [4]"))
        assertEquals("(2,[1])", eval("(hd (tl [1, 2]), List.take ([1, 2], 1))"))
        assertEquals("\"cba\"", eval("implode (rev (explode \"abc\"))"))
        assertEquals("SOME(~42)", eval("Int.fromString \" ~42x\""))
        assertEquals("NONE", eval("List.find (fn x => x > 5) [1, 2]"))
        assertEquals("uncaught exception Empty", failure("hd []"))
    }

    @Test
    fun functionsAndPatterns() {
        val declarations =
            """
            fun fact 0 = 1
              | fact n = n * fact (n - 1);
            fun add x y = x + y;
            val add2 = add 2;
            fun len [] = 0 | len (_ :: xs) = 1 + len xs;
            fun even 0 = true | even n = odd (n - 1)
            and odd 0 = false | odd n = even (n - 1);
            val (a, b) = (1, "one");
            val {seq, ...} = {seq = 7, data = "x"};
            """.trimIndent()
        assertEquals("120", eval("fact 5", declarations))
        assertEquals("5", eval("add2 3", declarations))
        assertEquals("3", eval("len [1, 2, 3]", declarations))
        assertEquals("true", eval("even 10 andalso odd 7", declarations))
        assertEquals("(1,\"one\",7)", eval("(a, b, seq)", declarations))
        assertEquals("\"x\"", eval("#data {seq = 1, data = \"x\"}"))
        assertEquals("2", eval("#2 (1, 2, 3)"))
        assertEquals("\"small\"", eval("case 3 of 0 => \"zero\" | n => if n < 10 then \"small\" else \"big\""))
        assertEquals("13", eval("let val x = 4 fun sq y = y * y in sq x - 7 + x end"))
        assertEquals("uncaught exception Match", failure("(fn 0 => 1) 2"))
    }

    @Test
    fun datatypesAndExceptions() {
        val declarations =
            """
            datatype shape = Circle of real | Square of real | Empty;
            fun area (Circle r) = 3.0 * r * r
              | area (Square s) = s * s
              | area Empty = 0.0;
            exception Negative of int;
            fun check n = if n < 0 then raise Negative n else n;
            """.trimIndent()
        assertEquals("12.0", eval("area (Circle 2.0)", declarations))
        assertEquals("0.0", eval("area Empty", declarations))
        assertEquals("5", eval("check ~5 handle Negative n => ~n", declarations))
        assertEquals("0", eval("(1 div 0) handle Div => 0"))
        assertEquals("\"boom\"", eval("(raise Fail \"boom\") handle Fail m => m"))
        assertEquals("uncaught exception Negative(~1)", failure("check ~1", declarations))
        val e = assertFailsWith<EvaluationException> { eval("check ~1", declarations) }
        assertTrue(e.message!!.startsWith("d:"), "located in the declarations, where the raise is: ${e.message}")
    }

    @Test
    fun referencesAndScopes() {
        val declarations =
            """
            globref counter = 0;
            fun tick () = (counter := !counter + 1; !counter);
            local val secret = 41 in val answer = secret + 1 end;
            open List;
            """.trimIndent()
        assertEquals("(1,2)", eval("let val a = tick () val b = tick () in (a, b) end", declarations))
        assertEquals("42", eval("answer", declarations))
        assertEquals("[1,3]", eval("filter (fn x => x mod 2 = 1) [1, 2, 3]", declarations), "open List brings List.filter into scope")
        assertEquals("true", eval("let val r = ref 1 in r = r end"))
    }

    @Test
    fun colourSets() {
        val declarations =
            """
            val n = 3;
            colset NO = int with 1..n;
            colset COLOUR = with red | green | blue;
            colset PH = index ph with 1..2;
            colset EVEN = subset INT by (fn x => x mod 2 = 0);
            colset SMALL = subset NO by (fn x => x < 3);
            colset MSG = union Data : NO + Stop;
            colset ANSWER = bool with (no, yes);
            var c : COLOUR;
            """.trimIndent()
        assertEquals("1`1++1`2++1`3", eval("NO.all ()", declarations))
        assertEquals("(1,3)", eval("(COLOUR.ord green, NO.size ())", declarations))
        assertEquals("1`ph(1)++1`ph(2)", eval("PH.all ()", declarations))
        assertEquals("(true,false)", eval("(EVEN.legal 4, EVEN.legal 5)", declarations))
        assertEquals("1`1++1`2", eval("SMALL.all ()", declarations))
        assertEquals("1`Data(2)++1`Stop", eval("1`(Data 2) ++ 1`Stop", declarations))
        assertEquals("true", eval("yes", declarations))
        assertEquals(
            "\"blue\"",
            eval(
                "COLOUR.mkstr c",
                declarations,
                mapOf(
                    "c" to
                        no.hvl.cpn.lang.value
                            .MlCon("blue"),
                ),
            ),
        )
        assertEquals("\"green\"", eval("case green of red => \"red\" | x => COLOUR.mkstr x", declarations))
    }

    @Test
    fun multisets() {
        assertEquals("2`1++1`2", eval("2`1 ++ 1`2"))
        assertEquals("1`1", eval("(2`1 ++ 1`2) -- (1`1 ++ 1`2)"))
        assertEquals("(3,2)", eval("(size (2`1 ++ 1`2), cf (1, 2`1 ++ 1`2))"))
        assertEquals("true", eval("1`1 <<= 2`1 andalso 1`1 ++ 1`2 == 1`2 ++ 1`1 andalso empty << 1`1"))
        assertEquals("1`\"a\"++1`\"b\"", eval("ext_col (fn n => if n = 1 then \"a\" else \"b\") (1`1 ++ 1`2)"))
        assertEquals("empty", eval("0`5"))
        assertTrue(failure("1`1 -- 2`1").startsWith("uncaught exception Fail"))
    }

    @Test
    fun randomFunctionsAreDeterministicAndInRange() {
        val (parser, interpreter) = interpreter("colset NO = int with 1..6;")
        interpreter.random = RandomSource.seeded(7)
        val draws = (1..50).map { interpreter.evaluate(parser.parseExpression(SourceText("e", "discrete (1, 6) + NO.ran () * 0"))) as Int }
        assertTrue(draws.all { it in 1..6 })
        interpreter.random = RandomSource.seeded(7)
        val again = (1..50).map { interpreter.evaluate(parser.parseExpression(SourceText("e", "discrete (1, 6) + NO.ran () * 0"))) as Int }
        assertEquals(draws, again)
    }

    @Test
    fun errorsPointAtTheFailingApplication() {
        val source = SourceText("e", "1 + (4 div 0)")
        val e =
            assertFailsWith<EvaluationException> {
                Interpreter().evaluate(CpnMlParser().parseExpression(source), emptyMap(), source)
            }
        assertEquals("e:1:6: uncaught exception Div", e.message)
    }

    @Test
    fun everyNameInTheTypeCheckersBasisHasAValue() {
        val interpreter = Interpreter()
        val missing =
            TypeEnvironment
                .basis()
                .values.keys
                .filterNot(interpreter::isBound)
        assertEquals(emptyList(), missing)
    }

    @Test
    fun variablesOfAnExpressionAreItsFreeCpnVariables() {
        val (parser, interpreter) = interpreter("colset NO = int; var n, k, m : NO;")
        val expr = parser.parseExpression(SourceText("e", "let val k = 1 in n + k end + (fn m => m) 2 + case 1 of n => n"))
        assertEquals(setOf("n"), interpreter.variablesOf(expr))
    }
}
