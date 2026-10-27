package no.hvl.cpn.lang.ast

/*
 * The shared abstract syntax of CPN inscriptions.
 *
 * Both front-ends parse into these nodes: CPN ML (T2.2, the SML'97 core plus the CPN Tools
 * declarations) and the Python-like syntax (T2.7). The type checker (T2.4) and evaluator (T2.5)
 * only ever see this tree, so the two syntaxes cannot drift apart semantically.
 *
 * Design rules:
 * - Nodes are immutable data classes. Their [Node.span] is *not* part of equality, so two trees
 *   parsed from different syntaxes compare equal when they mean the same thing. Parsers set the
 *   span once with [at]; `copy()` gives a node without a span.
 * - Nothing is resolved here. An identifier may name a variable, a constructor or a colour set;
 *   that is decided by the type checker, which has the environment.
 * - Infix applications keep their own node ([InfixExpr], [InfixPat]) instead of being desugared
 *   to `op+(a, b)`, so printers (T2.8) and diagnostics can reproduce what the user wrote.
 */

public sealed class Node {
    /** Where this node came from; [Span.NONE] for synthesized nodes. Excluded from equality. */
    public var span: Span = Span.NONE
        internal set
}

/** Sets [span] on a freshly built node and returns it, for use in parsers: `IfExpr(c, t, e).at(span)`. */
public fun <T : Node> T.at(span: Span): T {
    check(this.span.isNone || this.span == span) { "Span of $this is already set to ${this.span}" }
    this.span = span
    return this
}

// ---------------------------------------------------------------------------------------------
// Identifiers

/** An identifier at a binding site, or a record label. */
public data class Ident(
    val name: String,
) : Node() {
    override fun toString(): String = name
}

/** A possibly qualified identifier, e.g. `x`, `List.map`, `Int.toString`, or an operator such as `++`. */
public data class LongIdent(
    val qualifiers: List<String>,
    val name: String,
) : Node() {
    public constructor(name: String) : this(emptyList(), name)

    val text: String get() = (qualifiers + name).joinToString(".")

    override fun toString(): String = text
}

// ---------------------------------------------------------------------------------------------
// Constants

public sealed class Constant {
    /** SML `int` literal; `~3` is negative. Range checks happen in the type checker (D8). */
    public data class IntConst(
        val value: Long,
    ) : Constant()

    /** Kept as written so printers and CPN Tools comparisons see the same digits. */
    public data class RealConst(
        val text: String,
    ) : Constant() {
        val value: Double get() = text.replace('~', '-').toDouble()
    }

    public data class StringConst(
        val value: String,
    ) : Constant()

    public data class CharConst(
        val value: Char,
    ) : Constant()
}

// ---------------------------------------------------------------------------------------------
// Types (in type annotations, `type` and `datatype` declarations)

public sealed class TypeExpr : Node()

/** `'a`, or `''a` for an equality type variable. */
public data class TypeVar(
    val name: String,
) : TypeExpr() {
    val isEquality: Boolean get() = name.startsWith("''")
}

/** `int`, `NO`, `int list`, `(string, int) pair`: a type constructor applied to [args]. */
public data class TypeCon(
    val name: LongIdent,
    val args: List<TypeExpr> = emptyList(),
) : TypeExpr()

/** `int * string`; always two or more elements (unit is `TypeCon("unit")`). */
public data class TupleType(
    val elements: List<TypeExpr>,
) : TypeExpr()

public data class RecordType(
    val fields: List<RecordTypeField>,
) : TypeExpr()

public data class RecordTypeField(
    val label: Ident,
    val type: TypeExpr,
) : Node()

public data class FunctionType(
    val parameter: TypeExpr,
    val result: TypeExpr,
) : TypeExpr()

// ---------------------------------------------------------------------------------------------
// Patterns

public sealed class Pattern : Node()

/** `_`. A class rather than an object so each occurrence has its own span. */
public class WildcardPat : Pattern() {
    override fun equals(other: Any?): Boolean = other is WildcardPat

    override fun hashCode(): Int = WildcardPat::class.hashCode()

    override fun toString(): String = "WildcardPat"
}

/** A variable or a nullary constructor (`x`, `nil`, `true`, `red`); the type checker decides which. */
public data class IdentPat(
    val name: LongIdent,
    val opPrefixed: Boolean = false,
) : Pattern()

public data class ConstPat(
    val constant: Constant,
) : Pattern()

/** `(p1, ..., pn)`; the empty tuple is `()`. A one-element tuple never occurs (that is just parentheses). */
public data class TuplePat(
    val elements: List<Pattern>,
) : Pattern()

/** `{a = p, b, ...}`; [flexible] is the trailing `...`. A field written `b` alone is `b = b`. */
public data class RecordPat(
    val fields: List<PatField>,
    val flexible: Boolean = false,
) : Pattern()

public data class PatField(
    val label: Ident,
    val pattern: Pattern,
) : Node()

public data class ListPat(
    val elements: List<Pattern>,
) : Pattern()

