package no.hvl.cpn.lang.ast

import no.hvl.cpn.lang.ast.Constant.IntConst
import no.hvl.cpn.lang.ast.Constant.StringConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Builds the trees a parser would produce for the textbook simple protocol (Jensen & Kristensen 2009, ch. 2). */
class AstTest {
    private fun id(name: String) = IdentExpr(LongIdent(name))

    private fun int(value: Long) = ConstExpr(IntConst(value))

    private fun infix(
        op: String,
        left: Expr,
        right: Expr,
    ) = InfixExpr(LongIdent(op), left, right)

    /** `if n=k then k+1 else k`, the NextRec output arc of ReceivePacket. */
    private val nextRec = IfExpr(infix("=", id("n"), id("k")), infix("+", id("k"), int(1)), id("k"))

    @Test
    fun declarationsOfTheSimpleProtocol() {
        val decls =
            Declarations(
                listOf(
                    ColsetDecl(Ident("NO"), IntColset()),
                    ColsetDecl(Ident("DATA"), StringColset()),
                    ColsetDecl(Ident("NOxDATA"), ProductColset(listOf(LongIdent("NO"), LongIdent("DATA")))),
                    VarDecl(listOf(Ident("n"), Ident("k")), LongIdent("NO")),
                    VarDecl(listOf(Ident("d"), Ident("data")), LongIdent("DATA")),
                    ColsetDecl(Ident("BOOL"), BoolColset()),
                    VarDecl(listOf(Ident("success")), LongIdent("BOOL")),
                ),
            )
        assertEquals(
            "(decls (colset NO int) (colset DATA string) (colset NOxDATA (product NO DATA)) " +
                "(var (names n k) NO) (var (names d data) DATA) (colset BOOL bool) (var (names success) BOOL))",
            decls.toSExpr(),
        )
    }

    @Test
    fun arcInscriptions() {
        // 1`(n,d)
        val packet = infix("`", int(1), TupleExpr(listOf(id("n"), id("d"))))
        assertEquals("(infix ` 1 (tuple (id n) (id d)))", packet.toSExpr())
        assertEquals("(if (infix = (id n) (id k)) (infix + (id k) 1) (id k))", nextRec.toSExpr())
        // if success then 1`(n,d) else empty
        val transmit = IfExpr(id("success"), packet, id("empty"))
        assertEquals("(if (id success) (infix ` 1 (tuple (id n) (id d))) (id empty))", transmit.toSExpr())
    }

    @Test
    fun richerColourSets() {
        val decls =
            listOf(
                ColsetDecl(Ident("SMALL"), IntColset(RangeSpec(int(1), id("n")))),
                ColsetDecl(Ident("COLOUR"), EnumColset(listOf(Ident("red"), Ident("green")))),
                ColsetDecl(Ident("PH"), IndexColset(Ident("ph"), RangeSpec(int(1), int(5)))),
                ColsetDecl(
                    Ident("PACKET"),
                    UnionColset(listOf(UnionAlternative(Ident("Data"), LongIdent("NOxDATA")), UnionAlternative(Ident("Ack"), null))),
                ),
                ColsetDecl(Ident("REC"), RecordColset(listOf(RecordColsetField(Ident("seq"), LongIdent("NO"))))),
                ColsetDecl(Ident("Q"), ListColset(LongIdent("NO"), RangeSpec(int(0), int(3)))),
                ColsetDecl(Ident("EVEN"), SubsetColset(LongIdent("NO"), predicate = id("isEven"))),
                ColsetDecl(Ident("T"), IntColset(), timed = true),
                ColsetDecl(Ident("CLOCK"), TimeColset()),
            )
        assertEquals(
            listOf(
                "(colset SMALL (int (.. 1 (id n))))",
                "(colset COLOUR (enum red green))",
                "(colset PH (index ph (.. 1 5)))",
                "(colset PACKET (union (Data NOxDATA) Ack))",
                "(colset REC (record (seq NO)))",
                "(colset Q (list NO (.. 0 3)))",
                "(colset EVEN (subset-by NO (id isEven)))",
                "(colset-timed T int)",
                "(colset CLOCK time)",
            ),
            decls.map { it.toSExpr() },
        )
        assertFailsWith<IllegalArgumentException> { SubsetColset(LongIdent("NO")) }
    }

    @Test
    fun functionsWithPatternMatching() {
        // fun sum [] = 0 | sum (x::xs) = x + sum xs
        val sum =
            FunDecl(
                listOf(
                    FunBind(
                        Ident("sum"),
                        listOf(
                            FunClause(listOf(ListPat(emptyList())), null, int(0)),
                            FunClause(
                                listOf(InfixPat(LongIdent("::"), IdentPat(LongIdent("x")), IdentPat(LongIdent("xs")))),
                                null,
                                infix("+", id("x"), AppExpr(id("sum"), id("xs"))),
                            ),
                        ),
                    ),
                ),
            )
        assertEquals(
            "(fun (sum (clause (args (list)) _ 0) (clause (args (infix :: (id x) (id xs))) _ (infix + (id x) (app (id sum) (id xs))))))",
            sum.toSExpr(),
        )
    }

    @Test
    fun equalityIgnoresSpans() {
        // The same expression from two different sources, e.g. CPN ML and the Python-like syntax.
        val fromMl = IfExpr(infix("=", id("n"), id("k")).at(Span(3, 6)), id("k").at(Span(12, 13)), id("k")).at(Span(0, 20))
        val fromPython = IfExpr(infix("=", id("n"), id("k")).at(Span(10, 16)), id("k"), id("k").at(Span(22, 23))).at(Span(0, 23))
        assertEquals(fromMl, fromPython)
        assertEquals(fromMl.hashCode(), fromPython.hashCode())
        assertNotEquals<Node>(fromMl, nextRec)
        assertEquals(Span(0, 20), fromMl.span)
        assertTrue(fromMl.copy().span.isNone, "copy() drops the span")
        assertEquals(WildcardPat(), WildcardPat().at(Span(4, 5)))
        assertEquals(TimeColset(), TimeColset())
    }

    @Test
    fun spanIsSetOnce() {
        val node = id("x").at(Span(0, 1))
        node.at(Span(0, 1))
        assertFailsWith<IllegalStateException> { node.at(Span(2, 3)) }
    }

    @Test
    fun traversalVisitsEveryNodeInSourceOrder() {
        // let val y = n + 1 in y * k end
        val expr =
            LetExpr(listOf(ValDecl(listOf(ValBind(IdentPat(LongIdent("y")), infix("+", id("n"), int(1)))))), infix("*", id("y"), id("k")))
        val names =
            expr
                .descendants()
                .filterIsInstance<IdentExpr>()
                .map { it.name.text }
                .toList()
        assertEquals(listOf("n", "y", "k"), names)
        assertEquals(
            listOf("+", "*"),
            expr
                .descendants()
                .filterIsInstance<InfixExpr>()
                .map { it.operator.text }
                .toList(),
        )
        assertEquals(expr, expr.descendants().first())
        assertEquals(expr.descendants().count(), 1 + expr.children().sumOf { it.descendants().count() })
    }

    @Test
    fun constantsPrintAsWritten() {
        assertEquals("-3", ConstExpr(IntConst(-3)).toSExpr())
        assertEquals("\"a \\\"b\\\"  c\"", ConstExpr(StringConst("a \"b\"  c")).toSExpr())
        assertEquals(-2.5, Constant.RealConst("~2.5").value)
        assertEquals("#\"x\"", ConstPat(Constant.CharConst('x')).toSExpr())
    }
}
