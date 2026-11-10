package no.hvl.cpn.io.cpntools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.hvl.cpn.io.xml.Xml
import no.hvl.cpn.lang.ast.toSExpr
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round trips on every corpus model (T3.1b, T3.1c). JVM only, because it reads files.
 */
class CorpusRoundTripTest {
    private val corpus =
        generateSequence(File("").absoluteFile) {
            it.parentFile
        }.map { File(it, "corpus") }.first { File(it, "sources.json").isFile }

    private val files =
        (Json.parseToJsonElement(File(corpus, "sources.json").readText()) as JsonArray).map {
            (it as JsonObject)["file"]!!.jsonPrimitive.content
        }

    private fun text(file: String) = Xml.decode(File(corpus, file).readBytes())

    @Test
    fun theXmlReaderAndWriterReproduceEveryFileExactly() {
        val changed = files.filter { Xml.write(Xml.parse(text(it))) != text(it) }
        assertEquals(emptyList(), changed)
    }

    @Test
    fun exportingAnUnchangedImportGivesTheSameBytes() {
        val changed =
            files.mapNotNull {
                val imported = CpnToolsFormat.read(text(it), "m")
                val written = CpnToolsFormat.write(imported.document, imported.source)
                if (written == text(it)) null else "$it: ${firstDifference(text(it), written)}"
            }
        assertEquals(emptyList(), changed)
    }

    /** Export without the original file, as for a model made in the app, then import again: nothing may be lost. */
    @Test
    fun aGeneratedFileImportsAsTheSameModel() {
        val differences =
            files.mapNotNull { file ->
                val document = CpnToolsFormat.read(text(file), "m").document
                val written = CpnToolsFormat.write(document)
                val again = CpnToolsFormat.read(written, "m").document
                // A new file writes coordinates with six decimals, as CPN Tools does.
                // The declarations must say the same; a new file may lay them out differently (blank lines, a `;` between them).
                val expected = rounded(document)
                val actual = again.copy(declarations = document.declarations)
                when {
                    declarationTree(
                        again.declarations,
                    ) != declarationTree(document.declarations) -> "$file: ${firstDifference(document.declarations, again.declarations)}"
                    actual != expected -> "$file: ${firstDifference(expected, actual)}"
                    else -> null
                }
            }
        assertEquals(emptyList(), differences)
    }

    @Test
    fun exportingIsAFixedPointFromTheSecondExport() {
        val unstable =
            files.filter { file ->
                var written = CpnToolsFormat.write(CpnToolsFormat.read(text(file), "m").document)
                var stable = true
                repeat(4) {
                    val imported = CpnToolsFormat.read(written, "m")
                    val next = CpnToolsFormat.write(imported.document, imported.source)
                    if (next != written) stable = false
                    written = next
                }
                !stable
            }
        assertEquals(emptyList(), unstable)
    }

    private fun declarationTree(text: String) =
        no.hvl.cpn.lang.parse
            .CpnMlParser()
            .parseDeclarations(
                no.hvl.cpn.lang.ast
                    .SourceText("d", text),
            ).toSExpr()

    private fun rounded(d: no.hvl.cpn.io.model.CpnDocument): no.hvl.cpn.io.model.CpnDocument {
        fun r(p: no.hvl.cpn.io.model.Point) =
            no.hvl.cpn.io.model
                .Point(number(p.x).toDouble(), 0.0 - number(-p.y).toDouble())
        return d.copy(
            pages =
                d.pages.map { page ->
                    page.copy(
                        places = page.places.map { it.copy(position = r(it.position)) },
                        transitions = page.transitions.map { it.copy(position = r(it.position)) },
                        arcs = page.arcs.map { a -> a.copy(bends = a.bends.map(::r)) },
                    )
                },
        )
    }

    private fun firstDifference(
        a: String,
        b: String,
    ): String {
        val i = a.zip(b).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(a.length, b.length) else it }

        fun around(s: String) = s.substring(maxOf(0, i - 80), minOf(s.length, i + 80))
        return "at $i:\n--- file\n${around(a)}\n--- written\n${around(b)}"
    }

    private fun firstDifference(
        a: no.hvl.cpn.io.model.CpnDocument,
        b: no.hvl.cpn.io.model.CpnDocument,
    ): String {
        if (a.declarations != b.declarations) {
            val i =
                a.declarations.zip(b.declarations).indexOfFirst { (x, y) -> x != y }.let {
                    if (it <
                        0
                    ) {
                        minOf(a.declarations.length, b.declarations.length)
                    } else {
                        it
                    }
                }
            return "declarations differ at $i: ${a.declarations.substring(
                maxOf(0, i - 40),
                minOf(a.declarations.length, i + 40),
            )} | ${b.declarations.substring(
                maxOf(
                    0,
                    i - 40,
                ),
                minOf(b.declarations.length, i + 40),
            )}"
        }
        for ((pa, pb) in a.pages.zip(b.pages)) {
            if (pa == pb) continue
            pa.places
                .zip(pb.places)
                .firstOrNull { it.first != it.second }
                ?.let { return "place ${it.first} vs ${it.second}" }
            pa.transitions
                .zip(
                    pb.transitions,
                ).firstOrNull { it.first != it.second }
                ?.let { return "transition ${it.first} vs ${it.second}" }
            pa.arcs
                .zip(pb.arcs)
                .firstOrNull { it.first != it.second }
                ?.let { return "arc ${it.first} vs ${it.second}" }
            val sizes = "${pa.places.size}/${pb.places.size} ${pa.transitions.size}/${pb.transitions.size} ${pa.arcs.size}/${pb.arcs.size}"
            return "page ${pa.id}: ${pa.name}/${pb.name} $sizes"
        }
        return "pages ${a.pages.map { it.id }} vs ${b.pages.map { it.id }}"
    }
}
