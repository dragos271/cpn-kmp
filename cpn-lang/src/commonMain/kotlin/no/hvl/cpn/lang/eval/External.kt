package no.hvl.cpn.lang.eval

import no.hvl.cpn.core.Multiset
import no.hvl.cpn.lang.ast.FunBind
import no.hvl.cpn.lang.ast.TupleType
import no.hvl.cpn.lang.ast.TypeCon
import no.hvl.cpn.lang.ast.TypeExpr
import no.hvl.cpn.lang.colour.AliasColourSet
import no.hvl.cpn.lang.colour.BoolColourSet
import no.hvl.cpn.lang.colour.ColourSetEnvironment
import no.hvl.cpn.lang.colour.CpnColourSet
import no.hvl.cpn.lang.colour.EnumColourSet
import no.hvl.cpn.lang.colour.IndexColourSet
import no.hvl.cpn.lang.colour.IntColourSet
import no.hvl.cpn.lang.colour.ListColourSet
import no.hvl.cpn.lang.colour.LongColourSet
import no.hvl.cpn.lang.colour.ProductColourSet
import no.hvl.cpn.lang.colour.RealColourSet
import no.hvl.cpn.lang.colour.RecordColourSet
import no.hvl.cpn.lang.colour.StringColourSet
import no.hvl.cpn.lang.colour.SubsetColourSet
import no.hvl.cpn.lang.colour.TimedColourSet
import no.hvl.cpn.lang.colour.UnionColourSet
import no.hvl.cpn.lang.colour.UnitColourSet
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.lang.value.MlRecord
import no.hvl.cpn.lang.value.MlTuple

/**
 * A function implemented outside CPN ML (F13), e.g. in Python. It receives and returns *foreign*
 * values, the JSON data model: `Long`, `Double`, `String`, `Boolean`, `null`, `List` and
 * `Map<String, Any?>`. The interpreter converts CPN ML values to and from them ([toForeign],
 * [fromForeign]).
 */
public fun interface ExternalFunction {
    /** One foreign value per curried argument; `fun f (a, b)` has one argument, a list of two. */
    public fun call(arguments: List<Any?>): Any?
}

/**
 * Decides which declared functions are implemented outside CPN ML. Consulted for every `fun` while
 * declarations run; a function it returns replaces the CPN ML body, which stays as the fallback
 * CPN Tools uses.
 */
public fun interface ExternalFunctions {
    public fun lookup(name: String): ExternalFunction?
}

/** A failure in an external function: its exception, or a result that does not fit the declared type. */
public class ExternalFunctionException(
    message: String,
) : RuntimeException(message)

/**
 * A CPN ML value as a foreign (JSON) value. Enumeration constants become strings; other
 * constructors `{"tag": name, "value": argument}`; tuples and multisets lists; records maps; reals
 * doubles; unit null.
 */
public fun toForeign(value: Any?): Any? =
    when (value) {
        null, Unit -> null
        is Boolean, is String -> value
        is Int -> value.toLong()
        is Long -> value
        is MlReal -> value.value
        is MlCon -> if (value.hasArgument) mapOf("tag" to value.name, "value" to toForeign(value.argument)) else value.name
        is MlTuple -> value.elements.map(::toForeign)
        is MlRecord -> value.fields.mapValues { toForeign(it.value) }
        is List<*> -> value.map(::toForeign)
        is Multiset<*> -> asList(value).map(::toForeign)
        else -> throw ExternalFunctionException("cannot pass ${value::class.simpleName} to an external function")
    }

/** The CPN ML value of [foreign] at [type], the declared result type of an external function. */
internal fun fromForeign(
    foreign: Any?,
    type: TypeExpr,
    colourSets: ColourSetEnvironment,
): Any? =
    when (type) {
        is TupleType -> {
            val list = foreign as? List<*> ?: mismatch(foreign, "a list for a tuple")
            if (list.size != type.elements.size) mismatch(foreign, "${type.elements.size} elements")
            MlTuple(type.elements.indices.map { fromForeign(list[it], type.elements[it], colourSets) })
        }
        is TypeCon -> {
            val name = type.name.text
            when {
                name == "list" && type.args.size == 1 ->
                    (foreign as? List<*> ?: mismatch(foreign, "a list")).map { fromForeign(it, type.args[0], colourSets) }
                name == "int" -> int(foreign)
                name == "intinf" -> int(foreign).toLong()
                name == "real" -> MlReal(real(foreign))
                name == "string" -> foreign as? String ?: mismatch(foreign, "a string")
                name == "bool" -> foreign as? Boolean ?: mismatch(foreign, "true or false")
                name == "unit" -> Unit
                else -> {
                    val cs =
                        colourSets[name] ?: throw ExternalFunctionException("result type $name is not supported for external functions")
                    fromForeign(foreign, cs)
                }
            }
        }
        else -> throw ExternalFunctionException("result type $type is not supported for external functions")
    }

