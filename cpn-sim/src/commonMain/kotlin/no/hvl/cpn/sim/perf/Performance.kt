package no.hvl.cpn.sim.perf

import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.Transition
import no.hvl.cpn.sim.Simulator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What to measure during a simulation (F14), after CPN Tools' monitors. Places and transitions are
 * named as in the flattened net (`Page'Name 1`); `Page'Name` and a bare `Name` work too when they are
 * unambiguous.
 */
public sealed class Monitor(
    public val name: String,
) {
    /**
     * The number of tokens on [places] together, e.g. a queue length or the number of busy workers.
     * Its mean is weighted by time in a timed net (CPN Tools' "timed statistics") and by steps
     * otherwise.
     */
    public class MarkingSize(
        name: String,
        public val places: List<String>,
    ) : Monitor(name)

    /** How often [transition] occurs: the count, and the rate per time unit (or per step in an untimed net). */
    public class Occurrences(
        name: String,
        public val transition: String,
    ) : Monitor(name)

    /**
     * A number computed at every occurrence of [transition] from its binding and the model time, e.g.
     * the time a specimen spent in the lab: `intTime() - arrived`. CPN Tools' data collection monitor.
     */
    public class Observe(
        name: String,
        public val transition: String,
        public val value: (Binding, Long) -> Double,
    ) : Monitor(name)
}

/** When a run stops, and which part of it counts. */
public data class RunLimits(
    /** Stop at this model time (timed nets). */
    val untilTime: Long? = null,
    /** Stop after this many steps. */
    val maxSteps: Int = Int.MAX_VALUE,
    /** Ignore everything before this model time (or step, in an untimed net), so the start-up does not bias the result. */
    val warmup: Long = 0,
) {
    init {
        require(untilTime == null || untilTime > warmup) { "untilTime ($untilTime) must be after the warm-up ($warmup)" }
        require(maxSteps > 0) { "maxSteps must be positive" }
    }
}

/** Statistics of one monitor in one run. */
public data class MonitorStatistics(
    val monitor: String,
    /** Observations: occurrences counted, values observed, or (for marking sizes) markings seen. */
    val count: Long,
    /** Mean value; for [Monitor.Occurrences], the rate per time unit (or per step). */
    val mean: Double,
    val min: Double,
    val max: Double,
    /** Standard deviation of the observed values ([Monitor.Observe] only). */
    val standardDeviation: Double?,
)

/** Why a run stopped. */
public enum class RunEnd { TIME_LIMIT, STEP_LIMIT, DEAD }

/** The result of one simulation run. */
public data class RunStatistics(
    val seed: Long,
    val steps: Int,
    /** Model time at the end (in an untimed net: the number of steps). */
    val endTime: Long,
    val end: RunEnd,
    val monitors: List<MonitorStatistics>,
)

/** A monitor across replications: the mean of its per-run means, with a 95 % confidence interval. */
public data class MonitorSummary(
    val monitor: String,
    val mean: Double,
    val standardDeviation: Double,
    /** Half-width of the 95 % confidence interval (Student's t); 0 for one replication. */
    val halfWidth: Double,
    val min: Double,
    val max: Double,
) {
    val low: Double get() = mean - halfWidth
    val high: Double get() = mean + halfWidth
}

/** The result of [Performance.replicate]. */
public data class PerformanceReport(
    val net: String,
    val timed: Boolean,
    val limits: RunLimits,
    val runs: List<RunStatistics>,
    val summaries: List<MonitorSummary>,
)

/**
 * Simulation-based performance analysis (F14): independent runs with different seeds, monitors
 * collecting statistics, and confidence intervals across runs, as in CPN Tools' performance reports
 * (Jensen & Kristensen 2009, ch. 12).
 */
