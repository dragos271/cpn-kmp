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
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import no.hvl.cpn.core.ColourSet
import no.hvl.cpn.core.InvalidNetException
import no.hvl.cpn.core.Net
import no.hvl.cpn.io.JsonNetFormat
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.sim.RunResult
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason
import no.hvl.cpn.sim.UnboundVariableException
import kotlin.random.Random

/** Exit code for a model that cannot be read, loaded or simulated. */
private const val MODEL_ERROR = 1

class SimulateCommand(
    private val readFile: (String) -> String,
) : CliktCommand(name = "simulate") {
    private val file by argument(help = "Model file in the JSON format (see docs/json-format.md)")
    private val steps by option("--steps", "-n", help = "Maximum number of steps").int().restrictTo(min = 0).default(1000)
    private val seed by option(
        "--seed",
        "-s",
        help = "Random seed; the same seed gives the same run on every platform. Random if omitted.",
    ).long()
    private val format by option("--format", "-f", help = "Output format").choice("text", "json").default("text")
    private val trace by option("--trace", help = "Print every step (default)").flag("--no-trace", default = true)

    override fun help(context: Context): String = "Run an automatic simulation and print the trace and final marking."

    override fun run() {
        val net = load()
        val usedSeed = seed ?: Random.nextLong()
        val simulator = modelErrors { Simulator(net, usedSeed) }
        val result = modelErrors { simulator.run(steps) }
        when (format) {
            "json" -> echo(json(simulator, result, usedSeed))
            else -> text(simulator, result, usedSeed)
        }
    }

    private fun load(): Net {
        val text =
            try {
                readFile(file)
            } catch (e: Exception) {
                throw CliktError("Cannot read '$file': ${e.message}", statusCode = MODEL_ERROR)
            }
        return modelErrors { JsonNetFormat.parse(text) }
    }

    private fun <T> modelErrors(block: () -> T): T =
        try {
            block()
        } catch (e: InvalidNetException) {
            throw CliktError("$file: ${e.message}", statusCode = MODEL_ERROR)
        } catch (e: ModelFormatException) {
            throw CliktError("$file: ${e.message}", statusCode = MODEL_ERROR)
        } catch (e: UnboundVariableException) {
            throw CliktError("$file: ${e.message}", statusCode = MODEL_ERROR)
        } catch (e: IllegalStateException) {
            // Raised while firing, e.g. an output colour outside its colour set.
            throw CliktError("$file: simulation failed: ${e.message}", statusCode = MODEL_ERROR)
        }

    private fun text(
        simulator: Simulator,
        result: RunResult,
        usedSeed: Long,
    ) {
        echo("Seed: $usedSeed")
        if (trace) simulator.trace.forEach { echo(it.toString()) }
        echo("Stopped after ${result.stepsTaken} steps: ${describe(result.reason)}")
        echo("Final marking:")
        for (place in places(simulator)) echo("  ${place.name}: ${result.marking.tokensOf(place)}")
    }

    private fun json(
        simulator: Simulator,
        result: RunResult,
        usedSeed: Long,
    ): String =
        Json.encodeToString(
            buildJsonObject {
                put("net", simulator.flatNet.original.name)
                put("seed", usedSeed)
                put("stepsTaken", result.stepsTaken)
                put("stopReason", result.reason.name)
                if (trace) {
                    put(
                        "trace",
                        buildJsonArray {
                            for (occurrence in simulator.trace) {
                                add(
                                    buildJsonObject {
                                        put("step", occurrence.step)
                                        put("transition", occurrence.element.transition.name)
                                        put(
                                            "binding",
                                            buildJsonObject {
                                                val binding = occurrence.element.binding
                                                for (variable in binding.variables.sortedBy { it.name }) {
                                                    @Suppress("UNCHECKED_CAST")
                                                    val colourSet = variable.colourSet as ColourSet<Any?>
                                                    put(variable.name, JsonPrimitive(colourSet.format(binding[variable])))
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
                put("marking", buildJsonObject { for (place in places(simulator)) put(place.name, result.marking.tokensOf(place)) })
            },
        )

    private fun places(simulator: Simulator) =
        simulator.flatNet.net.pages
            .single()
            .places

    private fun describe(reason: StopReason): String =
        when (reason) {
            StopReason.DEAD -> "dead marking"
            StopReason.MAX_STEPS -> "step limit reached"
            StopReason.CONDITION -> "stop condition"
        }
}
