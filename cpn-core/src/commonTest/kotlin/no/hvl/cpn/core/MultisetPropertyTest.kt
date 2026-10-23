package no.hvl.cpn.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Algebraic laws of multisets, checked on random small multisets over a small universe. */
class MultisetPropertyTest {
    private val multisets: Arb<Multiset<Int>> =
        Arb.list(Arb.int(0..4), 0..8).map { it.toMultiset() }
    private val scalars = Arb.int(0..5)

    @Test
    fun plusIsCommutative() =
        runTest {
            checkAll(multisets, multisets) { a, b -> assertEquals(a + b, b + a) }
        }

    @Test
    fun plusIsAssociative() =
        runTest {
            checkAll(multisets, multisets, multisets) { a, b, c -> assertEquals((a + b) + c, a + (b + c)) }
        }

    @Test
    fun emptyIsIdentity() =
        runTest {
            checkAll(multisets) { a ->
                assertEquals(a, a + Multiset.empty())
                assertEquals(a, a - Multiset.empty())
            }
        }

    @Test
    fun minusUndoesPlus() =
        runTest {
            checkAll(multisets, multisets) { a, b -> assertEquals(a, (a + b) - b) }
        }

    @Test
    fun sizeIsAdditive() =
        runTest {
            checkAll(multisets, multisets) { a, b -> assertEquals(a.size + b.size, (a + b).size) }
        }

    @Test
    fun summandsAreSubMultisets() =
        runTest {
            checkAll(multisets, multisets) { a, b ->
                assertTrue(a subsetOf a + b)
                assertTrue(b subsetOf a + b)
            }
        }

    @Test
    fun subsetOfIsAntisymmetric() =
        runTest {
            checkAll(multisets, multisets) { a, b ->
                if (a subsetOf b && b subsetOf a) assertEquals(a, b)
            }
        }

    @Test
    fun scalarDistributesOverPlus() =
        runTest {
            checkAll(scalars, multisets, multisets) { n, a, b -> assertEquals(n * a + n * b, n * (a + b)) }
        }

    @Test
    fun scalarScalesSize() =
        runTest {
            checkAll(scalars, multisets) { n, a -> assertEquals(n * a.size, (n * a).size) }
        }

    @Test
    fun iteratorRoundTrips() =
        runTest {
            checkAll(multisets) { a ->
                assertEquals(a.size, a.count())
                assertEquals(a, a.toList().toMultiset())
            }
        }

    @Test
    fun equalMultisetsHaveEqualHashes() =
        runTest {
            checkAll(multisets) { a ->
                val shuffled = a.toList().reversed().toMultiset()
                assertEquals(a, shuffled)
                assertEquals(a.hashCode(), shuffled.hashCode())
            }
        }

    @Test
    fun canonicalFormatIsOrderIndependent() =
        runTest {
            checkAll(multisets) { a ->
                val shuffled = a.toList().reversed().toMultiset()
                assertEquals(a.format(naturalOrder()), shuffled.format(naturalOrder()))
            }
        }
}