/** The colour of [cs] that [foreign] stands for; checked to be in [cs]. */
internal fun fromForeign(
    foreign: Any?,
    cs: CpnColourSet,
): Any? {
    val value =
        when (cs) {
            is AliasColourSet -> fromForeign(foreign, cs.target)
            is TimedColourSet -> fromForeign(foreign, cs.untimed)
            is SubsetColourSet -> fromForeign(foreign, cs.base)
            is UnitColourSet -> Unit
            is BoolColourSet -> foreign as? Boolean ?: mismatch(foreign, "true or false")
            is IntColourSet -> int(foreign)
            is LongColourSet -> int(foreign).toLong()
            is RealColourSet -> MlReal(real(foreign))
            is StringColourSet -> foreign as? String ?: mismatch(foreign, "a string")
            is EnumColourSet -> MlCon(foreign as? String ?: mismatch(foreign, "one of ${cs.constants}"))
            is IndexColourSet -> {
                val (tag, arg) = tagged(foreign) ?: (cs.constructor to foreign)
                if (tag != cs.constructor) mismatch(foreign, "${cs.constructor}(n)")
                MlCon(cs.constructor, int(arg), hasArgument = true)
            }
            is ProductColourSet -> {
                val list = foreign as? List<*> ?: mismatch(foreign, "a list of ${cs.components.size}")
                if (list.size != cs.components.size) mismatch(foreign, "${cs.components.size} elements")
                MlTuple(cs.components.indices.map { fromForeign(list[it], cs.components[it]) })
            }
            is RecordColourSet -> {
                val map = foreign as? Map<*, *> ?: mismatch(foreign, "an object with ${cs.fields.map { it.first }}")
                MlRecord(
                    cs.fields.associate { (field, fcs) ->
                        field to fromForeign(map[field] ?: mismatch(foreign, "field '$field'"), fcs)
                    },
                )
            }
            is UnionColourSet -> {
                val (tag, arg) = tagged(foreign) ?: ((foreign as? String ?: mismatch(foreign, "a tag")) to null)
                val alternative =
                    cs.alternatives.firstOrNull { it.first == tag } ?: mismatch(foreign, "one of ${cs.alternatives.map { it.first }}")
                val argumentSet = alternative.second
                if (argumentSet == null) MlCon(tag) else MlCon(tag, fromForeign(arg, argumentSet), hasArgument = true)
            }
            is ListColourSet -> (foreign as? List<*> ?: mismatch(foreign, "a list")).map { fromForeign(it, cs.element) }
            else -> throw ExternalFunctionException("colour set ${cs.name} is not supported for external functions")
        }
    if (cs !is AliasColourSet && cs !is TimedColourSet && value !in cs) mismatch(foreign, "a colour of ${cs.name}")
    return value
}

private fun tagged(foreign: Any?): Pair<String, Any?>? {
    val map = foreign as? Map<*, *> ?: return null
    val tag = map["tag"] as? String ?: return null
    return tag to map["value"]
}

private fun int(foreign: Any?): Int =
    when (foreign) {
        is Int -> foreign
        is Long -> if (foreign in Int.MIN_VALUE..Int.MAX_VALUE) foreign.toInt() else mismatch(foreign, "an int")
        is Double ->
            if (foreign == kotlin.math.floor(foreign) &&
                kotlin.math.abs(foreign) < Int.MAX_VALUE
            ) {
                foreign.toInt()
            } else {
                mismatch(foreign, "a whole number")
            }
        else -> mismatch(foreign, "an int")
    }

private fun real(foreign: Any?): Double = (foreign as? Number)?.toDouble() ?: mismatch(foreign, "a number")

private fun mismatch(
    foreign: Any?,
    expected: String,
): Nothing = throw ExternalFunctionException("returned $foreign, expected $expected")

/** The number of curried arguments of [bind] and its declared result type, for an external function. */
internal fun externalSignature(bind: FunBind): Pair<Int, TypeExpr?> {
    val clause = bind.clauses.first()
    return clause.arguments.size to clause.resultType
}
