package no.hvl.cpn.io.cpntools

import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.sim.BindingSearch
import no.hvl.cpn.sim.Semantics
import no.hvl.cpn.statespace.StateSpace
import no.hvl.cpn.statespace.StateSpaceStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * State spaces of textbook models from the corpus (T3.3), with sizes known independently of this
 * code: worked out by hand for the first two; for the limit protocol, the sizes recalled from Jensen &
 * Kristensen (2009), still to be checked against the book. JVM only, because it reads files.
 */
class CorpusStateSpaceTest {
    private val corpus =
        generateSequence(File("").absoluteFile) {
            it.parentFile
        }.map { File(it, "corpus") }.first { File(it, "sources.json").isFile }

    private fun stateSpace(file: String) =
        StateSpace.build(ModelFiles.open(File(corpus, file).readBytes(), File(file).nameWithoutExtension).net!!)

    @Test
    fun diningPhilosophers() {
        // The markings are the sets of eating philosophers with no two neighbours: 1 + 5 + 5 = 11.
        // Arcs: 5 from nobody eating, 3 from each single eater, 2 from each pair: 5 + 15 + 10 = 30.
        val ss = stateSpace("cpn-js/sample-cpn-models/DiningPhilosophers/DiningPhilosophers.cpn")
        assertEquals(StateSpaceStatus.FULL, ss.status)
        assertEquals(11, ss.nodeCount)
        assertEquals(30, ss.arcCount)
        assertEquals(emptyList(), ss.deadMarkings)
        assertConsistent(ss)
    }

    @Test
    fun theDeterministicProtocolIsAChain() {
        val ss = stateSpace("cpnbook/models/chapter2/2-1DeterministicProtocol.cpn")
        assertEquals(31, ss.nodeCount)
        assertEquals(30, ss.arcCount)
        assertEquals(listOf(31), ss.deadMarkings)
        assertConsistent(ss)
    }

    @Test
    fun theLimitProtocol() {
        val ss = stateSpace("cpnbook/models/chapter7/7-2LimitProtocol.cpn")
        assertEquals(StateSpaceStatus.FULL, ss.status)
        assertEquals(13_215, ss.nodeCount)
        assertEquals(52_784, ss.arcCount)
        // One dead marking: every packet delivered.
        val dead = ss.deadMarkings.single()
        val received =
            ss.flatNet.net.pages
                .single()
                .places
                .single { it.name.startsWith("Protocol'Data Received") }
        assertEquals("1`\"COLOURED PETRI NET\"", ss.marking(dead).tokensOf(received))
        assertConsistent(ss)
    }

    /** Every arc is a firing of its source, and every node has an arc for each enabled binding element. */
    private fun assertConsistent(ss: StateSpace) {
        val semantics = Semantics(ss.flatNet.net)
        val search = BindingSearch(semantics)
        for (node in ss.nodes) {
            val marking = ss.marking(node)
            val arcs = ss.outArcs(node)
            assertEquals(search.enabledBindingElements(marking).toSet(), arcs.map { it.element }.toSet(), "node $node")
            for (arc in arcs) assertEquals(ss.marking(arc.to), semantics.fire(arc.element, marking), "arc $arc")
        }
    }
}
