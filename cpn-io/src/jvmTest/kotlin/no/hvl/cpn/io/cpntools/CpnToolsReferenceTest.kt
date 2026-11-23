package no.hvl.cpn.io.cpntools

import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.statespace.StateSpace
import no.hvl.cpn.statespace.StateSpaceStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * State space sizes computed by CPN Tools, from Garvik (2022), Table 6.1 and Appendix B: an oracle
 * independent of this code. How each model was obtained is in reference/garvik-2022/README.md.
 * JVM only, because it reads files.
 */
class CpnToolsReferenceTest {
    private val root =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "corpus/sources.json").isFile }

    /** The model in [path], with `val [name] = ...;` set to [value]. */
    private fun model(
        path: String,
        name: String? = null,
        value: Int = 0,
    ): ByteArray {
        val text = File(root, path).readBytes().toString(Charsets.ISO_8859_1)
        if (name == null) return text.toByteArray(Charsets.ISO_8859_1)
        val declaration = Regex("""val $name\s*=\s*\d+\s*;""")
        check(declaration.containsMatchIn(text)) { "$path declares no val $name" }
        return text.replace(declaration, "val $name = $value;").toByteArray(Charsets.ISO_8859_1)
    }

    private fun assertSize(
        nodes: Int,
        arcs: Int,
        bytes: ByteArray,
        name: String,
    ) {
        val ss = StateSpace.build(ModelFiles.open(bytes, name).net!!)
        assertEquals(StateSpaceStatus.FULL, ss.status, name)
        assertEquals(nodes to arcs, ss.nodeCount to ss.arcCount, "$name: nodes and arcs")
    }

    @Test
    fun twoPhaseCommit() {
        val file = "reference/garvik-2022/two-phase-commit.cpnd"
        assertSize(43, 64, model(file, "W", 2), "2PC, 2 workers")
        assertSize(281, 512, model(file, "W", 3), "2PC, 3 workers")
        assertSize(2323, 4774, model(file, "W", 4), "2PC, 4 workers")
    }

    @Test
    fun diningPhilosophers() {
        val file = "corpus/cpn-js/sample-cpn-models/DiningPhilosophers/DiningPhilosophers.cpn"
        assertSize(11, 30, model(file, "n", 5), "5 philosophers")
        assertSize(123, 680, model(file, "n", 10), "10 philosophers")
        assertSize(1364, 11310, model(file, "n", 15), "15 philosophers")
    }

    @Test
    fun simpleProtocolAndResourceAllocation() {
        assertSize(428, 1130, model("reference/garvik-2022/simple-protocol.cpnd"), "simple protocol")
        assertSize(13, 20, model("reference/garvik-2022/resource-allocation.cpnd"), "resource allocation")
    }

    @Test
    fun distributedDatabase() {
        val file = "corpus/cpn-js/sample-cpn-models/DistributedDataBase/DistributedDataBase.cpn"
        assertSize(28, 42, model(file, "n", 3), "3 databases")
        assertSize(1459, 4872, model(file, "n", 6), "6 databases")
        assertSize(59050, 314946, model(file, "n", 9), "9 databases")
    }
}
