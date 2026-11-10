package no.hvl.cpn.io.cpntools

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.PortType
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.io.model.ArcEdge
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.PlaceNode
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.TransitionNode
import no.hvl.cpn.io.model.addPlace
import no.hvl.cpn.io.model.connect
import no.hvl.cpn.io.model.delete
import no.hvl.cpn.io.model.updateArc
import no.hvl.cpn.io.model.updatePlace
import no.hvl.cpn.io.model.updateTransition
import no.hvl.cpn.io.xml.Xml
import no.hvl.cpn.io.xml.XmlElement
import no.hvl.cpn.sim.Simulator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CpnToolsWriterTest {
    /** A model as CPN Tools writes it, with a monitor and a style the document does not hold. */
    private val file =
        """
        <?xml version="1.0" encoding="iso-8859-1"?>
        <!DOCTYPE workspaceElements PUBLIC "-//CPN//DTD CPNXML 1.0//EN" "http://cpntools.org/DTD/6/cpn.dtd">

        <workspaceElements>
          <generator tool="CPN Tools"
                     version="4.0.1"
                     format="6"/>
          <cpnet>
            <globbox>
              <color id="ID1">
                <id>NO</id>
                <int/>
                <layout>colset NO = int;</layout>
              </color>
              <var id="ID2">
                <type>
                  <id>NO</id>
                </type>
                <id>n</id>
                <layout>var n : NO;</layout>
              </var>
            </globbox>
            <page id="ID6">
              <pageattr name="Main"/>
              <place id="ID10">
                <posattr x="0.000000"
                         y="0.000000"/>
                <fillattr colour="Red" pattern="" filled="true"/>
                <text>P</text>
                <type id="ID11">
                  <posattr x="20.000000" y="-20.000000"/>
                  <text tool="CPN Tools" version="4.0.1">NO</text>
                </type>
                <initmark id="ID12">
                  <posattr x="20.000000" y="20.000000"/>
                  <text tool="CPN Tools" version="4.0.1">1`3</text>
                </initmark>
              </place>
              <trans id="ID20" explicit="false">
                <posattr x="100.000000" y="0.000000"/>
                <text>Dec</text>
                <cond id="ID21">
                  <posattr x="100.000000" y="30.000000"/>
                  <text tool="CPN Tools" version="4.0.1">n &gt; 0</text>
                </cond>
              </trans>
              <arc id="ID30" orientation="PtoT" order="1">
                <transend idref="ID20"/>
                <placeend idref="ID10"/>
                <annot id="ID31">
                  <text tool="CPN Tools" version="4.0.1">n</text>
                </annot>
              </arc>
              <arc id="ID40" orientation="TtoP" order="1">
                <transend idref="ID20"/>
                <placeend idref="ID10"/>
                <annot id="ID41">
                  <text tool="CPN Tools" version="4.0.1">n - 1</text>
                </annot>
              </arc>
              <constraints/>
            </page>
            <instances>
              <instance id="ID50" page="ID6"/>
            </instances>
            <monitorblock name="Monitors">
              <monitor id="ID60" name="Count" type="0"/>
            </monitorblock>
          </cpnet>
        </workspaceElements>

        """.trimIndent()

    @Test
    fun anUnchangedModelIsWrittenBackByteForByte() {
        val imported = CpnToolsFormat.read(file, "m")
        assertEquals(file, CpnToolsFormat.write(imported.document, imported.source))
    }

    @Test
    fun editsAreWrittenAndEverythingElseIsKept() {
        val imported = CpnToolsFormat.read(file, "m")
        var doc = imported.document.copy(declarations = imported.document.declarations + "val limit = 2;\n")
        doc = doc.updatePlace("ID6", "ID10") { it.copy(name = "Count & <down>", position = Point(5.5, 7.0)) }
        doc = doc.updateTransition("ID6", "ID20") { it.copy(guard = "n > limit") }
        val added = doc.addPlace("ID6", Point(200.0, 0.0))
        doc = added.document.updatePlace("ID6", added.id) { it.copy(colourSet = "NO") }
        val connected = doc.connect("ID6", "ID20", added.id) as no.hvl.cpn.io.model.ConnectResult.Connected
        doc = connected.document.updateArc("ID6", connected.arcId) { it.copy(inscription = "n") }
        doc = doc.delete("ID6", "ID40")

        val written = CpnToolsFormat.write(doc, imported.source)
        val again = CpnToolsFormat.read(written, "m").document

        val page = again.pages.single()
        assertEquals(listOf("Count & <down>", "P1"), page.places.map { it.name })
        assertEquals(Point(5.5, 7.0), page.places[0].position)
        assertEquals("n > limit", page.transitions.single().guard)
        assertEquals(listOf("n", "n"), page.arcs.map { it.inscription }, "the deleted arc is gone, the new one is there")
        assertEquals(page.places[1].id, page.arcs[1].placeId)
        assertTrue("val limit = 2;" in again.declarations)
        // What the document does not hold is kept: the place's fill colour and the monitor.
        assertTrue("""<fillattr colour="Red" pattern="" filled="true"/>""" in written)
        assertTrue("""<monitor id="ID60" name="Count" type="0"/>""" in written)
        assertTrue("Count &amp; &lt;down&gt;" in written)
        // The edited model still runs, with the new guard.
        val net = assertNotNull(ModelFiles.open(written, "m").net)
        assertEquals(1, Simulator(net, seed = 1).run(10).stepsTaken, "3 > limit, then 2 is not")
    }

    @Test
    fun aModelMadeFromScratchBecomesACompleteCpnToolsFile() {
        val written = CpnToolsFormat.write(fromScratch)
        val root = Xml.parse(written).root
        assertEquals("workspaceElements", root.name)
        assertEquals("CPN Tools", root.child("generator")!!["tool"])
        val cpnet = root.child("cpnet")!!
        assertEquals(
            listOf("globbox", "page", "fusion", "instances", "options", "binders", "monitorblock"),
            cpnet.elements
                .map {
                    it.name
                }.distinctConsecutive(),
        )
        assertEveryReferenceResolves(root)
        // Ids like "Main.place1" are not CPN Tools ids: they are replaced consistently.
        assertTrue(Regex("""id="[^"]*\.""").find(written) == null, "no id with a dot")

        val again = CpnToolsFormat.read(written, "scratch").document
        // The socket was glued to the port by name; CPN Tools needs the pair written out, so it comes back explicit.
        val explicit =
            fromScratch.copy(
                pages =
                    listOf(
                        fromScratch.pages[0].let { p ->
                            p.copy(
                                transitions =
                                    p.transitions.map { t ->
                                        if (t.subpageId ==
                                            null
                                        ) {
                                            t
                                        } else {
                                            t.copy(portAssignments = mapOf("Main.place2" to "Page1.place1"))
                                        }
                                    },
                            )
                        },
                        fromScratch.pages[1],
                    ),
            )
        assertEquals(canonical(explicit), canonical(again))
        // And it behaves the same.
        val traceBefore =
            Simulator(
                assertNotNull(
                    ModelFiles
                        .open(
                            no.hvl.cpn.io.CpnDocumentFormat
                                .write(fromScratch),
                        ).net,
                ),
                seed = 9,
            ).run(50)
        val traceAfter = Simulator(assertNotNull(ModelFiles.open(written, "scratch").net), seed = 9).run(50)
        assertEquals(traceBefore.stepsTaken, traceAfter.stepsTaken)
        assertEquals(traceBefore.marking.toString(), traceAfter.marking.toString())
    }

    @Test
    fun declarationsThatDoNotParseCannotBeWritten() {
        assertFailsWith<ModelFormatException> { CpnToolsFormat.write(fromScratch.copy(declarations = "colset = ;")) }
    }

    /** F6, property 4: random nets survive export and import unchanged, and exporting again changes nothing. */
    @Test
    fun randomNetsRoundTrip() {
        repeat(500) { seed ->
            val doc = randomNet(Random(seed), appIds = seed % 2 == 0)
            val written = CpnToolsFormat.write(doc)
            val imported = CpnToolsFormat.read(written, doc.name)
            assertEquals(canonical(doc), canonical(imported.document), "seed $seed")
            val rewritten = CpnToolsFormat.write(imported.document, imported.source)
            assertEquals(written, rewritten, "seed $seed: exporting an unchanged import gives the same bytes")
            val third = CpnToolsFormat.read(rewritten, doc.name)
            assertEquals(rewritten, CpnToolsFormat.write(third.document, third.source), "seed $seed: fixed point")
            // The app's own format (save, open, save): nothing lost, and the same file again.
            val saved =
                no.hvl.cpn.io.CpnDocumentFormat
                    .write(doc)
            assertEquals(
                doc,
                no.hvl.cpn.io.CpnDocumentFormat
                    .read(saved),
                "seed $seed: document format",
            )
            assertEquals(
                saved,
                no.hvl.cpn.io.CpnDocumentFormat
                    .write(
                        no.hvl.cpn.io.CpnDocumentFormat
                            .read(saved),
                    ),
                "seed $seed",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------

    private val fromScratch =
        CpnDocument(
            "scratch",
            "colset NO = int with 0..3;\ncolset C = with red | green;\nvar n : NO;\nvar c : C;\n",
            listOf(
                PageDocument(
                    "Main",
                    "Main",
                    listOf(
                        PlaceNode("Main.place1", "Count", "NO", "1`3", Point(0.0, 0.0)),
                        PlaceNode("Main.place2", "Colours", "C", "1`red++1`green", Point(0.0, 100.0), fusionSet = "F"),
                    ),
                    listOf(
                        TransitionNode("Main.transition1", "Dec", "n > 0", Point(100.0, 0.0)),
                        TransitionNode("Main.substitution1", "Sub", "", Point(100.0, 100.0), subpageId = "Page1"),
                    ),
                    listOf(
                        ArcEdge("Main.arc1", "Main.place1", "Main.transition1", ArcDirection.PLACE_TO_TRANSITION, "n"),
                        ArcEdge(
                            "Main.arc2",
                            "Main.place1",
                            "Main.transition1",
                            ArcDirection.TRANSITION_TO_PLACE,
                            "n - 1",
                            listOf(Point(50.0, -30.0)),
                        ),
                        ArcEdge("Main.arc3", "Main.place2", "Main.substitution1", ArcDirection.BIDIRECTIONAL, ""),
                    ),
                ),
                PageDocument(
                    "Page1",
                    "Inner",
                    listOf(PlaceNode("Page1.place1", "Colours", "C", "", Point(0.0, 0.0), port = PortType.IN_OUT)),
                    listOf(TransitionNode("Page1.transition1", "Flip", "", Point(100.0, 0.0))),
                    listOf(ArcEdge("Page1.arc1", "Page1.place1", "Page1.transition1", ArcDirection.BIDIRECTIONAL, "c")),
                ),
            ),
        )

    /** [d] with ids replaced by their position, so documents can be compared across fresh ids. */
    private fun canonical(d: CpnDocument): String {
        val ids = HashMap<String, String>()
        d.pages.forEachIndexed { p, page ->
            ids[page.id] = "page$p"
            page.places.forEachIndexed { i, it -> ids[it.id] = "page$p.place$i" }
            page.transitions.forEachIndexed { i, it -> ids[it.id] = "page$p.trans$i" }
            page.arcs.forEachIndexed { i, it -> ids[it.id] = "page$p.arc$i" }
        }

        fun r(p: Point) = "(${number(p.x)},${number(p.y)})"
        return buildString {
            appendLine(d.declarations.trim())
            for (page in d.pages) {
                appendLine("page ${ids[page.id]} ${page.name}")
                for (it in page.places) {
                    appendLine(
                        "  place ${ids[it.id]} ${it.name} ${it.colourSet} ${it.initialMarking} ${r(
                            it.position,
                        )} ${it.port} ${it.fusionSet}",
                    )
                }
                for (it in page.transitions) {
                    val ports =
                        it.portAssignments.entries
                            .map { (s, p) -> "${ids[s]}=${ids[p]}" }
                            .sorted()
                    appendLine(
                        "  trans ${ids[it.id]} ${it.name} [${it.guard}] ${r(
                            it.position,
                        )} ${it.subpageId?.let(ids::get)} $ports ${it.time} ${it.priority} ${it.code}",
                    )
                }
                for (it in page.arcs) {
                    val ends = "${ids[it.placeId]} ${ids[it.transitionId]}"
                    appendLine("  arc ${ids[it.id]} $ends ${it.direction} ${it.kind} [${it.inscription}] ${it.bends.map(::r)}")
                }
            }
        }
    }

    private fun randomNet(
        random: Random,
        appIds: Boolean,
    ): CpnDocument {
        val colourSets =
            listOf(
                "NO" to listOf("1`2", "2`0++1`5", ""),
                "C" to listOf("1`red", "1`red++2`green", ""),
                "P" to listOf("1`(1,red)", ""),
                "UNIT" to listOf("1`()", ""),
            )
        val inscriptions = mapOf("NO" to "n", "C" to "c", "P" to "(n,c)", "UNIT" to "()")
        val names = listOf("A", "Buffer", "Packets\nTo Send", "a & b", "<x>", "Data \"Received\"", "t'1")
        var counter = 0

        fun id(page: String) = if (appIds) "$page.element${counter++}" else "ID${1000 + counter++}"

        fun point() = Point(random.nextInt(-5000, 5000) / 10.0, random.nextInt(-5000, 5000) / 10.0)

        val pageCount = random.nextInt(1, 4)
        val pageIds = List(pageCount) { if (appIds) "Page${it + 1}" else "ID${100 + it}" }
        val pages =
            pageIds.mapIndexed { p, pageId ->
                val places =
                    List(random.nextInt(0, 6)) {
                        val (cs, markings) = colourSets.random(random)
                        PlaceNode(
                            id(pageId),
                            names.random(random),
                            cs,
                            markings.random(random),
                            point(),
                            port = if (p > 0 && random.nextInt(3) == 0) PortType.entries.random(random) else null,
                            fusionSet = if (random.nextInt(5) == 0) "F${random.nextInt(2)}" else null,
                        )
                    }
                val transitions =
                    List(random.nextInt(0, 4)) {
                        TransitionNode(
                            id(pageId),
                            names.random(random),
                            listOf("", "n > 1", "[n < 5, c = red]").random(random),
                            point(),
                            time = listOf("", "@+3").random(random),
                            priority = listOf("", "P_HIGH").random(random),
                        )
                    }
                val arcs =
                    if (places.isEmpty() || transitions.isEmpty()) {
                        emptyList()
                    } else {
                        List(random.nextInt(0, 6)) {
                            val place = places.random(random)
                            ArcEdge(
                                id(pageId),
                                place.id,
                                transitions.random(random).id,
                                ArcDirection.entries.random(random),
                                inscriptions.getValue(place.colourSet),
                                List(random.nextInt(0, 3)) { point() },
                            )
                        }
                    }
                PageDocument(pageId, "Page $p", places, transitions, arcs)
            }
        // Make the first transition of page 0 a substitution for page 1, gluing one socket to a port.
        val withHierarchy =
            if (pages.size > 1 && pages[0].transitions.isNotEmpty() && pages[0].places.isNotEmpty() && random.nextBoolean()) {
                val top = pages[0]
                val sub = top.transitions[0]
                val socket = top.places[0]
                val port = PlaceNode(id(pages[1].id), socket.name, socket.colourSet, "", point(), port = PortType.IN_OUT)
                val inner = pages[1].copy(places = pages[1].places + port)
                val arcs =
                    top.arcs.filter { it.transitionId != sub.id } + ArcEdge(id(top.id), socket.id, sub.id, ArcDirection.BIDIRECTIONAL, "")
                listOf(
                    top.copy(
                        transitions =
                            listOf(
                                sub.copy(
                                    guard = "",
                                    time = "",
                                    priority = "",
                                    subpageId = inner.id,
                                    portAssignments =
                                        mapOf(
                                            socket.id to port.id,
                                        ),
                                ),
                            ) +
                                top.transitions.drop(1),
                        arcs = arcs,
                    ),
                    inner,
                ) + pages.drop(2)
            } else {
                pages
            }
        return CpnDocument(
            "random",
            "colset NO = int with 0..9;\ncolset C = with red | green;\ncolset P = product NO * C;\nvar n : NO;\nvar c : C;\n",
            withHierarchy,
        )
    }

    private fun assertEveryReferenceResolves(root: XmlElement) {
        val ids = mutableListOf<String>()

        fun collect(e: XmlElement) {
            e["id"]?.let { ids += it }
            e.elements.forEach(::collect)
        }
        collect(root)
        assertEquals(ids.size, ids.toSet().size, "ids are unique: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
        val known = ids.toSet()
        val refs = mutableListOf<String>()

        fun refs(e: XmlElement) {
            listOf("idref", "page", "trans", "instance", "subpage").forEach { a -> e[a]?.let { refs += it } }
            e["portsock"]?.let { s -> Regex("""[^(),]+""").findAll(s).forEach { refs += it.value } }
            e.elements.forEach(::refs)
        }
        refs(root.child("cpnet")!!)
        assertEquals(emptyList(), refs.filter { it !in known }, "every reference points at an element")
    }

    private fun List<String>.distinctConsecutive(): List<String> = filterIndexed { i, s -> i == 0 || this[i - 1] != s }
}
