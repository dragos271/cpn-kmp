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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.flatten
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.io.model.CompiledModel
import no.hvl.cpn.io.model.compile
import no.hvl.cpn.lang.eval.EvaluationException
import no.hvl.cpn.lang.parse.ParseException
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.sim.perf.Monitor
import no.hvl.cpn.sim.perf.MonitorSummary
import no.hvl.cpn.sim.perf.Performance
import no.hvl.cpn.sim.perf.PerformanceReport
import no.hvl.cpn.sim.perf.RunLimits
import kotlin.math.abs
import kotlin.math.roundToLong

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

    private class Scenario(
        val values: List<Pair<String, String>>,
        val report: PerformanceReport,
    ) {
        val label: String get() = if (values.isEmpty()) "" else values.joinToString(", ") { (k, v) -> "$k=$v" }
    }

    override fun run() {
        val bytes =
            try {
                readFile(file)
            } catch (e: Exception) {
                throw CliktError("Cannot read '$file': ${e.message}", statusCode = 1)
            }
        val specs = monitorsFile?.let { readMonitors(it) }
        val limits =
            try {
                RunLimits(untilTime = untilTime, maxSteps = steps, warmup = warmup)
            } catch (e: IllegalArgumentException) {
                throw CliktError(e.message, statusCode = 1)
            }
        val scenarios =
            scenarios(values.map(::parseSweep)).map { assignment ->
                val (net, compiled) = load(setValues(bytes, assignment, file))
                if (untilTime == null && net.isTimed && steps == 1_000_000) {
                    echo("note: no --until-time; each run stops after $steps steps", err = true)
                }
                val monitors = specs?.map { it.toMonitor(compiled) } ?: defaultMonitors(net)
                val report =
                    errors {
                        try {
                            Performance.replicate(net, monitors, limits, replications, seed)
                        } catch (e: IllegalArgumentException) {
                            throw CliktError("$file: ${e.message}", statusCode = 1)
                        }
                    }
                Scenario(assignment, report)
            }

        val text =
            when (format) {
                "json" -> json(scenarios)
                "markdown" -> table(scenarios, markdown = true)
                else -> table(scenarios, markdown = false)
            }
        val target = output
        if (target == null) {
            echo(text, trailingNewline = !text.endsWith("\n"))
        } else {
            writeFile(target, text.encodeToByteArray())
            echo("Wrote $target", err = true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Model and monitors

    private fun load(bytes: ByteArray): Pair<Net, CompiledModel?> {
        val model = errors { ModelFiles.open(bytes, ModelFiles.modelName(file)) }
        for (note in model.notes) echo("note: $note", err = true)
        val document = model.document ?: return (model.net ?: throw CliktError("$file: the model cannot run", statusCode = 1)) to null
        val compiled = document.compile()
        val net =
            compiled.net ?: throw CliktError(
                "$file: the model cannot run:\n" +
                    compiled.problems.joinToString("\n") { "  ${it.elementId ?: "declarations"}: ${it.message}" },
                statusCode = 1,
            )
        return net to compiled
    }

    private class MonitorSpec(
        val name: String,
        val type: String,
        val places: List<String>,
        val transition: String?,
        val expression: String?,
    )

    private fun readMonitors(path: String): List<MonitorSpec> {
        val text =
            try {
                readFile(path).decodeToString()
            } catch (e: Exception) {
                throw CliktError("Cannot read '$path': ${e.message}", statusCode = 1)
            }
        return try {
            val root = Json.parseToJsonElement(text).jsonObject
            root.getValue("monitors").jsonArray.map { element ->
                val o = element.jsonObject

                fun string(key: String) = (o[key] as? JsonPrimitive)?.content
                val type = string("type") ?: throw IllegalArgumentException("a monitor has no \"type\"")
                val places =
                    when (val p = o["places"] ?: o["place"]) {
                        null -> emptyList()
                        is JsonArray -> p.map { it.jsonPrimitive.content }
                        else -> listOf(p.jsonPrimitive.content)
                    }
                val spec = MonitorSpec(string("name") ?: type, type, places, string("transition"), string("expression"))
                when (type) {
                    "marking-size" -> require(places.isNotEmpty()) { "monitor '${spec.name}': \"places\" is required" }
                    "occurrences" -> requireNotNull(spec.transition) { "monitor '${spec.name}': \"transition\" is required" }
                    "observe" ->
                        require(spec.transition != null && spec.expression != null) {
                            "monitor '${spec.name}': \"transition\" and \"expression\" are required"
                        }
                    else -> throw IllegalArgumentException(
                        "monitor '${spec.name}': unknown type '$type' (marking-size, occurrences, observe)",
                    )
                }
                spec
            }
        } catch (e: CliktError) {
            throw e
        } catch (e: Exception) {
            throw CliktError("$path: ${e.message}", statusCode = 1)
        }
    }

    private fun MonitorSpec.toMonitor(compiled: CompiledModel?): Monitor =
        when (type) {
            "marking-size" -> Monitor.MarkingSize(name, places)
            "occurrences" -> Monitor.Occurrences(name, transition!!)
            else -> {
                if (compiled == null) throw CliktError("$file: observe monitors need a .cpn file or a cpn-document", statusCode = 1)
                val function =
                    try {
                        compiled.expression(expression!!)
                    } catch (e: ParseException) {
                        throw CliktError("monitor '$name': ${e.message}", statusCode = 1)
                    }
                Monitor.Observe(name, transition!!) { binding, time -> number(function(binding, time), name) }
            }
        }

    private fun number(
        value: Any?,
        monitor: String,
    ): Double =
        when (value) {
            is Number -> value.toDouble()
            is MlReal -> value.value
            else -> throw IllegalStateException("monitor '$monitor' must give a number, not $value")
        }

    /** Without a monitor file: the size of every place and the rate of every transition. */
    private fun defaultMonitors(net: Net): List<Monitor> {
        val flat =
            net
                .flatten()
                .net.pages
                .single()
        return flat.places.map { Monitor.MarkingSize("Tokens on ${it.name}", listOf(it.name)) } +
            flat.transitions.map { Monitor.Occurrences("Rate of ${it.name}", it.name) }
    }

    private fun parseSweep(option: String): Pair<String, List<String>> {
        val (name, value) = parseSet(option)
        return name to value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Every combination of the swept values, in order. */
    private fun scenarios(sweeps: List<Pair<String, List<String>>>): List<List<Pair<String, String>>> =
        sweeps.fold(listOf(emptyList())) { acc, (name, vs) -> acc.flatMap { prefix -> vs.map { prefix + (name to it) } } }

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

    private fun table(
        scenarios: List<Scenario>,
        markdown: Boolean,
    ): String =
        buildString {
            val first = scenarios.first().report
            val stop = first.limits.untilTime?.let { "until time $it" } ?: "up to ${first.limits.maxSteps} steps"
            val heading = "Performance report for ${first.net}"
            appendLine(if (markdown) "# $heading" else heading)
            appendLine()
            appendLine(
                "${first.runs.size} replications per scenario, $stop, warm-up ${first.limits.warmup}, " +
                    "seeds $seed..${seed + first.runs.size - 1}${if (first.timed) "" else " (untimed: means per step)"}.",
            )
            for (scenario in scenarios) {
                appendLine()
                if (scenario.label.isNotEmpty()) appendLine(if (markdown) "## ${scenario.label}" else "Scenario ${scenario.label}")
                val ends =
                    scenario.report.runs
                        .groupingBy { it.end }
                        .eachCount()
                if (ends.keys.any {
                        it.name == "DEAD"
                    }
                ) {
                    appendLine("Note: ${ends.entries.joinToString { "${it.value} runs ended ${it.key.name.lowercase()}" }}")
                }
                val rows =
                    scenario.report.summaries.map { s ->
                        listOf(s.monitor, fmt(s.mean), "${fmt(s.low)} – ${fmt(s.high)}", fmt(s.standardDeviation), fmt(s.min), fmt(s.max))
                    }
                val header = listOf("Monitor", "Mean", "95 % CI", "Std dev (runs)", "Min run", "Max run")
                if (markdown) {
                    appendLine("| " + header.joinToString(" | ") + " |")
                    appendLine("|" + "---|".repeat(header.size))
                    for (r in rows) appendLine("| " + r.joinToString(" | ") + " |")
                } else {
                    val widths = header.indices.map { i -> (rows.map { it[i].length } + header[i].length).max() }

                    fun line(cells: List<String>) =
                        cells
                            .mapIndexed { i, c ->
                                if (i ==
                                    0
                                ) {
                                    c.padEnd(widths[i])
                                } else {
                                    c.padStart(widths[i])
                                }
                            }.joinToString("  ")
                    appendLine("  " + line(header))
                    for (r in rows) appendLine("  " + line(r))
                }
            }
        }

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

    /** Four significant digits, without scientific notation for ordinary sizes. */
    private fun fmt(x: Double): String {
        if (x == 0.0) return "0"
        val digits =
            when {
                abs(x) >= 1000 -> 0
                abs(x) >= 100 -> 1
                abs(x) >= 10 -> 2
                abs(x) >= 1 -> 3
                else -> 4
            }
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val rounded = (x * scale).roundToLong()
        val sign = if (rounded < 0) "-" else ""
        val whole = abs(rounded) / scale
        val fraction = (abs(rounded) % scale).toString().padStart(digits, '0')
        return if (digits == 0) "$sign$whole" else "$sign$whole.$fraction"
    }
}