/** A constructor applied to an argument pattern: `SOME x`, `Packet (n, d)`. */
public data class ConPat(
    val constructor: LongIdent,
    val argument: Pattern,
) : Pattern()

/** An infix constructor pattern: `x :: xs`. */
public data class InfixPat(
    val operator: LongIdent,
    val left: Pattern,
    val right: Pattern,
) : Pattern()

/** `x as (a, b)`, optionally `x : t as (a, b)`. */
public data class AsPat(
    val name: Ident,
    val type: TypeExpr?,
    val pattern: Pattern,
) : Pattern()

public data class TypedPat(
    val pattern: Pattern,
    val type: TypeExpr,
) : Pattern()

// ---------------------------------------------------------------------------------------------
// Expressions

public sealed class Expr : Node()

public data class ConstExpr(
    val constant: Constant,
) : Expr()

/** A variable, constructor or function name; [opPrefixed] is SML `op +`. */
public data class IdentExpr(
    val name: LongIdent,
    val opPrefixed: Boolean = false,
) : Expr()

/** `(e1, ..., en)`; `()` is the empty tuple. */
public data class TupleExpr(
    val elements: List<Expr>,
) : Expr()

public data class RecordExpr(
    val fields: List<ExprField>,
) : Expr()

public data class ExprField(
    val label: Ident,
    val value: Expr,
) : Node()

/** `#label`, a record or tuple selector function (`#1`, `#seq`). */
public data class SelectorExpr(
    val label: String,
) : Expr()

public data class ListExpr(
    val elements: List<Expr>,
) : Expr()

/** Function application `f x`. */
public data class AppExpr(
    val function: Expr,
    val argument: Expr,
) : Expr()

/**
 * An infix operator application, after fixity resolution: `a + b`, ``1`x``, `ms1 ++ ms2`,
 * `x :: xs`, `t @+ 5`. Semantically `op operator (left, right)`.
 */
public data class InfixExpr(
    val operator: LongIdent,
    val left: Expr,
    val right: Expr,
) : Expr()

public data class TypedExpr(
    val expr: Expr,
    val type: TypeExpr,
) : Expr()

/** `andalso`: short-circuit, so not an ordinary function. */
public data class AndAlsoExpr(
    val left: Expr,
    val right: Expr,
) : Expr()

/** `orelse`: short-circuit, so not an ordinary function. */
public data class OrElseExpr(
    val left: Expr,
    val right: Expr,
) : Expr()

public data class IfExpr(
    val condition: Expr,
    val then: Expr,
    val otherwise: Expr,
) : Expr()

public data class CaseExpr(
    val scrutinee: Expr,
    val rules: List<MatchRule>,
) : Expr()

/** `fn p1 => e1 | p2 => e2` (a lambda in the Python-like syntax). */
public data class FnExpr(
    val rules: List<MatchRule>,
) : Expr()

/** One `pattern => body` alternative of `case`, `fn` or `handle`. */
public data class MatchRule(
    val pattern: Pattern,
    val body: Expr,
) : Node()

/** `let decls in body end`; a body `e1; e2` is a [SeqExpr]. */
public data class LetExpr(
    val declarations: List<Decl>,
    val body: Expr,
) : Expr()

/** `(e1; e2; ...; en)`: evaluates all, yields the last. */
public data class SeqExpr(
    val exprs: List<Expr>,
) : Expr()

public data class RaiseExpr(
    val exception: Expr,
) : Expr()

public data class HandleExpr(
    val expr: Expr,
    val rules: List<MatchRule>,
) : Expr()

public data class WhileExpr(
    val condition: Expr,
    val body: Expr,
) : Expr()

// ---------------------------------------------------------------------------------------------
// Declarations: SML core

public sealed class Decl : Node()

/** `val p1 = e1 and p2 = e2`; [recursive] is `val rec`. */
public data class ValDecl(
    val bindings: List<ValBind>,
    val recursive: Boolean = false,
) : Decl()

public data class ValBind(
    val pattern: Pattern,
    val value: Expr,
) : Node()

/** `fun f p1 p2 = e | f q1 q2 = e' and g ...`: mutually recursive functions. */
public data class FunDecl(
    val bindings: List<FunBind>,
) : Decl()

public data class FunBind(
    val name: Ident,
    val clauses: List<FunClause>,
) : Node()

/** One clause: curried argument patterns, an optional result type, and the body. */
public data class FunClause(
    val arguments: List<Pattern>,
    val resultType: TypeExpr?,
    val body: Expr,
) : Node()

/** `type ('a, 'b) t = ...`. */
public data class TypeDecl(
    val bindings: List<TypeBind>,
) : Decl()

public data class TypeBind(
    val parameters: List<TypeVar>,
    val name: Ident,
    val type: TypeExpr,
) : Node()

/** `datatype 'a tree = Leaf | Node of 'a tree * 'a * 'a tree`. */
public data class DatatypeDecl(
    val bindings: List<DatatypeBind>,
) : Decl()

public data class DatatypeBind(
    val parameters: List<TypeVar>,
    val name: Ident,
    val constructors: List<ConBind>,
) : Node()

