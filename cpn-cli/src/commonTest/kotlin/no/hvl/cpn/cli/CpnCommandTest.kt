package no.hvl.cpn.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpnCommandTest {
    @Test
    fun printsBanner() {
        val result = CpnCommand().test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.startsWith("cpn "))
    }

    @Test
    fun printsVersion() {
        val result = CpnCommand().test("--version")
        assertTrue(result.stdout.contains("0.1.0-SNAPSHOT"))
    }
}
