package app.wraith.client.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wraith.client.L
import app.wraith.client.PingInfo
import app.wraith.client.TrafficHistory
import app.wraith.client.Tunnel
import app.wraith.client.formatBytes
import app.wraith.client.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Statistics that never leave the phone: live speed, this session, the last days. */
@Composable
fun StatsScreen(m: AppModel) {
    val status by Tunnel.status.collectAsState()
    val on = status.state == Tunnel.CONNECTED
    var history by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(Unit) { while (true) { history = withContext(Dispatchers.IO) { TrafficHistory.read(m.ctx) }; delay(10_000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 120.dp)) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp)) {
            Text(L.t("Статистика", "Statistics"), color = W.text, style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Lock, null, Modifier.size(14.dp), tint = W.good)
                Spacer(Modifier.width(6.dp))
                Text(L.t("Считается только на этом телефоне и никуда не отправляется", "Counted on this phone only, never sent anywhere"), color = W.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))

        Glass(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(L.t("Загрузка", "Download"), color = W.muted, style = MaterialTheme.typography.labelMedium)
                        Text(if (on) formatBytes(status.rxRate) + L.t("/с", "/s") else "—", color = W.accent, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(L.t("Отдача", "Upload"), color = W.muted, style = MaterialTheme.typography.labelMedium)
                        Text(if (on) formatBytes(status.txRate) + L.t("/с", "/s") else "—", color = W.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    SpeedChart(m.speed.toList(), Modifier.fillMaxSize())
                    if (!on) Text(L.t("Подключитесь, чтобы увидеть график", "Connect to see the chart"), Modifier.align(Alignment.Center), color = W.faint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionTitle(L.t("Эта сессия", "This session"))
        Group {
            SettingRow(L.t("Сервер", "Server"), trailing = { Value(if (on) PingInfo.displayName(status.profileName) else L.t("не подключено", "not connected")) }, icon = Ic.Globe)
            Divider()
            SettingRow(L.t("Время подключения", "Connected for"), trailing = { Value(if (on) formatDuration(((m.now - status.since) / 1000).coerceAtLeast(0)) else "0:00:00") }, icon = Ic.Clock)
            Divider()
            SettingRow(L.t("Получено", "Received"), trailing = { Value(formatBytes(status.rx)) }, icon = Ic.Down)
            Divider()
            SettingRow(L.t("Отправлено", "Sent"), trailing = { Value(formatBytes(status.tx)) }, icon = Ic.Up)
            if (on && m.exit.ip.isNotEmpty()) {
                Divider()
                SettingRow(L.t("Внешний IP", "Exit IP"), m.exit.country.uppercase(), icon = Ic.Shield, trailing = { Value(m.exit.ip) })
            }
            if (on && m.exit.delay > 0) {
                Divider()
                SettingRow(L.t("Задержка через туннель", "Delay through the tunnel"), icon = Ic.Pulse, trailing = { Value("${m.exit.delay} " + L.t("мс", "ms")) })
            }
        }

        SectionTitle(L.t("Последние 14 дней", "Last 14 days"))
        val days = remember(history) { lastDays(history, 14) }
        Glass(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                val today = days.lastOrNull()?.second ?: 0L
                val week = days.takeLast(7).sumOf { it.second }
                val all = days.sumOf { it.second }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Total(L.t("Сегодня", "Today"), today, Modifier.weight(1f))
                    Total(L.t("7 дней", "7 days"), week, Modifier.weight(1f))
                    Total(L.t("14 дней", "14 days"), all, Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                DayBars(days, Modifier.fillMaxWidth().height(110.dp))
                Spacer(Modifier.height(6.dp))
                Row { days.forEachIndexed { i, d -> Text(if (i % 2 == 1) d.first else "", Modifier.weight(1f), color = W.faint, fontSize = 10.sp, maxLines = 1) } }
            }
        }

        SectionTitle(L.t("Подробнее", "More"))
        Group {
            SettingRow(L.t("Журнал соединений", "Connection log"), L.t("Какой адрес через какой выход ушёл", "Which address left through which outbound"), Ic.Route) {
                val access = m.tail("core/xray-access.log", 20000).lines().takeLast(80).joinToString("\n")
                m.info = L.t("Соединения · Xray", "Connections · Xray") to access.ifBlank { L.t("Пока пусто. Откройте сайт и вернитесь сюда.", "Empty so far. Open a website and come back.") }
            }
            Divider()
            SettingRow(L.t("Очистить историю трафика", "Clear traffic history"), icon = Ic.Delete, danger = true) {
                m.confirm = Confirm(L.t("Очистить историю?", "Clear the history?"), L.t("Счётчики по дням будут удалены.", "Daily counters will be deleted."), L.t("Очистить", "Clear"), true) { TrafficHistory.clear(m.ctx); history = JSONObject() }
            }
        }
    }
}

@Composable
private fun Value(text: String) = Text(text, color = W.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)

@Composable
private fun Total(label: String, bytes: Long, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = W.muted, style = MaterialTheme.typography.labelMedium)
        Text(formatBytes(bytes), color = W.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** (label, total bytes) for the last [n] days, oldest first. */
private fun lastDays(history: JSONObject, n: Int): List<Pair<String, Long>> {
    val key = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT); val label = SimpleDateFormat("dd", Locale.ROOT)
    val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
    return (0 until n).map {
        val d = cal.time; cal.add(Calendar.DAY_OF_YEAR, 1)
        val a = history.optJSONArray(key.format(d))
        label.format(d) to ((a?.optLong(0) ?: 0) + (a?.optLong(1) ?: 0))
    }
}

@Composable
private fun DayBars(days: List<Pair<String, Long>>, modifier: Modifier) {
    val accent = W.accent; val empty = W.glassHigh
    Canvas(modifier) {
        val max = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        val slot = size.width / days.size; val bar = slot * 0.56f
        days.forEachIndexed { i, (_, v) ->
            val h = (v.toFloat() / max * size.height).coerceAtLeast(4.dp.toPx())
            drawRoundRect(if (v > 0) accent.copy(alpha = if (i == days.lastIndex) 1f else 0.55f) else empty,
                Offset(i * slot + (slot - bar) / 2, size.height - h), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}
