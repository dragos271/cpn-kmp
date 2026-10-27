package no.hvl.cpn.lang.colour

import no.hvl.cpn.core.Multiset
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlRecord
import no.hvl.cpn.lang.value.MlTuple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CpnColourSetTest {
    private val small = IntColourSet("SMALL", 1..2)
    private val colour = EnumColourSet("COLOUR", listOf("red", "green", "blue"))
    private val bool = BoolColourSet("BOOL")

    private fun CpnColourSet.allText() = mkstrMs(all())

    @Test
    fun unitAndBool() {
        val signal = UnitColourSet("SIGNAL", "signal")
        assertEquals(1, signal.size)
        assertEquals("1`signal", signal.allText())
        val answer = BoolColourSet("ANSWER", "no", "yes")
        assertEquals("1`no++1`yes", answer.allText())
        assertEquals(1, answer.ord(true))
        assertEquals(false, answer.col(0))
        assertFalse(answer.legal(1))
    }

    @Test
    fun integers() {
        val range = IntColourSet("R", -1..1)
        assertEquals(3, range.size)
        assertEquals("1`~1++1`0++1`1", range.allText())
        assertEquals(2, range.ord(1))
        assertEquals(-1, range.col(0))
        assertFalse(2 in range)
        assertFalse(1L in range, "intinf values are not ints")
        assertFailsWith<ColourSetException> { range.col(3) }
        assertFailsWith<ColourSetException> { range.mkstr(5) }

        val int = IntColourSet("INT")
        assertNull(int.size)
        assertNull(int.values)
        assertTrue(int.legal(Int.MIN_VALUE))
        assertEquals("ord is not defined for INT: it is infinite", assertFailsWith<ColourSetException> { int.ord(1) }.message)
    }

    @Test
    fun largeRangesAreNotMaterialized() {
        val big = IntColourSet("BIG", 1..1_000_000)
        val values = big.values!!
        assertEquals(1_000_000, values.size)
        assertEquals(1_000_000, values[999_999])
        assertEquals(999_999L, big.ord(1_000_000))
        assertFailsWith<ColourSetException> { big.all(limit = 999_999) }
    }

    @Test
    fun intinfTimeAndReal() {
        val inf = LongColourSet("BIG", -5L..5L)
        assertEquals(11, inf.size)
        assertEquals(-5L, inf.col(0))
        assertNull(LongColourSet("TIME", 0..Long.MAX_VALUE).size, "too many colours to count")
        assertNull(LongColourSet("ALL", Long.MIN_VALUE..Long.MAX_VALUE).size)

        val prob = RealColourSet("PROB", 0.0..1.0)
        assertTrue(0.5 in prob)
        assertFalse(1.5 in prob)
        assertFalse(Double.NaN in prob)
        assertEquals("~2.5", RealColourSet("REAL").mkstr(-2.5))
    }

    @Test
    fun strings() {
        val letters = StringColourSet("LETTERS", 'a'..'z', 1..3)
        assertTrue("abc" in letters)
        assertFalse("abcd" in letters)
        assertFalse("" in letters)
        assertFalse("A" in letters)
        assertNull(letters.size, "string colour sets are never enumerable")
        assertEquals("\"say \\\"hi\\\"\"", StringColourSet("S").mkstr("say \"hi\""))
        assertTrue(StringColourSet("S").compare("ab", "b") < 0)
    }

    @Test
    fun enumerationsAndIndexes() {
        assertEquals("1`red++1`green++1`blue", colour.allText())
        assertEquals(2, colour.ord(MlCon("blue")))
        assertEquals(MlCon("green"), colour.col(1))
        assertFalse(MlCon("pink") in colour)
        assertEquals("1`red++1`blue", colour.mkstrMs(Multiset.of(MlCon("blue"), MlCon("red"))), "markings print in colour-set order")

        val ph = IndexColourSet("PH", "ph", 1..3)
        assertEquals("1`ph(1)++1`ph(2)++1`ph(3)", ph.allText())
        assertEquals(1, ph.ord(MlCon("ph", 2)))
        assertFalse(MlCon("ph", 4) in ph)
        assertFalse(MlCon("ph") in ph)
    }

    @Test
    fun products() {
        val pair = ProductColourSet("PAIR", listOf(small, colour))
        assertEquals(6, pair.size)
        assertEquals("1`(1,red)++1`(1,green)++1`(1,blue)++1`(2,red)++1`(2,green)++1`(2,blue)", pair.allText())
        assertEquals(4, pair.ord(MlTuple(listOf(2, MlCon("green")))))
        assertFalse(MlTuple(listOf(3, MlCon("red"))) in pair)
        assertFalse(MlTuple(listOf(1)) in pair)

        val noxdata = ProductColourSet("NOxDATA", listOf(IntColourSet("NO"), StringColourSet("DATA")))
        assertNull(noxdata.size)
        assertTrue(MlTuple(listOf(1, "COL")) in noxdata)
        assertEquals("(1,\"COL\")", noxdata.mkstr(MlTuple(listOf(1, "COL"))))
        assertTrue(noxdata.compare(MlTuple(listOf(1, "Z")), MlTuple(listOf(2, "A"))) < 0)
    }

    @Test
    fun records() {
        val record = RecordColourSet("REC", listOf("seq" to small, "ok" to bool))
        assertEquals(4, record.size)
        assertEquals("1`{seq=1,ok=false}++1`{seq=1,ok=true}++1`{seq=2,ok=false}++1`{seq=2,ok=true}", record.allText())
        assertTrue(MlRecord(mapOf("ok" to true, "seq" to 2)) in record, "field order does not matter")
        assertFalse(MlRecord(mapOf("seq" to 2)) in record)
        assertEquals(3, record.ord(MlRecord(mapOf("ok" to true, "seq" to 2))))
    }

    @Test
    fun unions() {
        val packet = UnionColourSet("PACKET", listOf("Data" to small, "Ack" to bool, "Stop" to null))
        assertEquals(5, packet.size)
        assertEquals("1`Data(1)++1`Data(2)++1`Ack(false)++1`Ack(true)++1`Stop", packet.allText())
        assertEquals(4, packet.ord(MlCon("Stop")))
        assertEquals(MlCon("Ack", true), packet.col(3))
        assertFalse(MlCon("Stop", 1) in packet)
        assertFalse(MlCon("Data") in packet)
        assertNull(UnionColourSet("U", listOf("A" to IntColourSet("INT"), "B" to null)).size)
    }

    @Test
    fun lists() {
        val bits = ListColourSet("BITS", bool, 0..2)
        assertEquals(7, bits.size)
        assertEquals(
            listOf("[]", "[false]", "[true]", "[false,false]", "[false,true]", "[true,false]", "[true,true]"),
            (0L until 7L).map { bits.format(bits.col(it)) },
        )
        assertEquals(5, bits.ord(listOf(true, false)))
        assertFalse(listOf(true, true, true) in bits)
        assertNull(ListColourSet("Q", bool).size, "unbounded length")
        assertTrue(listOf(true, true, true) in ListColourSet("Q", bool))
    }

    @Test
    fun subsetsAndAliases() {
        val digits = IntColourSet("DIGIT", 0..9)
        val even = SubsetColourSet("EVEN", digits, predicate = { (it as Int) % 2 == 0 })
        assertEquals(5, even.size)
        assertEquals("1`0++1`2++1`4++1`6++1`8", even.allText())
        assertEquals(2, even.ord(4))
        assertFalse(3 in even)

        val primes = SubsetColourSet("PRIME", digits, members = listOf(7, 2, 5, 3))
        assertEquals("1`2++1`3++1`5++1`7", primes.allText(), "ordered as the base, not as listed")
        assertFailsWith<ColourSetException> { SubsetColourSet("BAD", digits, members = listOf(10)) }
        assertNull(SubsetColourSet("POS", IntColourSet("INT"), predicate = { (it as Int) > 0 }).size)

        val alias = AliasColourSet("COLOUR2", colour)
        assertEquals("COLOUR2", alias.name)
        assertEquals(colour.allText(), alias.allText())
        val timed = TimedColourSet(colour)
        assertTrue(timed.timed)
        assertFalse(colour.timed)
    }

    @Test
    fun ordAndColAgreeWithTheOrderForEveryFiniteSet() {
        val sets =
            listOf(
                small,
                colour,
                bool,
                IndexColourSet("PH", "ph", 0..4),
                ProductColourSet("P", listOf(small, colour, bool)),
                RecordColourSet("R", listOf("a" to colour, "b" to small)),
                UnionColourSet("U", listOf("A" to small, "B" to null, "C" to colour)),
                ListColourSet("L", small, 1..3),
                SubsetColourSet(
                    "S",
                    ProductColourSet("P2", listOf(small, small)),
                    members = listOf(MlTuple(listOf(2, 1)), MlTuple(listOf(1, 2))),
                ),
            )
        for (cs in sets) {
            val n = cs.size!!
            for (i in 0 until n) {
                val c = cs.col(i)
                assertTrue(c in cs, "${cs.name}: col($i) is a colour")
                assertEquals(i, cs.ord(c), "${cs.name}: ord(col($i))")
                if (i + 1 < n) assertTrue(cs.compare(c, cs.col(i + 1)) < 0, "${cs.name}: col($i) < col(${i + 1})")
            }
            assertEquals(n.toInt(), cs.values!!.size)
        }
    }

    @Test
    fun ranUsesTheGivenRandomSource() {
        assertEquals(MlCon("blue"), colour.ran { bound -> bound - 1 })
        assertFailsWith<ColourSetException> { IntColourSet("INT").ran { 0 } }
    }

    @Test
    fun invalidDeclarationsAreRejected() {
        assertFailsWith<ColourSetException> { IntColourSet("E", 5..1) }
        assertFailsWith<ColourSetException> { EnumColourSet("E", emptyList()) }
        assertFailsWith<ColourSetException> { EnumColourSet("E", listOf("a", "a")) }
        assertFailsWith<ColourSetException> { ProductColourSet("P", listOf(small)) }
        assertFailsWith<ColourSetException> { RecordColourSet("R", listOf("a" to small, "a" to bool)) }
        assertFailsWith<ColourSetException> { UnionColourSet("U", listOf("A" to null, "A" to small)) }
    }
}
