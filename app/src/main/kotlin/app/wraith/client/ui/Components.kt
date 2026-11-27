package app.wraith.client.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wraith.client.Flags
import app.wraith.client.L
import app.wraith.client.Tunnel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Translucent dark surface with a hairline edge: readable over any photo. */
@Composable
fun Glass(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp), color: Color = W.glass, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(shape).background(color).border(1.dp, W.stroke, shape), content = content)
}

@Composable
fun GlassColumn(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp), padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(shape).background(W.glass).border(1.dp, W.stroke, shape).padding(padding), content = content)
}

/** Status chip of the home screen: optional icon or live dot, then one line of text that never wraps. */
@Composable
fun Chip(text: String, icon: ImageVector? = null, dot: Color? = null, pulse: Boolean = false, tint: Color = W.text, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(W.glass).border(1.dp, W.stroke, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (dot != null) {
            val t = rememberInfiniteTransition(label = "dot")
            val a by t.animateFloat(1f, if (pulse) 0.25f else 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot.copy(alpha = a)))
            Spacer(Modifier.width(8.dp))
        }
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = W.muted); Spacer(Modifier.width(7.dp)) }
        Text(text, color = tint, style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), color = W.accent, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
}

/** One line of a settings group. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, danger: Boolean = false, trailing: (@Composable () -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (danger) W.bad.copy(alpha = 0.14f) else W.accentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (danger) W.bad else W.accent)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) W.bad else W.text, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = W.muted, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        else if (onClick != null && icon != null) Icon(Ic.Chevron, null, Modifier.size(18.dp), tint = W.faint)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, icon: ImageVector? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, icon, trailing = {
        Switch(checked, null, colors = SwitchDefaults.colors(checkedThumbColor = W.onAccent, checkedTrackColor = W.accent, uncheckedTrackColor = Color(0xFF2A2633), uncheckedBorderColor = Color(0xFF4A4655)))
    }) { onChange(!checked) }
}

/** A group of rows on one glass card with hairline separators. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(26.dp), content = content)
}

@Composable
fun Divider() { Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(1.dp).background(W.stroke)) }

@Composable
fun IconBtn(icon: ImageVector, description: String, tint: Color = W.text, background: Color = W.glass, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(background).border(1.dp, W.stroke, CircleShape).clickable(onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(size * 0.48f), tint = tint)
    }
}

/** Pill button: filled with the accent, or glass. */
@Composable
fun Pill(text: String, icon: ImageVector? = null, filled: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f), label = "s")
    androidx.compose.foundation.layout.Row(
        modifier.scale(scale).heightIn(min = 52.dp).clip(CircleShape).background(if (filled) W.accent else W.glass).border(1.dp, if (filled) Color.Transparent else W.stroke, CircleShape)
            .clickable(src, null, enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (filled) W.onAccent else W.text
        if (icon != null) { Icon(icon, null, Modifier.size(19.dp), tint = c); Spacer(Modifier.width(9.dp)) }
        Text(text, color = c.copy(alpha = if (enabled) 1f else 0.5f), style = androidx.compose.material3.MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Round country flag, or the first emoji / letter of the name on an accent tile. */
@Composable
fun FlagBadge(code: String, fallback: String, size: Dp = 44.dp, selected: Boolean = false) {
    val ctx = LocalContext.current
    val bitmap: ImageBitmap? = remember(code) { Flags.bitmap(ctx, code)?.asImageBitmap() }
    val ring = if (selected) W.accent else W.stroke
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(W.glassHigh).border(if (selected) 2.dp else 1.dp, ring, RoundedCornerShape(size * 0.32f)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.size(size * 0.62f).clip(CircleShape), contentScale = ContentScale.Crop)
        else Text(fallback, color = W.accent, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun latencyColor(ms: Int): Color = when { ms < 0 -> W.bad; ms < 150 -> W.good; ms < 400 -> W.warn; else -> W.bad }

/** Full-screen backdrop: the user's photo (blurred and dimmed) or a slow animated aurora. */
@Composable
fun Backdrop(photo: ImageBitmap?, blur: Float, dim: Float, state: Int) {
    val c = W
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize().then(if (blur > 0.01f && Build.VERSION.SDK_INT >= 31) Modifier.blur((blur * 28).dp) else Modifier), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.15f + dim * 0.35f), 0.42f to Color.Black.copy(alpha = 0.1f + dim * 0.45f), 1f to c.bg.copy(alpha = 0.82f + dim * 0.16f))))
        } else {
            val t = rememberInfiniteTransition(label = "aurora")
            val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(26000, easing = LinearEasing)), label = "p")
            val energy by animateFloatAsState(when (state) { Tunnel.CONNECTED -> 1.25f; Tunnel.CONNECTING -> 1f; else -> 0.8f }, tween(1200), label = "e")
            val second = androidx.compose.ui.graphics.lerp(c.accent, Color(0xFF6C7BFF), 0.55f)
            val third = androidx.compose.ui.graphics.lerp(c.accent, Color(0xFF3FE0C5), 0.5f)
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                fun blob(color: Color, cx: Float, cy: Float, r: Float, a: Float) =
                    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
                blob(c.accent, w * (0.25f + 0.18f * cos(phase)), h * (0.18f + 0.08f * sin(phase * 2)), w * 0.95f, 0.30f * energy)
                blob(second, w * (0.85f + 0.12f * sin(phase)), h * (0.42f + 0.1f * cos(phase)), w * 0.9f, 0.22f * energy)
                blob(third, w * (0.35f + 0.2f * sin(phase + 1.3f)), h * (0.78f + 0.06f * cos(phase * 2)), w * 0.8f, 0.14f * energy)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to c.bg.copy(alpha = 0.35f), 1f to c.bg.copy(alpha = 0.92f))))
        }
    }
}

