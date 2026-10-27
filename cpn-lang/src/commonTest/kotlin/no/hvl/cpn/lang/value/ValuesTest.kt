package no.hvl.cpn.lang.value

import kotlin.test.Test
import kotlin.test.assertEquals

class ValuesTest {
    @Test
    fun realsPrintTheSameOnEveryPlatform() {
        val cases =
            listOf(
                1.5 to "1.5",
                2.0 to "2.0",
                -2.5 to "~2.5",
                0.001 to "0.001",
                0.00001 to "0.00001",
                1e-7 to "1.0E~7",
                1.5e10 to "15000000000.0",
                1e15 to "1.0E15",
                1.25e21 to "1.25E21",
                123.456 to "123.456",
                0.1 + 0.2 to "0.30000000000000004",
                0.0 to "0.0",
                -0.0 to "~0.0",
                Double.POSITIVE_INFINITY to "inf",
            )
        for ((value, expected) in cases) assertEquals(expected, formatReal(value), "formatReal($value)")
    }

    @Test
    fun integersUseTilde() {
        assertEquals("~3", formatInt(-3))
        assertEquals("42", formatInt(42))
        assertEquals("~9223372036854775808", formatInt(Long.MIN_VALUE))
    }

    @Test
    fun stringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\^A\"", formatString("a\"b\\c\n\t\u0001"))
    }

    @Test
    fun compoundValues() {
        assertEquals("(1,\"COL\")", formatValue(MlTuple(listOf(1, "COL"))))
        assertEquals("{seq=1,data=\"x\"}", formatValue(MlRecord(linkedMapOf("seq" to 1, "data" to "x"))))
        assertEquals(MlRecord(linkedMapOf("a" to 1, "b" to 2)), MlRecord(linkedMapOf("b" to 2, "a" to 1)))
        assertEquals("Data(1,\"COL\")", formatValue(MlCon("Data", MlTuple(listOf(1, "COL")))))
        assertEquals("Ack(~3)", formatValue(MlCon("Ack", -3)))
        assertEquals("Stop", formatValue(MlCon("Stop")))
        assertEquals("Ack(())", formatValue(MlCon("Ack", Unit)))
        assertEquals("[1,2]", formatValue(listOf(1, 2)))
        assertEquals("#\"x\"", formatValue('x'))
    }
}
