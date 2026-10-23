package no.hvl.cpn.io

import kotlin.test.Test
import kotlin.test.assertEquals

class IoModuleTest {
    @Test
    fun moduleIsOnClasspath() {
        assertEquals("cpn-io", IoModule.NAME)
    }
}
