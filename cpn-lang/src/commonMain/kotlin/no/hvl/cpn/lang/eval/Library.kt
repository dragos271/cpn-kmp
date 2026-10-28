package no.hvl.cpn.lang.eval

import no.hvl.cpn.core.Multiset
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.lang.value.MlTuple
import no.hvl.cpn.lang.value.formatInt
import no.hvl.cpn.lang.value.formatReal
import no.hvl.cpn.lang.value.formatValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The run-time side of the basis the type checker knows (types/TypeEnvironment.kt, `Basis`): the
 * SML Basis subset and the CPN ML library. Every name there has a value here; a test checks that.
 */
internal object Library {
    fun values(interpreter: Interpreter): Map<String, Any?> {
        val v = LinkedHashMap<String, Any?>()

        // -- Constructors and exceptions
        v["true"] = ConstantConstructor("true", true)
        v["false"] = ConstantConstructor("false", false)
        v["nil"] = ConstantConstructor("nil", emptyList<Any?>())
        v["::"] = ConsConstructor
        v["SOME"] = DataConstructor("SOME")
        v["NONE"] = ConstantConstructor("NONE", NONE)
        v["ref"] = RefConstructor
        v["Fail"] = DataConstructor("Fail")
        for (name in listOf("Empty", "Subscript", "Div", "Overflow", "Match", "Bind", "Option", "Size", "Domain", "Chr")) {
            v[name] = ConstantConstructor(name, MlCon(name))
        }

        // -- Overloaded arithmetic and comparison
        v["="] = fn2 { a, b -> valuesEqual(a, b) }
        v["<>"] = fn2 { a, b -> !valuesEqual(a, b) }
        v["+"] = fn2 { a, b -> arithmetic(a, b, Long::plus, Double::plus) }
        v["-"] = fn2 { a, b -> arithmetic(a, b, Long::minus, Double::minus) }
        v["*"] = fn2 { a, b -> arithmetic(a, b, Long::times, Double::times) }
        v["~"] = fn { negate(it) }
        v["abs"] = fn { if (isNegative(it)) negate(it) else it }
        v["/"] = fn2 { a, b -> MlReal(real(a) / real(b)) }
        v["div"] =
            fn2 { a, b ->
                if (isZero(b)) raise("Div")
                integer(a, b, (a as Number).toLong().floorDiv((b as Number).toLong()))
            }
        v["mod"] =
            fn2 { a, b ->
                if (isZero(b)) raise("Div")
                integer(a, b, (a as Number).toLong().mod((b as Number).toLong()))
            }
        v["<"] = fn2 { a, b -> compareValues(a, b) < 0 }
        v[">"] = fn2 { a, b -> compareValues(a, b) > 0 }
        v["<="] = fn2 { a, b -> compareValues(a, b) <= 0 }
        v[">="] = fn2 { a, b -> compareValues(a, b) >= 0 }

        // -- General
        v["^"] = fn2 { a, b -> (a as String) + (b as String) }
        v["@"] = fn2 { a, b -> list(a) + list(b) }
        v["not"] = fn { !(it as Boolean) }
        v["o"] = fn2 { f, g -> fn { x -> interpreter.call(f, interpreter.call(g, x)) } }
        v["before"] = fn2 { a, _ -> a }
        v["ignore"] = fn { Unit }
        v["!"] = fn { (it as MlRef).value }
        v[":="] =
            fn2 { r, value ->
                (r as MlRef).value = value
                Unit
            }
        v["print"] =
            fn {
                interpreter.output.append(it as String)
                Unit
            }

        // -- Lists
        val hd = fn { list(it).let { l -> if (l.isEmpty()) raise("Empty") else l.first() } }
        val tl = fn { list(it).let { l -> if (l.isEmpty()) raise("Empty") else l.subList(1, l.size) } }
        val nullFn = fn { list(it).isEmpty() }
        val length = fn { list(it).size }
        val rev = fn { list(it).reversed() }
        val map = curried { f, l -> list(l).map { interpreter.call(f, it) } }
        val app =
            curried { f, l ->
                list(l).forEach { interpreter.call(f, it) }
                Unit
            }
        val foldl = curried3 { f, init, l -> list(l).fold(init) { acc, x -> interpreter.call(f, pair(x, acc)) } }
        val foldr = curried3 { f, init, l -> list(l).foldRight(init) { x, acc -> interpreter.call(f, pair(x, acc)) } }
        v["hd"] = hd
        v["tl"] = tl
        v["null"] = nullFn
        v["length"] = length
        v["rev"] = rev
        v["map"] = map
        v["app"] = app
        v["foldl"] = foldl
        v["foldr"] = foldr
        v["List.map"] = map
        v["List.app"] = app
        v["List.foldl"] = foldl
        v["List.foldr"] = foldr
        v["List.length"] = length
        v["List.rev"] = rev
        v["List.null"] = nullFn
        v["List.hd"] = hd
        v["List.tl"] = tl
        v["List.filter"] = curried { p, l -> list(l).filter { interpreter.call(p, it) as Boolean } }
        v["List.exists"] = curried { p, l -> list(l).any { interpreter.call(p, it) as Boolean } }
        v["List.all"] = curried { p, l -> list(l).all { interpreter.call(p, it) as Boolean } }
        // Values are never null (unit is Unit), so null means "not found".
        v["List.find"] = curried { p, l -> list(l).firstOrNull { interpreter.call(p, it) as Boolean }?.let(::some) ?: NONE }
        v["List.partition"] =
            curried { p, l ->
                val (yes, no) = list(l).partition { interpreter.call(p, it) as Boolean }
                pair(yes, no)
            }
        v["List.last"] = fn { list(it).let { l -> if (l.isEmpty()) raise("Empty") else l.last() } }
        v["List.nth"] = fn2 { l, i -> list(l).let { if (i as Int !in it.indices) raise("Subscript") else it[i] } }
        v["List.take"] = fn2 { l, i -> list(l).let { if (i as Int !in 0..it.size) raise("Subscript") else it.subList(0, i) } }
        v["List.drop"] = fn2 { l, i -> list(l).let { if (i as Int !in 0..it.size) raise("Subscript") else it.subList(i, it.size) } }
        v["List.concat"] = fn { list(it).flatMap(::list) }
        v["List.tabulate"] = fn2 { n, f -> if ((n as Int) < 0) raise("Size") else (0 until n).map { interpreter.call(f, it) } }

        // -- Strings and characters
        val explode = fn { (it as String).toList() }
        val implode = fn { list(it).joinToString("") { c -> (c as Char).toString() } }
        val concat = fn { list(it).joinToString("") { s -> s as String } }
        val substring =
            fn3 { s, i, n ->
                val text = s as String
                val start = i as Int
                val count = n as Int
                if (start < 0 || count < 0 || start + count > text.length) raise("Subscript")
                text.substring(start, start + count)
            }
        val ord = fn { (it as Char).code }
        val chr = fn { if (it as Int !in 0..255) raise("Chr") else it.toChar() }
        v["explode"] = explode
        v["implode"] = implode
        v["concat"] = concat
        v["substring"] = substring
        v["str"] = fn { (it as Char).toString() }
        v["ord"] = ord
        v["chr"] = chr
        v["String.size"] = fn { (it as String).length }
        v["String.sub"] = fn2 { s, i -> (s as String).getOrElse(i as Int) { raise("Subscript") } }
        v["String.substring"] = substring
        v["String.concat"] = concat
        v["String.explode"] = explode
        v["String.implode"] = implode
        v["String.isPrefix"] = curried { prefix, s -> (s as String).startsWith(prefix as String) }
        v["Char.ord"] = ord
        v["Char.chr"] = chr
        v["Char.toString"] = fn { (it as Char).toString() }
        v["Char.isDigit"] = fn { it as Char in '0'..'9' }
        v["Char.isAlpha"] = fn { it as Char in 'a'..'z' || it in 'A'..'Z' }

        // -- Numbers
        val toReal = fn { MlReal((it as Number).toDouble()) }
        val floor = fn { toInt(kotlin.math.floor(real(it))) }
        val round = fn { toInt(kotlin.math.round(real(it))) }
        v["real"] = toReal
        v["floor"] = floor
        v["ceil"] = fn { toInt(kotlin.math.ceil(real(it))) }
        v["round"] = round
        v["trunc"] = fn { toInt(kotlin.math.truncate(real(it))) }
        v["Int.toString"] = fn { formatInt((it as Number).toLong()) }
        v["Int.fromString"] = fn { parseInt(it as String) }
        v["Int.max"] = fn2 { a, b -> if (compareValues(a, b) >= 0) a else b }
        v["Int.min"] = fn2 { a, b -> if (compareValues(a, b) <= 0) a else b }
        v["Int.abs"] = fn { if (isNegative(it)) negate(it) else it }
        v["Real.toString"] = fn { formatReal(real(it)) }
        v["Real.fromInt"] = toReal
        v["Real.floor"] = floor
        v["Real.round"] = round
        v["Real.max"] = fn2 { a, b -> MlReal(maxOf(real(a), real(b))) }
        v["Real.min"] = fn2 { a, b -> MlReal(minOf(real(a), real(b))) }
        v["Math.sqrt"] = fn { MlReal(sqrt(real(it))) }
        v["Math.exp"] = fn { MlReal(exp(real(it))) }
        v["Math.ln"] = fn { MlReal(ln(real(it))) }
        v["Math.sin"] = fn { MlReal(sin(real(it))) }
        v["Math.cos"] = fn { MlReal(cos(real(it))) }
        v["Math.pow"] = fn2 { a, b -> MlReal(real(a).pow(real(b))) }
        v["Math.pi"] = MlReal(PI)
        v["Bool.toString"] = fn { (it as Boolean).toString() }

        // -- Options
        val valOf = fn { (it as MlCon).let { o -> if (o.name == "SOME") o.argument else raise("Option") } }
        val isSome = fn { (it as MlCon).name == "SOME" }
        val getOpt = fn2 { o, default -> (o as MlCon).let { if (it.name == "SOME") it.argument else default } }
        v["valOf"] = valOf
        v["isSome"] = isSome
        v["getOpt"] = getOpt
        v["Option.valOf"] = valOf
        v["Option.isSome"] = isSome
        v["Option.getOpt"] = getOpt
        v["Option.map"] = curried { f, o -> (o as MlCon).let { if (it.name == "SOME") some(interpreter.call(f, it.argument)) else NONE } }

        // -- Multisets (CPN ML)
        v["`"] =
            fn2 { n, colour ->
                val count = n as Int
                if (count < 0) raise("Fail", "negative coefficient $count in $count`${formatValue(colour)}")
                if (count == 0) Multiset.empty<Any?>() else Multiset.repeated(colour, count)
            }
        v["++"] = fn2 { a, b -> toMultiset(a) + toMultiset(b) }
        v["--"] =
            fn2 { a, b ->
                val x = toMultiset(a)
                val y = toMultiset(b)
                if (!(y subsetOf x)) raise("Fail", "--: $y is not contained in $x")
                x - y
            }
        v["**"] = fn2 { n, ms -> toMultiset(ms) * (n as Int) }
        v["=="] = fn2 { a, b -> toMultiset(a) == toMultiset(b) }
        v["<><>"] = fn2 { a, b -> toMultiset(a) != toMultiset(b) }
        v["<<="] = fn2 { a, b -> toMultiset(a) subsetOf toMultiset(b) }
        v[">>="] = fn2 { a, b -> toMultiset(b) subsetOf toMultiset(a) }
        v["<<"] = fn2 { a, b -> toMultiset(a).let { x -> toMultiset(b).let { y -> x subsetOf y && x != y } } }
        v[">>"] = fn2 { a, b -> toMultiset(a).let { x -> toMultiset(b).let { y -> y subsetOf x && x != y } } }
        v["empty"] = Multiset.empty<Any?>()
        v["size"] = fn { toMultiset(it).size }
        v["cf"] = fn2 { colour, ms -> toMultiset(ms).count(colour) }
        v["random"] =
            fn {
                val ms = toMultiset(it)
                if (ms.isEmpty()) raise("Fail", "random: the multiset is empty")
                var index = interpreter.random.nextLong(ms.size.toLong())
                var chosen: Any? = null
                for ((colour, n) in ms.entries()) {
                    if (index < n) {
                        chosen = colour
                        break
                    }
                    index -= n
                }
                chosen
            }
        v["filter"] = curried { p, ms -> toMultiset(ms).filter { interpreter.call(p, it) as Boolean } }
        v["ext_col"] = curried { f, ms -> toMultiset(ms).map { interpreter.call(f, it) } }
        v["ms_to_col"] =
            fn {
                val ms = toMultiset(it)
                if (ms.size != 1) raise("Fail", "ms_to_col: $ms does not have exactly one token")
                ms.distinct.single()
            }

        // -- Time (untimed until T3.2: delays are ignored and the clock stays at 0)
        v["@+"] = fn2 { value, _ -> value }
        v["time"] = fn { 0 }
        v["intTime"] = fn { 0 }

        // -- Random distributions
        val random = { interpreter.random }
        v["discrete"] =
            fn2 { a, b ->
                val low = a as Int
                val high = b as Int
                if (low > high) raise("Fail", "discrete: empty range $low..$high")
                (low + random().nextLong(high.toLong() - low + 1)).toInt()
            }
        v["uniform"] = fn2 { a, b -> MlReal(real(a) + (real(b) - real(a)) * random().nextDouble()) }
        v["normal"] =
            fn2 { mean, variance ->
                // Box-Muller; 1 - u keeps the logarithm finite.
                val u = 1.0 - random().nextDouble()
                val w = random().nextDouble()
                MlReal(real(mean) + sqrt(real(variance)) * sqrt(-2.0 * ln(u)) * cos(2.0 * PI * w))
            }
        v["exponential"] = fn { rate -> MlReal(-ln(1.0 - random().nextDouble()) / real(rate)) }
        v["bernoulli"] = fn { p -> if (random().nextDouble() < real(p)) 1 else 0 }
        v["poisson"] =
            fn { mean ->
                // Knuth's method; fine for the small means used in models.
                val limit = exp(-real(mean))
                var k = 0
                var product = random().nextDouble()
                while (product > limit) {
                    k++
                    product *= random().nextDouble()
                }
                k
            }
        return v
    }

