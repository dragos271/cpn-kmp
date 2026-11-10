package no.hvl.cpn.io.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XmlTest {
    @Test
    fun readsElementsAttributesAndText() {
        val doc =
            Xml.parse(
                """
                <?xml version="1.0" encoding="iso-8859-1"?>
                <!DOCTYPE workspaceElements PUBLIC "-//CPN//DTD CPNXML 1.0//EN" "http://example/cpn.dtd">
                <a x="1" y='two &amp; three'>
                  <!-- a comment -->
                  <b>n &lt; 5 &#65;&#x42;</b>
                  <c/>
                  <d><![CDATA[1`x ++ <raw>]]></d>
                </a>
                """.trimIndent(),
            )
        val a = doc.root
        assertEquals("a", a.name)
        assertEquals(listOf("x" to "1", "y" to "two & three"), a.attributes)
        assertEquals("n < 5 AB", a.childText("b"))
        assertEquals(emptyList(), a.child("c")!!.children)
        assertEquals("1`x ++ <raw>", a.childText("d"))
        assertEquals(listOf("b", "c", "d"), a.elements.map { it.name })
        assertEquals(5, a.child("b")!!.line)
        assertTrue("DOCTYPE" in doc.prolog)
    }

    @Test
    fun errorsSayWhere() {
        val e = assertFailsWith<XmlException> { Xml.parse("<a>\n  <b></c>\n</a>") }
        assertEquals(2, e.line)
        assertEquals("</c> closes <b> from line 2", e.reason)
        assertFailsWith<XmlException> { Xml.parse("<a>") }
        assertFailsWith<XmlException> { Xml.parse("<a x=1/>") }
        assertFailsWith<XmlException> { Xml.parse("<a>&nbsp;</a>") }
    }

    @Test
    fun decodesTheDeclaredEncoding() {
        // "Ærø" in ISO-8859-1 is C6 72 F8; read as UTF-8 it would be broken.
        val latin1 =
            "<?xml version=\"1.0\" encoding=\"iso-8859-1\"?><a>".encodeToByteArray() + byteArrayOf(0xC6.toByte(), 0x72, 0xF8.toByte()) +
                "</a>".encodeToByteArray()
        assertEquals("Ærø", Xml.parse(Xml.decode(latin1)).root.text)
        val utf8 = "<a>Ærø</a>".encodeToByteArray()
        assertEquals("Ærø", Xml.parse(Xml.decode(utf8)).root.text)
    }
}
