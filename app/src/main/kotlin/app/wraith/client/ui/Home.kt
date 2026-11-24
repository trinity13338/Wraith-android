package app.wraith.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wraith.client.Endpoint
import app.wraith.client.L
import app.wraith.client.PingInfo
import app.wraith.client.Subscriptions
import app.wraith.client.Tunnel
import app.wraith.client.formatBytes
import app.wraith.client.formatDuration
import java.util.Calendar

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> L.t("Доброе утро", "Good morning")
    in 12..17 -> L.t("Добрый день", "Good afternoon")
    in 18..22 -> L.t("Добрый вечер", "Good evening")
    else -> L.t("Доброй ночи", "Good night")
}

@Composable
fun HomeScreen(m: AppModel) {
    val rev by m.revision.collectAsState()
    val status by Tunnel.status.collectAsState()
    val settings = remember(rev) { m.store.settings() }
    val selected = remember(rev) { m.store.selected() }
    val sub = remember(rev, selected) { selected?.optString("group")?.takeIf { it.isNotEmpty() }?.let { g -> m.store.subscriptions().firstOrNull { it.optString("id") == g } } }
    val name = settings.optString("greetName").trim()
    val state = status.state

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 112.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WRAITH", color = W.text.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            IconBtn(Ic.Refresh, L.t("Обновить подписки", "Update subscriptions")) {
                m.task(L.t("Обновляем подписки…", "Updating subscriptions…")) {
                    Subscriptions.updateAll(m.ctx, false)
                    val failed = m.store.subscriptions().count { it.has("error") }
                    if (failed == 0) L.t("Подписки обновлены", "Subscriptions updated") else L.t("Не обновлено: ", "Not updated: ") + failed
                }
            }
            Spacer(Modifier.width(10.dp))
            IconBtn(Ic.Plus, L.t("Добавить", "Add")) { m.importText = "" }
        }
        Spacer(Modifier.height(140.dp))
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(sub?.optString("name")?.ifEmpty { null } ?: "Wraith · Xray", color = W.accent, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(greeting() + if (name.isNotEmpty()) ",\n$name" else "", color = W.text, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(20.dp))
            // Telemetry in a fixed grid: every cell has its own width, so changing numbers never reflow it.
            AnimatedVisibility(state == Tunnel.CONNECTED) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(formatDuration(((m.now - status.since) / 1000).coerceAtLeast(0)), Ic.Clock, modifier = Modifier.weight(1f))
                        Chip(m.exit.ip.ifEmpty { "—" }, Ic.Globe, modifier = Modifier.weight(1.7f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip((if (m.exit.delay > 0) "${m.exit.delay}" else "—") + " " + L.t("мс", "ms"), tint = if (m.exit.delay > 0) latencyColor(m.exit.delay) else W.muted, modifier = Modifier.weight(0.7f))
                        Chip("↓ " + formatBytes(status.rxRate) + L.t("/с", "/s"), modifier = Modifier.weight(1.15f))
                        Chip("↑ " + formatBytes(status.txRate) + L.t("/с", "/s"), modifier = Modifier.weight(1.15f))
                    }
                }
            }
            AnimatedVisibility(state == Tunnel.OFF && status.error.isNotEmpty()) {
                Text(status.error, Modifier.padding(top = 10.dp), color = W.bad, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            ServerCard(m, selected, sub?.optString("name").orEmpty())
            Spacer(Modifier.height(14.dp))
            ConnectButton(state, enabled = state != Tunnel.STOPPING, rxRate = status.rxRate, txRate = status.txRate) { m.toggleConnection() }
        }
    }
}

@Composable
private fun ServerCard(m: AppModel, selected: org.json.JSONObject?, subName: String) {
    Glass(Modifier.fillMaxWidth().clickable { if (selected == null) m.importText = "" else m.tab = 1 }, RoundedCornerShape(26.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected == null) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(Ic.Plus, null, Modifier.size(26.dp), tint = W.accent) }
            } else {
                val n = selected.optString("name")
                FlagBadge(PingInfo.countryCode(n), PingInfo.firstEmoji(n).ifEmpty { n.take(1).uppercase() }, 52.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (subName.isNotEmpty()) Text("$subName  →", color = W.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(selected?.let { PingInfo.displayName(it.optString("name")) } ?: L.t("Сервер не выбран", "No server selected"),
                    color = W.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ping = selected?.let { m.pingResults[it.optString("id")] }
                Text(selected?.let { Endpoint.label(it.optJSONObject("outbound")) + (ping?.takeIf { p -> p.ms > 0 }?.let { p -> " · ${p.ms} " + L.t("мс", "ms") } ?: "") }
                    ?: L.t("Нажмите, чтобы добавить", "Tap to add"), color = W.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Icon(Ic.Chevron, null, Modifier.size(20.dp), tint = W.faint)
        }
    }
}
