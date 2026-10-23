package no.hvl.cpn.io

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.Variable

/**
 * The expression language of the temporary JSON format: just enough for I1 example nets. It is
 * replaced by CPN ML inscriptions parsed by `cpn-lang` in I2.
 *
 * Values are `Int`, `String`, `Boolean`, `Unit` and `Pair`.
 */
internal sealed interface Expr {
    val variables: Set<Variable<*>>

    fun eval(binding: Binding): Any?
}

private class Literal(
    val value: Any?,
) : Expr {
    override val variables: Set<Variable<*>> = emptySet()

    override fun eval(binding: Binding): Any? = value
}

private class VarRef(
    val variable: Variable<Any?>,
) : Expr {
    override val variables: Set<Variable<*>> = setOf(variable)

    override fun eval(binding: Binding): Any? = binding[variable]
}

private class If(
    val condition: Expr,
    val then: Expr,
    val otherwise: Expr,
) : Expr {
    override val variables: Set<Variable<*>> = condition.variables + then.variables + otherwise.variables

    override fun eval(binding: Binding): Any? = if (condition.eval(binding) as Boolean) then.eval(binding) else otherwise.eval(binding)
}

private class Tuple(
    val first: Expr,
    val second: Expr,
) : Expr {
    override val variables: Set<Variable<*>> = first.variables + second.variables

    override fun eval(binding: Binding): Any? = first.eval(binding) to second.eval(binding)
}

private class Op(
    val name: String,
    val args: List<Expr>,
    val path: String,
) : Expr {
    override val variables: Set<Variable<*>> = args.flatMap { it.variables }.toSet()

    override fun eval(binding: Binding): Any? {
        // andalso / orelse short-circuit, like CPN ML.
        if (name == "andalso") return args.all { it.eval(binding) as Boolean }
        if (name == "orelse") return args.any { it.eval(binding) as Boolean }
        val values = args.map { it.eval(binding) }
        return try {
            apply(values)
        } catch (e: ClassCastException) {
            throw ModelFormatException(path, "operator '$name' cannot be applied to $values")
        }
    }

    private fun apply(v: List<Any?>): Any? =
        when (name) {
            "+" -> v[0] as Int + v[1] as Int
            "-" -> v[0] as Int - v[1] as Int
            "*" -> v[0] as Int * v[1] as Int
            "div" -> (v[0] as Int).floorDiv(v[1] as Int)
            "mod" -> (v[0] as Int).mod(v[1] as Int)
            "^" -> v[0] as String + v[1] as String
            "=" -> v[0] == v[1]
            "<>" -> v[0] != v[1]
            "<" -> compare(v) < 0
            "<=" -> compare(v) <= 0
            ">" -> compare(v) > 0
            ">=" -> compare(v) >= 0
            "not" -> !(v[0] as Boolean)
            else -> error("unreachable")
        }

    @Suppress("UNCHECKED_CAST")
    private fun compare(v: List<Any?>): Int = (v[0] as Comparable<Any?>).compareTo(v[1])

    companion object {
        val ARITY: Map<String, Int> =
            listOf("+", "-", "*", "div", "mod", "^", "=", "<>", "<", "<=", ">", ">=", "andalso", "orelse").associateWith { 2 } +
                ("not" to 1)
    }
}

/** Parses an expression. Bare JSON values are literals: numbers, strings, booleans, and `null` for `()`. */
internal fun parseExpr(
    json: JsonElement,
    path: String,
    variables: Map<String, Variable<Any?>>,
): Expr =
    when (json) {
        is JsonNull -> Literal(Unit)
        is JsonPrimitive -> Literal(primitiveValue(json, path))
        is JsonArray -> throw ModelFormatException(path, "arrays are not expressions; use {\"tuple\": [a, b]}")
        is JsonObject -> {
            fun sub(key: String) = parseExpr(json.required(key, path), "$path.$key", variables)
            when {
                "var" in json -> {
                    val name = json.string("var", path)
                    VarRef(variables[name] ?: throw ModelFormatException("$path.var", "undeclared variable '$name'"))
                }
                "if" in json -> If(sub("if"), sub("then"), sub("else"))
                "tuple" in json -> {
                    val parts = json.array("tuple", path)
                    if (parts.size != 2) throw ModelFormatException("$path.tuple", "only pairs are supported, got ${parts.size} elements")
                    Tuple(parseExpr(parts[0], "$path.tuple[0]", variables), parseExpr(parts[1], "$path.tuple[1]", variables))
                }
                "op" in json -> {
                    val name = json.string("op", path)
                    val arity =
                        Op.ARITY[name]
                            ?: throw ModelFormatException("$path.op", "unknown operator '$name'; expected one of ${Op.ARITY.keys}")
                    val args = json.array("args", path)
                    if (args.size != arity) throw ModelFormatException("$path.args", "'$name' takes $arity arguments, got ${args.size}")
                    Op(name, args.mapIndexed { i, arg -> parseExpr(arg, "$path.args[$i]", variables) }, path)
                }
                else -> throw ModelFormatException(path, "expected an expression object with one of: var, if, tuple, op")
            }
        }
    }

internal fun primitiveValue(
    json: JsonPrimitive,
    path: String,
): Any =
    when {
        json.isString -> json.content
        json.booleanOrNull != null -> json.booleanOrNull!!
        json.intOrNull != null -> json.intOrNull!!
        else -> throw ModelFormatException(path, "unsupported value ${json.content}; only Int, String and Boolean literals are allowed")
    }
