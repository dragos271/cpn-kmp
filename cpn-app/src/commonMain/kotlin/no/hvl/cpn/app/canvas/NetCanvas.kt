package no.hvl.cpn.app.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import no.hvl.cpn.app.PageOverlay
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class CanvasMode { EDIT, SIMULATE }

/**
 * Pan and zoom of the canvas. World units are density-independent pixels (like Compose's dp), so a
 * model looks the same on retina and ordinary screens: screen = world * zoom * density + pan.
 */
@Stable
class Viewport {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var density by mutableFloatStateOf(1f)

    /** Screen pixels per world unit. */
    val scale: Float get() = zoom * density

    fun toWorld(screen: Offset): Point = Point(((screen.x - pan.x) / scale).toDouble(), ((screen.y - pan.y) / scale).toDouble())

    /** Zooms by [factor] keeping the world point under [anchor] fixed. */
    fun zoomAt(
        factor: Float,
        anchor: Offset = Offset(size.width / 2f, size.height / 2f),
    ) {
        val world = toWorld(anchor)
        zoom = (zoom * factor).coerceIn(0.2f, 4f)
        pan = Offset(anchor.x - world.x.toFloat() * scale, anchor.y - world.y.toFloat() * scale)
    }

    /** Shows the whole box [min]..[max] with a margin, at most at 100 %. */
    fun fit(
        min: Point,
        max: Point,
        margin: Float = 40f,
    ) {
        if (size.width == 0 || size.height == 0) return
        val width = (max.x - min.x).toFloat().coerceAtLeast(1f)
        val height = (max.y - min.y).toFloat().coerceAtLeast(1f)
        val available = Offset(size.width / density - 2 * margin, size.height / density - 2 * margin)
        zoom = min(1f, min(available.x / width, available.y / height)).coerceIn(0.2f, 4f)
        pan =
            Offset(
                (size.width - width * scale) / 2 - min.x.toFloat() * scale,
                (size.height - height * scale) / 2 - min.y.toFloat() * scale,
            )
    }
}

/** Text is measured at density 1, i.e. in world units, and scaled with the canvas when drawn. */
private val WORLD_DENSITY = Density(1f)

/** Colours of the canvas, taken from the app theme. */
data class CanvasColors(
    val background: Color,
    val grid: Color,
    val stroke: Color,
    val text: Color,
    val secondaryText: Color,
    val placeFill: Color,
    val transitionFill: Color,
    val selection: Color,
    val enabledFill: Color,
    val enabledStroke: Color,
    val tokenFill: Color,
    val tokenText: Color,
)

/**
 * Draws one page of a CPN in CPN Tools notation (ellipses for places, rectangles for transitions,
 * thick rectangles for substitution transitions, arcs with arrowheads and inscriptions) on a dot
 * grid. Drag the background to pan, scroll to zoom. In [CanvasMode.EDIT], drag shapes to move them;
 * in [CanvasMode.SIMULATE], click an enabled transition to fire it.
 */
