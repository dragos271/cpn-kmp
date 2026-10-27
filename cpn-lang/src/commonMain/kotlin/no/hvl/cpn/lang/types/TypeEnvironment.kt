package no.hvl.cpn.lang.types

import no.hvl.cpn.lang.ast.FunctionType
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.TupleType
import no.hvl.cpn.lang.ast.TypeExpr
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.ast.RecordType as RecordTypeExpr
import no.hvl.cpn.lang.ast.TypeCon as TypeConExpr
import no.hvl.cpn.lang.ast.TypeVar as TypeVarExpr

public enum class ValueKind { VALUE, CONSTRUCTOR, EXCEPTION }

/** What a value identifier means: its type scheme, and whether it is a constructor (usable in patterns). */
public class ValueBinding internal constructor(
    public val scheme: Scheme,
    public val kind: ValueKind,
    public val hasArgument: Boolean = false,
)

/** A type name: an abbreviation (`type t = int * int`, `colset NO = int`) or a type constructor (`list`, a datatype). */
internal sealed class TypeBinding {
    class Abbreviation(
        val params: List<TypeVar>,
        val body: Type,
    ) : TypeBinding()

    class Constructor(
        val name: String,
        val arity: Int,
    ) : TypeBinding()
}

/**
 * Whether values of a type constructor can be compared with `=`: `ALWAYS` (ref), `NEVER` (real,
 * exn), or `IF_ARGUMENTS` (list, option and most datatypes).
 */
internal enum class Equality { ALWAYS, NEVER, IF_ARGUMENTS }

/**
 * The static environment: values (including constructors), type names, and the declared colour
 * sets. Immutable: declarations produce extended environments, so `let` and `local` scoping is free.
 * Qualified names (`List.map`, `Int.toString`) are stored under their full text.
 */
public class TypeEnvironment internal constructor(
    internal val values: Map<String, ValueBinding>,
    internal val types: Map<String, TypeBinding>,
    internal val colourSets: Map<String, Type>,
    /** Equality of type constructors; shared, since datatype names are unique per checker. */
    internal val equality: MutableMap<String, Equality>,
) {
    internal fun withValue(
        name: String,
        binding: ValueBinding,
    ) = TypeEnvironment(values + (name to binding), types, colourSets, equality)

    internal fun withType(
        name: String,
        binding: TypeBinding,
    ) = TypeEnvironment(values, types + (name to binding), colourSets, equality)

    internal fun withColourSet(
        name: String,
        type: Type,
    ) = TypeEnvironment(values, types, colourSets + (name to type), equality)

    public fun value(name: String): ValueBinding? = values[name]

    /** The type of the declared colour set [name] (`colset NO = int` gives int), or null. */
    public fun colourSet(name: String): Type? = colourSets[name]

    public val colourSetNames: Set<String> get() = colourSets.keys

    public companion object {
        /** The SML Basis subset and CPN ML library that inscriptions can use. */
        public fun basis(): TypeEnvironment = Basis.environment
    }
}

/** Builds Type from a type expression, given the type names in scope and the type variables seen so far. */
internal class TypeConverter(
    private val environment: TypeEnvironment,
    private val variables: MutableMap<String, TypeVar>,
    private val newVariable: (TypeVarExpr) -> TypeVar,
    private val error: (TypeExpr, String) -> Nothing,
) {
    fun convert(expr: TypeExpr): Type =
        when (expr) {
            is TypeVarExpr -> variables.getOrPut(expr.name) { newVariable(expr) }
            is TupleType -> tupleOf(expr.elements.map(::convert))
            is RecordTypeExpr -> RecordType(expr.fields.associate { it.label.name to convert(it.type) })
            is FunctionType -> FunType(convert(expr.parameter), convert(expr.result))
            is TypeConExpr -> {
                val args = expr.args.map(::convert)
                when (val binding = environment.types[expr.name.text]) {
                    null -> error(expr, "unknown type ${expr.name.text}")
                    is TypeBinding.Constructor -> {
                        if (args.size !=
                            binding.arity
                        ) {
                            error(expr, "${expr.name.text} takes ${binding.arity} type argument(s), not ${args.size}")
                        }
                        TypeCon(binding.name, args)
                    }
                    is TypeBinding.Abbreviation -> {
                        if (args.size !=
                            binding.params.size
                        ) {
                            error(expr, "${expr.name.text} takes ${binding.params.size} type argument(s), not ${args.size}")
                        }
                        substitute(binding.body, binding.params.zip(args).toMap())
                    }
                }
            }
        }
}

