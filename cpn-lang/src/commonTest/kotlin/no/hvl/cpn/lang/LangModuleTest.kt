package no.hvl.cpn.lang

import kotlin.test.Test
import kotlin.test.assertEquals

class LangModuleTest {
    @Test
    fun moduleIsOnClasspath() {
        assertEquals("cpn-lang", LangModule.NAME)
    }
}
