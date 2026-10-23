package no.hvl.cpn.statespace

import kotlin.test.Test
import kotlin.test.assertEquals

class StateSpaceModuleTest {
    @Test
    fun moduleIsOnClasspath() {
        assertEquals("cpn-statespace", StateSpaceModule.NAME)
    }
}
