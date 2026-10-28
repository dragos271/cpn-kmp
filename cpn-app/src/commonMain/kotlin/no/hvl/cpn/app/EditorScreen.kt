package no.hvl.cpn.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.hvl.cpn.app.canvas.CanvasColors
import no.hvl.cpn.app.canvas.CanvasMode
import no.hvl.cpn.app.canvas.NetCanvas
import no.hvl.cpn.app.canvas.Tool
import no.hvl.cpn.app.canvas.Viewport
import no.hvl.cpn.app.canvas.snap
import no.hvl.cpn.app.files.modelFileAccess
import no.hvl.cpn.app.files.suggestedFileName
import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.io.LoadedModel
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.SavedFormat
import no.hvl.cpn.io.model.CompiledModel
import no.hvl.cpn.io.model.ConnectResult
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.Problem
import no.hvl.cpn.io.model.ProblemField
import no.hvl.cpn.io.model.addPlace
import no.hvl.cpn.io.model.addSubstitution
import no.hvl.cpn.io.model.addTransition
import no.hvl.cpn.io.model.check
import no.hvl.cpn.io.model.compile
import no.hvl.cpn.io.model.connect
import no.hvl.cpn.io.model.delete
import no.hvl.cpn.io.model.structure
import no.hvl.cpn.io.model.updateArc
import no.hvl.cpn.io.model.updatePlace
import no.hvl.cpn.io.model.updateTransition
import no.hvl.cpn.lang.eval.EvaluationException
import no.hvl.cpn.sim.StopReason

/**
 * The editor, laid out like a diagram tool: toolbar on top, shape palette on the left, the canvas,
 * a properties panel on the right, and page tabs plus simulation controls at the bottom.
 */