/** [type] with type variables replaced as given (for expanding abbreviations). */
internal fun substitute(
    type: Type,
    replacements: Map<TypeVar, Type>,
): Type =
    when (val t = prune(type)) {
        is TypeVar -> replacements[t] ?: t
        is TypeCon -> TypeCon(t.name, t.args.map { substitute(it, replacements) })
        is RecordType -> RecordType(t.fields.mapValues { substitute(it.value, replacements) })
        is FunType -> FunType(substitute(t.param, replacements), substitute(t.result, replacements))
    }

/**
 * The initial environment. Types are written in SML syntax and parsed; `'num` stands for int or real
 * (`+`, `~`, `abs`) and `'numtxt` for int, real, string or char (`<`), as in SML's default
 * overloading. `''a` is an equality type variable.
 */
internal object Basis {
    private val primitive =
        mapOf(
            "int" to Equality.IF_ARGUMENTS,
            "intinf" to Equality.IF_ARGUMENTS,
            "real" to Equality.NEVER,
            "string" to Equality.IF_ARGUMENTS,
            "char" to Equality.IF_ARGUMENTS,
            "bool" to Equality.IF_ARGUMENTS,
            "exn" to Equality.NEVER,
            "list" to Equality.IF_ARGUMENTS,
            "option" to Equality.IF_ARGUMENTS,
            "ref" to Equality.ALWAYS,
            "ms" to Equality.IF_ARGUMENTS,
        )
    private val arity = mapOf("list" to 1, "option" to 1, "ref" to 1, "ms" to 1)

    /** name : type, one per line; `con` marks constructors, `exn` exceptions. */
    private val VALUES =
        """
        = : ''a * ''a -> bool
        <> : ''a * ''a -> bool
        + : 'num * 'num -> 'num
        - : 'num * 'num -> 'num
        * : 'num * 'num -> 'num
        ~ : 'num -> 'num
        abs : 'num -> 'num
        / : real * real -> real
        div : int * int -> int
        mod : int * int -> int
        < : 'numtxt * 'numtxt -> bool
        > : 'numtxt * 'numtxt -> bool
        <= : 'numtxt * 'numtxt -> bool
        >= : 'numtxt * 'numtxt -> bool
        ^ : string * string -> string
        @ : 'a list * 'a list -> 'a list
        not : bool -> bool
        o : ('b -> 'c) * ('a -> 'b) -> 'a -> 'c
        before : 'a * unit -> 'a
        ignore : 'a -> unit
        ! : 'a ref -> 'a
        := : 'a ref * 'a -> unit
        print : string -> unit
        hd : 'a list -> 'a
        tl : 'a list -> 'a list
        null : 'a list -> bool
        length : 'a list -> int
        rev : 'a list -> 'a list
        map : ('a -> 'b) -> 'a list -> 'b list
        app : ('a -> unit) -> 'a list -> unit
        foldl : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
        foldr : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
        explode : string -> char list
        implode : char list -> string
        concat : string list -> string
        substring : string * int * int -> string
        str : char -> string
        ord : char -> int
        chr : int -> char
        real : int -> real
        floor : real -> int
        ceil : real -> int
        round : real -> int
        trunc : real -> int
        valOf : 'a option -> 'a
        isSome : 'a option -> bool
        getOpt : 'a option * 'a -> 'a
        List.map : ('a -> 'b) -> 'a list -> 'b list
        List.filter : ('a -> bool) -> 'a list -> 'a list
        List.exists : ('a -> bool) -> 'a list -> bool
        List.all : ('a -> bool) -> 'a list -> bool
        List.find : ('a -> bool) -> 'a list -> 'a option
        List.partition : ('a -> bool) -> 'a list -> 'a list * 'a list
        List.foldl : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
        List.foldr : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
        List.app : ('a -> unit) -> 'a list -> unit
        List.length : 'a list -> int
        List.rev : 'a list -> 'a list
        List.null : 'a list -> bool
        List.hd : 'a list -> 'a
        List.tl : 'a list -> 'a list
        List.last : 'a list -> 'a
        List.nth : 'a list * int -> 'a
        List.take : 'a list * int -> 'a list
        List.drop : 'a list * int -> 'a list
        List.concat : 'a list list -> 'a list
        List.tabulate : int * (int -> 'a) -> 'a list
        String.size : string -> int
        String.sub : string * int -> char
        String.substring : string * int * int -> string
        String.concat : string list -> string
        String.explode : string -> char list
        String.implode : char list -> string
        String.isPrefix : string -> string -> bool
        Int.toString : int -> string
        Int.fromString : string -> int option
        Int.max : int * int -> int
        Int.min : int * int -> int
        Int.abs : int -> int
        Real.toString : real -> string
        Real.fromInt : int -> real
        Real.floor : real -> int
        Real.round : real -> int
        Real.max : real * real -> real
        Real.min : real * real -> real
        Math.sqrt : real -> real
        Math.exp : real -> real
        Math.ln : real -> real
        Math.sin : real -> real
        Math.cos : real -> real
        Math.pow : real * real -> real
        Math.pi : real
        Bool.toString : bool -> string
        Char.ord : char -> int
        Char.chr : int -> char
        Char.toString : char -> string
        Char.isDigit : char -> bool
        Char.isAlpha : char -> bool
        Option.valOf : 'a option -> 'a
        Option.isSome : 'a option -> bool
        Option.getOpt : 'a option * 'a -> 'a
        Option.map : ('a -> 'b) -> 'a option -> 'b option
        ` : int * 'a -> 'a ms
        ++ : 'a ms * 'a ms -> 'a ms
        -- : 'a ms * 'a ms -> 'a ms
        ** : int * 'a ms -> 'a ms
        == : ''a ms * ''a ms -> bool
        <><> : ''a ms * ''a ms -> bool
        <<= : ''a ms * ''a ms -> bool
        >>= : ''a ms * ''a ms -> bool
        << : ''a ms * ''a ms -> bool
        >> : ''a ms * ''a ms -> bool
        empty : 'a ms
        size : 'a ms -> int
        cf : ''a * ''a ms -> int
        random : 'a ms -> 'a
        filter : ('a -> bool) -> 'a ms -> 'a ms
        ext_col : ('a -> 'b) -> 'a ms -> 'b ms
        ms_to_col : 'a ms -> 'a
        @+ : 'a * int -> 'a
        time : unit -> int
        intTime : unit -> int
        discrete : int * int -> int
        uniform : real * real -> real
        normal : real * real -> real
        exponential : real -> real
        bernoulli : real -> int
        poisson : real -> int
        con true : bool
        con false : bool
        con nil : 'a list
        con :: : 'a * 'a list -> 'a list
        con SOME : 'a -> 'a option
        con NONE : 'a option
        con ref : 'a -> 'a ref
        exn Fail : string -> exn
        exn Empty : exn
        exn Subscript : exn
        exn Div : exn
        exn Overflow : exn
        exn Match : exn
        exn Bind : exn
        exn Option : exn
        exn Size : exn
        exn Domain : exn
        exn Chr : exn
        """.trimIndent()

