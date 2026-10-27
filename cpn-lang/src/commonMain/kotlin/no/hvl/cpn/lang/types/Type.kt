package no.hvl.cpn.lang.types

/*
 * Types of CPN ML (the SML'97 core with CPN Tools' additions), for Hindley-Milner inference.
 *
 * Tuples are records with labels 1..n, as in the Definition of Standard ML, and unit is the empty
 * record. Type variables are mutable cells that unification binds (with path compression in
 * [prune]), and carry a level for efficient generalization (Rémy's levels).
 */

public sealed class Type

/** A type variable. [instance] is set once unification binds it. */
public class TypeVar internal constructor(
    internal val id: Int,
    internal var level: Int,
    internal var kind: Kind,
) : Type() {
    internal var instance: Type? = null

    override fun toString(): String = TypePrinter().print(this)
}

/** A type constructor applied to arguments: `int`, `string list`, `NO ms`, `(int, bool) pair`. */
public data class TypeCon(
    val name: String,
    val args: List<Type> = emptyList(),
) : Type() {
    override fun toString(): String = TypePrinter().print(this)
}

/** A record type; tuples are records with labels `1`..`n`, unit is the empty record. */
public data class RecordType(
    val fields: Map<String, Type>,
) : Type() {
    override fun toString(): String = TypePrinter().print(this)
}

public data class FunType(
    val param: Type,
    val result: Type,
) : Type() {
    override fun toString(): String = TypePrinter().print(this)
}

/** What a type variable may stand for. */
internal sealed class Kind {
    /** Any type. */
    data object Free : Kind()

    /** Only types that admit equality (`''a`): not real, not functions. */
    data object Equality : Kind()

    /**
     * An overloaded operator's argument (`+` on int or real): one of [allowed]; [default] if nothing
     * decides it (SML's default overloading resolution).
     */
    data class Overloaded(
        val allowed: Set<String>,
        val default: String,
    ) : Kind()

    /** A flexible record, from `#label` or `{a, ...}`: some record type with at least [fields]. */
    data class FlexRecord(
        val fields: Map<String, Type>,
        val equality: Boolean = false,
    ) : Kind()
}

/** A polymorphic type: [vars] are universally quantified in [body]. */
public class Scheme internal constructor(
    internal val vars: List<TypeVar>,
    public val body: Type,
) {
    override fun toString(): String = TypePrinter().print(body)

    public companion object {
        public fun mono(type: Type): Scheme = Scheme(emptyList(), type)
    }
}

/** Follows bound type variables to the type they stand for, compressing paths on the way. */
internal fun prune(type: Type): Type {
    if (type is TypeVar) {
        val instance = type.instance ?: return type
        val pruned = prune(instance)
        type.instance = pruned
        return pruned
    }
    return type
}

/** The type with every bound variable replaced by what it stands for (for printing and tests). */
public fun Type.resolved(): Type =
    when (val t = prune(this)) {
        is TypeVar -> t
        is TypeCon -> TypeCon(t.name, t.args.map { it.resolved() })
        is RecordType -> RecordType(t.fields.mapValues { it.value.resolved() })
        is FunType -> FunType(t.param.resolved(), t.result.resolved())
    }

internal val UNIT: Type = RecordType(emptyMap())
internal val INT: Type = TypeCon("int")
internal val REAL: Type = TypeCon("real")
internal val STRING: Type = TypeCon("string")
internal val CHAR: Type = TypeCon("char")
internal val BOOL: Type = TypeCon("bool")
internal val EXN: Type = TypeCon("exn")

internal fun tuple(vararg elements: Type): Type = tupleOf(elements.toList())

internal fun tupleOf(elements: List<Type>): Type = RecordType(elements.withIndex().associate { (i, t) -> (i + 1).toString() to t })

internal fun listType(element: Type): Type = TypeCon("list", listOf(element))

internal fun multisetType(element: Type): Type = TypeCon("ms", listOf(element))

/** Record labels in SML order: numeric labels numerically, before alphanumeric ones. */
internal val labelOrder: Comparator<String> =
    compareBy<String> { it.toIntOrNull() == null }.thenBy { it.toIntOrNull() ?: 0 }.thenBy { it }

/** Is [type] a tuple, i.e. a record with labels exactly 1..n, n >= 2? */
internal fun tupleElements(type: RecordType): List<Type>? {
    val n = type.fields.size
    if (n < 2) return null
    return (1..n).map { type.fields[it.toString()] ?: return null }
}

/**
 * Prints types in SML notation: `int * string -> bool`, `'a list`, `{seq: int, data: string}`.
 * One printer names type variables consistently (`'a`, `'b`, ... in order of appearance; `''a` for
 * equality variables), so use the same printer for types shown together in one message.
 */
public class TypePrinter {
    private val names = HashMap<TypeVar, String>()

    public fun print(type: Type): String = print(type, 0)

    // Precedence: 0 = anywhere, 1 = function parameter (tuples fine, arrows need parentheses),
    // 2 = tuple element (both need parentheses), 3 = type constructor argument.
    private fun print(
        type: Type,
        precedence: Int,
    ): String =
        when (val t = prune(type)) {
            is TypeVar -> name(t)
            is TypeCon ->
                when (t.args.size) {
                    0 -> t.name
                    1 -> "${print(t.args[0], 3)} ${t.name}"
                    else -> t.args.joinToString(", ", "(", ") ${t.name}") { print(it, 0) }
                }
            is RecordType -> {
                val elements = tupleElements(t)
                when {
                    t.fields.isEmpty() -> "unit"
                    elements != null -> paren(precedence >= 2, elements.joinToString(" * ") { print(it, 2) })
                    else ->
                        t.fields.entries
                            .sortedWith(
                                compareBy(labelOrder) { it.key },
                            ).joinToString(", ", "{", "}") { (k, v) -> "$k: ${print(v, 0)}" }
                }
            }
            is FunType -> paren(precedence >= 1, "${print(t.param, 1)} -> ${print(t.result, 0)}")
        }

    private fun paren(
        needed: Boolean,
        text: String,
    ) = if (needed) "($text)" else text

    private fun name(v: TypeVar): String =
        names.getOrPut(v) {
            val letter = ('a' + names.size % 26).toString() + if (names.size >= 26) (names.size / 26).toString() else ""
            when (val k = v.kind) {
                Kind.Equality -> "''$letter"
                is Kind.Overloaded -> "'$letter" + k.allowed.sorted().joinToString("|", "[", "]")
                is Kind.FlexRecord ->
                    "'$letter" +
                        k.fields.keys
                            .sortedWith(labelOrder)
                            .joinToString(", ", "{", ", ...}")
                Kind.Free -> "'$letter"
            }
        }
}