/**
 * The connect control: a wide pill. Off it says what it does. On, packets run through it in two
 * lanes: upload to the right on top, download to the left below, each lane as fast as its
 * traffic. While connecting (and disconnecting) a Material 3 wavy progress indicator runs across it.
 */
@Composable
fun ConnectButton(state: Int, enabled: Boolean, rxRate: Long, txRate: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val view = LocalView.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "scale")
    val on = state == Tunnel.CONNECTED; val busy = state == Tunnel.CONNECTING || state == Tunnel.STOPPING
    val fill by animateColorAsState(when { on -> W.accent; busy -> W.accent.copy(alpha = 0.55f); else -> W.glassHigh }, tween(450), label = "fill")
    val ink by animateColorAsState(if (on || busy) W.onAccent else W.text, tween(450), label = "ink")
    val presence by animateFloatAsState(if (on || busy) 1f else 0f, tween(500), label = "presence")
    // Lane speeds in dp/s: idle drift when quiet, faster with traffic (log scale), a dash while connecting.
    fun laneSpeed(rate: Long) = if (busy) 240f else 38f + 46f * kotlin.math.log10(1f + rate / 1024f).coerceAtMost(4f)
    val upSpeed by animateFloatAsState(laneSpeed(txRate), tween(900), label = "up")
    val downSpeed by animateFloatAsState(laneSpeed(rxRate), tween(900), label = "down")
    var upOffset by remember { mutableFloatStateOf(0f) }
    var downOffset by remember { mutableFloatStateOf(0f) }
    var wavePhase by remember { mutableFloatStateOf(0f) }
    if (on || busy || presence > 0f) LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) { val dt = (now - last) / 1e9f; upOffset += upSpeed * dt; downOffset += downSpeed * dt; wavePhase += dt * 2f * Math.PI.toFloat() * 1.1f }
            last = now
        }
    }
    // Waves while connecting, packet lanes once connected; they cross-fade.
    val waveMix by animateFloatAsState(if (busy) 1f else 0f, tween(450), label = "wave")
    val laneMix by animateFloatAsState(if (on) 1f else 0f, tween(450), label = "lanes")
    val t = rememberInfiniteTransition(label = "sweep")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "sweep")
    Box(
        modifier.fillMaxWidth().height(80.dp).scale(scale).clip(CircleShape).background(fill)
            .border(1.dp, if (on || busy) Color.Transparent else W.stroke, CircleShape)
            .clickable(src, null, enabled = enabled) {
                if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) else view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (presence > 0.01f) Canvas(Modifier.fillMaxSize().padding(horizontal = 26.dp)) {
            val h = 6.dp.toPx(); val fade = 34.dp.toPx(); val gap = 9.dp.toPx()
            val lengths = floatArrayOf(22f, 38f, 14f, 28f, 46f, 18f, 32f, 12f)
            fun lane(cy: Float, offsetDp: Float, rightwards: Boolean, alpha: Float, seed: Int) {
                val offset = offsetDp.dp.toPx()
                // One repeating strip of packets of different sizes with gaps; it slides by offset.
                var strip = 0f
                for (i in lengths.indices) strip += lengths[(i + seed) % lengths.size].dp.toPx() + gap + ((i * 7 + seed * 3) % 5) * 6.dp.toPx()
                var start = -(offset % strip)
                while (start < size.width) {
                    var x = start
                    for (i in lengths.indices) {
                        val len = lengths[(i + seed) % lengths.size].dp.toPx()
                        val left = if (rightwards) x else size.width - x - len
                        val mid = left + len / 2
                        val edge = (minOf(mid, size.width - mid) / fade).coerceIn(0f, 1f)
                        if (edge > 0f && left < size.width && left + len > 0f) {
                            // A comet: solid head, tail fading out behind it.
                            val k = alpha * edge * presence
                            val tail = ink.copy(alpha = 0f); val body = ink.copy(alpha = k)
                            val brush = if (rightwards) Brush.horizontalGradient(listOf(tail, body.copy(alpha = k * 0.6f), body), left, left + len)
                                else Brush.horizontalGradient(listOf(body, body.copy(alpha = k * 0.6f), tail), left, left + len)
                            drawRoundRect(brush, Offset(left, cy - h / 2), androidx.compose.ui.geometry.Size(len, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                        }
                        x += len + gap + ((i * 7 + seed * 3) % 5) * 6.dp.toPx()
                    }
                    start += strip
                }
            }
            val lanes = laneMix
            if (lanes > 0.01f) {
                lane(size.height / 2 - 9.dp.toPx(), upOffset, true, 0.75f * lanes, 0)
                lane(size.height / 2 + 9.dp.toPx(), downOffset, false, lanes, 3)
            }
            if (waveMix > 0.01f) {
                // Material 3 wavy indeterminate indicator: a flat track and wavy active stretches that sweep
                // across (the head leads, the tail catches up), kept apart from the track by a gap. Two
                // stretches half a cycle apart, so one is always on its way.
                val k = waveMix * presence
                val cy = size.height / 2; val stroke = 5.dp.toPx(); val amp = 3.5.dp.toPx(); val wave = 26.dp.toPx(); val gapPx = 6.dp.toPx()
                val w = size.width
                val parts = listOf(0f, 0.5f).map { shift ->
                    val p = (sweep + shift) % 1f
                    val head = (-0.06f + 1.12f * (1f - (1f - p) * (1f - p))) * w
                    val tail = (-0.06f + 1.12f * p * p) * w
                    tail.coerceIn(0f, w) to head.coerceIn(0f, w)
                }.filter { it.second - it.first > 1f }.sortedBy { it.first }
                val track = ink.copy(alpha = 0.22f * k)
                var free = 0f
                fun flat(a: Float, b: Float) { if (b - a > stroke) drawLine(track, Offset(a + stroke / 2, cy), Offset(b - stroke / 2, cy), stroke, StrokeCap.Round) }
                for ((from, to) in parts) { flat(free, from - gapPx); free = maxOf(free, to + gapPx) }
                flat(free, w)
                for ((tail, head) in parts) {
                    val from = tail.coerceAtLeast(stroke / 2); val to = head.coerceAtMost(w - stroke / 2)
                    if (to - from < 1f) continue
                    val path = androidx.compose.ui.graphics.Path()
                    var x = from; var first = true
                    while (true) {
                        val y = cy + amp * kotlin.math.sin(x / wave * 2f * Math.PI.toFloat() - wavePhase)
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                        if (x >= to) break
                        x = minOf(to, x + 2f)
                    }
                    drawPath(path, ink.copy(alpha = k), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
        AnimatedVisibility(!on && !busy, enter = fadeIn(tween(300, 200)), exit = fadeOut(tween(120))) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Power, null, Modifier.size(24.dp), tint = W.accent)
                Spacer(Modifier.width(12.dp))
                Text(L.t("Подключиться", "Connect"), color = ink, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            }
        }
    }
}

data class NavItem(val icon: ImageVector, val label: String)

/** Floating navigation: a glass pill for the main sections and a separate round settings button. */
@Composable
fun FloatingNav(items: List<NavItem>, selected: Int, settingsSelected: Boolean, onSelect: (Int) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Row(
            Modifier.weight(1f).height(66.dp).clip(CircleShape).background(W.glassHigh).border(1.dp, W.stroke, CircleShape).padding(7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            items.forEachIndexed { i, item ->
                val active = i == selected && !settingsSelected
                val bg by animateColorAsState(if (active) W.accentSoft else Color.Transparent, tween(300), label = "bg")
                androidx.compose.foundation.layout.Row(
                    Modifier.then(if (active) Modifier.weight(1.6f) else Modifier.weight(1f)).fillMaxSize().clip(CircleShape).background(bg).clickable { onSelect(i) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, item.label, Modifier.size(22.dp), tint = if (active) W.accent else W.muted)
                    AnimatedVisibility(active, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        Text(item.label, Modifier.padding(start = 9.dp), color = W.text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(66.dp).clip(CircleShape).background(if (settingsSelected) W.accent else W.glassHigh).border(1.dp, W.stroke, CircleShape).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Ic.Settings, L.t("Настройки", "Settings"), Modifier.size(24.dp), tint = if (settingsSelected) W.onAccent else W.muted)
        }
    }
}

/** Live speed: download as a filled area, upload as a line. */
@Composable
fun SpeedChart(samples: List<Pair<Long, Long>>, modifier: Modifier = Modifier) {
    val accent = W.accent; val up = W.text.copy(alpha = 0.7f); val grid = W.stroke
    Canvas(modifier.drawBehind { }) {
        val n = 60
        val max = (samples.maxOfOrNull { maxOf(it.first, it.second) } ?: 0L).coerceAtLeast(16 * 1024L).toFloat() * 1.15f
        for (i in 1..3) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
        if (samples.size < 2) return@Canvas
        val step = size.width / (n - 1)
        val offset = (n - samples.size) * step
        fun y(v: Long) = size.height - (v / max) * size.height
        val down = Path(); val area = Path(); val upPath = Path()
        samples.forEachIndexed { i, (d, u) ->
            val x = offset + i * step
            if (i == 0) { down.moveTo(x, y(d)); area.moveTo(x, size.height); area.lineTo(x, y(d)); upPath.moveTo(x, y(u)) }
            else { down.lineTo(x, y(d)); area.lineTo(x, y(d)); upPath.lineTo(x, y(u)) }
        }
        area.lineTo(offset + (samples.size - 1) * step, size.height); area.close()
        drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0f))))
        drawPath(down, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(upPath, up, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}
