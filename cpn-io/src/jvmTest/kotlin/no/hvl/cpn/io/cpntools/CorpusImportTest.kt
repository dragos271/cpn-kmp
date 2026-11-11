package no.hvl.cpn.io.cpntools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.model.compile
import no.hvl.cpn.sim.Simulator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Imports every model of the corpus (T3.5) and tries to run it (T3.1). JVM only, because it reads
 * files. Writes `build/reports/corpus-import.md`, the table docs/corpus-results.md is made from.
 *
 * Fails if any model cannot be imported at all, or if fewer models run than [MIN_RUNNING], so a
 * change that makes the importer or the engine worse on real models is noticed.
 */
class CorpusImportTest {
    private val corpus =
        generateSequence(File("").absoluteFile) {
            it.parentFile
        }.map { File(it, "corpus") }.first { File(it, "sources.json").isFile }

    private enum class Outcome { RUNS, PROBLEMS, RUN_FAILED, IMPORT_FAILED }

    private class Result(
        val file: String,
        val outcome: Outcome,
        val detail: String,
        val notes: List<String>,
    )

    @Test
    fun everyCorpusModelImports() {
        val files =
            (Json.parseToJsonElement(File(corpus, "sources.json").readText()) as JsonArray).map {
                (it as JsonObject)["file"]!!.jsonPrimitive.content
            }
        val results = files.map(::tryModel)
        writeReport(results)

        val failed = results.filter { it.outcome == Outcome.IMPORT_FAILED }
        assertEquals(emptyList(), failed.map { "${it.file}: ${it.detail}" }, "every corpus model must import")
        val running = results.count { it.outcome == Outcome.RUNS }
        assertTrue(running >= MIN_RUNNING, "only $running of ${results.size} corpus models run; expected at least $MIN_RUNNING")
    }

    @Test
    fun theTextbookProtocolDeliversItsData() {
        // Jensen & Kristensen, figure 2.1, imported from the book's own .cpn file.
        val file = "cpnbook/models/chapter2/2-1DeterministicProtocol.cpn"
        val net = ModelFiles.open(File(corpus, file).readBytes(), "2-1DeterministicProtocol").net!!
        val simulator = Simulator(net, seed = 2026)
        val result = simulator.run(1000)
        assertEquals(30, result.stepsTaken, "6 packets, 5 steps each")
        val received =
            simulator.flatNet.net.pages
                .flatMap { it.places }
                .single { it.name.startsWith("Sequential'Packets Received") }
        assertEquals(
            "1`(1,\"COL \")++1`(2,\"OUR\")++1`(3,\"ED \")++1`(4,\"PET\")++1`(5,\"RI \")++1`(6,\"NET\")",
            result.marking.tokensOf(received).toString(),
        )
    }

    @Test
    fun theTimedProtocolKeepsModelTime() {
        // Jensen & Kristensen, section 10.1, from the book's own .cpn file.
        val file = "cpnbook/models/chapter10/10-1TimedProtocol.cpn"
        val net = ModelFiles.open(File(corpus, file).readBytes(), "TimedProtocol").net!!
        for (seed in 1L..20L) {
            val simulator = Simulator(net, seed)
            val result = simulator.run(10_000)
            assertEquals(no.hvl.cpn.sim.StopReason.DEAD, result.reason, "seed $seed")
            val times = simulator.trace.map { it.time!! }
            assertEquals(times.sorted(), times, "seed $seed: the clock never goes backwards")
            // Send Packet at 0 puts (1,"COL") on A at 0 + 9, so Transmit Packet is next, at 9.
            assertEquals("1 @0 TimedProtocol'Send Packet 1 <d=\"COL\",n=1>", simulator.trace[0].toString())
            assertEquals(9, simulator.trace[1].time, "seed $seed")
            val received =
                simulator.flatNet.net.pages
                    .flatMap { it.places }
                    .single { it.name.startsWith("TimedProtocol'Data Received") }
            assertEquals(listOf("COLOURED PETRI  NET"), result.marking[received].distinct.toList(), "seed $seed")
        }
    }

