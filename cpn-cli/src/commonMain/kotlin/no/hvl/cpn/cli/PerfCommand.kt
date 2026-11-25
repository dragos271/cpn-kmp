package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import no.hvl.cpn.api.perf.ModelValues
import no.hvl.cpn.api.perf.MonitorFile
import no.hvl.cpn.api.perf.Scenario
import no.hvl.cpn.api.perf.StudyException
import no.hvl.cpn.api.perf.formatReport
import no.hvl.cpn.api.perf.study
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.lang.eval.EvaluationException
import no.hvl.cpn.sim.perf.MonitorSummary
import no.hvl.cpn.sim.perf.RunLimits

/**
 * `cpn perf model --monitors m.json --replications 30 --until-time 100000`: simulation-based
 * performance analysis (F14). Runs independent replications, collects the monitors' statistics, and
 * reports each monitor's mean with a 95 % confidence interval. `--set W=3,4,5` compares scenarios.
 */
class PerfCommand(
    private val readFile: (String) -> ByteArray,
    private val writeFile: (String, ByteArray) -> Unit,
) : CliktCommand(name = "perf") {
    private val file by argument(help = "Model file: a CPN Tools .cpn file or an editable cpn-document")
    private val monitorsFile by option(
        "--monitors",
        "-m",
        help = "JSON file with the monitors (see docs/performance-reports.md); default: every place's size and every transition's rate",
    )
    private val replications by option("--replications", "-r", help = "Independent runs per scenario").int().restrictTo(min = 1).default(10)
    private val untilTime by option(
        "--until-time",
        "-t",
        help = "Model time at which each run stops (timed nets)",
    ).long().restrictTo(min = 1)
    private val steps by option("--steps", "-n", help = "Maximum steps per run").int().restrictTo(min = 1).default(1_000_000)
    private val warmup by option(
        "--warmup",
        help = "Model time (or steps) at the start of each run that is not measured",
    ).long().restrictTo(min = 0).default(0)
    private val seed by option("--seed", "-s", help = "Seed of the first run; run i uses seed + i").long().default(1)
    private val values by option(
        "--set",
        help = "Set a declared value; several values give one scenario each, e.g. --set WORKERS=2,3,4 (repeatable)",
    ).multiple()
    private val format by option("--format", "-f", help = "Output format").choice("text", "markdown", "json").default("text")
    private val output by option("--output", "-o", help = "Write the report to this file instead of printing it")

    override fun help(context: Context): String =
        "Performance analysis: repeated simulation runs with monitors, reported with 95 % confidence intervals."

    override fun run() {
        val bytes =
            try {
                readFile(file)
            } catch (e: Exception) {
                throw CliktError("Cannot read '$file': ${e.message}", statusCode = 1)
            }
        val specs =
            monitorsFile?.let { path ->
                val text =
                    try {
                        readFile(path).decodeToString()
                    } catch (e: Exception) {
                        throw CliktError("Cannot read '$path': ${e.message}", statusCode = 1)
                    }
                study { MonitorFile.parse(text) }
            }
        val limits = study { RunLimits(untilTime = untilTime, maxSteps = steps, warmup = warmup) }
        val directory = ModelFiles.directoryOf(file)
        val model = errors { ModelFiles.open(bytes, ModelFiles.modelName(file), directory) }
        for (note in model.notes) echo("note: $note", err = true)
        if (untilTime == null && model.net?.isTimed == true) echo("note: no --until-time; each run stops after $steps steps", err = true)
        val scenarios =
            errors {
                study {
                    model.document.study(specs, limits, replications, seed, values.map(ModelValues::parseSweep), directory)
                }
            }

        val text =
            when (format) {
                "json" -> json(scenarios)
                "markdown" -> formatReport(scenarios, seed, markdown = true)
                else -> formatReport(scenarios, seed, markdown = false)
            }
        val target = output
        if (target == null) {
            echo(text, trailingNewline = !text.endsWith("\n"))
        } else {
            writeFile(target, text.encodeToByteArray())
            echo("Wrote $target", err = true)
        }
    }

    private fun <T> study(block: () -> T): T =
        try {
            block()
        } catch (e: StudyException) {
            throw CliktError("$file: ${e.message}", statusCode = 1)
        } catch (e: IllegalArgumentException) {
            if (e is EvaluationException) throw e
            throw CliktError("$file: ${e.message}", statusCode = 1)
        }

    private fun <T> errors(block: () -> T): T =
        try {
            block()
        } catch (e: ModelFormatException) {
            throw CliktError("$file: ${e.message}", statusCode = 1)
        } catch (e: EvaluationException) {
            throw CliktError("$file: simulation failed: ${e.message}", statusCode = 1)
        } catch (e: IllegalStateException) {
            throw CliktError("$file: simulation failed: ${e.message}", statusCode = 1)
        }

    // ---------------------------------------------------------------------------------------------
    // Output

    private fun json(scenarios: List<Scenario>): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                val first = scenarios.first().report
                put("net", first.net)
                put("timed", first.timed)
                put("replications", first.runs.size)
                first.limits.untilTime?.let { put("untilTime", it) }
                put("maxSteps", first.limits.maxSteps)
                put("warmup", first.limits.warmup)
                put("seed", seed)
                putJsonArray("scenarios") {
                    for (scenario in scenarios) {
                        addJsonObject {
                            putJsonObject("set") { scenario.values.forEach { (k, v) -> put(k, v) } }
                            putJsonArray("monitors") { scenario.report.summaries.forEach { addJsonObject { summary(it) } } }
                            putJsonArray("runs") {
                                for (run in scenario.report.runs) {
                                    addJsonObject {
                                        put("seed", run.seed)
                                        put("steps", run.steps)
                                        put("endTime", run.endTime)
                                        put("end", run.end.name)
                                        putJsonArray("monitors") {
                                            for (m in run.monitors) {
                                                addJsonObject {
                                                    put("monitor", m.monitor)
                                                    put("count", m.count)
                                                    put("mean", m.mean)
                                                    put("min", m.min)
                                                    put("max", m.max)
                                                    m.standardDeviation?.let { put("standardDeviation", it) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )

    private fun kotlinx.serialization.json.JsonObjectBuilder.summary(s: MonitorSummary) {
        put("monitor", s.monitor)
        put("mean", s.mean)
        put("standardDeviation", s.standardDeviation)
        put("ciLow", s.low)
        put("ciHigh", s.high)
        put("minRun", s.min)
        put("maxRun", s.max)
    }
}