@Composable
fun NetCanvas(
    page: PageDocument,
    mode: CanvasMode,
    selection: String?,
    overlay: PageOverlay?,
    viewport: Viewport,
    colors: CanvasColors,
    onSelect: (String?) -> Unit,
    onMove: (String, Point) -> Unit,
    onFire: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    viewport.density = LocalDensity.current.density
    val nameStyle = TextStyle(fontSize = 13.sp, color = colors.text)
    val shapes =
        remember(page, measurer) {
            shapesOf(page) { name ->
                measurer
                    .measure(name, nameStyle, density = WORLD_DENSITY)
                    .size.width
                    .toDouble()
            }
        }
    val shapeById = remember(shapes) { shapes.associateBy { it.id } }
    val arcs = remember(page, shapeById) { arcLines(page, shapeById) }

    val currentShapes by rememberUpdatedState(shapes)
    val currentMode by rememberUpdatedState(mode)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnFire by rememberUpdatedState(onFire)

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport.size = it }
            .pointerInput(viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val hit = hitTest(currentShapes, viewport.toWorld(down.position))
                    val startPan = viewport.pan
                    val startCenter = hit?.center
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - down.position
                        if (!dragged && delta.getDistance() > viewConfiguration.touchSlop) dragged = true
                        if (dragged) {
                            if (hit != null && currentMode == CanvasMode.EDIT) {
                                currentOnMove(
                                    hit.id,
                                    snap(
                                        Point(
                                            startCenter!!.x + delta.x / viewport.scale,
                                            startCenter.y + delta.y / viewport.scale,
                                        ),
                                    ),
                                )
                            } else {
                                viewport.pan = startPan + delta
                            }
                            change.consume()
                        }
                    }
                    if (!dragged) {
                        currentOnSelect(hit?.id)
                        if (hit != null && !hit.isPlace && currentMode == CanvasMode.SIMULATE) currentOnFire(hit.id)
                    }
                }
            }.pointerInput(viewport) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val change = event.changes.first()
                            val dy = change.scrollDelta.y
                            if (dy != 0f) viewport.zoomAt(if (dy > 0) 0.9f else 1.1f, change.position)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        drawRect(colors.background)
        drawGrid(viewport, colors.grid)
        withTransform({
            translate(viewport.pan.x, viewport.pan.y)
            scale(viewport.scale, viewport.scale, pivot = Offset.Zero)
        }) {
            val labelStyle = TextStyle(fontSize = 12.sp, color = colors.text, fontFamily = FontFamily.Monospace)
            val noteStyle = TextStyle(fontSize = 11.sp, color = colors.secondaryText, fontStyle = FontStyle.Italic)

            for (arc in arcs) drawArcLine(arc, colors.stroke)
            for (arcDoc in page.arcs) {
                if (arcDoc.inscription.isBlank()) continue
                val line = arcs.firstOrNull { it.id == arcDoc.id } ?: continue
                drawLabel(measurer, ellipsize(arcDoc.inscription, 34), line.labelAnchor(), labelStyle, centered = true)
            }

            for (place in page.places) {
                val shape = shapeById.getValue(place.id)
                drawPlace(shape, colors, selected = place.id == selection)
                drawLabel(measurer, place.name, shape.center, nameStyle, centered = true, middle = true)
                drawLabel(measurer, place.colourSet, Point(shape.right - 6, shape.bottom + 2), noteStyle)
                if (overlay == null) {
                    if (place.initialMarking.isNotBlank()) {
                        drawLabel(measurer, ellipsize(place.initialMarking, 30), Point(shape.right - 4, shape.top - 16), labelStyle)
                    }
                } else {
                    overlay.tokens[place.id]?.takeIf { it.count > 0 }?.let { tokens ->
                        drawTokens(measurer, shape, tokens.count, tokens.text, colors)
                    }
                }
            }

            for (transition in page.transitions) {
                val shape = shapeById.getValue(transition.id)
                val enabled = overlay != null && transition.id in overlay.enabledTransitions
                drawTransition(
                    shape,
                    colors,
                    substitution = transition.subpageId != null,
                    enabled = enabled,
                    selected =
                        transition.id == selection,
                )
                drawLabel(measurer, transition.name, shape.center, nameStyle, centered = true, middle = true)
                if (transition.guard.isNotBlank()) {
                    drawLabel(measurer, ellipsize("[${transition.guard}]", 40), Point(shape.left, shape.top - 18), labelStyle)
                }
                transition.subpageId?.let { drawLabel(measurer, it, Point(shape.left + 4, shape.bottom + 2), noteStyle) }
            }
        }
    }
}

private fun ellipsize(
    text: String,
    max: Int,
): String = if (text.length <= max) text else text.take(max - 1) + "…"

private fun DrawScope.drawGrid(
    viewport: Viewport,
    color: Color,
) {
    val step = 20f * viewport.scale
    if (step < 6f) return
    val radius = 1.1f
    var x = viewport.pan.x % step
    while (x < size.width) {
        var y = viewport.pan.y % step
        while (y < size.height) {
            drawCircle(color, radius, Offset(x, y))
            y += step
        }
        x += step
    }
}

private fun DrawScope.drawPlace(
    shape: NodeShape,
    colors: CanvasColors,
    selected: Boolean,
) {
    val topLeft = Offset(shape.left.toFloat(), shape.top.toFloat())
    val size = Size((shape.halfWidth * 2).toFloat(), (shape.halfHeight * 2).toFloat())
    drawOval(colors.placeFill, topLeft, size)
    drawOval(colors.stroke, topLeft, size, style = Stroke(1.5f))
    if (selected) drawSelection(shape, colors.selection)
}