    private val NONE = MlCon("NONE")

    private fun some(value: Any?) = MlCon("SOME", value, hasArgument = true)

    private fun pair(
        a: Any?,
        b: Any?,
    ) = MlTuple(listOf(a, b))

    private fun list(value: Any?): List<Any?> = value as List<Any?>

    // -- Numbers. D8: int arithmetic on Ints checks for overflow (SML's Overflow); Longs (intinf, time) do not.

    private fun arithmetic(
        a: Any?,
        b: Any?,
        onLongs: (Long, Long) -> Long,
        onReals: (Double, Double) -> Double,
    ): Any =
        when {
            a is MlReal && b is MlReal -> MlReal(onReals(a.value, b.value))
            else -> integer(a, b, onLongs((a as Number).toLong(), (b as Number).toLong()))
        }

    /** [result] as an Int if both operands were Ints (raising Overflow if it does not fit), else as a Long. */
    private fun integer(
        a: Any?,
        b: Any?,
        result: Long,
    ): Any =
        if (a is Int && b is Int) {
            if (result !in Int.MIN_VALUE..Int.MAX_VALUE) raise("Overflow")
            result.toInt()
        } else {
            result
        }

    private fun negate(value: Any?): Any =
        when (value) {
            is Int -> if (value == Int.MIN_VALUE) raise("Overflow") else -value
            is Long -> -value
            else -> MlReal(-real(value))
        }

