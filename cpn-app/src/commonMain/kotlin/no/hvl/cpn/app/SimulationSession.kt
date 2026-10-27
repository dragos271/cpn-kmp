package no.hvl.cpn.app

import no.hvl.cpn.core.Net
import no.hvl.cpn.core.Place
import no.hvl.cpn.sim.BindingElement
import no.hvl.cpn.sim.Occurrence
import no.hvl.cpn.sim.RunResult
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason

/** What the screen shows after a change: computed once, so the binding search runs once per step. */
data class SessionSnapshot(
    val revision: Int,
    val stepCount: Int,
    val marking: List<PlaceRow>,
    val enabled: List<BindingElement>,
    val trace: List<Occurrence>,
    val lastStop: StopReason?,
) {
    val isDead: Boolean get() = enabled.isEmpty()
}

data class PlaceRow(
    val name: String,
    val tokenCount: Int,
    val tokens: String,
)

/**
 * The app's view of one simulation: a thin, UI-free wrapper around the engine's [Simulator], so the
 * app shows exactly what `cpn simulate` computes. The UI renders [snapshot] and re-reads it after
 * each action.
 */
class SimulationSession(
    val model: Net,
    seed: Long,
) {
    var seed: Long = seed
        private set

    private var simulator = Simulator(model, seed)

    /** Bumped after every change; the UI reads it to know when to redraw. */
    var revision: Int = 0
        private set

    /** Why the last automatic run stopped, for the status line. */
    var lastStop: StopReason? = null
        private set

    /** Places of the flattened net, named as in CPN Tools (`Page'Place 1`). */
    val places: List<Place<*>> get() =
        simulator.flatNet.net.pages
            .single()
            .places

    val enabled: List<BindingElement> get() = simulator.enabled()

    val trace: List<Occurrence> get() = simulator.trace

    val stepCount: Int get() = simulator.stepCount

    val isDead: Boolean get() = simulator.isDead()

    fun tokensOf(place: Place<*>): String = simulator.marking.tokensOf(place)

    fun tokenCount(place: Place<*>): Int = simulator.marking[place].size

    fun snapshot(): SessionSnapshot =
        SessionSnapshot(
            revision = revision,
            stepCount = stepCount,
            marking = places.map { PlaceRow(it.name, tokenCount(it), tokensOf(it)) },
            enabled = enabled,
            trace = trace,
            lastStop = lastStop,
        )

    /** Fires a binding element the user picked. */
    fun fire(element: BindingElement): Occurrence = simulator.fire(element).also { changed(null) }

    /** One random step; null if the marking is dead. */
    fun step(): Occurrence? = simulator.step().also { changed(if (it == null) StopReason.DEAD else null) }

    fun run(maxSteps: Int): RunResult = simulator.run(maxSteps).also { changed(it.reason) }

    /** Back to the initial marking, optionally with a new seed. */
    fun reset(newSeed: Long = seed) {
        if (newSeed != seed) {
            seed = newSeed
            simulator = Simulator(model, newSeed)
        } else {
            simulator.reset()
        }
        changed(null)
    }

    private fun changed(stop: StopReason?) {
        lastStop = stop
        revision++
    }
}
