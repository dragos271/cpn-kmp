package no.hvl.cpn.sim

import kotlin.test.Test
import kotlin.test.assertEquals

class SimModuleTest {
    @Test
    fun moduleIsOnClasspath() {
        assertEquals("cpn-sim", SimModule.NAME)
    }
}
