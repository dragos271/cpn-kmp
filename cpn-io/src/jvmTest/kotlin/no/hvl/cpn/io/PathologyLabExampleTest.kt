package no.hvl.cpn.io

import no.hvl.cpn.io.model.compile
import no.hvl.cpn.sim.perf.Monitor
import no.hvl.cpn.sim.perf.Performance
import no.hvl.cpn.sim.perf.RunLimits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pathology lab example from the supervisor's whiteboard sketch (F15) keeps running: with its
 * Python delay_section, with the CPN ML fallback (as in CPN Tools), and as a .cpn file.
 */
class PathologyLabExampleTest {
    private val folder =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "examples/pathology-lab") }
            .first { File(it, "patholab.cpnd").isFile }

    private fun week(
        text: String,
        directory: String?,
    ): Map<String, Double> {
        val compiled = ModelFiles.open(text, "patholab", directory).document.compile(directory = directory)
        val net = assertNotNull(compiled.net, compiled.problems.joinToString())
        val turnaround = compiled.expression("intTime () - #arrived c")
        val monitors =
            listOf(
                Monitor.Observe("Turnaround", "Answered") { b, t -> (turnaround(b, t) as Number).toDouble() },
                Monitor.Occurrences("Answered", "Answered"),
                Monitor.MarkingSize("Idle workers", listOf("Worker pool"), availableOnly = true),
            )
        val run = Performance.run(net, monitors, RunLimits(untilTime = 7 * 1440), seed = 1)
        return run.monitors.associate { it.monitor to it.mean }
    }

    @Test
    fun theExampleRunsWithPython() {
        val r = week(File(folder, "patholab.cpnd").readText(), folder.path)
        // Processing alone takes 24 hours, so every case takes more than a day.
        assertTrue(r.getValue("Turnaround") > 1440, "turnaround $r")
        assertTrue(r.getValue("Answered") * 5 * 1440 > 100, "cases are answered: $r")
        assertTrue(r.getValue("Idle workers") in 0.0..2.0, "$r")
    }

    @Test
    fun theExampleRunsWithItsCpnMlFallbackAsInCpnTools() {
        val withoutPython = File(folder, "patholab.cpnd").readText().replace("@python patholab.py", "no python here")
        assertTrue(week(withoutPython, folder.path).getValue("Turnaround") > 1440)
    }

    @Test
    fun theCpnToolsFileIsTheSameModel() {
        val fromCpn = week(File(folder, "patholab.cpn").readText(Charsets.ISO_8859_1), folder.path)
        assertEquals(week(File(folder, "patholab.cpnd").readText(), folder.path), fromCpn, "the same seed gives the same week")
    }
}