public object Performance {
    /** One run with [seed]. */
    public fun run(
        net: Net,
        monitors: List<Monitor>,
        limits: RunLimits,
        seed: Long,
    ): RunStatistics {
        val simulator = Simulator(net, seed, keepTrace = false)
        val flat =
            simulator.flatNet.net.pages
                .single()
        val timed = simulator.flatNet.net.isTimed
        val collectors = monitors.map { collector(it, flat.places, flat.transitions) }

        fun now(steps: Int): Long = if (timed) simulator.time else steps.toLong()

        var steps = 0
        var last = 0L
        for (c in collectors) c.start(simulator, limits.warmup)
        var end = RunEnd.STEP_LIMIT
        while (steps < limits.maxSteps) {
            val before = simulator.marking
            val occurrence = simulator.step()
            if (occurrence == null) {
                end = RunEnd.DEAD
                break
            }
            val time = occurrence.time ?: (steps + 1).toLong()
            val stop = limits.untilTime
            if (stop != null && time > stop) {
                // This step happens after the end: the marking before it lasts until the end.
                for (c in collectors) c.hold(before, last, stop, limits.warmup)
                last = stop
                end = RunEnd.TIME_LIMIT
                break
            }
            for (c in collectors) c.hold(before, last, time, limits.warmup)
            last = time
            steps++
            for (c in collectors) c.fired(occurrence.element.transition, occurrence.element.binding, simulator.marking, time, limits.warmup)
        }
        if (end == RunEnd.DEAD) {
            // A dead marking stays as it is until the time limit, if there is one.
            val stop = limits.untilTime ?: last
            for (c in collectors) c.hold(simulator.marking, last, max(stop, last), limits.warmup)
            last = max(stop, last)
        }
        val endTime = if (timed || end == RunEnd.DEAD) last else now(steps)
        val span = (endTime - limits.warmup).coerceAtLeast(0)
        return RunStatistics(seed, steps, endTime, end, collectors.map { it.statistics(span) })
    }

    /** [replications] independent runs, with seeds `seed`, `seed + 1`, ... */
    public fun replicate(
        net: Net,
        monitors: List<Monitor>,
        limits: RunLimits,
        replications: Int,
        seed: Long = 1,
    ): PerformanceReport {
        require(replications > 0) { "replications must be positive" }
        val runs = (0 until replications).map { run(net, monitors, limits, seed + it) }
        val summaries =
            monitors.indices.map { i ->
                val means = runs.map { it.monitors[i].mean }
                summarise(monitors[i].name, means)
            }
        return PerformanceReport(net.name, net.isTimed, limits, runs, summaries)
    }

