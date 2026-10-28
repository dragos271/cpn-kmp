package no.hvl.cpn.lang.colour

import no.hvl.cpn.core.ColourSet
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.lang.value.MlRecord
import no.hvl.cpn.lang.value.MlTuple
import no.hvl.cpn.lang.value.formatConstructed
import no.hvl.cpn.lang.value.formatInt
import no.hvl.cpn.lang.value.formatReal
import no.hvl.cpn.lang.value.formatString

/** A colour set operation that does not apply, e.g. `ord` on an infinite set or a colour outside the set. */
public class ColourSetException(
    message: String,
) : IllegalArgumentException(message)

/**
 * A CPN ML colour set (CPN Tools 4 semantics), with the standard colour set functions:
 *
 * | CPN ML          | Kotlin       |
 * |-----------------|--------------|
 * | `CS.legal c`    | [legal]      |
 * | `CS.size ()`    | [size]       |
 * | `CS.all ()`     | [all]        |
 * | `CS.ord c`      | [ord]        |
 * | `CS.col i`      | [col]        |
 * | `CS.ran ()`     | [ran]        |
 * | `CS.mkstr c`    | [mkstr]      |
 * | `CS.mkstr_ms m` | [mkstrMs]    |
 *
 * Colours are ordered: by declaration for finite sets (so [ord] follows [order]) and naturally for
 * infinite ones. Markings print in this order.
 *
 * [size], [ord] and [col] only exist for finite sets ("small" colour sets in CPN Tools). They are
 * computed arithmetically, so `int with 1..1000000` is not materialized; [values] is a virtual list.
 */
public abstract class CpnColourSet(
    final override val name: String,
) : ColourSet<Any?> {
    /** Whether tokens of this colour set carry time stamps (`colset ... timed`, T3.2). */
    public open val timed: Boolean get() = false

    /** Number of colours; null if infinite (or beyond `Long`). */
    public abstract val size: Long?

    /** Position of [colour] in colour-set order, from 0. */
    public abstract fun ord(colour: Any?): Long

    /** The colour at position [index], from 0. */
    public abstract fun col(index: Long): Any?

    /** Total order on colours of this set. */
    public abstract fun compare(
        a: Any?,
        b: Any?,
    ): Int

    abstract override fun contains(value: Any?): Boolean

    abstract override fun format(value: Any?): String

    override val order: Comparator<in Any?> = Comparator { a, b -> compare(a, b) }

    final override val isFinite: Boolean get() = size != null

    override val values: List<Any?>? by lazy {
        val n = size ?: return@lazy null
        if (n > Int.MAX_VALUE) return@lazy null
        object : AbstractList<Any?>() {
            override val size: Int = n.toInt()

            override fun get(index: Int): Any? = col(index.toLong())
        }
    }

    public fun legal(colour: Any?): Boolean = contains(colour)

    public fun mkstr(colour: Any?): String {
        requireMember(colour)
        return format(colour)
    }

    public fun mkstrMs(multiset: Multiset<*>): String {
        @Suppress("UNCHECKED_CAST")
        val ms = multiset as Multiset<Any?>
        ms.distinct.forEach(::requireMember)
        return ms.format(order, ::format)
    }

    /** All colours, once each. CPN Tools refuses this for large sets; so do we, above [limit]. */
    public fun all(limit: Long = 1_000_000): Multiset<Any?> {
        val n = requireSize("all")
        if (n > limit) throw ColourSetException("$name has $n colours; all() is limited to $limit")
        return (0 until n).map(::col).let { Multiset.of(*it.toTypedArray()) }
    }

    /** A colour chosen with [nextLong] (uniform in `0 until bound`). */
    public fun ran(nextLong: (bound: Long) -> Long): Any? = col(nextLong(requireSize("ran")))

    protected fun requireSize(operation: String): Long =
        size ?: throw ColourSetException("$operation is not defined for $name: it is infinite")

    protected fun requireMember(colour: Any?) {
        if (!contains(colour)) throw ColourSetException("${describe(colour)} is not a colour of $name")
    }

    protected fun requireIndex(index: Long) {
        val n = requireSize("col")
        if (index !in 0 until n) throw ColourSetException("col $index is out of range for $name (0..${n - 1})")
    }

    private fun describe(colour: Any?): String =
        no.hvl.cpn.lang.value
            .formatValue(colour)

    override fun toString(): String = name
}

