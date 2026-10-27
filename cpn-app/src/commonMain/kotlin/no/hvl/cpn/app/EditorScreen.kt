package no.hvl.cpn.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.hvl.cpn.app.canvas.CanvasColors
import no.hvl.cpn.app.canvas.CanvasMode
import no.hvl.cpn.app.canvas.NetCanvas
import no.hvl.cpn.app.canvas.Viewport
import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.io.LoadedModel
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.sim.StopReason

/**
 * The editor, laid out like a diagram tool: toolbar on top, shape palette on the left, the canvas,
 * a properties panel on the right, and page tabs plus simulation controls at the bottom.
 */
@Composable
fun EditorScreen(model: LoadedModel) {
    var document by remember(model) { mutableStateOf(model.document) }
    var pageId by remember(model) {
        mutableStateOf(
            model.document.pages
                .first()
                .id,
        )
    }
    var selection by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(CanvasMode.SIMULATE) }
    val viewports = remember(model) { mutableStateMapOf<String, Viewport>() }
    val viewport = viewports.getOrPut(pageId) { Viewport() }

    val session = remember(model) { SimulationSession(model, seed = 2026) }
    var snapshot by remember(session) { mutableStateOf(session.snapshot()) }

    fun act(action: () -> Unit) {
        action()
        snapshot = session.snapshot()
    }

    val page = document.page(pageId) ?: document.pages.first()

    // Fit each page to the window the first time it is shown.
    val fitted = remember(model) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(pageId, viewport.size) {
        if (fitted[pageId] != true && viewport.size != IntSize.Zero) {
            fitPage(viewport, page)
            fitted[pageId] = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(document, mode, viewport, onMode = { mode = it }, onFit = { fitPage(viewport, page) })
        HorizontalDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Palette(Modifier.width(92.dp).fillMaxHeight())
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                NetCanvas(
                    page = page,
                    mode = mode,
                    selection = selection,
                    overlay = if (mode == CanvasMode.SIMULATE) snapshot.overlay(page.id) else null,
                    viewport = viewport,
                    colors = canvasColors(),
                    onSelect = { selection = it },
                    onMove = { id, position -> document = document.withPage(document.page(pageId)!!.moved(id, position)) },
                    onFire = { id -> act { session.fireTransition(page.id, id) } },
                    modifier = Modifier.fillMaxSize(),
                )
                CanvasHint(mode, Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
            VerticalDivider()
            PropertiesPanel(document, page, selection, mode, snapshot, session, ::act, Modifier.width(310.dp).fillMaxHeight())
        }
        HorizontalDivider()
        BottomBar(document, page.id, mode, session, snapshot, onPage = {
            pageId = it
            selection = null
        }, act = ::act)
    }
}

private fun fitPage(
    viewport: Viewport,
    page: PageDocument,
) {
    val points = page.places.map { it.position } + page.transitions.map { it.position }
    if (points.isEmpty()) return
    // Positions are centres; leave room for shapes and labels around them.
    viewport.fit(
        Point(points.minOf { it.x } - 90, points.minOf { it.y } - 70),
        Point(points.maxOf { it.x } + 160, points.maxOf { it.y } + 60),
    )
}

@Composable
private fun canvasColors(): CanvasColors {
    val scheme = MaterialTheme.colorScheme
    return CanvasColors(
        background = scheme.surface,
        grid = scheme.outlineVariant,
        stroke = scheme.onSurface,
        text = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        placeFill = scheme.surface,
        transitionFill = scheme.surface,
        selection = scheme.primary,
        enabledFill = Color(0xFFE3F2E1),
        enabledStroke = Color(0xFF2E7D32),
        tokenFill = Color(0xFF9CCC65),
        tokenText = Color(0xFF1B3A0A),
    )
}