    /** The mean, standard deviation and 95 % confidence interval of one monitor's per-run [values]. */
    public fun summarise(
        name: String,
        values: List<Double>,
    ): MonitorSummary {
        val n = values.size
        val mean = values.sum() / n
        val sd = if (n > 1) sqrt(values.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
        val half = if (n > 1) tCritical95(n - 1) * sd / sqrt(n.toDouble()) else 0.0
        return MonitorSummary(name, mean, sd, half, values.min(), values.max())
    }

    /** Two-sided 95 % critical value of Student's t with [df] degrees of freedom. */
    internal fun tCritical95(df: Int): Double =
        when {
            df <= 0 -> throw IllegalArgumentException("df must be positive")
            df <= T95.size -> T95[df - 1]
            df <= 40 -> 2.021
            df <= 60 -> 2.000
            df <= 120 -> 1.980
            else -> 1.960
        }

    private val T95 =
        doubleArrayOf(
            12.706,
            4.303,
            3.182,
            2.776,
            2.571,
            2.447,
            2.365,
            2.306,
            2.262,
            2.228,
            2.201,
            2.179,
            2.160,
            2.145,
            2.131,
            2.120,
            2.110,
            2.101,
            2.093,
            2.086,
            2.080,
            2.074,
            2.069,
            2.064,
            2.060,
            2.056,
            2.052,
            2.048,
            2.045,
            2.042,
        )

    private fun collector(
        monitor: Monitor,
        places: List<Place<*>>,
        transitions: List<Transition>,
    ): Collector =
        when (monitor) {
            is Monitor.MarkingSize -> SizeCollector(monitor.name, monitor.places.map { find(it, places, "place") { p -> p.name } })
            is Monitor.Occurrences -> CountCollector(monitor.name, find(monitor.transition, transitions, "transition") { it.name })
            is Monitor.Observe ->
                ObserveCollector(
                    monitor.name,
                    find(monitor.transition, transitions, "transition") { it.name },
                    monitor.value,
                )
        }

    /** `Page'Name 1`, or `Page'Name`, or `Name` if only one element has it. */
    private fun <T> find(
        name: String,
        elements: List<T>,
        kind: String,
        nameOf: (T) -> String,
    ): T {
        elements.firstOrNull { nameOf(it) == name }?.let { return it }
        elements.firstOrNull { nameOf(it) == "$name 1" }?.let { return it }
        val bare = elements.filter { nameOf(it).substringAfter('\'').substringBeforeLast(' ') == name }
        return when (bare.size) {
            1 -> bare.single()
            0 -> throw IllegalArgumentException("No $kind '$name'; the net has: ${elements.joinToString { nameOf(it) }}")
            else -> throw IllegalArgumentException("'$name' names several ${kind}s: ${bare.joinToString { nameOf(it) }}; use Page'Name")
        }
    }
}

private abstract class Collector(
    val name: String,
) {
    open fun start(
        simulator: Simulator,
        warmup: Long,
    ) {}

    /** The marking [marking] held from [from] to [to]. */
    open fun hold(
        marking: no.hvl.cpn.core.Marking,
        from: Long,
        to: Long,
        warmup: Long,
    ) {}

    /** [transition] occurred at [time], giving [after]. */
    open fun fired(
        transition: Transition,
        binding: Binding,
        after: no.hvl.cpn.core.Marking,
        time: Long,
        warmup: Long,
    ) {}

    abstract fun statistics(span: Long): MonitorStatistics
}

private class SizeCollector(
    name: String,
    val places: List<Place<*>>,
) : Collector(name) {
    private var area = 0.0
    private var seen = 0L
    private var min = Double.POSITIVE_INFINITY
    private var max = Double.NEGATIVE_INFINITY

    private fun size(marking: no.hvl.cpn.core.Marking): Int = places.sumOf { marking[it].size }

    private fun see(value: Int) {
        seen++
        min = min(min, value.toDouble())
        max = max(max, value.toDouble())
    }

    override fun start(
        simulator: Simulator,
        warmup: Long,
    ) {
        if (warmup == 0L) see(size(simulator.marking))
    }

    override fun hold(
        marking: no.hvl.cpn.core.Marking,
        from: Long,
        to: Long,
        warmup: Long,
    ) {
        val start = max(from, warmup)
        if (to > start) {
            val size = size(marking)
            area += size.toDouble() * (to - start)
            if (from < warmup) see(size) // the marking at the end of the warm-up
        }
    }

    override fun fired(
        transition: Transition,
        binding: Binding,
        after: no.hvl.cpn.core.Marking,
        time: Long,
        warmup: Long,
    ) {
        if (time >= warmup) see(size(after))
    }

    override fun statistics(span: Long): MonitorStatistics =
        MonitorStatistics(name, seen, if (span > 0) area / span else 0.0, if (seen > 0) min else 0.0, if (seen > 0) max else 0.0, null)
}

private class CountCollector(
    name: String,
    val transition: Transition,
) : Collector(name) {
    private var count = 0L

    override fun fired(
        transition: Transition,
        binding: Binding,
        after: no.hvl.cpn.core.Marking,
        time: Long,
        warmup: Long,
    ) {
        if (transition === this.transition && time >= warmup) count++
    }

    override fun statistics(span: Long): MonitorStatistics {
        val rate = if (span > 0) count.toDouble() / span else 0.0
        return MonitorStatistics(name, count, rate, rate, rate, null)
    }
}

private class ObserveCollector(
    name: String,
    val transition: Transition,
    val value: (Binding, Long) -> Double,
) : Collector(name) {
    private var count = 0L
    private var sum = 0.0
    private var squares = 0.0
    private var min = Double.POSITIVE_INFINITY
    private var max = Double.NEGATIVE_INFINITY

    override fun fired(
        transition: Transition,
        binding: Binding,
        after: no.hvl.cpn.core.Marking,
        time: Long,
        warmup: Long,
    ) {
        if (transition !== this.transition || time < warmup) return
        val v = value(binding, time)
        count++
        sum += v
        squares += v * v
        min = min(min, v)
        max = max(max, v)
    }

    override fun statistics(span: Long): MonitorStatistics {
        if (count == 0L) return MonitorStatistics(name, 0, 0.0, 0.0, 0.0, null)
        val mean = sum / count
        val variance = if (count > 1) ((squares - count * mean * mean) / (count - 1)).coerceAtLeast(0.0) else 0.0
        return MonitorStatistics(name, count, mean, min, max, sqrt(variance))
    }
}
