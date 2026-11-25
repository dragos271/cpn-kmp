package no.hvl.cpn.api.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.flatten
import no.hvl.cpn.io.model.CompiledModel
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.io.model.compile
import no.hvl.cpn.lang.eval.ExternalFunctionException
import no.hvl.cpn.lang.parse.ParseException
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.sim.perf.Monitor
import no.hvl.cpn.sim.perf.Performance
import no.hvl.cpn.sim.perf.PerformanceReport
import no.hvl.cpn.sim.perf.RunLimits
import kotlin.math.roundToLong

/**
 * A monitor as written in a monitor file (F14), before it is bound to a compiled model. Shared by
 * `cpn perf` and the app, so both read the same files and measure the same things.
 */
public data class MonitorSpec(
    val name: String,
    /** `marking-size`, `occurrences` or `observe`. */
    val type: String,
    val places: List<String> = emptyList(),
    val transition: String? = null,
    /** For `observe`: the CPN ML expression observed at each occurrence. */
    val expression: String? = null,
    /** For `observe`: only occurrences for which this CPN ML condition holds. */
    val condition: String? = null,
    /** For `marking-size`: count only tokens available now (timed nets), e.g. idle workers. */
    val available: Boolean = false,
)

/** A problem with a monitor file, a monitor, or a scenario; its message is meant for the user. */
public class StudyException(
    message: String,
) : RuntimeException(message)

/** Reading and writing monitor files: `{"monitors": [{"name": ..., "type": ..., ...}]}`. */
public object MonitorFile {
    /** @throws StudyException if [text] is not a valid monitor file. */
    public fun parse(text: String): List<MonitorSpec> =
        try {
            Json.parseToJsonElement(text).jsonObject.getValue("monitors").jsonArray.map { element ->
                val o = element.jsonObject

                fun string(key: String) = (o[key] as? JsonPrimitive)?.content
                val type = string("type") ?: throw StudyException("a monitor has no \"type\"")
                val places =
                    when (val p = o["places"] ?: o["place"]) {
                        null -> emptyList()
                        is JsonArray -> p.map { it.jsonPrimitive.content }
                        else -> listOf(p.jsonPrimitive.content)
                    }
                val spec =
                    MonitorSpec(
                        string("name") ?: type,
                        type,
                        places,
                        string("transition"),
                        string("expression"),
                        string("when"),
                        string("available") == "true",
                    )
                val problem =
                    when (type) {
                        "marking-size" -> if (places.isEmpty()) "\"places\" is required" else null
                        "occurrences" -> if (spec.transition == null) "\"transition\" is required" else null
                        "observe" ->
                            if (spec.transition == null ||
                                spec.expression == null
                            ) {
                                "\"transition\" and \"expression\" are required"
                            } else {
                                null
                            }
                        else -> "unknown type '$type' (marking-size, occurrences, observe)"
                    }
                if (problem != null) throw StudyException("monitor '${spec.name}': $problem")
                spec
            }
        } catch (e: StudyException) {
            throw e
        } catch (e: Exception) {
            throw StudyException("not a monitor file: ${e.message}")
        }

    public fun format(monitors: List<MonitorSpec>): String =
        prettyJson.encodeToString(
            kotlinx.serialization.json.JsonObject
                .serializer(),
            buildJsonObject {
                putJsonArray("monitors") {
                    for (m in monitors) {
                        addJsonObject {
                            put("name", m.name)
                            put("type", m.type)
                            if (m.places.isNotEmpty()) putJsonArray("places") { m.places.forEach { add(JsonPrimitive(it)) } }
                            m.transition?.let { put("transition", it) }
                            m.expression?.let { put("expression", it) }
                            m.condition?.let { put("when", it) }
                            if (m.available) put("available", true)
                        }
                    }
                }
            },
        )