@Composable
private fun TopBar(
    document: CpnDocument,
    mode: CanvasMode,
    viewport: Viewport,
    onMode: (CanvasMode) -> Unit,
    onFit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("CPN Tool", fontWeight = FontWeight.SemiBold)
        Text(document.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        TextButton(onClick = { viewport.zoomAt(1f / 1.2f) }) { Text("−") }
        Text("${(viewport.zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { viewport.zoomAt(1.2f) }) { Text("+") }
        TextButton(onClick = onFit) { Text("Fit") }
        Spacer(Modifier.weight(1f))
        ModeButton("Edit", mode == CanvasMode.EDIT) { onMode(CanvasMode.EDIT) }
        ModeButton("Simulate", mode == CanvasMode.SIMULATE) { onMode(CanvasMode.SIMULATE) }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) FilledTonalButton(onClick = onClick) { Text(label) } else TextButton(onClick = onClick) { Text(label) }
}

/** The shape palette. Dragging shapes onto the canvas comes with the editor (A5). */
@Composable
private fun Palette(modifier: Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Shapes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val stroke = MaterialTheme.colorScheme.onSurface
        Column(
            Modifier.alpha(0.55f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PaletteItem("Place") { drawOval(stroke, Offset(4f, 8f), Size(size.width - 8, size.height - 16), style = Stroke(1.5f)) }
            PaletteItem("Transition") { drawRect(stroke, Offset(6f, 10f), Size(size.width - 12, size.height - 20), style = Stroke(1.5f)) }
            PaletteItem("Arc") {
                drawLine(stroke, Offset(4f, size.height / 2), Offset(size.width - 6, size.height / 2), 1.5f)
                drawLine(stroke, Offset(size.width - 6, size.height / 2), Offset(size.width - 14, size.height / 2 - 6), 1.5f)
                drawLine(stroke, Offset(size.width - 6, size.height / 2), Offset(size.width - 14, size.height / 2 + 6), 1.5f)
            }
            PaletteItem("Subpage") { drawRect(stroke, Offset(6f, 10f), Size(size.width - 12, size.height - 20), style = Stroke(4f)) }
        }
        Text(
            "Adding shapes comes next",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun PaletteItem(
    label: String,
    icon: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 56.dp, height = 40.dp), onDraw = icon)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CanvasHint(
    mode: CanvasMode,
    modifier: Modifier,
) {
    val text =
        when (mode) {
            CanvasMode.EDIT -> "Drag shapes to move them · drag the background to pan · scroll to zoom"
            CanvasMode.SIMULATE -> "Click a green transition to fire it · drag to pan · scroll to zoom"
        }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(6.dp),
                ).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun PropertiesPanel(
    document: CpnDocument,
    page: PageDocument,
    selection: String?,
    mode: CanvasMode,
    snapshot: SessionSnapshot,
    session: SimulationSession,
    act: (() -> Unit) -> Unit,
    modifier: Modifier,
) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val place = selection?.let(page::place)
        val transition = selection?.let(page::transition)
        when {
            place != null -> {
                Heading("Place")
                Text(place.name, fontWeight = FontWeight.SemiBold)
                Field("Colour set") { Text(place.colourSet, style = mono) }
                place.port?.let { Field("Port") { Text(it.name.lowercase().replace('_', '/'), style = mono) } }
                place.fusionSet?.let { Field("Fusion set") { Text(it, style = mono) } }
                Field("Initial marking") { Code(place.initialMarking.ifBlank { "empty" }) }
                if (mode == CanvasMode.SIMULATE) {
                    val tokens = snapshot.overlay(page.id).tokens[place.id]
                    Field("Current marking (${tokens?.count ?: 0} tokens)") { Code(tokens?.text ?: "empty") }
                }
            }
            transition != null -> {
                Heading(if (transition.subpageId != null) "Substitution transition" else "Transition")
                Text(transition.name, fontWeight = FontWeight.SemiBold)
                transition.subpageId?.let { Field("Subpage") { Text(it, style = mono) } }
                if (transition.subpageId == null) Field("Guard") { Code(transition.guard.ifBlank { "none" }) }
                Field("Arcs") {
                    for (arc in page.arcsOf(transition.id)) {
                        val arrow =
                            when (arc.direction) {
                                ArcDirection.PLACE_TO_TRANSITION -> "in  "
                                ArcDirection.TRANSITION_TO_PLACE -> "out "
                                ArcDirection.BIDIRECTIONAL -> "both"
                            }
                        val placeName = page.place(arc.placeId)?.name ?: arc.placeId
                        Text("$arrow $placeName  ${arc.inscription}", style = mono)
                    }
                }
                if (mode == CanvasMode.SIMULATE && transition.subpageId == null) {
                    val bindings = snapshot.bindings[transition.id].orEmpty()
                    Field(if (bindings.isEmpty()) "Not enabled" else "Enabled bindings") {
                        for (binding in bindings) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(binding.binding.toString(), style = mono, modifier = Modifier.weight(1f))
                                TextButton(onClick = { act { session.fire(binding) } }) { Text("Fire") }
                            }
                        }
                    }
                }
            }
            else -> {
                Heading("Model")
                Text(document.name, fontWeight = FontWeight.SemiBold)
                Text("${document.pages.size} page(s). Select a shape to see its properties.", style = MaterialTheme.typography.bodySmall)
                Field("Declarations") { Code(document.declarations) }
            }
        }
        if (mode == CanvasMode.SIMULATE) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Heading("Trace")
            if (snapshot.trace.isEmpty()) {
                Text("No steps yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (occurrence in snapshot.trace.asReversed().take(60)) Text(occurrence.toString(), style = mono)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Field(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Code(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                .padding(6.dp),
    )
}

@Composable
private fun BottomBar(
    document: CpnDocument,
    pageId: String,
    mode: CanvasMode,
    session: SimulationSession,
    snapshot: SessionSnapshot,
    onPage: (String) -> Unit,
    act: (() -> Unit) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (page in document.pages) {
            if (page.id == pageId) {
                FilledTonalButton(onClick = {}) { Text(page.name) }
            } else {
                TextButton(onClick = { onPage(page.id) }) { Text(page.name) }
            }
        }
        Spacer(Modifier.weight(1f))
        if (mode == CanvasMode.SIMULATE) {
            SeedField(session, act)
            OutlinedButton(onClick = { act { session.step() } }, enabled = !snapshot.isDead) { Text("Step") }
            OutlinedButton(onClick = { act { session.run(25) } }, enabled = !snapshot.isDead) { Text("Run 25") }
            OutlinedButton(onClick = { act { session.run(1000) } }, enabled = !snapshot.isDead) { Text("Run to end") }
            val status =
                when {
                    snapshot.isDead -> "step ${snapshot.stepCount} · dead marking"
                    snapshot.lastStop == StopReason.MAX_STEPS -> "step ${snapshot.stepCount} · step limit"
                    else -> "step ${snapshot.stepCount} · ${snapshot.enabled.size} enabled"
                }
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        } else {
            Text("Edit mode: moving shapes changes the layout only", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Seed input plus Reset: resetting with a new seed starts a new reproducible run. */
@Composable
private fun SeedField(
    session: SimulationSession,
    act: (() -> Unit) -> Unit,
) {
    var text by remember(session) { mutableStateOf(session.seed.toString()) }
    val seed = text.toLongOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Seed", style = MaterialTheme.typography.labelMedium)
        BasicTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || c == '-' }.take(19) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier =
                Modifier
                    .width(90.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
        )
        OutlinedButton(onClick = { act { session.reset(seed ?: session.seed) } }) { Text("Reset") }
    }
}
