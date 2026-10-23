package no.hvl.cpn.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpnTest {
    @Test
    fun allEngineModulesAreLinked() {
        assertEquals(5, Cpn.modules.size)
    }

    @Test
    fun reportsPlatform() {
        val platform = Cpn.platform
        println("Running on $platform")
        assertTrue(platform.isNotBlank())
    }
}