@Composable
fun EditorScreen(
    model: LoadedModel,
    /** Where the model was opened from (a path on desktop), or null if it has not been saved yet. */
    location: String?,
    onOpen: (LoadedModel, String?) -> Unit,
) {
    val history = remember(model) { EditHistory(model.document) }
    val document = history.current
    var pageId by remember(model) {
        mutableStateOf(
            model.document.pages
                .first()
                .id,
        )
    }
    var selection by remember { mutableStateOf<String?>(null) }
    // Runnable models open ready to simulate; models that cannot run yet open for editing.
    var mode by remember(model) { mutableStateOf(if (model.net != null) CanvasMode.SIMULATE else CanvasMode.EDIT) }
    var tool by remember { mutableStateOf<Tool?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val viewports = remember(model) { mutableStateMapOf<String, Viewport>() }
    val page = document.page(pageId) ?: document.pages.first()
    val viewport = viewports.getOrPut(page.id) { Viewport() }

    // What runs is the document as it is now: the opened net while only the layout changed,
    // otherwise the document compiled by the CPN ML evaluator (T2.5). Moving shapes keeps the run.
    val structure = remember(document) { document.structure() }
    val compiled =
        remember(structure) {
            if (model.net != null &&
                structure == model.document.structure()
            ) {
                CompiledModel(model.net, document.check())
            } else {
                document.compile()
            }
        }
    val problems = compiled.problems
    val problemIds = remember(problems) { problems.mapNotNull { it.elementId }.toSet() }
    // Creating the session fails for nets the binding search cannot handle, e.g. a variable bound by no input arc.
    val started = remember(compiled) { compiled.net?.let { net -> runCatching { SimulationSession(document, net, seed = 2026) } } }
    val session = started?.getOrNull()
    val cannotRun =
        when {
            compiled.net == null ->
                "This model can't run yet: fix the ${plural(problems.size, "problem")} marked in red (switch to Edit to see them)."
            session == null -> "This model can't run: ${started?.exceptionOrNull()?.message}"
            else -> null
        }

    /** The session's state for the screen; an inscription that fails (e.g. raises Div) stops the simulation with a message. */
    fun snapshotOf(session: SimulationSession?): SessionSnapshot? =
        try {
            session?.snapshot()
        } catch (e: EvaluationException) {
            message = "Simulation stopped: ${e.message}"
            null
        }
    var snapshot by remember(session) { mutableStateOf(snapshotOf(session)) }

    fun act(action: () -> Unit) {
        try {
            action()
        } catch (e: EvaluationException) {
            message = "Simulation stopped: ${e.message}"
        }
        snapshot = snapshotOf(session)
    }

    // Saving: what was last saved, where, and a short notice about it.
    val files = remember { modelFileAccess() }
    val scope = rememberCoroutineScope()
    var savedDocument by remember(model) { mutableStateOf(model.document) }
    var savedTo by remember(model) { mutableStateOf(location) }
    var notice by remember(model) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    val unsaved = document != savedDocument

    fun save(asNewFile: Boolean) {
        scope.launch {
            val current = history.current
            val saved = ModelFiles.save(model, current)
            val target = if (asNewFile || !files.canSaveInPlace) null else savedTo
            val where =
                try {
                    files.save(saved.text, suggestedFileName(current.name), target)
                } catch (e: Exception) {
                    message = "Couldn't save: ${e.message}"
                    return@launch
                } ?: return@launch
            savedTo = where
            savedDocument = current
            message = null
            notice =
                when (saved.format) {
                    SavedFormat.RUNNABLE_MODEL -> "Saved to $where"
                    SavedFormat.DOCUMENT -> "Saved to $where as an editable document"
                }
        }
    }

    /** Runs [action] now, or after the user agrees to drop unsaved changes. */
    fun discardingChanges(action: () -> Unit) {
        if (history.current == savedDocument) action() else confirmDiscard = action
    }

    fun open() {
        scope.launch {
            val file = files.open() ?: return@launch
            try {
                onOpen(ModelFiles.open(file.text), file.name)
            } catch (e: Exception) {
                message = "Couldn't open ${file.name}: ${e.message}"
            }
        }
    }

    // An edit gesture ends when the selection changes, so the next edit is its own undo step.
    LaunchedEffect(selection) { history.breakCoalescing() }

    // Event handlers can outlive the composition that created them (Compose may reuse lambdas), so
    // they read the document and page from the history when they run, never from captured values.
    fun currentPageId(): String =
        history.current.page(pageId)?.id ?: history.current.pages
            .first()
            .id

    fun add(
        kind: Tool,
        at: Point,
    ) {
        val added =
            when (kind) {
                Tool.PLACE -> history.current.addPlace(currentPageId(), at)
                Tool.TRANSITION -> history.current.addTransition(currentPageId(), at)
                Tool.SUBPAGE -> history.current.addSubstitution(currentPageId(), at)
                Tool.ARC -> return
            }
        history.breakCoalescing()
        history.edit { added.document }
        selection = added.id
        tool = null
        message = null
        mode = CanvasMode.EDIT
    }

    fun connect(
        fromId: String,
        toId: String,
    ) {
        when (val result = history.current.connect(currentPageId(), fromId, toId)) {
            is ConnectResult.Connected -> {
                history.breakCoalescing()
                history.edit { result.document }
                selection = result.arcId
                message = null
            }
            is ConnectResult.Rejected -> message = result.reason
        }
    }

    fun deleteSelection() {
        val id = selection ?: return
        history.breakCoalescing()
        history.edit { it.delete(currentPageId(), id) }
        selection = null
    }

    fun keepSelectionIfItExists() {
        val id = selection ?: return
        if (history.current
                .page(currentPageId())
                ?.ids()
                ?.contains(id) != true
        ) {
            selection = null
        }
    }

    fun undo() {
        history.undo()
        keepSelectionIfItExists()
    }

    fun redo() {
        history.redo()
        keepSelectionIfItExists()
    }

    // Fit each page to the window the first time it is shown.
    val fitted = remember(model) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(page.id, viewport.size) {
        if (fitted[page.id] != true && viewport.size != IntSize.Zero) {
            fitPage(viewport, page)
            fitted[page.id] = true
        }
    }

    val canvasFocus = remember { FocusRequester() }
    var canvasFocused by remember { mutableStateOf(false) }

    // Keyboard shortcuts need a focused element; on the web a clicked button does not keep focus,
    // so palette and toolbar actions hand it back to the canvas.
    fun focusCanvas() {
        runCatching { canvasFocus.requestFocus() }
    }
    LaunchedEffect(Unit) { focusCanvas() }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var paletteDrag by remember { mutableStateOf<Pair<Tool, Offset>?>(null) }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val command = event.isMetaPressed || event.isCtrlPressed
        return when {
            command && event.key == Key.S -> true.also { save(asNewFile = event.isShiftPressed) }
            command && event.key == Key.O -> true.also { discardingChanges(::open) }
            command && event.key == Key.Z && event.isShiftPressed -> true.also { redo() }
            command && event.key == Key.Z -> true.also { undo() }
            command && event.key == Key.Y -> true.also { redo() }
            event.key == Key.Escape ->
                true.also {
                    tool = null
                    selection = null
                    message = null
                }
            // Delete and Backspace edit text in fields; they only delete shapes when the canvas has focus.
            (event.key == Key.Delete || event.key == Key.Backspace) && canvasFocused && mode == CanvasMode.EDIT && selection != null ->
                true.also { deleteSelection() }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent(::handleKey)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                document = document,
                unsaved = unsaved,
                canSaveAs = files.canSaveInPlace,
                onNew = { discardingChanges { onOpen(ModelFiles.newModel(), null) } },
                onOpen = { discardingChanges(::open) },
                onSave = { save(asNewFile = false) },
                onSaveAs = { save(asNewFile = true) },
                mode = mode,
                viewport = viewport,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                canDelete = mode == CanvasMode.EDIT && selection != null,
                onMode = {
                    mode = it
                    tool = null
                    message = null
                    focusCanvas()
                },
                onFit = { fitPage(viewport, page) },
                onUndo = {
                    undo()
                    focusCanvas()
                },
                onRedo = {
                    redo()
                    focusCanvas()
                },
                onDelete = ::deleteSelection,
            )
            HorizontalDivider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Palette(
                    selected = tool,
                    onArm = { armed ->
                        tool = if (tool == armed) null else armed
                        message = null
                        mode = CanvasMode.EDIT
                        focusCanvas()
                    },
                    onDragStart = { dragged, at -> paletteDrag = dragged to at },
                    onDrag = { at -> paletteDrag = paletteDrag?.let { (t, _) -> t to at } },
                    onDrop = {
                        paletteDrag?.let { (dropped, at) ->
                            if (canvasBounds.contains(at)) add(dropped, snap(viewport.toWorld(at - canvasBounds.topLeft)))
                        }
                        paletteDrag = null
                        focusCanvas()
                    },
                    modifier = Modifier.width(92.dp).fillMaxHeight(),
                )
                VerticalDivider()
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { canvasBounds = it.boundsInRoot() }
                        .focusRequester(canvasFocus)
                        .onFocusChanged { canvasFocused = it.isFocused }
                        .focusable()
                        .pointerInput(Unit) {
                            // Clicking the canvas gives it keyboard focus (for Delete) without taking the click.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press) canvasFocus.requestFocus()
                                }
                            }
                        },
                ) {
                    NetCanvas(
                        page = page,
                        mode = mode,
                        tool = tool,
                        selection = selection,
                        overlay = if (mode == CanvasMode.SIMULATE) snapshot?.overlay(page.id) else null,
                        problems = if (mode == CanvasMode.EDIT) problemIds else emptySet(),
                        viewport = viewport,
                        colors = canvasColors(),
                        onSelect = {
                            selection = it
                            message = null
                        },
                        onMove = { id, position ->
                            history.edit("move:$id") { doc -> doc.withPage(doc.page(currentPageId())!!.moved(id, position)) }
                        },
                        onMoveEnd = { history.breakCoalescing() },
                        onFire = { id -> act { session?.fireTransition(currentPageId(), id) } },
                        onPlace = ::add,
                        onConnect = ::connect,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (mode == CanvasMode.SIMULATE) {
                        cannotRun?.let { Notice(it, Modifier.align(Alignment.TopCenter).padding(10.dp)) }
                    }
                    (message ?: notice)?.let { text ->
                        Toast(text, isError = message != null, Modifier.align(Alignment.BottomEnd).padding(10.dp))
                    }
                    CanvasHint(mode, tool, Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
                VerticalDivider()
                PropertiesPanel(
                    document = document,
                    page = page,
                    selection = selection,
                    mode = mode,
                    problems = problems,
                    snapshot = snapshot,
                    session = session,
                    act = ::act,
                    edit = { key, change -> history.edit(key, change) },
                    onDelete = ::deleteSelection,
                    onOpenPage = {
                        pageId = it
                        selection = null
                    },
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )
            }
            HorizontalDivider()
            BottomBar(
                document = document,
                pageId = page.id,
                mode = mode,
                session = session,
                snapshot = snapshot,
                problemCount = problems.size,
                onPage = {
                    pageId = it
                    selection = null
                },
                act = ::act,
            )
        }
        paletteDrag?.let { (dragged, at) -> DragGhost(dragged, at) }
        confirmDiscard?.let { action ->
            AlertDialog(
                onDismissRequest = { confirmDiscard = null },
                title = { Text("Discard unsaved changes?") },
                text = { Text("Your changes to ${document.name} haven't been saved.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = null
                        action()
                    }) { Text("Discard") }
                },
                dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Cancel") } },
            )
        }
    }
}

