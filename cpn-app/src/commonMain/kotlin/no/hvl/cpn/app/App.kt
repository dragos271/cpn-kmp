package no.hvl.cpn.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.hvl.cpn.api.Cpn
import no.hvl.cpn.app.resources.Res
import no.hvl.cpn.io.JsonNetFormat
import no.hvl.cpn.sim.StopReason

/** The example shipped with the app: the simple protocol of Jensen & Kristensen (2009), ch. 2. */
private const val EXAMPLE = "files/simple-protocol.json"

/** Root of the app, shared by desktop and web. */
@Composable
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var session by remember { mutableStateOf<SimulationSession?>(null) }
            var loadError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    session = SimulationSession(JsonNetFormat.parse(Res.readBytes(EXAMPLE).decodeToString()), seed = 2026)
                } catch (e: Exception) {
                    loadError = "Could not load the example model: ${e.message}"
                }
            }
            val current = session
            when {
                loadError != null -> Text(loadError!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                current == null -> Text("Loading model…", Modifier.padding(24.dp))
                else -> SimulationScreen(current)
            }
        }
    }
}

@Composable
private fun SimulationScreen(session: SimulationSession) {
    var snapshot by remember { mutableStateOf(session.snapshot()) }

    fun act(action: () -> Unit) {
        action()
        snapshot = session.snapshot()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(session)
        Controls(session, snapshot, ::act)
        Status(snapshot)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel("Marking", Modifier.weight(1.2f)) { MarkingList(snapshot) }
            Panel("Enabled — click to fire", Modifier.weight(1f)) { EnabledList(session, snapshot, ::act) }
            Panel("Trace", Modifier.weight(1f)) { TraceList(snapshot) }
        }
    }
}

@Composable
private fun Header(session: SimulationSession) {
    Column {
        Text("CPN Tool — ${session.model.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Engine ${Cpn.VERSION} on ${Cpn.platform}. Same engine and seed as `cpn simulate`.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Controls(
    session: SimulationSession,
    snapshot: SessionSnapshot,
    act: (() -> Unit) -> Unit,
) {
    var seedText by remember { mutableStateOf(session.seed.toString()) }
    val seed = seedText.toLongOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = seedText,
            onValueChange = { seedText = it.filter { c -> c.isDigit() || c == '-' }.take(19) },
            label = { Text("Seed") },
            singleLine = true,
            isError = seed == null,
            modifier = Modifier.width(160.dp),
        )
        OutlinedButton(onClick = { act { session.reset(seed ?: session.seed) } }, enabled = seed != null) { Text("Reset") }
        Button(onClick = { act { session.step() } }, enabled = !snapshot.isDead) { Text("Step") }
        Button(onClick = { act { session.run(25) } }, enabled = !snapshot.isDead) { Text("Run 25") }
        Button(onClick = { act { session.run(1000) } }, enabled = !snapshot.isDead) { Text("Run to end") }
    }
}

@Composable
private fun Status(snapshot: SessionSnapshot) {
    val text =
        buildString {
            append("Step ${snapshot.stepCount}")
            when {
                snapshot.isDead -> append(" · dead marking: no transition is enabled")
                snapshot.lastStop == StopReason.MAX_STEPS -> append(" · stopped at the step limit")
                else -> append(" · ${snapshot.enabled.size} enabled binding elements")
            }
        }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Panel(
    title: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier.fillMaxHeight()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
private fun MarkingList(snapshot: SessionSnapshot) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(snapshot.marking) { row ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenBadge(row.tokenCount)
                Column {
                    Text(row.name, fontWeight = FontWeight.Medium)
                    Text(row.tokens, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TokenBadge(count: Int) {
    val colour = if (count > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier.width(36.dp).background(colour, RoundedCornerShape(50)).padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EnabledList(
    session: SimulationSession,
    snapshot: SessionSnapshot,
    act: (() -> Unit) -> Unit,
) {
    if (snapshot.isDead) {
        Text("Nothing is enabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(snapshot.enabled) { element ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { act { session.fire(element) } }
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            ) {
                Text(element.transition.name, fontWeight = FontWeight.Medium)
                Text(element.binding.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TraceList(snapshot: SessionSnapshot) {
    if (snapshot.trace.isEmpty()) {
        Text("No steps yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(snapshot.trace.asReversed()) { occurrence ->
            Text(occurrence.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
