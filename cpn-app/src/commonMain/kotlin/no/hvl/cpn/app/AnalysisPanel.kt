package no.hvl.cpn.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.hvl.cpn.api.perf.ModelValues
import no.hvl.cpn.api.perf.MonitorFile
import no.hvl.cpn.api.perf.Scenario
import no.hvl.cpn.api.perf.StudyException
import no.hvl.cpn.api.perf.formatReport
import no.hvl.cpn.api.perf.reportHeader
import no.hvl.cpn.api.perf.reportRows
import no.hvl.cpn.api.perf.study
import no.hvl.cpn.app.files.ModelFileAccess
import no.hvl.cpn.core.Net
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.sim.perf.RunLimits
import no.hvl.cpn.statespace.StandardReport
import no.hvl.cpn.statespace.StateSpace
import no.hvl.cpn.statespace.StateSpaceLimits
import no.hvl.cpn.statespace.StateSpaceStatus
import no.hvl.cpn.statespace.standardReport
import kotlin.time.Duration.Companion.seconds

/**
 * Analyse mode (A7 and F14 in the app): the state space with CPN Tools' standard report, and
 * performance studies with monitors, replications and scenarios. Both use the same engine code as
 * `cpn statespace --report` and `cpn perf`, so they give the same numbers.
 */
@Composable
fun AnalysisPanel(
    document: CpnDocument,
    /** The compiled model; null with [problem] if it cannot run. */
    net: Net?,
    problem: String?,
    directory: String?,
    files: ModelFileAccess,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("State space") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Performance") })
        }
        if (net == null) {
            Text(
                problem ?: "This model can't run yet.",
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        // Both tabs stay alive, so switching keeps their results.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            StateSpaceTab(net, visible = tab == 0)
            PerformanceTab(document, net, directory, files, visible = tab == 1)
        }
    }
}