private fun PageDocument.ids(): Set<String> = (places.map { it.id } + transitions.map { it.id } + arcs.map { it.id }).toSet()

private fun fitPage(
    viewport: Viewport,
    page: PageDocument,
) {
    val points = page.places.map { it.position } + page.transitions.map { it.position }
    if (points.isEmpty()) {
        viewport.zoom = 1f
        viewport.pan = Offset.Zero
        return
    }
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
        error = scheme.error,
    )
}

@Composable
private fun TopBar(
    document: CpnDocument,
    unsaved: Boolean,
    canSaveAs: Boolean,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    mode: CanvasMode,
    viewport: Viewport,
    canUndo: Boolean,
    canRedo: Boolean,
    canDelete: Boolean,
    onMode: (CanvasMode) -> Unit,
    onFit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The commands scroll sideways in narrow windows; the mode switch stays visible on the right.
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolbarCommands(
                document,
                unsaved,
                canSaveAs,
                onNew,
                onOpen,
                onSave,
                onSaveAs,
                viewport,
                canUndo,
                canRedo,
                canDelete,
                onFit,
                onUndo,
                onRedo,
                onDelete,
            )
        }
        Spacer(Modifier.width(8.dp))
        ModeButton("Edit", mode == CanvasMode.EDIT) { onMode(CanvasMode.EDIT) }
        ModeButton("Simulate", mode == CanvasMode.SIMULATE) { onMode(CanvasMode.SIMULATE) }
    }
}

