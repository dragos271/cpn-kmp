package no.hvl.cpn.io.cpntools

import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.sim.Simulator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The models made with this tool for checking in CPN Tools (T3.1b, docs/interop-log.md): each
 * committed `.cpn` in interop/cpn is exactly what `cpn convert` writes for its source, and runs like it.
 */
class InteropModelsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "interop/models").isDirectory }

    private val sources =
        File(root, "interop/models").listFiles { f -> f.extension == "cpnd" }!!.sorted().associate { it.nameWithoutExtension to it } +
            ("simple-protocol" to File(root, "examples/simple-protocol.json"))

    @Test
    fun theCommittedFilesAreWhatTheConverterWrites() {
        for ((name, source) in sources) {
            val model = ModelFiles.open(source.readBytes(), name)
            val expected = ModelFiles.exportCpn(model, model.document)
            val committed = File(root, "interop/cpn/$name.cpn")
            assertEquals(
                expected.decodeToString(),
                committed.readText(Charsets.ISO_8859_1),
                "$name: run `cpn convert` again to update interop/cpn/$name.cpn",
            )
        }
    }

    @Test
    fun eachCpnFileRunsLikeItsSource() {
        for ((name, source) in sources) {
            val original = assertNotNull(ModelFiles.open(source.readBytes(), name).net, name)
            val exported = assertNotNull(ModelFiles.open(File(root, "interop/cpn/$name.cpn").readBytes(), name).net, name)
            for (seed in 1L..3L) {
                val a = Simulator(original, seed).apply { run(500) }
                val b = Simulator(exported, seed).apply { run(500) }
                assertEquals(a.trace.map { it.toString() }, b.trace.map { it.toString() }, "$name, seed $seed")
            }
        }
    }
}