    val environment: TypeEnvironment by lazy { build() }

    private fun build(): TypeEnvironment {
        val types = HashMap<String, TypeBinding>()
        for (name in primitive.keys) types[name] = TypeBinding.Constructor(name, arity[name] ?: 0)
        types["unit"] = TypeBinding.Abbreviation(emptyList(), UNIT)
        // The standard declarations of a new CPN Tools net: colset UNIT = unit; colset BOOL = bool; ...
        val standardColourSets =
            mapOf(
                "UNIT" to UNIT,
                "BOOL" to BOOL,
                "INT" to INT,
                "INTINF" to INT,
                "TIME" to INT,
                "REAL" to REAL,
                "STRING" to STRING,
            )
        for ((name, type) in standardColourSets) types[name] = TypeBinding.Abbreviation(emptyList(), type)
        // Starts with only the type names, enough to read the value types below.
        var env = TypeEnvironment(emptyMap(), types, standardColourSets, primitive.toMutableMap())
        val parser = CpnMlParser()
        val unifier = Unifier { Equality.IF_ARGUMENTS }
        for (line in VALUES.lines()) {
            val (declaration, typeText) = line.split(" : ", limit = 2)
            val words = declaration.split(" ")
            val kind =
                when (words.first()) {
                    "con" -> ValueKind.CONSTRUCTOR
                    "exn" -> ValueKind.EXCEPTION
                    else -> ValueKind.VALUE
                }
            val name = if (kind == ValueKind.VALUE) declaration else words[1]
            val variables = HashMap<String, TypeVar>()
            val type =
                TypeConverter(env, variables, { v ->
                    unifier.fresh(
                        1,
                        when {
                            v.name == "'num" -> Kind.Overloaded(setOf("int", "real"), "int")
                            v.name == "'numtxt" -> Kind.Overloaded(setOf("int", "real", "string", "char"), "int")
                            v.isEquality -> Kind.Equality
                            else -> Kind.Free
                        },
                    )
                }, { _, message -> error("basis type of $name: $message") }).convert(parser.parseType(SourceText(name, typeText)))
            // Every variable is quantified, overloaded ones included, so each use gets fresh ones.
            val scheme = Scheme(variables.values.toList(), type)
            env = env.withValue(name, ValueBinding(scheme, kind, hasArgument = prune(type) is FunType && kind != ValueKind.VALUE))
        }
        return env
    }
}