    private fun isNegative(value: Any?): Boolean = if (value is MlReal) value.value < 0 else (value as Number).toLong() < 0

    private fun real(value: Any?): Double = (value as MlReal).value

    private fun isZero(value: Any?): Boolean = (value as Number).toLong() == 0L

    private fun toInt(value: Double): Int {
        if (value.isNaN()) raise("Domain")
        if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) raise("Overflow")
        return value.toInt()
    }

    /** SML `Int.fromString`: optional whitespace and sign (`~`, `-` or `+`), then digits; the rest is ignored. */
    private fun parseInt(text: String): MlCon {
        val s = text.trimStart()
        var i = 0
        val negative = s.isNotEmpty() && (s[0] == '~' || s[0] == '-')
        if (s.isNotEmpty() && (s[0] == '~' || s[0] == '-' || s[0] == '+')) i++
        val digits = s.drop(i).takeWhile { it in '0'..'9' }
        if (digits.isEmpty()) return NONE
        val magnitude = digits.toLongOrNull() ?: raise("Overflow")
        val value = if (negative) -magnitude else magnitude
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) raise("Overflow")
        return some(value.toInt())
    }

    // -- Function values

    private fun fn(body: (Any?) -> Any?): MlFunction =
        object : MlFunction {
            override fun apply(argument: Any?): Any? = body(argument)
        }

    private fun fn2(body: (Any?, Any?) -> Any?): MlFunction2 =
        object : MlFunction2 {
            override fun apply2(
                left: Any?,
                right: Any?,
            ): Any? = body(left, right)
        }

    private fun fn3(body: (Any?, Any?, Any?) -> Any?): MlFunction =
        fn { argument ->
            val (a, b, c) = (argument as MlTuple).elements
            body(a, b, c)
        }

    private fun curried(body: (Any?, Any?) -> Any?): MlFunction = fn { a -> fn { b -> body(a, b) } }

    private fun curried3(body: (Any?, Any?, Any?) -> Any?): MlFunction = fn { a -> fn { b -> fn { c -> body(a, b, c) } } }
}
