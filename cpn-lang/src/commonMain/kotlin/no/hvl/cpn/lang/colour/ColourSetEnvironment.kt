package no.hvl.cpn.lang.colour

import no.hvl.cpn.lang.ast.AliasColset
import no.hvl.cpn.lang.ast.BoolColset
import no.hvl.cpn.lang.ast.ColsetDecl
import no.hvl.cpn.lang.ast.ColsetSpec
import no.hvl.cpn.lang.ast.ConstExpr
import no.hvl.cpn.lang.ast.Constant
import no.hvl.cpn.lang.ast.Declarations
import no.hvl.cpn.lang.ast.EnumColset
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.IndexColset
import no.hvl.cpn.lang.ast.IntColset
import no.hvl.cpn.lang.ast.IntInfColset
import no.hvl.cpn.lang.ast.ListColset
import no.hvl.cpn.lang.ast.ListExpr
import no.hvl.cpn.lang.ast.LongIdent
import no.hvl.cpn.lang.ast.ProductColset
import no.hvl.cpn.lang.ast.RangeSpec
import no.hvl.cpn.lang.ast.RealColset
import no.hvl.cpn.lang.ast.RecordColset
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span
import no.hvl.cpn.lang.ast.StringColset
import no.hvl.cpn.lang.ast.SubsetColset
import no.hvl.cpn.lang.ast.TimeColset
import no.hvl.cpn.lang.ast.TupleExpr
import no.hvl.cpn.lang.ast.UnionColset
import no.hvl.cpn.lang.ast.UnitColset
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.lang.value.MlTuple

/** A `colset` declaration that cannot be turned into a colour set, located in its source. */
public class ColsetDeclarationException(
    public val span: Span,
    public val reason: String,
    source: SourceText? = null,
) : IllegalArgumentException(if (source == null) reason else "${source.describe(span)}: $reason")

/**
 * Evaluates the expressions inside colour set declarations: range bounds (`1..n`), `subset ... with`
 * member lists and `subset ... by` predicates. The full evaluator (T2.5) implements this; until then
 * [LiteralColsetEvaluator] handles constants.
 */
public interface ColsetEvaluator {
    /** The value of [expr]: `Int`, `Long`, [MlReal], `String`, a list, ... */
    public fun value(expr: Expr): Any?

    /** [expr] as a predicate on colours of [base]. */
    public fun predicate(
        expr: Expr,
        base: CpnColourSet,
    ): (Any?) -> Boolean
}

/** Evaluates literals only (numbers, strings, characters, and lists or tuples of them). */
public object LiteralColsetEvaluator : ColsetEvaluator {
    override fun value(expr: Expr): Any? =
        when (expr) {
            is ConstExpr ->
                when (val c = expr.constant) {
                    is Constant.IntConst -> c.value
                    is Constant.RealConst -> MlReal(c.value)
                    is Constant.StringConst -> c.value
                    is Constant.CharConst -> c.value
                }
            is ListExpr -> expr.elements.map(::value)
            is TupleExpr -> MlTuple(expr.elements.map(::value))
            else -> throw ColsetDeclarationException(expr.span, "only constants are supported here until the evaluator exists (T2.5)")
        }

    override fun predicate(
        expr: Expr,
        base: CpnColourSet,
    ): (Any?) -> Boolean = throw ColsetDeclarationException(expr.span, "'subset ... by' needs the evaluator (T2.5)")
}

/**
 * The colour sets declared so far, by name. Declarations are processed in order, so a colour set
 * can only use those declared before it, as in CPN Tools.
 */
