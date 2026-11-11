package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.hvl.cpn.core.InvalidNetException
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.io.model.compile
import no.hvl.cpn.lang.eval.EvaluationException
import no.hvl.cpn.sim.UnboundVariableException
import no.hvl.cpn.statespace.StandardReport
import no.hvl.cpn.statespace.StateSpace
import no.hvl.cpn.statespace.StateSpaceLimits
import no.hvl.cpn.statespace.StateSpaceStatus
import no.hvl.cpn.statespace.standardReport
import kotlin.time.Duration.Companion.seconds

/** `cpn statespace model [--report]`: explores the state space and prints its statistics (T3.3) or the standard report (T3.4). */
class StateSpaceCommand(
    private val readFile: (String) -> ByteArray,
) : CliktCommand(name = "statespace") {
    private val file by argument(help = "Model file: a CPN Tools .cpn file, a JSON model or an editable cpn-document")
    private val maxNodes by option("--max-nodes", help = "Stop after this many nodes").int().restrictTo(min = 1).default(1_000_000)
    private val maxArcs by option("--max-arcs", help = "Stop after this many arcs").int().restrictTo(min = 1).default(10_000_000)
    private val maxSeconds by option("--max-seconds", help = "Stop after this many seconds").int().restrictTo(min = 1)
    private val format by option("--format", "-f", help = "Output format").choice("text", "json").default("text")
    private val report by option(
        "--report",
        "-r",
        help = "Print CPN Tools' standard report: bounds, home, liveness and fairness properties",
    ).flag()

    override fun help(context: Context): String =
        "Explore the state space (reachability graph) and print its size and dead markings, or with --report the standard report."

    override fun run() {
        val bytes =
            try {
                readFile(file)
            } catch (e: Exception) {
                throw CliktError("Cannot read '$file': ${e.message}", statusCode = 1)
            }
        val stateSpace =
            try {
                val model = ModelFiles.open(bytes, ModelFiles.modelName(file))
                for (note in model.notes) echo("note: $note", err = true)
                val net =
                    model.net ?: throw CliktError(
                        "$file: the model cannot run:\n" +
                            model.document
                                .compile()
                                .problems
                                .joinToString("\n") { "  ${it.elementId ?: "declarations"}: ${it.message}" },
                        statusCode = 1,
                    )
                StateSpace.build(net, StateSpaceLimits(maxNodes, maxArcs, maxSeconds?.seconds))
            } catch (e: ModelFormatException) {
                throw CliktError("$file: ${e.message}", statusCode = 1)
            } catch (e: InvalidNetException) {
                throw CliktError("$file: ${e.message}", statusCode = 1)
            } catch (e: UnboundVariableException) {
                throw CliktError("$file: ${e.message}", statusCode = 1)
            } catch (e: EvaluationException) {
                throw CliktError("$file: state space exploration failed: ${e.message}", statusCode = 1)
            } catch (e: IllegalStateException) {
                throw CliktError("$file: state space exploration failed: ${e.message}", statusCode = 1)
            }
        val seconds = stateSpace.duration.inWholeMilliseconds / 1000.0
        val status = if (stateSpace.status == StateSpaceStatus.FULL) "Full" else "Partial"
        when (format) {
            "json" ->
                echo(
                    Json.encodeToString(
                        buildJsonObject {
                            put("net", stateSpace.flatNet.original.name)
                            put("status", status)
                            stateSpace.stoppedBy?.let { put("stoppedBy", it) }
                            put("nodes", stateSpace.nodeCount)
                            put("arcs", stateSpace.arcCount)
                            put("seconds", seconds)
                            putJsonArray("deadMarkings") { stateSpace.deadMarkings.forEach { add(it) } }
                            if (report) reportJson(stateSpace.standardReport())
                        },
                    ),
                )
            else ->
                if (report) {
                    echo(stateSpace.standardReport().format(), trailingNewline = false)
                } else {
                    echo("State space of ${stateSpace.flatNet.original.name}")
                    echo("  Status: $status" + (stateSpace.stoppedBy?.let { " (stopped after $it)" } ?: ""))
                    echo("  Nodes:  ${stateSpace.nodeCount}")
                    echo("  Arcs:   ${stateSpace.arcCount}")
                    echo("  Time:   $seconds s")
                    val dead = stateSpace.deadMarkings
                    echo(
                        "  Dead markings: ${dead.size}" +
                            if (dead.isEmpty()) "" else " [${dead.take(20).joinToString(", ")}${if (dead.size > 20) ", ..." else ""}]",
                    )
                }
        }
        // A partial state space is still a result, but scripts should be able to tell.
        if (stateSpace.status == StateSpaceStatus.PARTIAL) throw CliktError(statusCode = 2)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.reportJson(r: StandardReport) {
    put("sccNodes", r.sccNodes)
    put("sccArcs", r.sccArcs)
    putJsonArray("integerBounds") {
        r.integerBounds.forEach { b ->
            add(
                buildJsonObject {
                    put("place", b.place)
                    put("upper", b.upper)
                    put("lower", b.lower)
                },
            )
        }
    }
    putJsonArray("multisetBounds") {
        r.multisetBounds.forEach { b ->
            add(
                buildJsonObject {
                    put("place", b.place)
                    put("upper", b.upper)
                    put("lower", b.lower)
                },
            )
        }
    }
    putJsonArray("homeMarkings") { r.homeMarkings.forEach { add(it) } }
    putJsonArray("deadTransitions") { r.deadTransitions.forEach { add(it) } }
    putJsonArray("liveTransitions") { r.liveTransitions.forEach { add(it) } }
    val fairness = r.fairness
    if (fairness == null) {
        put("infiniteOccurrenceSequences", false)
    } else {
        put("infiniteOccurrenceSequences", true)
        put("fairness", buildJsonObject { fairness.forEach { (t, f) -> put(t, f.text) } })
    }
}
