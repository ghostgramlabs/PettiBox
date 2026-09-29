package com.ghostgramlabs.pettibox.ui.screens.detail

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Scale + translation for pinch-zoomable content. Translation is clamped so
 * the scaled content always covers the viewport — you can't drag it off into
 * empty space — and whatever a pan couldn't apply is handed back to the
 * caller as overflow (the pager or the PDF page list takes it from there).
 */
@Stable
class ZoomState(private val maxScale: Float = 5f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    internal var size: IntSize = IntSize.Zero

    val isZoomed: Boolean get() = scale > 1.01f

    /** Applies one gesture step; returns the part of [pan] that hit the clamp. */
    fun transform(centroid: Offset, zoom: Float, pan: Offset): Offset {
        val old = scale
        val new = (old * zoom).coerceIn(1f, maxScale)
        // Keep the content point under the fingers fixed while scaling.
        val c = center()
        val scaled = if (new != old) centroid - c - (centroid - c - offset) * (new / old) else offset
        val wanted = scaled + pan
        val clamped = clamp(wanted, new)
        scale = new
        offset = clamped
        return wanted - clamped
    }

    /** Double-tap: zoom in around [tap], or back out to fit if already zoomed. */
    suspend fun toggle(tap: Offset) {
        val fromScale = scale
        val fromOffset = offset
        val toScale = if (isZoomed) 1f else DOUBLE_TAP_SCALE
        val c = center()
        val toOffset = clamp(tap - c - (tap - c - fromOffset) * (toScale / fromScale), toScale)
        animate(0f, 1f, animationSpec = tween(220)) { f, _ ->
            scale = fromScale + (toScale - fromScale) * f
            offset = lerp(fromOffset, toOffset, f)
        }
    }

    private fun center() = Offset(size.width / 2f, size.height / 2f)

    private fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    private companion object {
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }

/** Draws this node's content at [state]'s scale and translation. */
fun Modifier.zoomTransform(state: ZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}

/**
 * Pinch to zoom, drag to pan while zoomed, double-tap to toggle.
 *
 * One-finger drags at 1x are left alone so a surrounding pager or list keeps
 * swiping/scrolling as usual. Once zoomed, drags pan the content; any pan the
 * clamp rejects goes to [onOverflow], which returns true if it used it. If it
 * didn't, the event stays unconsumed so a pager can take over at the edge.
 *
 * [pass] is [PointerEventPass.Initial] when this wraps a scrollable child
 * that would otherwise claim the drag before we see it.
 */
@Composable
fun Modifier.zoomable(
    state: ZoomState,
    pass: PointerEventPass = PointerEventPass.Main,
    onOverflow: (Offset) -> Boolean = { false },
    onFling: (Velocity) -> Unit = {}
): Modifier {
    val scope = rememberCoroutineScope()
    return this
        // Scaled content must not paint outside the viewport (over the
        // header bar or the status bar).
        .clipToBounds()
        .onSizeChanged { state.size = it }
        .pointerInput(state) {
            detectTapGestures(onDoubleTap = { tap -> scope.launch { state.toggle(tap) } })
        }
        .pointerInput(state, pass) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                val velocity = VelocityTracker()
                velocity.addPosition(down.uptimeMillis, down.position)
                var panned = false
                var wasMultiTouch = false
                while (true) {
                    val event = awaitPointerEvent(pass)
                    if (event.changes.any { it.isConsumed }) break
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) {
                        if (panned && !wasMultiTouch) onFling(velocity.calculateVelocity())
                        break
                    }
                    val multiTouch = pressed > 1
                    wasMultiTouch = wasMultiTouch || multiTouch
                    if (!multiTouch && !state.isZoomed) continue

                    val overflow = state.transform(
                        centroid = event.calculateCentroid(useCurrent = false),
                        zoom = event.calculateZoom(),
                        pan = event.calculatePan()
                    )
                    val handled = overflow == Offset.Zero || onOverflow(overflow)
                    // A zoomed image pushed against its side edge hands the
                    // swipe to the pager instead of swallowing it.
                    val releaseToPager = !multiTouch && !handled && abs(overflow.x) > abs(overflow.y)
                    if (!releaseToPager) {
                        panned = true
                        event.changes.firstOrNull { it.pressed }?.let {
                            velocity.addPosition(it.uptimeMillis, it.position)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
        }
}
