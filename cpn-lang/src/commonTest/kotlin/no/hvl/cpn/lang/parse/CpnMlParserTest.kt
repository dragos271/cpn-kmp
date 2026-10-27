package no.hvl.cpn.lang.parse

import no.hvl.cpn.lang.ast.Declarations
import no.hvl.cpn.lang.ast.IfExpr
import no.hvl.cpn.lang.ast.InfixExpr
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span
import no.hvl.cpn.lang.ast.descendants
import no.hvl.cpn.lang.ast.toSExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CpnMlParserTest {
    private fun expr(text: String): String = parseCpnMlExpression(text).toSExpr()

    private fun decls(text: String): List<String> = parseCpnMl(text).declarations.map { it.toSExpr() }

    private fun decl(text: String): String = decls(text).single()

    private fun error(text: String): String = assertFailsWith<ParseException> { parseCpnMl(text, "d") }.message!!

    // -- The textbook simple protocol (Jensen & Kristensen 2009, ch. 2) --------------------------

    @Test
    fun simpleProtocolDeclarations() {
        val text =
            """
            colset NO = int;
            colset DATA = string;
            colset NOxDATA = product NO * DATA;
            colset BOOL = bool;
            var n, k : NO;
            var d, data : DATA;
            var success : BOOL;
            """.trimIndent()
        assertEquals(
            listOf(
                "(colset NO int)",
                "(colset DATA string)",
                "(colset NOxDATA (product NO DATA))",
                "(colset BOOL bool)",
                "(var (names n k) NO)",
                "(var (names d data) DATA)",
                "(var (names success) BOOL)",
            ),
            decls(text),
        )
    }

    @Test
    fun simpleProtocolInscriptions() {
        assertEquals("(infix ` 1 (tuple (id n) (id d)))", expr("1`(n,d)"))
        assertEquals("(if (id success) (infix ` 1 (tuple (id n) (id d))) (id empty))", expr("if success then 1`(n,d) else empty"))
        assertEquals("(if (infix = (id n) (id k)) (infix + (id k) 1) (id k))", expr("if n=k then k+1 else k"))
        assertEquals("(if (infix = (id n) (id k)) (infix ^ (id data) (id d)) (id data))", expr("if n=k\nthen data^d\nelse data"))
        assertEquals(
            "(infix ++ (infix ++ (infix ` 1 (tuple 1 \"COL\")) (infix ` 1 (tuple 2 \"OUR\"))) (infix ` 1 (tuple 3 \"ED \")))",
            expr("""1`(1,"COL")++1`(2,"OUR")++1`(3,"ED ")"""),
        )
    }

    // -- Precedence and associativity --------------------------------------------------------

    @Test
    fun arithmeticPrecedence() {
        assertEquals("(infix + 1 (infix * 2 3))", expr("1 + 2 * 3"))
        assertEquals("(infix - (infix - 1 2) 3)", expr("1 - 2 - 3"))
        assertEquals("(infix * (infix + 1 2) 3)", expr("(1 + 2) * 3"))
        assertEquals("(infix = (infix + (id a) 1) (infix * (id b) 2))", expr("a + 1 = b * 2"))
    }

    @Test
    fun consAndAppendAreRightAssociative() {
        assertEquals("(infix :: (id a) (infix :: (id b) (id c)))", expr("a :: b :: c"))
        assertEquals("(infix @ (id xs) (infix :: (id y) (list)))", expr("xs @ y :: []"))
    }

    @Test
    fun applicationBindsTighterThanInfix() {
        assertEquals("(infix + (app (id f) (id x)) (app (app (id g) (id y)) (id z)))", expr("f x + g y z"))
        assertEquals("(app (id ~) (id x))", expr("~ x"))
        assertEquals("-3", expr("~3"))
    }

    @Test
    fun multisetAndTimePrecedence() {
        assertEquals("(infix ++ (infix ` 2 (id x)) (infix ` 3 (id y)))", expr("2`x ++ 3`y"))
        assertEquals("(infix ++ (infix @+ (infix ` 1 (id x)) 5) (infix ` 1 (id y)))", expr("1`x@+5 ++ 1`y"))
        assertEquals("(infix == (infix ++ (id a) (id b)) (id c))", expr("a ++ b == c"))
        assertEquals("(infix ++ (infix ** 2 (id ms)) (id ms2))", expr("2**ms ++ ms2"))
    }

    @Test
    fun booleanOperators() {
        assertEquals("(orelse (id a) (andalso (id b) (id c)))", expr("a orelse b andalso c"))
        assertEquals("(andalso (infix = (id n) (id k)) (infix <> (id d) \"\"))", expr("n = k andalso d <> \"\""))
        assertEquals("(andalso (id ok) (if (id a) (id b) (id c)))", expr("ok andalso if a then b else c"))
    }

    @Test
    fun opMakesInfixPrefix() {
        assertEquals("(app (id-op +) (tuple 1 2))", expr("op + (1, 2)"))
        assertEquals("(app (app (id foldr) (id-op ++)) (id empty))", expr("foldr (op ++) empty"))
        assertEquals("(id-op =)", expr("op ="))
    }

    @Test
    fun mixingAssociativityAtOneLevelIsAnError() {
        val error =
            assertFailsWith<ParseException> {
                CpnMlParser().apply { parseDeclarations(SourceText("d", "infixr 6 +++")) }.parseExpression(SourceText("e", "a + b +++ c"))
            }
        assertEquals("e:1:7: cannot mix left- and right-associative operators '+' and '+++' of precedence 6", error.message)
    }

    // -- Expressions --------------------------------------------------------------------------

    @Test
    fun compoundExpressions() {
        assertEquals("(tuple)", expr("()"))
        assertEquals("(seq (app (id print) \"a\") 1)", expr("(print \"a\"; 1)"))
        assertEquals("(list 1 2 3)", expr("[1, 2, 3]"))
        assertEquals("(record (seq 1) (data \"x\"))", expr("{seq = 1, data = \"x\"}"))
        assertEquals("(app #1 (id p))", expr("#1 p"))
        assertEquals("(app #seq (id r))", expr("#seq r"))
        assertEquals("(: (id x) int)", expr("x : int"))
        assertEquals("(let (decls (val (= (id y) (infix + (id n) 1)))) (infix * (id y) 2))", expr("let val y = n + 1 in y * 2 end"))
        assertEquals("(let (decls) (seq 1 2))", expr("let in 1; 2 end"))
        assertEquals("(fn (=> (tuple (id x) (id y)) (infix + (id x) (id y))))", expr("fn (x, y) => x + y"))
        assertEquals(
            "(case (id l) (=> (list) 0) (=> (infix :: (id x) _) (id x)))",
            expr("case l of [] => 0 | x :: _ => x"),
        )
        assertEquals("(handle (app (id hd) (id l)) (=> (id Empty) 0))", expr("hd l handle Empty => 0"))
        assertEquals("(raise (app (id Fail) \"no\"))", expr("raise Fail \"no\""))
        assertEquals("(while (id go) (tuple))", expr("while go do ()"))
        assertEquals("(app (id SOME) #\"c\")", expr("SOME #\"c\""))
    }

    @Test
    fun guardsAsLists() {
        // CPN Tools guards may be a list of conditions, all of which must hold.
        assertEquals("(list (infix = (id n) (id k)) (infix < (id k) 10))", expr("[n = k, k < 10]"))
    }

    // -- Patterns -----------------------------------------------------------------------------

    @Test
    fun patterns() {
        assertEquals(
            "(fun (f (clause (args (tuple (id x) _) (infix :: (id y) (id ys)) (con SOME (id z))) _ (id x))))",
            decl("fun f (x, _) (y :: ys) (SOME z) = x"),
        )
        assertEquals("(val (= (record... (seq (id seq)) (data (id d))) (id r)))", decl("val {seq, data = d, ...} = r"))
        assertEquals("(val (= (as p (tuple (id a) (id b))) (id q)))", decl("val p as (a, b) = q"))
        assertEquals("(val (= (: (id x) int) 1))", decl("val x : int = 1"))
        assertEquals("(val (= (list 1 -2 \"s\") (id l)))", decl("val [1, ~2, \"s\"] = l"))
        assertEquals("(val (= (con Data (tuple (id n) (id d))) (id p)))", decl("val Data (n, d) = p"))
    }

    // -- Declarations ---------------------------------------------------------------------------

    @Test
    fun functionDeclarations() {
        assertEquals(
            "(fun (len (clause (args (list)) _ 0) (clause (args (infix :: _ (id xs))) int (infix + 1 (app (id len) (id xs))))))",
            decl("fun len [] = 0\n  | len (_ :: xs) : int = 1 + len xs"),
        )
        assertEquals(
            "(fun (even (clause (args 0) _ (id true)) (clause (args (id n)) _ (app (id odd) (infix - (id n) 1)))) " +
                "(odd (clause (args 0) _ (id false)) (clause (args (id n)) _ (app (id even) (infix - (id n) 1)))))",
            decl("fun even 0 = true | even n = odd (n - 1) and odd 0 = false | odd n = even (n - 1)"),
        )
        assertEquals("(fun (add (clause (args (id x) (id y)) _ (infix + (id x) (id y)))))", decl("fun add x y = x + y"))
    }

    @Test
    fun infixFunctionDeclarations() {
        val result =
            decls(
                """
                infix 6 +++;
                fun a +++ b = a + b;
                fun (a +++ b) c = c;
                val z = 1 +++ 2 +++ 3
                """.trimIndent(),
            )
        assertEquals("(infix 6 +++)", result[0])
        assertEquals("(fun (+++ (clause (args (tuple (id a) (id b))) _ (infix + (id a) (id b)))))", result[1])
        assertEquals("(fun (+++ (clause (args (tuple (id a) (id b)) (id c)) _ (id c))))", result[2])
        assertEquals("(val (= (id z) (infix +++ (infix +++ 1 2) 3)))", result[3])
    }

    @Test
    fun fixityIsScopedByLet() {
        val parser = CpnMlParser()
        parser.parseDeclarations(SourceText("d", "val x = let infix 9 &&& in 1 &&& 2 end"))
        assertTrue(!parser.fixities.isInfix("&&&"), "infix inside let must not leak")
        parser.parseDeclarations(SourceText("d", "infixr 2 &&&; nonfix ++"))
        assertTrue(parser.fixities.isInfix("&&&"))
        assertEquals("(app (app (id ++) (id a)) (id b))", parser.parseExpression(SourceText("e", "++ a b")).toSExpr())
    }

    @Test
    fun typeDatatypeExceptionLocalOpen() {
        assertEquals("(type (pair (params 'a) (tytuple 'a 'a)))", decl("type 'a pair = 'a * 'a"))
        assertEquals("(type (f (params) (-> int (-> int bool))))", decl("type f = int -> int -> bool"))
        assertEquals(
            "(type (r (params) (tyrecord (seq int) (data (tycon list string)))))",
            decl("type r = {seq : int, data : string list}"),
        )
        assertEquals("(type (m (params) (tycon map (tycon option int) string)))", decl("type m = (int option, string) map"))
        assertEquals(
            "(datatype (tree (params 'a) Leaf (of Node (tytuple (tycon tree 'a) 'a (tycon tree 'a)))))",
            decl("datatype 'a tree = Leaf | Node of 'a tree * 'a * 'a tree"),
        )
        assertEquals("(exception Empty (of Bad string))", decl("exception Empty and Bad of string"))
        assertEquals("(local (decls (val (= (id a) 1))) (decls (val (= (id b) (id a)))))", decl("local val a = 1 in val b = a end"))
        assertEquals("(open List Int)", decl("open List Int"))
        assertEquals("(val-rec (= (id f) (fn (=> (id x) (id x)))))", decl("val rec f = fn x => x"))
    }

    @Test
    fun colourSetDeclarations() {
        val result =
            decls(
                """
                colset U = unit with signal;
                colset B = bool with (no, yes);
                colset SMALL = int with 1..n;
                colset BIG = intinf with ~1000..1000;
                colset R = real with 0.0..1.5;
                colset T = time;
                colset LETTERS = string with "a".."z" and 1..10;
                colset COLOUR = with red | green | blue;
                colset PH = index ph with 1..5;
                colset NOxDATA = product NO * DATA * BOOL;
                colset REC = record seq:NO * data:DATA;
                colset PACKET = union Data:NOxDATA + Ack:NO + Stop;
                colset QUEUE = list NO with 0..3;
                colset EVEN = subset NO by fn n => n mod 2 = 0;
                colset PRIME = subset NO with [2, 3, 5];
                colset NO2 = NO;
                colset TNO = int timed;
                colset TNOxDATA = product NO * DATA timed;
                colset TR = int with 0..10 timed;
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                "(colset U (unit signal))",
                "(colset B (bool no yes))",
                "(colset SMALL (int (.. 1 (id n))))",
                "(colset BIG (intinf (.. -1000 1000)))",
                "(colset R (real (.. 0.0 1.5)))",
                "(colset T time)",
                "(colset LETTERS (string (.. \"a\" \"z\") (.. 1 10)))",
                "(colset COLOUR (enum red green blue))",
                "(colset PH (index ph (.. 1 5)))",
                "(colset NOxDATA (product NO DATA BOOL))",
                "(colset REC (record (seq NO) (data DATA)))",
                "(colset PACKET (union (Data NOxDATA) (Ack NO) Stop))",
                "(colset QUEUE (list NO (.. 0 3)))",
                "(colset EVEN (subset-by NO (fn (=> (id n) (infix = (infix mod (id n) 2) 0)))))",
                "(colset PRIME (subset-with NO (list 2 3 5)))",
                "(colset NO2 (alias NO))",
                "(colset-timed TNO int)",
                "(colset-timed TNOxDATA (product NO DATA))",
                "(colset-timed TR (int (.. 0 10)))",
            ),
            result,
        )
    }

    @Test
    fun globrefAndUse() {
        assertEquals(listOf("(globref counter 0)", "(use \"lib.sml\")"), decls("globref counter = 0; use \"lib.sml\";"))
    }

    @Test
    fun contextualKeywordsAreOrdinaryIdentifiersElsewhere() {
        assertEquals("(val (= (id var) (id colset)))", decl("val var = colset"))
        assertEquals("(app (id list) (id timed))", expr("list timed"))
    }

    // -- Spans and errors -----------------------------------------------------------------------

    @Test
    fun nodesCarryTheirSourceSpans() {
        val source = SourceText("arc", "if n=k then k+1 else k")
        val tree = CpnMlParser().parseExpression(source) as IfExpr
        assertEquals(Span(0, 22), tree.span)
        assertEquals("n=k", source.slice(tree.condition.span))
        assertEquals("k+1", source.slice(tree.then.span))
        assertTrue(tree.descendants().all { !it.span.isNone }, "every parsed node has a span")
        val decls: Declarations = CpnMlParser().parseDeclarations(SourceText("d", "colset NO = int;\nvar n : NO;"))
        assertEquals("var n : NO", SourceText("d", "colset NO = int;\nvar n : NO;").slice(decls.declarations[1].span))
        assertTrue(parseCpnMlExpression("a + b * c").descendants().filterIsInstance<InfixExpr>().count() == 2)
    }

    @Test
    fun syntaxErrorsPointAtTheProblem() {
        assertEquals("d:1:18: expected 'then', found 'else'", error("val x = if n = k else 0"))
        assertEquals("d:2:1: expected 'else', found end of input", error("val x = if a then b\n"))
        assertEquals("d:1:1: expected a declaration, found 'x'", error("x = 1"))
        assertEquals("d:1:13: expected a colour set, found '1'", error("colset NO = 1;"))
        assertEquals("d:1:22: expected '..', found ';'", error("colset S = int with 1;"))
        assertEquals("d:1:15: clauses of one function must have the same name: 'f' and 'g'", error("fun f 0 = 1 | g n = n"))
        assertEquals("d:1:1: 'structure' is not supported in CPN inscriptions", error("structure S = struct end"))
        assertEquals("d:1:13: exception aliases (exception E = F) are not supported", error("exception E = F"))
        assertEquals("d:1:22: the 'declare' clause of colour sets is not supported", error("colset C = int timed declare ms"))
        assertEquals(
            "e:1:7: expected end of input, found ')'",
            assertFailsWith<ParseException> { CpnMlParser().parseExpression(SourceText("e", "(a, b))")) }.message,
        )
    }
}