public class ColourSetEnvironment(
    private val evaluator: ColsetEvaluator = LiteralColsetEvaluator,
    private val source: SourceText? = null,
) {
    private val sets = LinkedHashMap<String, CpnColourSet>()

    public operator fun get(name: String): CpnColourSet? = sets[name]

    public val names: Set<String> get() = sets.keys

    public fun define(colourSet: CpnColourSet): CpnColourSet {
        if (colourSet.name in sets) throw ColourSetException("colour set ${colourSet.name} is already declared")
        sets[colourSet.name] = colourSet
        return colourSet
    }

    /** Declares every `colset` in [declarations]; other declarations are left to the type checker. */
    public fun declareAll(declarations: Declarations): ColourSetEnvironment {
        declarations.declarations.filterIsInstance<ColsetDecl>().forEach(::declare)
        return this
    }

    public fun declare(decl: ColsetDecl): CpnColourSet {
        val name = decl.name.name
        if (name in sets) fail(decl.name.span, "colour set $name is already declared")
        val colourSet =
            try {
                build(name, decl.spec)
            } catch (e: ColourSetException) {
                fail(decl.span, e.message ?: "invalid colour set")
            }
        sets[name] = if (decl.timed) TimedColourSet(colourSet) else colourSet
        return sets.getValue(name)
    }

    private fun build(
        name: String,
        spec: ColsetSpec,
    ): CpnColourSet =
        when (spec) {
            is UnitColset -> UnitColourSet(name, spec.newName?.name ?: "()")
            is BoolColset -> BoolColourSet(name, spec.falseName?.name ?: "false", spec.trueName?.name ?: "true")
            is IntColset -> IntColourSet(name, spec.range?.let { intRange(it) })
            is IntInfColset -> LongColourSet(name, spec.range?.let { longRange(it) })
            is RealColset ->
                RealColourSet(
                    name,
                    spec.range?.let {
                        (value(it.low, "a real") { v -> (v as? MlReal)?.value })..value(it.high, "a real") { v -> (v as? MlReal)?.value }
                    },
                )
            is TimeColset -> LongColourSet(name, 0..Long.MAX_VALUE)
            is StringColset ->
                StringColourSet(
                    name,
                    spec.characters?.let { range -> character(range.low)..character(range.high) },
                    spec.length?.let { intRange(it) },
                )
            is EnumColset -> EnumColourSet(name, spec.constants.map { it.name })
            is IndexColset -> IndexColourSet(name, spec.constructor.name, intRange(spec.range))
            is ProductColset -> ProductColourSet(name, spec.components.map(::lookup))
            is RecordColset -> RecordColourSet(name, spec.fields.map { it.label.name to lookup(it.colset) })
            is UnionColset -> UnionColourSet(name, spec.alternatives.map { it.constructor.name to it.colset?.let(::lookup) })
            is ListColset -> ListColourSet(name, lookup(spec.element), spec.length?.let { intRange(it) })
            is SubsetColset -> {
                val base = lookup(spec.base)
                if (spec.predicate != null) {
                    SubsetColourSet(name, base, predicate = evaluator.predicate(spec.predicate, base))
                } else {
                    val members = value(spec.members!!, "a list of colours") { it as? List<*> }
                    SubsetColourSet(name, base, members = members.map { coerce(it, base) })
                }
            }
            is AliasColset -> AliasColourSet(name, lookup(spec.colset))
        }

    private fun lookup(name: LongIdent): CpnColourSet = sets[name.text] ?: fail(name.span, "unknown colour set ${name.text}")

    private fun intRange(range: RangeSpec): IntRange = int(range.low)..int(range.high)

    private fun longRange(range: RangeSpec): LongRange =
        value(range.low, "an integer") {
            it as? Long ?: (it as? Int)?.toLong()
        }..value(range.high, "an integer") { it as? Long ?: (it as? Int)?.toLong() }

    private fun int(expr: Expr): Int =
        value(expr, "an int") { v ->
            when (v) {
                is Int -> v
                is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else fail(expr.span, "$v does not fit in an int")
                else -> null
            }
        }

    private fun character(expr: Expr): Char = value(expr, "a one-character string") { v -> (v as? String)?.singleOrNull() ?: v as? Char }

    private fun <T : Any> value(
        expr: Expr,
        expected: String,
        convert: (Any?) -> T?,
    ): T = convert(evaluator.value(expr)) ?: fail(expr.span, "expected $expected")

    /** Literal ints arrive as Long from the literal evaluator. */
    private fun coerce(
        value: Any?,
        base: CpnColourSet,
    ): Any? = if (value is Long && value !in base && value.toInt().toLong() == value && value.toInt() in base) value.toInt() else value

    private fun fail(
        span: Span,
        message: String,
    ): Nothing = throw ColsetDeclarationException(span, message, source)

    public companion object {
        /** The standard declarations of a new CPN Tools net: UNIT, BOOL, INT, INTINF, TIME, REAL, STRING. */
        public fun standard(
            evaluator: ColsetEvaluator = LiteralColsetEvaluator,
            source: SourceText? = null,
        ): ColourSetEnvironment =
            ColourSetEnvironment(evaluator, source).apply {
                define(UnitColourSet("UNIT"))
                define(BoolColourSet("BOOL"))
                define(IntColourSet("INT"))
                define(LongColourSet("INTINF"))
                define(LongColourSet("TIME", 0..Long.MAX_VALUE))
                define(RealColourSet("REAL"))
                define(StringColourSet("STRING"))
            }
    }
}
