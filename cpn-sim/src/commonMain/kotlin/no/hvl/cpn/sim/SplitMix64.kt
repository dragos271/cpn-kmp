package no.hvl.cpn.sim

/**
 * SplitMix64 (Steele, Lea & Flood 2014), a small seedable PRNG.
 *
 * Used instead of `kotlin.random.Random` because its output is fixed by this code alone: the same
 * seed gives the same simulation on JVM, JS, Wasm and native, and across Kotlin versions.
 */
public class SplitMix64(
    seed: Long,
) {
    private var state: Long = seed

    public fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in `0 until bound`, without modulo bias. */
    public fun nextInt(bound: Int): Int {
        require(bound > 0) { "Bound must be positive, was $bound" }
        val m = bound - 1
        var r = next31()
        if (bound and m == 0) return ((bound.toLong() * r) shr 31).toInt()
        var u = r
        r = u % bound
        while (u - r + m < 0) {
            u = next31()
            r = u % bound
        }
        return r
    }

    private fun next31(): Int = (nextLong() ushr 33).toInt()
}
