package no.hvl.cpn.app.files

import kotlin.test.Test
import kotlin.test.assertEquals

class FileNameTest {
    @Test
    fun suggestedNamesAreSafeJsonFileNames() {
        assertEquals("SimpleProtocol.json", suggestedFileName("SimpleProtocol"))
        assertEquals("Simple protocol v2.json", suggestedFileName("Simple protocol v2"))
        assertEquals("ab.json", suggestedFileName("a/b"))
        assertEquals("model.json", suggestedFileName("  "))
        assertEquals("model.json", suggestedFileName("model.json"))
    }
}
