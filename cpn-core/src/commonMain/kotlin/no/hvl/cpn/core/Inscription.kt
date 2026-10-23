package no.hvl.cpn.core

/** A CPN variable (CPN ML `var n : INT`). Names are unique within a net. */
public class Variable<T>(
    public val name: String,
    public val colourSet: ColourSet<T>,
) {
    override fun toString(): String = name
}

/** An assignment of colours to variables, i.e. the binding of a transition occurrence. */
public class Binding private constructor(
    private val values: Map<Variable<*>, Any?>,
) {
    public val variables: Set<Variable<*>> get() = values.keys

    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(variable: Variable<T>): T {
        require(variable in values) { "Variable ${variable.name} is not bound in $this" }
        return values[variable] as T
    }

    public fun isBound(variable: Variable<*>): Boolean = variable in values

    public fun <T> with(
        variable: Variable<T>,
        value: T,
    ): Binding {
        require(value in variable.colourSet) { "$value is not a colour of ${variable.colourSet} (variable ${variable.name})" }
        return Binding(values + (variable to value))
    }

    override fun equals(other: Any?): Boolean = other is Binding && values == other.values

    override fun hashCode(): Int = values.hashCode()

    /** CPN Tools style, e.g. `<n=1,p="x">`, sorted by variable name. */
    override fun toString(): String =
        values.entries
            .sortedBy { it.key.name }
            .joinToString(",", "<", ">") { (v, value) -> "${v.name}=${formatColour(v, value)}" }

    public companion object {
        public val EMPTY: Binding = Binding(emptyMap())

        public fun of(vararg pairs: Pair<Variable<*>, Any?>): Binding =
            pairs.fold(EMPTY) { binding, (variable, value) ->
                @Suppress("UNCHECKED_CAST")
                binding.with(variable as Variable<Any?>, value)
            }
    }
}

@Suppress("UNCHECKED_CAST")
private fun formatColour(
    variable: Variable<*>,
    value: Any?,
): String = (variable.colourSet as ColourSet<Any?>).format(value)

/**
 * An arc inscription: evaluates to the multiset of tokens an arc moves under a binding.
 *
 * The built-in kinds ([Constant], [VariableTerm], [Function]) cover nets written in Kotlin. `cpn-lang`
 * adds its own implementation for parsed CPN ML expressions. [Constant] and [VariableTerm] are
 * visible to the binding search (T1.6), which can bind variables directly from them.
 */
public interface ArcInscription<T> {
    /** Free variables of the inscription. */
    public val variables: Set<Variable<*>>

    public fun evaluate(binding: Binding): Multiset<T>

    /** A closed multiset, e.g. ``1`()``. */
    public class Constant<T>(
        public val value: Multiset<T>,
    ) : ArcInscription<T> {
        override val variables: Set<Variable<*>> = emptySet()

        override fun evaluate(binding: Binding): Multiset<T> = value

        override fun toString(): String = value.toString()
    }

    /** ``count`variable``; with `count = 1` this is just the variable, as CPN ML allows. */
    public class VariableTerm<T>(
        public val variable: Variable<T>,
        public val count: Int = 1,
    ) : ArcInscription<T> {
        init {
            require(count > 0) { "Count must be positive, was $count" }
        }

        override val variables: Set<Variable<*>> = setOf(variable)

        override fun evaluate(binding: Binding): Multiset<T> = Multiset.repeated(binding[variable], count)

        override fun toString(): String = if (count == 1) variable.name else "$count`${variable.name}"
    }

    /** Arbitrary Kotlin code over the listed [variables]; [source] is used for display only. */
    public class Function<T>(
        override val variables: Set<Variable<*>>,
        private val source: String = "<fn>",
        private val body: (Binding) -> Multiset<T>,
    ) : ArcInscription<T> {
        override fun evaluate(binding: Binding): Multiset<T> = body(binding)

        override fun toString(): String = source
    }
}

/** A transition guard: a boolean condition over a binding. */
public interface Guard {
    public val variables: Set<Variable<*>>

    public fun evaluate(binding: Binding): Boolean

    public companion object {
        /** The empty guard, always satisfied. */
        public val TRUE: Guard =
            object : Guard {
                override val variables: Set<Variable<*>> = emptySet()

                override fun evaluate(binding: Binding): Boolean = true

                override fun toString(): String = "true"
            }

        public fun of(
            vararg variables: Variable<*>,
            source: String = "<guard>",
            condition: (Binding) -> Boolean,
        ): Guard =
            object : Guard {
                override val variables: Set<Variable<*>> = variables.toSet()

                override fun evaluate(binding: Binding): Boolean = condition(binding)

                override fun toString(): String = source
            }
    }
}
