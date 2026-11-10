package no.hvl.cpn.io.cpntools

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.PortType
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.io.model.ArcKind
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.ProblemField
import no.hvl.cpn.io.model.check
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CpnToolsFormatTest {
    /** A small model in the shape CPN Tools 4 writes: a counter page that uses a substitution transition. */
    private val model =
        """
        <?xml version="1.0" encoding="iso-8859-1"?>
        <!DOCTYPE workspaceElements PUBLIC "-//CPN//DTD CPNXML 1.0//EN" "http://cpntools.org/DTD/6/cpn.dtd">
        <workspaceElements>
          <generator tool="CPN Tools" version="4.0.1" format="6"/>
          <cpnet>
            <globbox>
              <block id="ID1">
                <id>Standard declarations</id>
                <color id="ID2"><id>INT</id><int/></color>
                <color id="ID3"><id>E</id><enum><id>e</id></enum></color>
              </block>
              <var id="ID4"><type><id>NO</id></type><id>n</id><id>k</id><layout>var n, k : NO;</layout></var>
              <color id="ID5"><id>NO</id><int><with><ml>0</ml><ml>max</ml></with></int><layout>colset NO = int with 0..max; (* counter *)

        v</layout></color>
              <ml id="ID6">val max = 3</ml>
            </globbox>
            <page id="P1">
              <pageattr name="Top"/>
              <place id="A">
                <posattr x="10.0" y="20.0"/>
                <text>Count
        er</text>
                <type id="A1"><text>NO</text></type>
                <initmark id="A2"><text>1`0</text></initmark>
              </place>
              <trans id="T">
                <posattr x="100.0" y="-40.0"/>
                <text>Inc</text>
                <cond id="T1"><text>n &lt; max, n &gt;= 0</text></cond>
                <time id="T2"><text>@+2</text></time>
                <priority id="T3"><text>P_HIGH</text></priority>
              </trans>
              <trans id="S">
                <posattr x="200.0" y="0.0"/>
                <text>Sub</text>
                <subst subpage="P2" portsock="(PA,A)"><subpageinfo id="S1"/></subst>
              </trans>
              <arc id="a1" orientation="PtoT" order="1">
                <transend idref="T"/><placeend idref="A"/>
                <bendpoint id="b2" serial="2"><posattr x="3.0" y="4.0"/></bendpoint>
                <bendpoint id="b1" serial="1"><posattr x="1.0" y="2.0"/></bendpoint>
                <annot id="a1t"><text>n</text></annot>
              </arc>
              <arc id="a2" orientation="TtoP" order="1">
                <transend idref="T"/><placeend idref="A"/>
                <annot id="a2t"><text>n + 1</text></annot>
              </arc>
              <arc id="a3" orientation="BOTHDIR" order="1">
                <transend idref="S"/><placeend idref="A"/>
                <annot id="a3t"><text/></annot>
              </arc>
            </page>
            <page id="P2">
              <pageattr name="Inner"/>
              <place id="PA">
                <posattr x="0.0" y="0.0"/>
                <text>Counter</text>
                <type id="PA1"><text>NO</text></type>
                <initmark id="PA2"><text/></initmark>
                <port id="PA3" type="I/O"/>
                <fusioninfo id="PA4" name="F1"/>
              </place>
            </page>
            <instances><instance id="I1" page="P1"><instance id="I2" trans="S"/></instance></instances>
            <monitorblock name="Monitors"><monitor id="M1" name="Count" type="0"/></monitorblock>
          </cpnet>
        </workspaceElements>
        """.trimIndent()

    private val imported = CpnToolsFormat.read(model, "counter")
    private val document = imported.document

    @Test
    fun readsPagesPlacesTransitionsAndArcs() {
        assertEquals("counter", document.name)
        assertEquals(listOf("Top", "Inner"), document.pages.map { it.name })
        val top = document.pages[0]
        val place = top.place("A")!!
        assertEquals("Count\ner", place.name)
        assertEquals("NO", place.colourSet)
        assertEquals("1`0", place.initialMarking)
        assertEquals(Point(10.0, -20.0), place.position, "CPN Tools' y axis points up")

        val inc = top.transition("T")!!
        assertEquals("n < max, n >= 0", inc.guard)
        assertEquals("@+2", inc.time)
        assertEquals("P_HIGH", inc.priority)

        val a1 = top.arcs.first { it.id == "a1" }
        assertEquals(ArcDirection.PLACE_TO_TRANSITION, a1.direction)
        assertEquals(listOf(Point(1.0, -2.0), Point(3.0, -4.0)), a1.bends, "bend points in serial order")
        assertEquals(ArcDirection.BIDIRECTIONAL, top.arcs.first { it.id == "a3" }.direction)
    }

    @Test
    fun readsHierarchy() {
        val sub = document.pages[0].transition("S")!!
        assertEquals("P2", sub.subpageId)
        assertEquals(mapOf("A" to "PA"), sub.portAssignments, "portsock pairs are (port, socket)")
        val port = document.pages[1].place("PA")!!
        assertEquals(PortType.IN_OUT, port.port)
        assertEquals("F1", port.fusionSet)
    }

    @Test
    fun declarationsComeInDependencyOrderWithStrayLayoutTextDropped() {
        val lines = document.declarations.lines().filter { it.isNotBlank() }
        assertEquals(
            listOf(
                "(* Standard declarations *)",
                "colset INT = int;",
                "colset E = with e;",
                "val max = 3;",
                "colset NO = int with 0..max; (* counter *)",
                "var n, k : NO;",
            ),
            lines,
        )
        assertTrue(imported.notes.any { "'v'" in it }, "the stray text is reported: ${imported.notes}")
        assertTrue(imported.notes.any { "reordered" in it })
    }

    @Test
    fun ignoredFeaturesAreNoted() {
        val notes = imported.notes.joinToString("\n")
        assertTrue("delay" !in notes, "delays are supported since T3.2: $notes")
        assertTrue("1 transition priorities are ignored" in notes, notes)
        assertTrue("1 monitors are ignored" in notes, notes)
    }

    @Test
    fun theImportedModelRuns() {
        val opened = ModelFiles.open(model, "counter")
        val net = assertNotNull(opened.net, "problems: ${document.check()}")
        val simulator = Simulator(net, seed = 1)
        val result = simulator.run(100)
        // The guard list stops the counter at max = 3.
        assertEquals(StopReason.DEAD, result.reason)
        assertEquals(3, result.stepsTaken)
        // The two-line name becomes one line, and the fused port is glued to its socket.
        val counter =
            simulator.flatNet.net.pages
                .flatMap { it.places }
                .single()
        assertEquals("Top'Count er 1", counter.name)
        assertEquals("1`3", result.marking.tokensOf(counter).toString())
    }

    @Test
    fun unsupportedArcsAndCodeAreProblemsNotSilentlyDropped() {
        val inhibitor = model.replace("""<arc id="a1" orientation="PtoT"""", """<arc id="a1" orientation="Inhibitor"""")
        val doc = CpnToolsFormat.read(inhibitor, "x").document
        assertEquals(
            ArcKind.INHIBITOR,
            doc.pages[0]
                .arcs
                .first { it.id == "a1" }
                .kind,
        )
        assertEquals(
            listOf("Inhibitor arcs are not supported yet"),
            doc.check().filter { it.field == ProblemField.STRUCTURE }.map { it.message },
        )
    }

    @Test
    fun rejectsFilesThatAreNotCpnTools() {
        assertTrue(CpnToolsFormat.recognises(model))
        assertFailsWith<ModelFormatException> { CpnToolsFormat.read("<html/>", "x") }
        assertFailsWith<ModelFormatException> { CpnToolsFormat.read("<workspaceElements><cpnet></cpnet>", "x") }
        val badArc = model.replace("<placeend idref=\"A\"/>\n        <bendpoint", "<placeend idref=\"nowhere\"/>\n        <bendpoint")
        assertFailsWith<ModelFormatException> { CpnToolsFormat.read(badArc, "x") }
    }
}