@Composable
private fun StateSpaceTab(
    net: Net,
    visible: Boolean,
) {
    val scope = rememberCoroutineScope()
    var maxNodes by remember(net) { mutableStateOf("200000") }
    var maxSeconds by remember(net) { mutableStateOf("60") }
    var running by remember(net) { mutableStateOf(false) }
    var report by remember(net) { mutableStateOf<StandardReport?>(null) }
    var error by remember(net) { mutableStateOf<String?>(null) }
    if (!visible) return
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Explores every reachable marking and computes CPN Tools' standard report: bounds, home markings, " +
                "liveness and fairness. Large models can have millions of markings, so the search stops at the limits.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Max nodes", maxNodes, { maxNodes = it }, Modifier.width(160.dp))
            NumberField("Max seconds", maxSeconds, { maxSeconds = it }, Modifier.width(140.dp))
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    error = null
                    scope.launch {
                        try {
                            val limits =
                                StateSpaceLimits(
                                    maxNodes = maxNodes.toIntOrNull() ?: 200_000,
                                    maxTime = (maxSeconds.toIntOrNull() ?: 60).seconds,
                                )
                            report = withContext(Dispatchers.Default) { StateSpace.build(net, limits).standardReport() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            running = false
                        }
                    }
                },
            ) { Text(if (running) "Exploring…" else "Explore state space") }
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        report?.let { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact("Status", if (r.status == StateSpaceStatus.FULL) "Full" else "Partial")
                Fact("Nodes", r.nodes.toString())
                Fact("Arcs", r.arcs.toString())
                Fact("SCCs", r.sccNodes.toString())
                Fact("Dead markings", r.deadMarkings.size.toString())
                Fact("Live transitions", r.liveTransitions.size.toString())
                Fact("Time", "${r.seconds} s")
            }
            if (r.status == StateSpaceStatus.PARTIAL) {
                Text(
                    "The limits stopped the search: the properties below hold for the explored part only.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Standard report", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(r.format())) }) { Text("Copy report") }
            }
            SelectionContainer {
                Text(
                    r.format(),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun PerformanceTab(
    document: CpnDocument,
    net: Net,
    directory: String?,
    files: ModelFileAccess,
    visible: Boolean,
) {
    val scope = rememberCoroutineScope()
    val defaults = remember(net) { MonitorFile.format(MonitorFile.defaults(net)) }
    var monitors by remember(net) { mutableStateOf(defaults) }
    var replications by remember(net) { mutableStateOf("10") }
    var untilTime by remember(net) { mutableStateOf(if (net.isTimed) "10000" else "") }
    var steps by remember(net) { mutableStateOf(if (net.isTimed) "1000000" else "10000") }
    var warmup by remember(net) { mutableStateOf("0") }
    var seed by remember(net) { mutableStateOf("1") }
    var scenarios by remember(net) { mutableStateOf("") }
    var job by remember(net) { mutableStateOf<Job?>(null) }
    var progress by remember(net) { mutableStateOf(0f) }
    var results by remember(net) { mutableStateOf<List<Scenario>?>(null) }
    var usedSeed by remember(net) { mutableStateOf(1L) }
    var error by remember(net) { mutableStateOf<String?>(null) }
    if (!visible) return
    val clipboard = LocalClipboardManager.current

    fun run() {
        error = null
        progress = 0f
        val runSeed = seed.toLongOrNull() ?: 1L
        job =
            scope.launch {
                try {
                    val specs = MonitorFile.parse(monitors)
                    val limits =
                        RunLimits(
                            untilTime = untilTime.toLongOrNull(),
                            maxSteps = steps.toIntOrNull() ?: 1_000_000,
                            warmup = warmup.toLongOrNull() ?: 0,
                        )
                    val sweeps =
                        scenarios
                            .split(';')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map(ModelValues::parseSweep)
                    val reps = replications.toIntOrNull()?.coerceAtLeast(1) ?: 10
                    val running = coroutineContext[Job]
                    results =
                        withContext(Dispatchers.Default) {
                            document.study(specs, limits, reps, runSeed, sweeps, directory) { done, total ->
                                if (running?.isActive == false) throw CancellationException("stopped")
                                progress = done.toFloat() / total
                            }
                        }
                    usedSeed = runSeed
                } catch (e: CancellationException) {
                    error = "Stopped."
                } catch (e: StudyException) {
                    error = e.message
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                } finally {
                    job = null
                }
            }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Runs the model several times with different seeds and reports each monitor's mean with a 95 % confidence " +
                "interval, as `cpn perf` does. Scenarios compare declared values, e.g. WORKERS=2,3,4 (several: separate with ;).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Replications", replications, { replications = it }, Modifier.width(130.dp))
            if (net.isTimed) NumberField("Until time", untilTime, { untilTime = it }, Modifier.width(140.dp))
            NumberField("Max steps", steps, { steps = it }, Modifier.width(140.dp))
            NumberField(if (net.isTimed) "Warm-up (time)" else "Warm-up (steps)", warmup, { warmup = it }, Modifier.width(150.dp))
            NumberField("Seed", seed, { seed = it }, Modifier.width(100.dp))
        }
        OutlinedTextField(
            scenarios,
            { scenarios = it },
            label = { Text("Scenarios (optional)") },
            placeholder = { Text("GROSSING_STAFF=1,2,3") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Monitors", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    val file = files.open() ?: return@launch
                    monitors = file.text
                }
            }) { Text("Load…") }
            TextButton(onClick = { monitors = defaults }) { Text("Every place and transition") }
        }
        OutlinedTextField(
            monitors,
            { monitors = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = job == null, onClick = ::run) { Text(if (job == null) "Run study" else "Running…") }
            if (job != null) OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") }
            if (job != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        results?.let { list ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(formatReport(list, usedSeed, markdown = true))) }) {
                    Text("Copy as Markdown")
                }
            }
            val first = list.first().report
            Text(
                "${first.runs.size} replications per scenario, " +
                    (first.limits.untilTime?.let { "until time $it" } ?: "up to ${first.limits.maxSteps} steps") +
                    ", warm-up ${first.limits.warmup}" + if (first.timed) "." else " (untimed: means per step).",
                style = MaterialTheme.typography.bodySmall,
            )
            for (scenario in list) {
                if (scenario.label.isNotEmpty()) Text(scenario.label, style = MaterialTheme.typography.titleSmall)
                ResultTable(reportRows(scenario))
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ResultTable(rows: List<List<String>>) {
    val widths = listOf(300.dp, 90.dp, 170.dp, 110.dp, 90.dp, 90.dp)
    SelectionContainer {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            Row {
                reportHeader.forEachIndexed { i, h ->
                    Text(
                        h,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(widths[i]).padding(4.dp),
                    )
                }
            }
            HorizontalDivider()
            for (row in rows) {
                Row {
                    row.forEachIndexed { i, cell ->
                        Text(
                            cell,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily =
                                        if (i ==
                                            0
                                        ) {
                                            FontFamily.Default
                                        } else {
                                            FontFamily.Monospace
                                        },
                                ),
                            modifier = Modifier.width(widths[i]).padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value,
        { text -> onChange(text.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}
