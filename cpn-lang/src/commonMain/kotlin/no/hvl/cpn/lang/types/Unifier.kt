package no.hvl.cpn.lang.types

/** Two types cannot be made equal; [detail] says why when there is more to it than the two types. */
internal class UnifyException(
    val detail: String?,
) : Exception(detail)

/**
 * Unification with the occurs check, level adjustment for generalization, equality types (`''a`),
 * overloading (`+` on int or real) and flexible records (`#label`, `{a, ...}`).
 *
 * [equalityOf] says when a type constructor admits equality (`list` if its argument does, `real`
 * and `exn` never, `ref` always), which depends on the declared datatypes.
 */
internal class Unifier(
    private val equalityOf: (String) -> Equality,
) {
    private var nextId = 0

    fun fresh(
        level: Int,
        kind: Kind = Kind.Free,
    ): TypeVar = TypeVar(nextId++, level, kind)

    fun unify(
        a: Type,
        b: Type,
    ) {
        val x = prune(a)
        val y = prune(b)
        when {
            x === y -> Unit
            x is TypeVar -> bind(x, y)
            y is TypeVar -> bind(y, x)
            x is TypeCon && y is TypeCon -> {
                if (x.name != y.name || x.args.size != y.args.size) throw UnifyException(null)
                x.args.zip(y.args).forEach { (p, q) -> unify(p, q) }
            }
            x is RecordType && y is RecordType -> {
                if (x.fields.keys != y.fields.keys) throw UnifyException(null)
                x.fields.forEach { (label, t) -> unify(t, y.fields.getValue(label)) }
            }
            x is FunType && y is FunType -> {
                unify(x.param, y.param)
                unify(x.result, y.result)
            }
            else -> throw UnifyException(null)
        }
    }

    private fun bind(
        v: TypeVar,
        t: Type,
    ) {
        if (t is TypeVar) {
            // Merge what both variables allow into one of them.
            val kind = combine(v.kind, t.kind)
            t.kind = kind
            t.level = minOf(t.level, v.level)
            v.instance = t
            return
        }
        if (occurs(v, t)) throw UnifyException("a type cannot contain itself (${TypePrinter().print(t)} would be infinite)")
        adjustLevels(t, v.level)
        when (val kind = v.kind) {
            Kind.Free -> Unit
            Kind.Equality -> requireEquality(t)
            is Kind.Overloaded -> {
                if (t !is TypeCon || t.args.isNotEmpty() || t.name !in kind.allowed) {
                    throw UnifyException(
                        "only ${kind.allowed.sorted().joinToString(" or ")} can be used here, not ${TypePrinter().print(t)}",
                    )
                }
            }
            is Kind.FlexRecord -> {
                if (t !is RecordType) throw UnifyException("${TypePrinter().print(t)} is not a record or tuple")
                for ((label, fieldType) in kind.fields) {
                    val actual = t.fields[label] ?: throw UnifyException("${TypePrinter().print(t)} has no field $label")
                    unify(fieldType, actual)
                }
                if (kind.equality) requireEquality(t)
            }
        }
        v.instance = t
    }

    private fun combine(
        a: Kind,
        b: Kind,
    ): Kind =
        when {
            a == Kind.Free -> b
            b == Kind.Free -> a
            a == Kind.Equality && b == Kind.Equality -> Kind.Equality
            a == Kind.Equality -> withEquality(b)
            b == Kind.Equality -> withEquality(a)
            a is Kind.Overloaded && b is Kind.Overloaded -> {
                val allowed = a.allowed intersect b.allowed
                if (allowed.isEmpty()) {
                    throw UnifyException(
                        "no type is both ${a.allowed.sorted().joinToString("/")} and ${b.allowed.sorted().joinToString("/")}",
                    )
                }
                Kind.Overloaded(allowed, if (a.default in allowed) a.default else allowed.first())
            }
            a is Kind.FlexRecord && b is Kind.FlexRecord -> {
                val fields = LinkedHashMap(a.fields)
                for ((label, t) in b.fields) {
                    val existing = fields[label]
                    if (existing == null) fields[label] = t else unify(existing, t)
                }
                Kind.FlexRecord(fields, a.equality || b.equality)
            }
            else -> throw UnifyException("a record cannot be an overloaded number or string")
        }

    private fun withEquality(kind: Kind): Kind =
        when (kind) {
            is Kind.Overloaded -> {
                val allowed = kind.allowed.filter { equalityOf(it) != Equality.NEVER }.toSet()
                if (allowed.isEmpty()) throw UnifyException("none of ${kind.allowed.sorted().joinToString(", ")} admits equality")
                Kind.Overloaded(allowed, if (kind.default in allowed) kind.default else allowed.first())
            }
            is Kind.FlexRecord -> {
                kind.fields.values.forEach(::requireEquality)
                kind.copy(equality = true)
            }
            else -> kind
        }

    /** Makes [type] an equality type, turning its free variables into equality variables, or fails. */
    fun requireEquality(type: Type) {
        when (val t = prune(type)) {
            is TypeVar -> t.kind = combine(t.kind, Kind.Equality)
            is FunType -> throw UnifyException("functions cannot be compared with = (they do not admit equality)")
            is RecordType -> t.fields.values.forEach(::requireEquality)
            is TypeCon ->
                when (equalityOf(t.name)) {
                    Equality.NEVER -> throw UnifyException(
                        "${t.name} does not admit equality, so values of type ${TypePrinter().print(t)} cannot be compared with =",
                    )
                    Equality.ALWAYS -> Unit // ref: comparable whatever it holds
                    Equality.IF_ARGUMENTS -> t.args.forEach(::requireEquality)
                }
        }
    }

    private fun occurs(
        v: TypeVar,
        type: Type,
    ): Boolean =
        when (val t = prune(type)) {
            is TypeVar -> t === v || (t.kind as? Kind.FlexRecord)?.fields?.values?.any { occurs(v, it) } == true
            is TypeCon -> t.args.any { occurs(v, it) }
            is RecordType -> t.fields.values.any { occurs(v, it) }
            is FunType -> occurs(v, t.param) || occurs(v, t.result)
        }

    private fun adjustLevels(
        type: Type,
        level: Int,
    ) {
        when (val t = prune(type)) {
            is TypeVar -> {
                if (t.level > level) t.level = level
                (t.kind as? Kind.FlexRecord)?.fields?.values?.forEach { adjustLevels(it, level) }
            }
            is TypeCon -> t.args.forEach { adjustLevels(it, level) }
            is RecordType -> t.fields.values.forEach { adjustLevels(it, level) }
            is FunType -> {
                adjustLevels(t.param, level)
                adjustLevels(t.result, level)
            }
        }
    }

    /** Quantifies the variables above [level]; overloaded and flexible-record variables stay monomorphic, as in SML. */
    fun generalize(
        type: Type,
        level: Int,
    ): Scheme {
        val vars = LinkedHashSet<TypeVar>()

        fun collect(t: Type) {
            when (val p = prune(t)) {
                is TypeVar -> if (p.level > level && (p.kind == Kind.Free || p.kind == Kind.Equality)) vars += p
                is TypeCon -> p.args.forEach(::collect)
                is RecordType -> p.fields.values.forEach(::collect)
                is FunType -> {
                    collect(p.param)
                    collect(p.result)
                }
            }
        }
        collect(type)
        return Scheme(vars.toList(), type)
    }

    fun instantiate(
        scheme: Scheme,
        level: Int,
    ): Type {
        if (scheme.vars.isEmpty()) return scheme.body
        val copies = scheme.vars.associateWith { fresh(level, it.kind) }

        fun copy(t: Type): Type =
            when (val p = prune(t)) {
                is TypeVar -> copies[p] ?: p
                is TypeCon -> TypeCon(p.name, p.args.map(::copy))
                is RecordType -> RecordType(p.fields.mapValues { copy(it.value) })
                is FunType -> FunType(copy(p.param), copy(p.result))
            }
        return copy(scheme.body)
    }

    /** SML's default overloading: an unresolved `+` at the end of a declaration works on int. */
    fun defaultOverloads(type: Type) {
        when (val t = prune(type)) {
            is TypeVar -> (t.kind as? Kind.Overloaded)?.let { t.instance = TypeCon(it.default) }
            is TypeCon -> t.args.forEach(::defaultOverloads)
            is RecordType -> t.fields.values.forEach(::defaultOverloads)
            is FunType -> {
                defaultOverloads(t.param)
                defaultOverloads(t.result)
            }
        }
    }
}
