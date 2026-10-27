package no.hvl.cpn.lang.types

import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.parse.CpnMlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TypeCheckerTest {
    private val protocol =
        """
        colset NO = int;
        colset DATA = string;
        colset NOxDATA = product NO * DATA;
        colset BOOL = bool;
        var n, k : NO;
        var d, data : DATA;
        var success : BOOL;
        """.trimIndent()

    /** A parser and checker that have seen [declarations] (which must type-check). */
    private fun checker(declarations: String = ""): Pair<CpnMlParser, TypeChecker> {
        val parser = CpnMlParser()
        val checker = TypeChecker()
        val source = SourceText("d", declarations)
        val errors = checker.checkDeclarations(parser.parseDeclarations(source), source)
        assertEquals(emptyList(), errors.map { it.message }, "declarations should type-check")
        return parser to checker
    }

    private fun typeOf(
        expr: String,
        declarations: String = "",
    ): String {
        val (parser, checker) = checker(declarations)
        return TypePrinter().print(checker.typeOf(parser.parseExpression(SourceText("e", expr)), SourceText("e", expr)))
    }

    private fun errorOf(
        expr: String,
        declarations: String = "",
    ): String {
        val (parser, checker) = checker(declarations)
        val source = SourceText("e", expr)
        return assertFailsWith<TypeError> { checker.typeOf(parser.parseExpression(source), source) }.message!!
    }

    private fun valueType(
        name: String,
        declarations: String,
    ): String =
        TypePrinter().print(
            checker(declarations)
                .second.environment
                .value(name)!!
                .scheme.body,
        )

    private fun declarationErrors(declarations: String): List<String> {
        val source = SourceText("d", declarations)
        return TypeChecker().checkDeclarations(CpnMlParser().parseDeclarations(source), source).map { it.message!! }
    }

    // -- Core typing ----------------------------------------------------------------------------

    @Test
    fun constantsAndOverloadedArithmetic() {
        assertEquals("int", typeOf("1 + 2 * 3"))
        assertEquals("real", typeOf("1.5 + 2.0"))
        assertEquals("string", typeOf("\"a\" ^ \"b\""))
        assertEquals("bool", typeOf("\"a\" < \"b\" andalso 1 <= 2"))
        assertEquals("int -> int", typeOf("fn x => x + x"), "unresolved + defaults to int")
        assertEquals("real -> real", typeOf("fn x => x + 1.0"))
        assertEquals("unit", typeOf("()"))
    }

    @Test
    fun letPolymorphism() {
        assertEquals("'a -> 'a", valueType("id", "fun id x = x"))
        assertEquals("int * bool", typeOf("let fun f x = x in (f 1, f true) end"))
        assertEquals("('a -> 'b) -> 'a list -> 'b list", typeOf("map"))
        assertEquals("int list", typeOf("map (fn x => x + 1) [1, 2]"))
        assertEquals("'a list -> int", valueType("len", "fun len [] = 0 | len (_ :: t) = 1 + len t"))
        assertEquals("int -> bool", valueType("even", "fun even 0 = true | even n = odd (n - 1) and odd 0 = false | odd n = even (n - 1)"))
        assertEquals("int -> int", valueType("fact", "val rec fact = fn 0 => 1 | n => n * fact (n - 1)"))
    }

    @Test
    fun valueRestriction() {
        // `ref []` is not a value, so r gets one (unknown) type instead of a polymorphic one.
        val (parser, checker) = checker("val r = ref []")
        val source = SourceText("e", "(r := [1]; r := [true])")
        val error = assertFailsWith<TypeError> { checker.typeOf(parser.parseExpression(source), source) }
        assertTrue(":= cannot be applied" in error.message!!, error.message)
        assertEquals("'a list -> 'a list", valueType("f", "val f = fn x => x @ []"), "fn is a value, so f is polymorphic")
    }

    @Test
    fun equalityTypes() {
        assertEquals("''a * ''a -> bool", valueType("same", "fun same (a, b) = a = b"))
        assertEquals("bool", typeOf("[1, 2] = [1, 2] andalso (1, \"a\") <> (2, \"b\")"))
        assertEquals(
            "e:1:1: operator = cannot be applied to real and real (it takes ''a * ''a -> bool): " +
                "real does not admit equality, so values of type real cannot be compared with =",
            errorOf("1.0 = 2.0"),
        )
        assertTrue("functions cannot be compared" in errorOf("(fn x => x) = (fn y => y)"))
        assertTrue(
            "does not admit equality" in errorOf("R 1.0 = R 2.0", "datatype t = R of real"),
            "datatypes holding reals cannot be compared",
        )
        assertEquals("bool", typeOf("Leaf = Leaf", "datatype 'a tree = Leaf | Node of 'a tree * 'a * 'a tree"))
    }

    @Test
    fun recordsAndSelectors() {
        assertEquals("int", typeOf("#seq {seq = 1, data = \"x\"}"))
        assertEquals("string", typeOf("#2 (1, \"x\", true)"))
        assertEquals("{data: string, seq: int}", typeOf("{seq = 1, data = \"x\"}"))
        assertEquals("int", typeOf("let val {seq, ...} = {seq = 1, data = \"x\"} in seq end"))
        assertTrue("has no field seq" in errorOf("#seq (1, 2)"))
    }

    @Test
    fun datatypesAndExceptions() {
        val tree = "datatype 'a tree = Leaf | Node of 'a tree * 'a * 'a tree"
        assertEquals("'a tree -> int", valueType("count", "$tree; fun count Leaf = 0 | count (Node (l, _, r)) = count l + 1 + count r"))
        assertEquals("int tree", typeOf("Node (Leaf, 1, Leaf)", tree))
        assertEquals("int", typeOf("(raise Bad \"x\") handle Bad s => String.size s", "exception Bad of string"))
        assertEquals("'a option", typeOf("NONE"))
        assertEquals(
            "e:1:19: constructor Node takes 'a tree * 'a * 'a tree, not int",
            errorOf("case Leaf of Node 1 => 0 | Leaf => 1", tree),
        )
    }

    @Test
    fun localOpenAndAbbreviations() {
        val (parser, checker) = checker("local val a = 1 in val b = a + 1 end; type pair = int * int; open List")

        fun type(e: String) = TypePrinter().print(checker.typeOf(parser.parseExpression(SourceText("e", e))))
        assertEquals("int", type("b"))
        assertFailsWith<TypeError> { checker.typeOf(parser.parseExpression(SourceText("e", "a"))) }
        assertEquals("int * int", type("(1, 2) : pair"))
        assertEquals("int", type("nth ([1, 2], 0)"))
    }

    // -- CPN ----------------------------------------------------------------------------------------

    @Test
    fun simpleProtocolDeclarations() {
        val (_, checker) = checker(protocol)
        assertEquals("int * string", TypePrinter().print(checker.environment.colourSet("NOxDATA")!!))
        assertEquals("int", valueType("n", protocol))
        assertEquals("string", valueType("data", protocol))
        assertEquals("(int * string) ms", typeOf("1`(n,d)", protocol))
        assertEquals("(int * string) ms", typeOf("1`(1,\"COL\")++1`(2,\"OUR\")", protocol))
        assertEquals("int", typeOf("if n=k then k+1 else k", protocol))
    }

    @Test
    fun arcInscriptionsMustMatchThePlace() {
        val (parser, checker) = checker(protocol)

        fun arc(
            text: String,
            colourSet: String,
        ) = checker
            .checkArcInscription(
                parser.parseExpression(SourceText("arc", text)),
                colourSet,
                SourceText("arc", text),
            ).map { it.message }
        assertEquals(emptyList(), arc("1`(n,d)", "NOxDATA"))
        assertEquals(emptyList(), arc("(n,d)", "NOxDATA"), "a single colour means 1`colour")
        assertEquals(emptyList(), arc("if success then 1`(n,d) else empty", "NOxDATA"))
        assertEquals(emptyList(), arc("empty", "NOxDATA"))
        assertEquals(emptyList(), arc("if n=k then data^d else data", "DATA"))
        assertEquals(
            listOf("arc:1:1: arc inscription has type int, but the place holds NOxDATA: expected int * string or (int * string) ms"),
            arc("n", "NOxDATA"),
        )
        assertEquals(
            listOf("arc:1:1: arc inscription has type string ms, but the place holds NO: expected int or int ms"),
            arc("1`d", "NO"),
        )
    }

    @Test
    fun guardsAndInitialMarkings() {
        val (parser, checker) = checker(protocol)

        fun guard(text: String) =
            checker.checkGuard(parser.parseExpression(SourceText("g", text)), SourceText("g", text)).map { it.message }
        assertEquals(emptyList(), guard("n = k"))
        assertEquals(emptyList(), guard("[n = k, k < 10]"))
        assertEquals(listOf("g:1:1: a guard must be bool or a list of bools, not int"), guard("n + 1"))
        val marking = "1`(1,\"COL\")++1`(2,\"OUR\")"
        assertEquals(
            emptyList(),
            checker.checkInitialMarking(parser.parseExpression(SourceText("m", marking)), "NOxDATA").map { it.message },
        )
        assertEquals(1, checker.checkInitialMarking(parser.parseExpression(SourceText("m", "1`1")), "DATA").size)
    }

    @Test
    fun colourSetDeclarationsDeclareTypesAndConstructors() {
        val declarations =
            """
            colset NO = int;
            colset COLOUR = with red | green | blue;
            colset PH = index ph with 1..5;
            colset PACKET = union Data:NO + Ack;
            colset REC = record seq:NO * ok:BOOL;
            colset QUEUE = list NO with 0..3;
            colset ANSWER = bool with (no, yes);
            colset EVEN = subset NO by fn x => x mod 2 = 0;
            colset T = int timed;
            """.trimIndent()
        assertEquals("COLOUR", typeOf("red", declarations))
        assertEquals("PH", typeOf("ph 3", declarations))
        assertEquals("PACKET", typeOf("Data 4", declarations))
        assertEquals("PACKET", typeOf("Ack", declarations))
        assertEquals("int", typeOf("#seq {seq = 1, ok = true}", declarations))
        assertEquals("ANSWER", typeOf("yes", declarations))
        assertEquals("int list", typeOf("[1, 2] : QUEUE", declarations))
        assertEquals("bool", typeOf("red = green", declarations))
        // Colour set functions.
        assertEquals("COLOUR ms", typeOf("COLOUR.all()", declarations))
        assertEquals("int", typeOf("COLOUR.ord blue", declarations))
        assertEquals("COLOUR", typeOf("COLOUR.col 1", declarations))
        assertEquals("string", typeOf("PH.mkstr (ph 2)", declarations))
    }

    @Test
    fun cpnLibrary() {
        assertEquals("int", typeOf("size (1`\"a\" ++ 2`\"b\")"))
        assertEquals("int", typeOf("cf (\"a\", 1`\"a\")"))
        assertEquals("bool", typeOf("1`1 == 1`1 andalso 1`1 <<= 2`1"))
        assertEquals("int ms", typeOf("filter (fn x => x > 1) (1`1 ++ 1`2)"))
        assertEquals("int", typeOf("discrete (1, 6)"))
        assertEquals("int", typeOf("!counter + 1", "globref counter = 0"))
    }

    // -- Errors -------------------------------------------------------------------------------------

    @Test
    fun errorMessagesPointAtTheProblem() {
        assertEquals("e:1:1: unbound value or constructor: foo", errorOf("foo 1"))
        assertEquals("e:1:14: Int.toString expects an argument of type int, but this has type string", errorOf("Int.toString \"a\""))
        assertTrue(errorOf("1 + \"a\"").startsWith("e:1:1: operator + cannot be applied to int and string"))
        assertEquals(
            "e:1:21: the branches of if have different types: then gives int, else gives string",
            errorOf("if true then 1 else \"a\""),
        )
        assertEquals("e:1:4: the condition of if must be bool, not int", errorOf("if 1 then 2 else 3"))
        assertEquals("e:1:2: expression has type int, not the annotated string", errorOf("(1 : string)"))
        assertTrue("cannot contain itself" in errorOf("fn f => f f"))
        assertEquals("e:1:1: this function has type int and cannot be applied to an argument", errorOf("1 2"))
    }

    @Test
    fun declarationErrorsAreCollectedAndLocated() {
        val errors =
            declarationErrors(
                """
                colset NO = int;
                var x : MISSING;
                val a = 1 + "two";
                colset E = subset NO by fn x => x;
                use "lib.sml";
                val fine = 1;
                """.trimIndent(),
            )
        assertEquals(4, errors.size, errors.toString())
        assertEquals("d:2:9: unknown colour set MISSING", errors[0])
        assertTrue(errors[1].startsWith("d:3:9: operator + cannot be applied to int and string"), errors[1])
        assertEquals("d:4:25: the subset predicate must have type int -> bool, not int -> int", errors[2])
        assertEquals("d:5:1: use \"lib.sml\" is not supported: put the declarations in the model", errors[3])
    }
}
