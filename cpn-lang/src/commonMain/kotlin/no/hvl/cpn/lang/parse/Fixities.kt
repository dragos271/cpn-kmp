package no.hvl.cpn.lang.parse

/** Precedence (0 binds loosest, 9 tightest) and associativity of an infix identifier. */
public data class InfixInfo(
    val precedence: Int,
    val rightAssociative: Boolean,
)

/**
 * The infix operators in scope. Immutable: `infix` declarations produce a new table, and `let` /
 * `local` blocks restore the outer one afterwards, as in SML.
 */
public class Fixities private constructor(
    private val table: Map<String, InfixInfo>,
) {
    public operator fun get(name: String): InfixInfo? = table[name]

    public fun isInfix(name: String): Boolean = name in table

    public fun with(
        names: List<String>,
        info: InfixInfo?,
    ): Fixities = Fixities(if (info == null) table - names.toSet() else table + names.associateWith { info })

    public val operators: Set<String> get() = table.keys

    public companion object {
        /** The SML'97 Basis top-level fixities. */
        public val SML: Fixities =
            Fixities(
                buildMap {
                    fun infix(
                        precedence: Int,
                        vararg ops: String,
                        right: Boolean = false,
                    ) = ops.forEach { put(it, InfixInfo(precedence, right)) }
                    infix(7, "*", "/", "div", "mod")
                    infix(6, "+", "-", "^")
                    infix(5, "::", "@", right = true)
                    infix(4, "=", "<>", ">", ">=", "<", "<=")
                    infix(3, ":=", "o")
                    infix(0, "before")
                },
            )

        /**
         * SML plus the CPN ML multiset and time operators. Chosen so the usual inscription forms
         * parse as intended: ``1`x++2`y`` is `(1`x) ++ (2`y)`, ``1`x@+5 ++ 1`y`` is
         * `((1`x) @+ 5) ++ (1`y)`, and `a ++ b == c` compares sums. These levels are provisional
         * until checked against CPN Tools in the differential tests (T4.2).
         */
        public val CPN: Fixities =
            SML
                .with(listOf("`"), InfixInfo(8, false))
                .with(listOf("**", "@+"), InfixInfo(7, false))
                .with(listOf("++", "--"), InfixInfo(6, false))
                .with(listOf("==", "<><>", "<<=", ">>=", "<<", ">>"), InfixInfo(4, false))
    }
}