    @Test
    fun theLayoutComesFromTheFile() {
        // The values in the book's .cpn file (y negated: CPN Tools' y axis points up).
        val bytes = File(corpus, "cpnbook/models/chapter10/10-1TimedProtocol.cpn").readBytes()
        val document = ModelFiles.open(bytes, "TimedProtocol").document
        val page = document.pages.single()
        val packets = page.place("ID1784")!!
        assertEquals(
            no.hvl.cpn.io.model
                .Size(78.0, 44.0),
            packets.size,
        )
        assertEquals(
            no.hvl.cpn.io.model
                .Point(94.0, -58.0),
            packets.colourSetLabel,
        )
        assertEquals(
            no.hvl.cpn.io.model
                .Point(91.5, -109.0),
            packets.markingLabel,
        )
        val send = page.transition("ID1794")!!
        assertEquals(
            no.hvl.cpn.io.model
                .Size(70.0, 39.0),
            send.size,
        )
        assertEquals(
            no.hvl.cpn.io.model
                .Point(208.433333, 55.5),
            send.timeLabel,
        )
        assertEquals(
            no.hvl.cpn.io.model
                .Point(110.933333, -5.5),
            send.guardLabel,
        )
        val arc = page.arcs.single { it.id == "ID1815" }
        assertEquals(
            no.hvl.cpn.io.model
                .Point(206.0, -28.0),
            arc.label,
        )
        // The app's own format keeps it all.
        assertEquals(
            document,
            no.hvl.cpn.io.CpnDocumentFormat
                .read(
                    no.hvl.cpn.io.CpnDocumentFormat
                        .write(document),
                ),
        )
    }

    @Test
    fun movedLabelsAreExported() {
        val text =
            no.hvl.cpn.io.xml.Xml
                .decode(File(corpus, "cpnbook/models/chapter10/10-1TimedProtocol.cpn").readBytes())
        val imported = CpnToolsFormat.read(text, "TimedProtocol")
        val page = imported.document.pages.single()
        // Dragging Packets To Send 10 to the right moves its labels and half of its arcs' labels.
        val moved =
            imported.document.withPage(
                page.moved(
                    "ID1784",
                    page.place("ID1784")!!.position +
                        no.hvl.cpn.io.model
                            .Point(10.0, 0.0),
                ),
            )
        val written = CpnToolsFormat.write(moved, imported.source)
        val again =
            CpnToolsFormat
                .read(written, "TimedProtocol")
                .document.pages
                .single()
        assertEquals(
            no.hvl.cpn.io.model
                .Point(104.0, -58.0),
            again.place("ID1784")!!.colourSetLabel,
        )
        assertEquals(
            no.hvl.cpn.io.model
                .Point(211.0, -28.0),
            again.arcs.single { it.id == "ID1815" }.label,
        )
        assertEquals(page.transition("ID1794")!!.timeLabel, again.transition("ID1794")!!.timeLabel, "other labels stay")
    }

    private fun tryModel(file: String): Result {
        val model =
            try {
                ModelFiles.open(File(corpus, file).readBytes(), ModelFiles.modelName(file))
            } catch (e: Exception) {
                return Result(file, Outcome.IMPORT_FAILED, "${e::class.simpleName}: ${e.message}", emptyList())
            }
        val net = model.net
        if (net == null) {
            val problems = model.document.compile().problems
            val first = problems.firstOrNull()?.let { "${it.field}: ${it.message}" }
            return Result(file, Outcome.PROBLEMS, "${problems.size} problem(s); first: $first", model.notes)
        }
        return try {
            val result = Simulator(net, seed = 1).run(STEPS)
            Result(file, Outcome.RUNS, "${result.stepsTaken} steps, ${result.reason}", model.notes)
        } catch (e: Exception) {
            Result(file, Outcome.RUN_FAILED, "${e::class.simpleName}: ${e.message?.lineSequence()?.first()}", model.notes)
        }
    }

    private fun writeReport(results: List<Result>) {
        val counts =
            Outcome.entries.joinToString(
                ", ",
            ) { o -> "${o.name.lowercase().replace('_', ' ')}: ${results.count { it.outcome == o }}" }
        val text =
            buildString {
                append("Corpus import: ${results.size} models; $counts\n\n")
                append("| Model | Outcome | Detail | Ignored or unsupported |\n|---|---|---|---|\n")
                for (r in results) {
                    fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
                    append("| `${r.file}` | ${r.outcome} | ${cell(r.detail)} | ${cell(r.notes.joinToString("; "))} |\n")
                }
            }
        File("build/reports").mkdirs()
        File("build/reports/corpus-import.md").writeText(text)
        println(text)
    }

    private companion object {
        const val STEPS = 200

        /** Raised as the importer and engine improve; never lowered without a reason in TASKS.md. */
        const val MIN_RUNNING = 26
    }
}
