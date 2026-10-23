package no.hvl.cpn.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreModuleTest {
    @Test
    fun moduleIsOnClasspath() {
        assertEquals("cpn-core", CoreModule.NAME)
    }
}