private fun DrawScope.drawTransition(
    shape: NodeShape,
    colors: CanvasColors,
    substitution: Boolean,
    enabled: Boolean,
    selected: Boolean,
) {
    val topLeft = Offset(shape.left.toFloat(), shape.top.toFloat())
    val size = Size((shape.halfWidth * 2).toFloat(), (shape.halfHeight * 2).toFloat())
    if (enabled) {
        // CPN Tools marks enabled transitions with a green aura.
        drawRoundRect(
            colors.enabledStroke.copy(alpha = 0.25f),
            topLeft - Offset(5f, 5f),
            Size(size.width + 10, size.height + 10),
            CornerRadius(6f),
        )
    }
    drawRect(if (enabled) colors.enabledFill else colors.transitionFill, topLeft, size)
    drawRect(if (enabled) colors.enabledStroke else colors.stroke, topLeft, size, style = Stroke(if (substitution) 4f else 1.5f))
    if (selected) drawSelection(shape, colors.selection)
}

private fun DrawScope.drawSelection(
    shape: NodeShape,
    color: Color,
) {
    drawRect(
        color,
        Offset(shape.left.toFloat() - 6, shape.top.toFloat() - 6),
        Size((shape.halfWidth * 2).toFloat() + 12, (shape.halfHeight * 2).toFloat() + 12),
        style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))),
    )
}

private fun DrawScope.drawArcLine(
    arc: ArcLine,
    color: Color,
) {
    val start = Offset(arc.start.x.toFloat(), arc.start.y.toFloat())
    val end = Offset(arc.end.x.toFloat(), arc.end.y.toFloat())
    drawLine(color, start, end, strokeWidth = 1.5f)
    drawArrowHead(start, end, color)
    if (arc.bothEnds) drawArrowHead(end, start, color)
}

private fun DrawScope.drawArrowHead(
    from: Offset,
    to: Offset,
    color: Color,
) {
    val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val length = 11.0
    val spread = 0.42
    val path =
        Path().apply {
            moveTo(to.x, to.y)
            lineTo((to.x - length * cos(angle - spread)).toFloat(), (to.y - length * sin(angle - spread)).toFloat())
            lineTo((to.x - length * cos(angle + spread)).toFloat(), (to.y - length * sin(angle + spread)).toFloat())
            close()
        }
    drawPath(path, color)
}

/** CPN Tools' token count bubble on the place, and the marking in a box next to it. */
private fun DrawScope.drawTokens(
    measurer: TextMeasurer,
    shape: NodeShape,
    count: Int,
    text: String,
    colors: CanvasColors,
) {
    val bubble = Offset((shape.right - 4).toFloat(), (shape.top + 2).toFloat())
    val countLayout = measurer.measure(count.toString(), TextStyle(fontSize = 11.sp, color = colors.tokenText), density = WORLD_DENSITY)
    val radius = max(10f, countLayout.size.width / 2f + 5f)
    drawCircle(colors.tokenFill, radius, bubble)
    drawText(countLayout, topLeft = bubble - Offset(countLayout.size.width / 2f, countLayout.size.height / 2f))

    val boxLayout =
        measurer.measure(
            ellipsize(text, 44),
            TextStyle(fontSize = 11.sp, color = colors.tokenText, fontFamily = FontFamily.Monospace),
            density = WORLD_DENSITY,
        )
    // The marking goes above and to the right of the place, where there is usually free space.
    val boxTopLeft = Offset(bubble.x + radius - 2, bubble.y - radius - boxLayout.size.height - 6)
    drawRoundRect(
        colors.tokenFill.copy(alpha = 0.55f),
        boxTopLeft,
        Size(boxLayout.size.width + 10f, boxLayout.size.height + 6f),
        CornerRadius(4f),
    )
    drawText(boxLayout, topLeft = boxTopLeft + Offset(5f, 3f))
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Point,
    style: TextStyle,
    centered: Boolean = false,
    middle: Boolean = false,
) {
    val layout = measurer.measure(text, style, density = WORLD_DENSITY)
    val x = if (centered) at.x.toFloat() - layout.size.width / 2f else at.x.toFloat()
    val y =
        if (middle) {
            at.y.toFloat() - layout.size.height / 2f
        } else if (centered) {
            at.y.toFloat() - layout.size.height / 2f
        } else {
            at.y.toFloat()
        }
    drawText(layout, topLeft = Offset(x, y))
}