// -------------------------------------------------------------------------------------------------
// Simple colour sets

/** `unit [with name]`. The colour is `Unit`, printed as [unitName]. */
public class UnitColourSet(
    name: String,
    public val unitName: String = "()",
) : CpnColourSet(name) {
    override val size: Long = 1

    override fun contains(value: Any?): Boolean = value == Unit

    override fun ord(colour: Any?): Long {
        requireMember(colour)
        return 0
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        return Unit
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = 0

    override fun format(value: Any?): String = unitName
}

/** `bool [with (falseName, trueName)]`; `false < true`. */
public class BoolColourSet(
    name: String,
    public val falseName: String = "false",
    public val trueName: String = "true",
) : CpnColourSet(name) {
    override val size: Long = 2

    override fun contains(value: Any?): Boolean = value is Boolean

    override fun ord(colour: Any?): Long {
        requireMember(colour)
        return if (colour == true) 1 else 0
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        return index == 1L
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = (a as Boolean).compareTo(b as Boolean)

    override fun format(value: Any?): String = if (value == true) trueName else falseName
}

/** `int [with low..high]`; colours are `Int`. */
public class IntColourSet(
    name: String,
    public val range: IntRange? = null,
) : CpnColourSet(name) {
    init {
        if (range != null && range.isEmpty()) throw ColourSetException("$name: empty range ${range.first}..${range.last}")
    }

    override val size: Long? = range?.let { it.last.toLong() - it.first + 1 }

    override fun contains(value: Any?): Boolean = value is Int && (range == null || value in range)

    override fun ord(colour: Any?): Long {
        requireSize("ord")
        requireMember(colour)
        return (colour as Int).toLong() - range!!.first
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        return (range!!.first + index).toInt()
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = (a as Int).compareTo(b as Int)

    override fun format(value: Any?): String = formatInt((value as Int).toLong())
}

/** `intinf [with low..high]`, and `time` (non-negative). Colours are `Long` until bignums are needed (D8). */
public class LongColourSet(
    name: String,
    public val range: LongRange? = null,
) : CpnColourSet(name) {
    init {
        if (range != null && range.isEmpty()) throw ColourSetException("$name: empty range ${range.first}..${range.last}")
    }

    // `last - first` wraps around to a negative number if the range has more than Long.MAX_VALUE colours.
    override val size: Long? =
        range?.let {
            (it.last - it.first).let { span ->
                if (span < 0 ||
                    span == Long.MAX_VALUE
                ) {
                    null
                } else {
                    span + 1
                }
            }
        }

    override fun contains(value: Any?): Boolean = value is Long && (range == null || value in range)

    override fun ord(colour: Any?): Long {
        requireSize("ord")
        requireMember(colour)
        return colour as Long - range!!.first
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        return range!!.first + index
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = (a as Long).compareTo(b as Long)

    override fun format(value: Any?): String = formatInt(value as Long)
}

/** `real [with low..high]`. Never finite. */
public class RealColourSet(
    name: String,
    public val range: ClosedFloatingPointRange<Double>? = null,
) : CpnColourSet(name) {
    override val size: Long? = null

    override fun contains(value: Any?): Boolean = value is MlReal && !value.value.isNaN() && (range == null || value.value in range)

    override fun ord(colour: Any?): Long = throw ColourSetException("ord is not defined for $name: it is infinite")

    override fun col(index: Long): Any? = throw ColourSetException("col is not defined for $name: it is infinite")

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = (a as MlReal).compareTo(b as MlReal)

    override fun format(value: Any?): String = formatReal((value as MlReal).value)
}

/**
 * `string [with "a".."z" [and 1..10]]`: allowed characters and lengths. Treated as infinite even
 * when both are bounded, as in CPN Tools (string colour sets are never "small").
 */
public class StringColourSet(
    name: String,
    public val characters: CharRange? = null,
    public val lengths: IntRange? = null,
) : CpnColourSet(name) {
    override val size: Long? = null

    override fun contains(value: Any?): Boolean =
        value is String &&
            (characters == null || value.all { it in characters }) &&
            (lengths == null || value.length in lengths)

    override fun ord(colour: Any?): Long = throw ColourSetException("ord is not defined for $name: string colour sets are not enumerable")

    override fun col(index: Long): Any? = throw ColourSetException("col is not defined for $name: string colour sets are not enumerable")

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = (a as String).compareTo(b as String)

    override fun format(value: Any?): String = formatString(value as String)
}

/** `with a | b | c`: colours are nullary [MlCon]s, ordered as declared. */
public class EnumColourSet(
    name: String,
    public val constants: List<String>,
) : CpnColourSet(name) {
    private val index: Map<String, Int> = constants.withIndex().associate { (i, c) -> c to i }

    init {
        if (constants.isEmpty()) throw ColourSetException("$name: an enumeration needs at least one constant")
        if (index.size != constants.size) throw ColourSetException("$name: duplicate enumeration constants")
    }

    override val size: Long = constants.size.toLong()

    override fun contains(value: Any?): Boolean = value is MlCon && !value.hasArgument && value.name in index

    override fun ord(colour: Any?): Long {
        requireMember(colour)
        return index.getValue((colour as MlCon).name).toLong()
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        return MlCon(constants[index.toInt()])
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = index.getValue((a as MlCon).name).compareTo(index.getValue((b as MlCon).name))

    override fun format(value: Any?): String = (value as MlCon).name
}

/** `index ph with 1..5`: colours `ph(1)` ... `ph(5)` as [MlCon] with an `Int` argument. */
public class IndexColourSet(
    name: String,
    public val constructor: String,
    public val range: IntRange,
) : CpnColourSet(name) {
    init {
        if (range.isEmpty()) throw ColourSetException("$name: empty range ${range.first}..${range.last}")
    }

    override val size: Long = range.last.toLong() - range.first + 1

    override fun contains(value: Any?): Boolean =
        value is MlCon && value.name == constructor && value.argument is Int && value.argument in range

    override fun ord(colour: Any?): Long {
        requireMember(colour)
        return ((colour as MlCon).argument as Int).toLong() - range.first
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        return MlCon(constructor, (range.first + index).toInt())
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = ((a as MlCon).argument as Int).compareTo((b as MlCon).argument as Int)

    override fun format(value: Any?): String = formatConstructed(constructor, formatInt(((value as MlCon).argument as Int).toLong()))
}

// -------------------------------------------------------------------------------------------------
// Compound colour sets

/** Mixed-radix helpers: first component most significant, as in lexicographic order. */
private fun productSize(sizes: List<Long?>): Long? {
    var total = 1L
    for (size in sizes) {
        if (size == null) return null
        if (size != 0L && total > Long.MAX_VALUE / size) return null
        total *= size
    }
    return total
}

/** `product A * B * C`: colours are [MlTuple]s, ordered lexicographically. */
public class ProductColourSet(
    name: String,
    public val components: List<CpnColourSet>,
) : CpnColourSet(name) {
    init {
        if (components.size < 2) throw ColourSetException("$name: a product needs at least two components")
    }

    override val size: Long? = productSize(components.map { it.size })

    override fun contains(value: Any?): Boolean =
        value is MlTuple && value.elements.size == components.size && components.zip(value.elements).all { (cs, v) -> v in cs }

    override fun ord(colour: Any?): Long {
        requireSize("ord")
        requireMember(colour)
        return components.zip((colour as MlTuple).elements).fold(0L) { acc, (cs, v) -> acc * cs.size!! + cs.ord(v) }
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        var rest = index
        val elements = arrayOfNulls<Any?>(components.size)
        for (i in components.indices.reversed()) {
            val n = components[i].size!!
            elements[i] = components[i].col(rest % n)
            rest /= n
        }
        return MlTuple(elements.toList())
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int {
        val x = (a as MlTuple).elements
        val y = (b as MlTuple).elements
        for (i in components.indices) {
            val c = components[i].compare(x[i], y[i])
            if (c != 0) return c
        }
        return 0
    }

    override fun format(value: Any?): String =
        components.zip((value as MlTuple).elements).joinToString(",", "(", ")") { (cs, v) -> cs.format(v) }
}

/** `record a: A * b: B`: colours are [MlRecord]s, ordered field by field in declaration order. */
public class RecordColourSet(
    name: String,
    public val fields: List<Pair<String, CpnColourSet>>,
) : CpnColourSet(name) {
    init {
        if (fields.isEmpty()) throw ColourSetException("$name: a record needs at least one field")
        if (fields.map { it.first }.toSet().size != fields.size) throw ColourSetException("$name: duplicate record labels")
    }

    private val labels = fields.map { it.first }

    override val size: Long? = productSize(fields.map { it.second.size })

    override fun contains(value: Any?): Boolean =
        value is MlRecord && value.fields.keys == labels.toSet() && fields.all { (label, cs) -> value[label] in cs }

    override fun ord(colour: Any?): Long {
        requireSize("ord")
        requireMember(colour)
        return fields.fold(0L) { acc, (label, cs) -> acc * cs.size!! + cs.ord((colour as MlRecord)[label]) }
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        var rest = index
        val values = arrayOfNulls<Any?>(fields.size)
        for (i in fields.indices.reversed()) {
            val cs = fields[i].second
            values[i] = cs.col(rest % cs.size!!)
            rest /= cs.size!!
        }
        return MlRecord(labels.zip(values).toMap(LinkedHashMap()))
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int {
        for ((label, cs) in fields) {
            val c = cs.compare((a as MlRecord)[label], (b as MlRecord)[label])
            if (c != 0) return c
        }
        return 0
    }

    override fun format(value: Any?): String =
        fields.joinToString(",", "{", "}") { (label, cs) -> "$label=${cs.format((value as MlRecord)[label])}" }
}

/**
 * `union Data: DATA + Ack: NO + Stop`: colours are [MlCon]s; nullary alternatives have no argument.
 * Ordered by alternative, then by argument.
 */
public class UnionColourSet(
    name: String,
    public val alternatives: List<Pair<String, CpnColourSet?>>,
) : CpnColourSet(name) {
    private val index: Map<String, Int> = alternatives.withIndex().associate { (i, alt) -> alt.first to i }

    init {
        if (alternatives.isEmpty()) throw ColourSetException("$name: a union needs at least one alternative")
        if (index.size != alternatives.size) throw ColourSetException("$name: duplicate union constructors")
    }

    private val sizes: List<Long?> = alternatives.map { (_, cs) -> if (cs == null) 1L else cs.size }

    override val size: Long? =
        sizes.fold(0L as Long?) { acc, n -> if (acc == null || n == null || acc > Long.MAX_VALUE - n) null else acc + n }

    override fun contains(value: Any?): Boolean {
        if (value !is MlCon) return false
        val i = index[value.name] ?: return false
        val cs = alternatives[i].second
        return if (cs == null) !value.hasArgument else value.hasArgument && value.argument in cs
    }

    override fun ord(colour: Any?): Long {
        requireSize("ord")
        requireMember(colour)
        val con = colour as MlCon
        val i = index.getValue(con.name)
        val offset = sizes.take(i).sumOf { it!! }
        return offset + (alternatives[i].second?.ord(con.argument) ?: 0)
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        var rest = index
        for ((i, alt) in alternatives.withIndex()) {
            val n = sizes[i]!!
            if (rest < n) return alt.second?.let { MlCon(alt.first, it.col(rest)) } ?: MlCon(alt.first)
            rest -= n
        }
        error("unreachable")
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int {
        val x = a as MlCon
        val y = b as MlCon
        val i = index.getValue(x.name)
        val j = index.getValue(y.name)
        if (i != j) return i.compareTo(j)
        return alternatives[i].second?.compare(x.argument, y.argument) ?: 0
    }

    override fun format(value: Any?): String {
        val con = value as MlCon
        val cs = alternatives[index.getValue(con.name)].second ?: return con.name
        return formatConstructed(con.name, cs.format(con.argument))
    }
}

/**
 * `list E [with low..high]`: colours are Kotlin lists. Finite when [element] is finite and the
 * length is bounded; then ordered by length first, then lexicographically, so [ord] follows [order].
 */
public class ListColourSet(
    name: String,
    public val element: CpnColourSet,
    public val lengths: IntRange? = null,
) : CpnColourSet(name) {
    init {
        if (lengths != null &&
            (lengths.isEmpty() || lengths.first < 0)
        ) {
            throw ColourSetException("$name: invalid length range ${lengths.first}..${lengths.last}")
        }
    }

    /** Number of lists of each allowed length, or null if not finite. */
    private val countsByLength: List<Long>? =
        run {
            val k = element.size ?: return@run null
            val range = lengths ?: return@run null
            range.map { length -> productSize(List(length) { k }) ?: return@run null }
        }

    override val size: Long? =
        countsByLength?.fold(0L as Long?) { acc, n ->
            if (acc == null ||
                acc > Long.MAX_VALUE - n
            ) {
                null
            } else {
                acc + n
            }
        }

    override fun contains(value: Any?): Boolean =
        value is List<*> && (lengths == null || value.size in lengths) && value.all { it in element }

    override fun ord(colour: Any?): Long {
        requireSize("ord")
        requireMember(colour)
        val list = colour as List<*>
        val offset = countsByLength!!.take(list.size - lengths!!.first).sum()
        return offset + list.fold(0L) { acc, v -> acc * element.size!! + element.ord(v) }
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        var rest = index
        var length = lengths!!.first
        for (count in countsByLength!!) {
            if (rest < count) break
            rest -= count
            length++
        }
        val k = element.size!!
        val items = arrayOfNulls<Any?>(length)
        for (i in (0 until length).reversed()) {
            items[i] = element.col(rest % k)
            rest /= k
        }
        return items.toList()
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int {
        val x = a as List<*>
        val y = b as List<*>
        if (x.size != y.size) return x.size.compareTo(y.size)
        for (i in x.indices) {
            val c = element.compare(x[i], y[i])
            if (c != 0) return c
        }
        return 0
    }

    override fun format(value: Any?): String = (value as List<*>).joinToString(",", "[", "]") { element.format(it) }
}

/**
 * `subset B by p` or `subset B with [c1, c2]`. Ordered as in [base]. With `by`, the subset is
 * enumerable only if [base] is, and is materialized once on first use.
 */
public class SubsetColourSet(
    name: String,
    public val base: CpnColourSet,
    private val predicate: ((Any?) -> Boolean)? = null,
    members: List<Any?>? = null,
) : CpnColourSet(name) {
    init {
        if ((predicate == null) == (members == null)) throw ColourSetException("$name: a subset needs exactly one of 'by' and 'with'")
        members?.forEach { if (it !in base) throw ColourSetException("$name: ${base.format(it)} is not a colour of ${base.name}") }
    }

    private val listed: Set<Any?>? = members?.toSet()

    private val enumerated: List<Any?>? by lazy {
        when {
            listed != null -> listed.sortedWith(base.order)
            base.isFinite -> base.values?.filter { predicate!!(it) }
            else -> null
        }
    }

    override val size: Long? get() = enumerated?.size?.toLong()

    override fun contains(value: Any?): Boolean = value in base && (listed?.contains(value) ?: predicate!!(value))

    override fun ord(colour: Any?): Long {
        requireSize("ord")
        requireMember(colour)
        return enumerated!!.indexOf(colour).toLong()
    }

    override fun col(index: Long): Any? {
        requireIndex(index)
        return enumerated!![index.toInt()]
    }

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = base.compare(a, b)

    override fun format(value: Any?): String = base.format(value)
}

/** `colset NO2 = NO;`: the same colours under another name. */
public class AliasColourSet(
    name: String,
    public val target: CpnColourSet,
) : CpnColourSet(name) {
    override val size: Long? get() = target.size

    override fun contains(value: Any?): Boolean = target.contains(value)

    override fun ord(colour: Any?): Long = target.ord(colour)

    override fun col(index: Long): Any? = target.col(index)

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = target.compare(a, b)

    override fun format(value: Any?): String = target.format(value)
}

/** Wraps a colour set declared `timed`; colours are the same, tokens carry time stamps (T3.2). */
public class TimedColourSet(
    public val untimed: CpnColourSet,
) : CpnColourSet(untimed.name) {
    override val timed: Boolean get() = true

    override val size: Long? get() = untimed.size

    override fun contains(value: Any?): Boolean = untimed.contains(value)

    override fun ord(colour: Any?): Long = untimed.ord(colour)

    override fun col(index: Long): Any? = untimed.col(index)

    override fun compare(
        a: Any?,
        b: Any?,
    ): Int = untimed.compare(a, b)

    override fun format(value: Any?): String = untimed.format(value)
}