    /** Without a monitor file: the size of every place and the rate of every transition. */
    public fun defaults(net: Net): List<MonitorSpec> {
        val flat =
            net
                .flatten()
                .net.pages
                .single()
        return flat.places.map { MonitorSpec("Tokens on ${it.name}", "marking-size", places = listOf(it.name)) } +
            flat.transitions.map { MonitorSpec("Rate of ${it.name}", "occurrences", transition = it.name) }
    }

    private val prettyJson = Json { prettyPrint = true }
}

/** Binds [this] to a compiled model. @throws StudyException if its expressions do not parse or the model has none. */
public fun MonitorSpec.toMonitor(compiled: CompiledModel?): Monitor =
    when (type) {
        "marking-size" -> Monitor.MarkingSize(name, places, available)
        "occurrences" -> Monitor.Occurrences(name, transition!!)
        else -> {
            if (compiled == null) throw StudyException("monitor '$name': observe monitors need a .cpn file or a cpn-document")
            val (value, test) =
                try {
                    compiled.expression(expression!!) to condition?.let { compiled.expression(it) }
                } catch (e: ParseException) {
                    throw StudyException("monitor '$name': ${e.message}")
                }
            Monitor.Observe(
                name,
                transition!!,
                condition =
                    test?.let { t ->
                        { binding, time ->
                            t(binding, time) as? Boolean
                                ?: error("monitor '$name': \"when\" must be true or false")
                        }
                    },
            ) { binding, time ->
                when (val v = value(binding, time)) {
                    is Number -> v.toDouble()
                    is MlReal -> v.value
                    else -> error("monitor '$name' must give a number, not $v")
                }
            }
        }
    }

/**
 * Values declared in a model (`val NAME = ...;`), changed for scenarios: `--set W=3` in the
 * command line, the scenario field in the app.
 */
public object ModelValues {
    /**
     * [text] with the declaration of [name] set to [value]. [text] may be declarations, a JSON
     * document (where a line break is the two characters `\n`) or a `.cpn` file (which keeps a
     * declaration twice, as text and as layout): every copy is changed.
     * @throws StudyException if there is no such declaration.
     */
    public fun set(
        text: String,
        name: String,
        value: String,
    ): String {
        val declaration = Regex("""(?:(?<=\\[nrt])|(?<![\w']))val\s+${Regex.escape(name)}\s*=\s*[^;]*;""")
        if (!declaration.containsMatchIn(text)) throw StudyException("the model has no 'val $name = ...;' declaration")
        return declaration.replace(text, Regex.escapeReplacement("val $name = $value;"))
    }

    /** `NAME=VALUE`. @throws StudyException otherwise. */
    public fun parseAssignment(text: String): Pair<String, String> {
        val name = text.substringBefore('=', "").trim()
        val value = text.substringAfter('=', "").trim()
        if (name.isEmpty() || value.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '\'' }) {
            throw StudyException("expected NAME=VALUE, for example W=3; got '$text'")
        }
        return name to value
    }

    /** `NAME=V1,V2,V3`: one scenario per value. */
    public fun parseSweep(text: String): Pair<String, List<String>> {
        val (name, values) = parseAssignment(text)
        return name to values.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Every combination of the swept values, in order; one empty scenario if there are none. */
    public fun scenarios(sweeps: List<Pair<String, List<String>>>): List<List<Pair<String, String>>> =
        sweeps.fold(listOf(emptyList())) { acc, (name, vs) -> acc.flatMap { prefix -> vs.map { prefix + (name to it) } } }
}

/** One scenario's result: the values it set, and the report. */
public class Scenario(
    public val values: List<Pair<String, String>>,
    public val report: PerformanceReport,
) {
    public val label: String get() = values.joinToString(", ") { (k, v) -> "$k=$v" }
}

/**
 * Runs a performance study on a document: for each scenario, the document with its values set is
 * compiled, its monitors bound, and [replications] runs made. Used by the app; `cpn perf` does the
 * same on files.
 *
 * @param monitors null for [MonitorFile.defaults].
 * @param onRun called after each run, with the runs done and the total, e.g. for a progress bar;
 *   it may throw to cancel.
 * @throws StudyException for a problem the user can fix (a value that is not declared, a model that
 *   does not compile, a monitor naming a missing place).
 */