@Composable
private fun ToolbarCommands(
    document: CpnDocument,
    unsaved: Boolean,
    canSaveAs: Boolean,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    viewport: Viewport,
    canUndo: Boolean,
    canRedo: Boolean,
    canDelete: Boolean,
    onFit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
) {
    run {
        Text("CPN Tool", fontWeight = FontWeight.SemiBold)
        Text(document.name + if (unsaved) " •" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onNew) { Text("New") }
        TextButton(onClick = onOpen) { Text("Open…") }
        TextButton(onClick = onSave) { Text("Save") }
        if (canSaveAs) TextButton(onClick = onSaveAs) { Text("Save as…") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
        TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
        TextButton(onClick = onDelete, enabled = canDelete) { Text("Delete") }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = { viewport.zoomAt(1f / 1.2f) }) { Text("−") }
        Text("${(viewport.zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { viewport.zoomAt(1.2f) }) { Text("+") }
        TextButton(onClick = onFit) { Text("Fit") }
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

private fun paletteIcon(
    tool: Tool,
    stroke: Color,
): DrawScope.() -> Unit =
    when (tool) {
        Tool.PLACE -> {
            { drawOval(stroke, Offset(4f, 8f), Size(size.width - 8, size.height - 16), style = Stroke(1.5f)) }
        }
        Tool.TRANSITION -> {
            { drawRect(stroke, Offset(6f, 10f), Size(size.width - 12, size.height - 20), style = Stroke(1.5f)) }
        }
        Tool.SUBPAGE -> {
            { drawRect(stroke, Offset(6f, 10f), Size(size.width - 12, size.height - 20), style = Stroke(4f)) }
        }
        Tool.ARC -> {
            {
                val y = size.height / 2
                drawLine(stroke, Offset(4f, y), Offset(size.width - 6, y), 1.5f)
                drawLine(stroke, Offset(size.width - 6, y), Offset(size.width - 14, y - 6), 1.5f)
                drawLine(stroke, Offset(size.width - 6, y), Offset(size.width - 14, y + 6), 1.5f)
            }
        }
    }

private fun paletteLabel(tool: Tool): String =
    when (tool) {
        Tool.PLACE -> "Place"
        Tool.TRANSITION -> "Transition"
        Tool.ARC -> "Arc"
        Tool.SUBPAGE -> "Subpage"
    }

/** The shape palette: drag a shape onto the canvas, or click it and then click the canvas. */
@Composable
private fun Palette(
    selected: Tool?,
    onArm: (Tool) -> Unit,
    onDragStart: (Tool, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Shapes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val stroke = MaterialTheme.colorScheme.onSurface
        for (tool in Tool.entries) {
            var origin by remember { mutableStateOf(Offset.Zero) }
            val background = if (tool == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            Column(
                Modifier
                    .background(background, RoundedCornerShape(8.dp))
                    .padding(4.dp)
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(tool) {
                        // One gesture loop for both: moving past the touch slop drags the shape out
                        // (arcs need two ends, so they are only armed); anything else is a click.
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                if (!dragging && moved && tool != Tool.ARC) {
                                    dragging = true
                                    onDragStart(tool, origin + down.position)
                                }
                                if (dragging) {
                                    onDrag(origin + change.position)
                                    change.consume()
                                }
                            }
                            if (dragging) onDrop() else onArm(tool)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(width = 56.dp, height = 36.dp), onDraw = paletteIcon(tool, stroke))
                Text(paletteLabel(tool), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "Drag onto the canvas, or click a shape, then the canvas",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** The shape following the pointer while it is dragged from the palette. */
@Composable
private fun DragGhost(
    tool: Tool,
    at: Offset,
) {
    val stroke = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .offset { IntOffset((at.x - 28.dp.toPx()).toInt(), (at.y - 18.dp.toPx()).toInt()) }
            .size(width = 56.dp, height = 36.dp)
            .alpha(0.8f),
        onDraw = paletteIcon(tool, stroke),
    )
}

@Composable
private fun CanvasHint(
    mode: CanvasMode,
    tool: Tool?,
    modifier: Modifier,
) {
    val text =
        when {
            mode == CanvasMode.SIMULATE -> "Click a green transition to fire it · drag to pan · scroll to zoom"
            tool == Tool.ARC -> "Drag from a place to a transition, or back, to draw an arc · Esc to stop"
            tool != null -> "Click the canvas to add a ${paletteLabel(tool).lowercase()} · Esc to cancel"
            else -> "Drag shapes to move them · Delete removes the selection · Cmd/Ctrl+Z undoes"
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
private fun Toast(
    text: String,
    isError: Boolean,
    modifier: Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            modifier
                .width(420.dp)
                .background(
                    if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(8.dp),
                ).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Notice(
    text: String,
    modifier: Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier =
            modifier
                .width(520.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PropertiesPanel(
    document: CpnDocument,
    page: PageDocument,
    selection: String?,
    mode: CanvasMode,
    problems: List<Problem>,
    snapshot: SessionSnapshot?,
    session: SimulationSession?,
    act: (() -> Unit) -> Unit,
    edit: (String?, (CpnDocument) -> CpnDocument) -> Unit,
    onDelete: () -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier,
) {
    val editing = mode == CanvasMode.EDIT

    fun problem(
        id: String?,
        field: ProblemField,
    ) = problems.firstOrNull { it.elementId == id && it.field == field }?.message

    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val place = selection?.let(page::place)
        val transition = selection?.let(page::transition)
        val arc = selection?.let { id -> page.arcs.firstOrNull { it.id == id } }
        when {
            place != null -> {
                Heading("Place")
                TextProperty("Name", place.name, editing) { v ->
                    edit("name:${place.id}") { it.updatePlace(page.id, place.id) { p -> p.copy(name = v) } }
                }
                TextProperty("Colour set", place.colourSet, editing, error = problem(place.id, ProblemField.COLOUR_SET)) { v ->
                    edit("colset:${place.id}") { it.updatePlace(page.id, place.id) { p -> p.copy(colourSet = v) } }
                }
                place.port?.let { Field("Port") { Code(it.name.lowercase().replace('_', '/')) } }
                place.fusionSet?.let { Field("Fusion set") { Code(it) } }
                TextProperty(
                    "Initial marking",
                    place.initialMarking,
                    editing,
                    error = problem(place.id, ProblemField.INITIAL_MARKING),
                    singleLine = false,
                    placeholder = "empty",
                ) { v -> edit("marking:${place.id}") { it.updatePlace(page.id, place.id) { p -> p.copy(initialMarking = v) } } }
                if (!editing) {
                    val tokens = snapshot?.overlay(page.id)?.tokens?.get(place.id)
                    Field("Current marking (${tokens?.count ?: 0} tokens)") { Code(tokens?.text ?: "empty") }
                }
            }
            transition != null -> {
                Heading(if (transition.subpageId != null) "Substitution transition" else "Transition")
                TextProperty("Name", transition.name, editing) { v ->
                    edit("name:${transition.id}") { it.updateTransition(page.id, transition.id) { t -> t.copy(name = v) } }
                }
                val subpageId = transition.subpageId
                if (subpageId != null) {
                    Field("Subpage") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Code(subpageId, Modifier.weight(1f))
                            TextButton(onClick = { onOpenPage(subpageId) }) { Text("Open") }
                        }
                    }
                    problem(transition.id, ProblemField.STRUCTURE)?.let { ProblemText(it) }
                } else {
                    TextProperty(
                        "Guard",
                        transition.guard,
                        editing,
                        error = problem(transition.id, ProblemField.GUARD),
                        placeholder = "none",
                    ) { v ->
                        edit("guard:${transition.id}") { it.updateTransition(page.id, transition.id) { t -> t.copy(guard = v) } }
                    }
                }
                Field("Arcs") {
                    for (a in page.arcsOf(transition.id)) {
                        val placeName = page.place(a.placeId)?.name ?: a.placeId
                        Text("${directionLabel(a.direction)}  $placeName  ${a.inscription}", style = MONO)
                    }
                }
                if (!editing && transition.subpageId == null && snapshot != null && session != null) {
                    val bindings = snapshot.bindings[transition.id].orEmpty()
                    Field(if (bindings.isEmpty()) "Not enabled" else "Enabled bindings") {
                        for (binding in bindings) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(binding.binding.toString(), style = MONO, modifier = Modifier.weight(1f))
                                TextButton(onClick = { act { session.fire(binding) } }) { Text("Fire") }
                            }
                        }
                    }
                }
            }
            arc != null -> {
                Heading("Arc")
                val placeName = page.place(arc.placeId)?.name ?: arc.placeId
                val transitionName = page.transition(arc.transitionId)?.name ?: arc.transitionId
                Text("$placeName — $transitionName", fontWeight = FontWeight.SemiBold)
                Field("Direction") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (direction in ArcDirection.entries) {
                            val label = directionLabel(direction)
                            if (direction == arc.direction) {
                                FilledTonalButton(onClick = {}) { Text(label) }
                            } else {
                                TextButton(
                                    onClick = { edit(null) { it.updateArc(page.id, arc.id) { a -> a.copy(direction = direction) } } },
                                    enabled = editing,
                                ) { Text(label) }
                            }
                        }
                    }
                }
                TextProperty(
                    "Inscription",
                    arc.inscription,
                    editing,
                    error = problem(arc.id, ProblemField.INSCRIPTION),
                    singleLine = false,
                ) { v ->
                    edit("inscription:${arc.id}") { it.updateArc(page.id, arc.id) { a -> a.copy(inscription = v) } }
                }
            }
            else -> {
                Heading("Model")
                Text(document.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${document.pages.size} page(s). Select a shape or an arc to see its properties.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextProperty(
                    "Declarations",
                    document.declarations,
                    editing,
                    error = problems.filter { it.field == ProblemField.DECLARATIONS }.joinToString("\n") { it.message }.ifBlank { null },
                    singleLine = false,
                ) { v -> edit("declarations") { it.copy(declarations = v) } }
                // Problems with how the net fits together, found when compiling it.
                problems.filter { it.field == ProblemField.STRUCTURE && it.elementId == null }.forEach { ProblemText(it.message) }
            }
        }
        if (editing && (place != null || transition != null || arc != null)) {
            OutlinedButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) { Text("Delete") }
        }
        if (!editing && snapshot != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Heading("Trace")
            if (snapshot.trace.isEmpty()) {
                Text("No steps yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (occurrence in snapshot.trace.asReversed().take(60)) Text(occurrence.toString(), style = MONO)
        }
    }
}

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private fun directionLabel(direction: ArcDirection): String =
    when (direction) {
        ArcDirection.PLACE_TO_TRANSITION -> "in"
        ArcDirection.TRANSITION_TO_PLACE -> "out"
        ArcDirection.BIDIRECTIONAL -> "both"
    }

/** A property: an editable field in Edit mode, read-only text in Simulate mode. Errors show underneath. */
@Composable
private fun TextProperty(
    label: String,
    value: String,
    editable: Boolean,
    error: String? = null,
    singleLine: Boolean = true,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    if (editable) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it, style = MONO) } },
            textStyle = MONO,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            isError = error != null,
            supportingText = error?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Field(label) {
            Code(value.ifBlank { placeholder ?: "" })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
private fun Code(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Text(
        text,
        style = MONO,
        modifier = modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)).padding(6.dp),
    )
}

@Composable
private fun BottomBar(
    document: CpnDocument,
    pageId: String,
    mode: CanvasMode,
    session: SimulationSession?,
    snapshot: SessionSnapshot?,
    problemCount: Int,
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
        if (mode == CanvasMode.SIMULATE && (session == null || snapshot == null)) {
            Text("Can't run", style = MaterialTheme.typography.bodySmall)
        } else if (mode == CanvasMode.SIMULATE && session != null && snapshot != null) {
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
            when {
                problemCount > 0 ->
                    Text(
                        if (problemCount == 1) "1 problem, marked in red" else "$problemCount problems, marked in red",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                else -> Text("No problems found", style = MaterialTheme.typography.bodySmall)
            }
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
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
        )
        OutlinedButton(onClick = { act { session.reset(seed ?: session.seed) } }) { Text("Reset") }
    }
}

@Composable
private fun ProblemText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun plural(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"
