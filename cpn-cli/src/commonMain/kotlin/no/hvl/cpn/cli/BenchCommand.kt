package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import no.hvl.cpn.api.Cpn
import no.hvl.cpn.core.InvalidNetException
import no.hvl.cpn.core.Net
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.io.model.compile
import no.hvl.cpn.lang.eval.EvaluationException
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason
import no.hvl.cpn.sim.UnboundVariableException
import no.hvl.cpn.statespace.StateSpace
import no.hvl.cpn.statespace.StateSpaceStatus
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * `cpn bench model`: times simulation runs and state space exploration inside one process, after a
 * warm-up, so start-up and just-in-time compilation are not counted. The measurement repeats Garvik
 * (2022, section 6.3): the time to execute a number of random steps.
 */
class BenchCommand(
    private val readFile: (String) -> ByteArray,
) : CliktCommand(name = "bench") {
    private val file by argument(help = "Model file: a CPN Tools .cpn file, a JSON model or an editable cpn-document")
    private val steps by option(
        "--steps",
        "-n",
        help = "Comma-separated step counts to time, e.g. 500000,1000000",
    ).int().split(",").default(listOf(100_000))
    private val repeat by option(
        "--repeat",
        "-r",
        help = "Runs per measurement; the median is reported",
    ).int().restrictTo(min = 1).default(3)
    private val warmup by option("--warmup", help = "Steps run first and not timed").int().restrictTo(min = 0).default(100_000)
    private val seed by option("--seed", "-s", help = "Seed of the first run; run i uses seed + i").long().default(1)
    private val stateSpace by option("--state-space", help = "Also time the full state space exploration").flag()
    private val format by option("--format", "-f", help = "Output format").choice("text", "json").default("text")
    private val values by option(
        "--set",
        help = "Set a value declared in the model, e.g. --set W=3 for 'val W = ...;' (repeatable)",
    ).multiple()

    override fun help(context: Context): String =
        "Measure simulation speed (time for N random steps) and, with --state-space, state space exploration time."

    private class Run(
        val steps: Int,
        val seconds: List<Double>,
        val stoppedEarly: Boolean,
    ) {
        val median: Double get() = seconds.sorted()[seconds.size / 2]
        val stepsPerSecond: Double get() = if (median > 0) steps / median else Double.POSITIVE_INFINITY
    }

    override fun run() {
        val net = load()
        val name = net.name
        errors { Simulator(net, seed, keepTrace = false).run(warmup) }

        val runs =
            steps.map { n ->
                var stopped = false
                val times =
                    (0 until repeat).map { i ->
                        val simulator = errors { Simulator(net, seed + i, keepTrace = false) }
                        val start = TimeSource.Monotonic.markNow()
                        val result = errors { simulator.run(n) }
                        val elapsed = start.elapsedNow()
                        if (result.reason != StopReason.MAX_STEPS) stopped = true
                        elapsed.seconds()
                    }
                Run(n, times, stopped)
            }

        var nodes = 0
        var arcs = 0
        var full = true
        val stateSpaceTimes =
            if (!stateSpace) {
                emptyList()
            } else {
                errors { StateSpace.build(net) } // warm-up
                (0 until repeat).map {
                    val start = TimeSource.Monotonic.markNow()
                    val ss = errors { StateSpace.build(net) }
                    val elapsed = start.elapsedNow()
                    nodes = ss.nodeCount
                    arcs = ss.arcCount
                    full = ss.status == StateSpaceStatus.FULL
                    elapsed.seconds()
                }
            }

        when (format) {
            "json" ->
                echo(
                    Json.encodeToString(
                        buildJsonObject {
                            put("net", name)
                            put("platform", Cpn.platform)
                            putJsonObject("set") { values.map(::parseSet).forEach { (k, v) -> put(k, v) } }
                            put("repeat", repeat)
                            putJsonArray("simulation") {
                                for (run in runs) {
                                    addJsonObject {
                                        put("steps", run.steps)
                                        put("medianSeconds", run.median)
                                        put("stepsPerSecond", run.stepsPerSecond)
                                        putJsonArray("seconds") { run.seconds.forEach { add(it) } }
                                        put("stoppedEarly", run.stoppedEarly)
                                    }
                                }
                            }
                            if (stateSpace) {
                                putJsonObject("stateSpace") {
                                    put("nodes", nodes)
                                    put("arcs", arcs)
                                    put("full", full)
                                    put("medianSeconds", stateSpaceTimes.sorted()[stateSpaceTimes.size / 2])
                                    putJsonArray("seconds") { stateSpaceTimes.forEach { add(it) } }
                                }
                            }
                        },
                    ),
                )
            else -> {
                echo("Benchmark of $name on ${Cpn.platform} (median of $repeat runs, after $warmup warm-up steps)")
                for (run in runs) {
                    val note = if (run.stoppedEarly) "  (stopped early: dead marking)" else ""
                    echo("  ${run.steps} steps: ${fmt(run.median)}, ${run.stepsPerSecond.toLong()} steps/s$note")
                }
                if (stateSpace) {
                    val median = stateSpaceTimes.sorted()[stateSpaceTimes.size / 2]
                    echo("  State space: $nodes nodes, $arcs arcs${if (full) "" else " (partial)"}: ${fmt(median)}")
                }
            }
        }
    }

    private fun Duration.seconds(): Double = inWholeMicroseconds / 1_000_000.0

    /** Seconds with millisecond precision, or milliseconds with microsecond precision below one second. */
    private fun fmt(seconds: Double): String {
        val us = (seconds * 1_000_000).toLong()
        return if (us >= 1_000_000) {
            "${us / 1_000_000}.${(us / 1000 % 1000).toString().padStart(3, '0')} s"
        } else {
            "${us / 1000}.${(us % 1000).toString().padStart(3, '0')} ms"
        }
    }

    private fun load(): Net {
        val bytes =
            try {
                setValues(readFile(file), values.map(::parseSet), file)
            } catch (e: CliktError) {
                throw e
            } catch (e: Exception) {
                throw CliktError("Cannot read '$file': ${e.message}", statusCode = 1)
            }
        val model = errors { ModelFiles.open(bytes, ModelFiles.modelName(file)) }
        for (note in model.notes) echo("note: $note", err = true)
        return model.net ?: throw CliktError(
            "$file: the model cannot run:\n" +
                model.document
                    .compile()
                    .problems
                    .joinToString("\n") { "  ${it.elementId ?: "declarations"}: ${it.message}" },
            statusCode = 1,
        )
    }

    private fun <T> errors(block: () -> T): T =
        try {
            block()
        } catch (e: InvalidNetException) {
            throw CliktError("$file: ${e.message}", statusCode = 1)
        } catch (e: ModelFormatException) {
            throw CliktError("$file: ${e.message}", statusCode = 1)
        } catch (e: UnboundVariableException) {
            throw CliktError("$file: ${e.message}", statusCode = 1)
        } catch (e: IllegalStateException) {
            throw CliktError("$file: failed: ${e.message}", statusCode = 1)
        } catch (e: EvaluationException) {
            throw CliktError("$file: failed: ${e.message}", statusCode = 1)
        }
}