public fun CpnDocument.study(
    monitors: List<MonitorSpec>?,
    limits: RunLimits,
    replications: Int,
    seed: Long = 1,
    sweeps: List<Pair<String, List<String>>> = emptyList(),
    directory: String? = null,
    onRun: (done: Int, total: Int) -> Unit = { _, _ -> },
): List<Scenario> {
    val combos = ModelValues.scenarios(sweeps)
    val total = combos.size * replications
    var done = 0
    return combos.map { assignment ->
        var declarations = this.declarations
        for ((name, value) in assignment) declarations = ModelValues.set(declarations, name, value)
        val compiled = copy(declarations = declarations).compile(directory = directory)
        val net =
            compiled.net ?: throw StudyException(
                "the model cannot run${if (assignment.isEmpty()) {
                    ""
                } else {
                    " with ${assignment.joinToString {
                        "${it.first}=${it.second}"
                    }}"
                }}: " +
                    compiled.problems.joinToString("; ") { it.message },
            )
        try {
            compiled.python?.check()
        } catch (e: ExternalFunctionException) {
            throw StudyException(e.message ?: "Python functions cannot run")
        }
        val bound = (monitors ?: MonitorFile.defaults(net)).map { it.toMonitor(compiled) }
        val report =
            try {
                Performance.replicate(net, bound, limits, replications, seed) { _ -> onRun(++done, total) }
            } catch (e: IllegalArgumentException) {
                throw StudyException(e.message ?: "cannot run the study")
            }
        Scenario(assignment, report)
    }
}

/** The header of a report table, and one row per monitor of [scenario]: monitor, mean, 95 % CI, std dev, min and max run. */
public val reportHeader: List<String> = listOf("Monitor", "Mean", "95 % CI", "Std dev (runs)", "Min run", "Max run")

public fun reportRows(scenario: Scenario): List<List<String>> =
    scenario.report.summaries.map { s ->
        listOf(
            s.monitor,
            formatStatistic(s.mean),
            "${formatStatistic(s.low)} – ${formatStatistic(s.high)}",
            formatStatistic(s.standardDeviation),
            formatStatistic(s.min),
            formatStatistic(s.max),
        )
    }

/** A study's results as text or Markdown: what `cpn perf` prints and the app copies. */
public fun formatReport(
    scenarios: List<Scenario>,
    seed: Long,
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
            val rows = reportRows(scenario)
            if (markdown) {
                appendLine("| " + reportHeader.joinToString(" | ") + " |")
                appendLine("|" + "---|".repeat(reportHeader.size))
                for (r in rows) appendLine("| " + r.joinToString(" | ") + " |")
            } else {
                val widths = reportHeader.indices.map { i -> (rows.map { it[i].length } + reportHeader[i].length).max() }

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
                appendLine("  " + line(reportHeader))
                for (r in rows) appendLine("  " + line(r))
            }
        }
    }

/** Four significant digits, without scientific notation for ordinary sizes. */
public fun formatStatistic(x: Double): String {
    if (x == 0.0) return "0"
    val digits =
        when {
            kotlin.math.abs(x) >= 1000 -> 0
            kotlin.math.abs(x) >= 100 -> 1
            kotlin.math.abs(x) >= 10 -> 2
            kotlin.math.abs(x) >= 1 -> 3
            else -> 4
        }
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val rounded = (x * scale).roundToLong()
    val sign = if (rounded < 0) "-" else ""
    val whole = kotlin.math.abs(rounded) / scale
    val fraction = (kotlin.math.abs(rounded) % scale).toString().padStart(digits, '0')
    return if (digits == 0) "$sign$whole" else "$sign$whole.$fraction"
}