/** A constructor of a datatype or union colour set; [argument] is the type after `of`. */
public data class ConBind(
    val name: Ident,
    val argument: TypeExpr?,
) : Node()

public data class ExceptionDecl(
    val bindings: List<ConBind>,
) : Decl()

/** `local decls in decls end`. */
public data class LocalDecl(
    val locals: List<Decl>,
    val body: List<Decl>,
) : Decl()

public enum class Fixity { INFIX, INFIXR, NONFIX }

/** `infix 6 ++` (left associative), `infixr 5 ::`, `nonfix +`. [precedence] defaults to 0 in SML and is null for `nonfix`. */
public data class FixityDecl(
    val fixity: Fixity,
    val precedence: Int?,
    val operators: List<Ident>,
) : Decl()

public data class OpenDecl(
    val structures: List<LongIdent>,
) : Decl()

// ---------------------------------------------------------------------------------------------
// Declarations: CPN Tools additions

/** `colset NAME = spec [timed];`. */
public data class ColsetDecl(
    val name: Ident,
    val spec: ColsetSpec,
    val timed: Boolean = false,
) : Decl()

/** `var n, k : NO;`. */
public data class VarDecl(
    val names: List<Ident>,
    val colset: LongIdent,
) : Decl()

/** `globref counter = 0;`: a global reference shared by all code. */
public data class GlobrefDecl(
    val name: Ident,
    val value: Expr,
) : Decl()

/** `use "file.sml";` (kept so the importer can report it; loading files is not supported). */
public data class UseDecl(
    val path: String,
) : Decl()

/** `low..high` in colour set restrictions; bounds may be expressions such as `1..n`. */
public data class RangeSpec(
    val low: Expr,
    val high: Expr,
) : Node()

/** The right-hand side of a `colset` declaration (CPN Tools 4 colour set constructors). */
public sealed class ColsetSpec : Node()

/** `unit [with new_unit_name]`. */
public data class UnitColset(
    val newName: Ident? = null,
) : ColsetSpec()

/** `bool [with (falseName, trueName)]`. */
public data class BoolColset(
    val falseName: Ident? = null,
    val trueName: Ident? = null,
) : ColsetSpec()

/** `int [with low..high]`. */
public data class IntColset(
    val range: RangeSpec? = null,
) : ColsetSpec()

/** `intinf [with low..high]`. */
public data class IntInfColset(
    val range: RangeSpec? = null,
) : ColsetSpec()

/** `real [with low..high]`. */
public data class RealColset(
    val range: RangeSpec? = null,
) : ColsetSpec()

/** `time`. A class rather than an object so each occurrence has its own span. */
public class TimeColset : ColsetSpec() {
    override fun equals(other: Any?): Boolean = other is TimeColset

    override fun hashCode(): Int = TimeColset::class.hashCode()

    override fun toString(): String = "TimeColset"
}

/** `string [with "a".."z" [and 1..10]]`: allowed characters and allowed lengths. */
public data class StringColset(
    val characters: RangeSpec? = null,
    val length: RangeSpec? = null,
) : ColsetSpec()

/** `with red | green | blue`. */
public data class EnumColset(
    val constants: List<Ident>,
) : ColsetSpec()

/** `index ph with 1..5`, colours `ph(1)` ... `ph(5)`. */
public data class IndexColset(
    val constructor: Ident,
    val range: RangeSpec,
) : ColsetSpec()

/** `product NO * DATA`. */
public data class ProductColset(
    val components: List<LongIdent>,
) : ColsetSpec()

/** `record seq: NO * data: DATA`. */
public data class RecordColset(
    val fields: List<RecordColsetField>,
) : ColsetSpec()

public data class RecordColsetField(
    val label: Ident,
    val colset: LongIdent,
) : Node()

/** `union Data: DATA + Ack: NO + Stop`. */
public data class UnionColset(
    val alternatives: List<UnionAlternative>,
) : ColsetSpec()

public data class UnionAlternative(
    val constructor: Ident,
    val colset: LongIdent?,
) : Node()

/** `list NO [with 0..5]`: lists whose length is in [length]. */
public data class ListColset(
    val element: LongIdent,
    val length: RangeSpec? = null,
) : ColsetSpec()

/** `subset NO by isEven` or `subset NO with [1, 3, 5]`; exactly one of [predicate] and [members] is set. */
public data class SubsetColset(
    val base: LongIdent,
    val predicate: Expr? = null,
    val members: Expr? = null,
) : ColsetSpec() {
    init {
        require((predicate == null) != (members == null)) { "A subset colour set needs exactly one of 'by' and 'with'" }
    }
}

/** `colset NO2 = NO;`. */
public data class AliasColset(
    val colset: LongIdent,
) : ColsetSpec()

// ---------------------------------------------------------------------------------------------
// Roots

/** A declaration block, e.g. the CPN Tools `globbox` or a page's local declarations. */
public data class Declarations(
    val declarations: List<Decl>,
) : Node()
