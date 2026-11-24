package app.wraith.client.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wraith.client.CoreRouter
import app.wraith.client.Endpoint
import app.wraith.client.L
import app.wraith.client.Ping
import app.wraith.client.PingInfo
import app.wraith.client.RoutingConfig
import app.wraith.client.Subscriptions
import app.wraith.client.Tunnel
import app.wraith.client.XrayRuntimeConfig
import app.wraith.client.formatBytes
import app.wraith.client.jsonArrayOf
import app.wraith.client.safe
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private class ServerGroup(val id: String, val sub: JSONObject?, val title: String, val members: List<JSONObject>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(m: AppModel) {
    val rev by m.revision.collectAsState()
    val settings = remember(rev) { m.store.settings() }
    val profiles = remember(rev) { m.store.profiles() }
    val subs = remember(rev) { m.store.subscriptions() }
    val selectedId = remember(rev) { m.store.selectedId() }
    val sort = settings.optString("sort", "none")
    val q = m.search.trim().lowercase(Locale.ROOT)
    val groups = remember(rev, q, sort, m.pingResults.toMap()) {
        val known = subs.map { it.optString("id") }.toSet()
        fun searchable(p: JSONObject) = (p.optString("name") + " " + p.optJSONObject("outbound")?.let { it.optString("server") + " " + it.optString("type") }).lowercase(Locale.ROOT)
        val order = compareBy<JSONObject> { !it.optBoolean("favorite") }.let { c ->
            when (sort) {
                "ping" -> c.thenBy { m.pingResults[it.optString("id")]?.ms?.takeIf { ms -> ms > 0 } ?: Int.MAX_VALUE }
                "name" -> c.thenBy { PingInfo.displayName(it.optString("name")).lowercase(Locale.ROOT) }
                else -> c
            }
        }
        val list = subs.map { s -> ServerGroup(s.optString("id"), s, s.optString("name", L.t("Подписка", "Subscription")), profiles.filter { it.optString("group") == s.optString("id") && searchable(it).contains(q) }.sortedWith(order)) }.toMutableList()
        val manual = profiles.filter { it.optString("group") !in known && searchable(it).contains(q) }.sortedWith(order)
        if (manual.isNotEmpty() || (q.isEmpty() && profiles.any { it.optString("group") !in known })) list += ServerGroup("", null, L.t("Мои серверы", "My servers"), manual)
        if (q.isNotEmpty()) list.filter { it.members.isNotEmpty() } else list
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 16.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.t("Серверы", "Servers"), color = W.text, style = MaterialTheme.typography.headlineMedium)
                        Text(serversCount(profiles.size),
                            color = W.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconBtn(if (m.pingRunning) Ic.Close else Ic.Pulse, if (m.pingRunning) L.t("Остановить проверку", "Stop") else L.t("Пинг всех", "Ping all"),
                        tint = if (m.pingRunning) W.onAccent else W.text, background = if (m.pingRunning) W.accent else W.glass) { if (m.pingRunning) m.cancelPing() else m.pingAll() }
                    Spacer(Modifier.width(10.dp))
                    IconBtn(Ic.Plus, L.t("Добавить", "Add")) { m.importText = "" }
                }
                if (profiles.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SearchField(m.search, L.t("Поиск по названию или адресу", "Search by name or address")) { m.search = it }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (profiles.isEmpty()) item { EmptyServers(m) }
        else if (groups.isEmpty()) item {
            Text(L.t("Ничего не найдено", "Nothing found"), Modifier.fillMaxWidth().padding(32.dp), color = W.muted, textAlign = TextAlign.Center)
        }
        for (g in groups) {
            val collapsed = m.collapsed[g.id] == true && q.isEmpty()
            item(key = "group-" + g.id) { GroupHeader(m, g, collapsed) }
            if (!collapsed) items(g.members, key = { "p-" + it.optString("id") }) { p ->
                ServerRow(m, p, p.optString("id") == selectedId)
            }
        }
    }
}

@Composable
fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(W.glass).border(1.dp, W.stroke, CircleShape).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Search, null, Modifier.size(20.dp), tint = W.muted)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = W.faint, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = W.text, fontSize = 16.sp), cursorBrush = SolidColor(W.accent), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Ic.Close, L.t("Очистить", "Clear"), Modifier.size(20.dp).clickable { onChange("") }, tint = W.muted)
    }
}

@Composable
private fun EmptyServers(m: AppModel) {
    Glass(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(W.accentSoft), contentAlignment = Alignment.Center) { Icon(Ic.Globe, null, Modifier.size(30.dp), tint = W.accent) }
            Spacer(Modifier.height(16.dp))
            Text(L.t("Пока нет серверов", "No servers yet"), color = W.text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(L.t("Добавьте ссылку на сервер, HTTPS-подписку или JSON-конфигурацию Xray.", "Add a server link, an HTTPS subscription or an Xray JSON configuration."),
                color = W.muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(L.t("Добавить", "Add"), Ic.Plus, filled = true, modifier = Modifier.weight(1f)) { m.importText = "" }
                Pill("QR", Ic.Qr, modifier = Modifier.weight(1f)) { m.host?.scanQr() }
            }
        }
    }
}

@Composable
private fun GroupHeader(m: AppModel, g: ServerGroup, collapsed: Boolean) {
    val sub = g.sub
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { m.collapsed[g.id] = !collapsed }.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                Text(g.title, color = W.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = serversCount(g.members.size) + if (collapsed) L.t(" · свёрнуто", " · collapsed") else ""
                val line = when {
                    sub == null -> count
                    sub.has("error") -> sub.optString("error")
                    sub.optLong("updated") > 0 -> L.t("Обновлено ", "Updated ") + DateFormat.format("dd.MM, HH:mm", sub.optLong("updated")) + " · " + count
                    else -> count
                }
                Text(line, color = if (sub?.has("error") == true) W.bad else W.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (sub != null) {
                IconBtn(Ic.Refresh, L.t("Обновить", "Update"), size = 38.dp) {
                    m.task(L.t("Обновляем «", "Updating «") + sub.optString("name") + "»…") { Subscriptions.update(m.ctx, sub); L.t("Подписка обновлена", "Subscription updated") }
                }
                Spacer(Modifier.width(8.dp))
            }
            IconBtn(Ic.Pulse, L.t("Пинг группы", "Ping group"), size = 38.dp) { m.pingGroup(g.id) }
            if (sub != null) { Spacer(Modifier.width(8.dp)); IconBtn(Ic.More, L.t("Действия", "Actions"), size = 38.dp) { subscriptionSheet(m, sub) } }
        }
        if (sub != null) {
            val info = sub.optString("subscription-userinfo")
            if (info.isNotEmpty()) Usage(info)
            sub.optString("skipped").takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(start = 6.dp, top = 6.dp), color = W.warn, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            Announcement(m, sub)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Usage(info: String) {
    val v = Subscriptions.usage(info)
    val used = (v["upload"] ?: 0) + (v["download"] ?: 0); val total = v["total"] ?: 0; val expire = v["expire"] ?: 0
    Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp)) {
        Text(formatBytes(used) + " / " + (if (total > 0) formatBytes(total) else "∞") + (if (expire > 0) L.t(" · до ", " · until ") + DateFormat.format("dd.MM.yyyy", expire * 1000) else ""),
            color = W.muted, style = MaterialTheme.typography.bodySmall)
        if (total > 0) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(W.glassHigh)) {
                Box(Modifier.fillMaxWidth((used.toFloat() / total).coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(if (used >= total) W.bad else W.accent))
            }
        }
    }
}

/** Happ announcement (sub-info-*) and the expiry notice three days before the end. */
@Composable
private fun Announcement(m: AppModel, sub: JSONObject) {
    var message = sub.optString("sub-info-text").trim()
    var button = sub.optString("sub-info-button-text").trim(); var link = sub.optString("sub-info-button-link").trim()
    var color = when (sub.optString("sub-info-color").lowercase()) { "red" -> W.bad; "green" -> W.good; else -> W.accent }
    val expire = Subscriptions.usage(sub.optString("subscription-userinfo"))["expire"] ?: 0
    val days = if (expire > 0) (expire * 1000 - System.currentTimeMillis()) / 86400000L else Long.MAX_VALUE
    if (sub.optBoolean("sub-expire", true) && expire > 0 && days <= 3) {
        message = when { days < 0 -> L.t("Подписка истекла", "Subscription expired"); days == 0L -> L.t("Подписка истекает сегодня", "Subscription expires today"); else -> L.t("Подписка истекает через $days дн.", "Subscription expires in $days d.") }
        color = W.bad
        if (link.isEmpty()) link = sub.optString("sub-expire-button-link")
        if (link.isNotEmpty()) button = L.t("Продлить", "Renew")
    }
    if (message.isEmpty() || message == "0") return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Bell, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(message, Modifier.weight(1f), color = W.text, style = MaterialTheme.typography.bodySmall, maxLines = 4)
        if (button.isNotEmpty() && link.isNotEmpty()) Text(button.take(25), Modifier.clip(CircleShape).clickable { m.openLink(link) }.padding(horizontal = 10.dp, vertical = 6.dp), color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(m: AppModel, p: JSONObject, selected: Boolean) {
    val id = p.optString("id")
    val name = p.optString("name")
    val bg by animateColorAsState(if (selected) W.accentSoft else W.glass, label = "bg")
    val edge by animateColorAsState(if (selected) W.accent.copy(alpha = 0.6f) else W.stroke, label = "edge")
    val shape = RoundedCornerShape(22.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(shape).background(bg).border(1.dp, edge, shape)
        .combinedClickable(onClick = { m.select(p) }, onLongClick = { serverSheet(m, p) }).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FlagBadge(PingInfo.countryCode(name), if (p.optBoolean("favorite")) "★" else PingInfo.firstEmoji(name).ifEmpty { name.take(1).uppercase() }, 42.dp, selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(PingInfo.displayName(name), color = W.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val pool = p.optJSONArray("xrayCandidates")?.length() ?: 0
            Text(p.optString("description").ifEmpty { Endpoint.label(p.optJSONObject("outbound")) } + (if (pool > 1) L.t(" · пул $pool", " · pool of $pool") else ""),
                color = W.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        when {
            m.pinging[id] == true -> CircularProgressIndicator(Modifier.size(18.dp), color = W.accent, strokeWidth = 2.dp)
            m.pingResults[id] != null -> {
                val r = m.pingResults[id]!!
                val c = latencyColor(r.ms)
                Text(if (r.ms > 0) "${r.ms} " + L.t("мс", "ms") else Ping.shortError(r.error), Modifier.clip(CircleShape).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    color = c, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { serverSheet(m, p) }, contentAlignment = Alignment.Center) { Icon(Ic.More, L.t("Действия", "Actions"), Modifier.size(20.dp), tint = W.muted) }
    }
}

fun serverSheet(m: AppModel, p: JSONObject) {
    val id = p.optString("id"); val fav = p.optBoolean("favorite"); val name = PingInfo.displayName(p.optString("name"))
    m.sheet = Sheet(name, Endpoint.label(p.optJSONObject("outbound")) + " · " + p.optJSONObject("outbound")?.optString("server").orEmpty(), listOf(
        Action(L.t("Проверить задержку", "Check latency"), Ic.Pulse) { m.startPing(listOf(p)) },
        Action(if (fav) L.t("Убрать из избранного", "Remove from favourites") else L.t("В избранное", "Add to favourites"), Ic.Star) { m.store.update(id) { it.put("favorite", !fav) } },
        Action(L.t("Скопировать ссылку", "Copy link"), Ic.Copy) { m.copy(exportProfile(p)) },
        Action(L.t("Показать QR-код", "Show QR code"), Ic.Qr) { m.qr = exportProfile(p) },
        Action(L.t("Изменить конфигурацию", "Edit configuration"), Ic.Edit) { editProfile(m, p) },
        Action(L.t("Диагностика", "Diagnostics"), Ic.Info) { m.task(L.t("Проверяем конфигурацию…", "Checking configuration…")) { val r = diagnose(m, p); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { m.info = L.t("Диагностика", "Diagnostics") to r }; null } },
        Action(L.t("Удалить", "Delete"), Ic.Delete, danger = true) {
            m.confirm = Confirm(L.t("Удалить сервер?", "Delete the server?"), "«$name»" + if (p.optString("group").isEmpty()) "" else L.t(" вернётся при следующем обновлении подписки.", " comes back on the next subscription update."), L.t("Удалить", "Delete"), true) { m.store.deleteProfile(id) }
        },
    ))
}

private fun exportProfile(p: JSONObject) = p.optString("source").ifEmpty { p.optJSONObject("outbound")?.toString(2).orEmpty() }

private fun editProfile(m: AppModel, p: JSONObject) {
    val raw = p.optString("source").trim()
    val initial = try { if (raw.startsWith("{")) JSONObject(raw).toString(2) else if (raw.startsWith("[")) JSONArray(raw).toString(2) else raw } catch (_: Exception) { raw }
    m.editor = Editor(L.t("Конфигурация · ", "Configuration · ") + PingInfo.displayName(p.optString("name")), initial) { value ->
        val parsed = app.wraith.client.ImportParser.parse(value)
        if (parsed.size != 1) throw IllegalArgumentException(L.t("Нужна конфигурация ровно одного сервера.", "Exactly one server configuration is required."))
        val r = parsed[0]
        r.put("id", p.getString("id")); r.put("group", p.optString("group")); r.put("favorite", p.optBoolean("favorite"))
        if (!r.has("routing") && p.has("routing")) r.put("routing", p.get("routing"))
        if (!r.has("description") && p.has("description")) r.put("description", p.get("description"))
        m.store.replaceProfile(r); m.changed(); m.toast(L.t("Сохранено", "Saved"))
    }
}

/** What the core is given for one server and what it says about it. */
private fun diagnose(m: AppModel, p: JSONObject): String {
    val out = StringBuilder()
    val e = p.optJSONObject("outbound")
    out.append(L.t("Сервер: ", "Server: ")).append(Endpoint.label(e)).append(" · ").append(e?.optString("server")).append(':').append(e?.optInt("server_port")).append('\n')
    p.optJSONArray("xrayCandidates")?.let { out.append(L.t("Серверов в пуле: ", "Servers in the pool: ")).append(it.length()).append('\n') }
    out.append(L.t("Маршрутизация: ", "Routing: ")).append(RoutingConfig.summary(m.store.snapshot(), p)).append('\n')
    val state = m.store.snapshot().put("profiles", jsonArrayOf(p)).put("selected", p.optString("id"))
    try {
        val src = p.optString("source").trim()
        if (src.startsWith("{") || app.wraith.client.XrayLinks.outbound(src) != null) { XrayRuntimeConfig.build(state, p, 0, null); out.append(L.t("\nКонфигурация Xray собрана ✓\n", "\nXray configuration built ✓\n")) }
        else out.append(L.t("\nСсылка будет преобразована ядром при подключении.\n", "\nThe link is converted by the core at connect time.\n"))
    } catch (ex: Throwable) { out.append(L.t("\nОшибка конфигурации: ", "\nConfiguration error: ")).append(ex.safe()).append('\n') }
    val err = Tunnel.status.value.error
    if (err.isNotEmpty()) out.append(L.t("\nПоследняя ошибка подключения: ", "\nLast connection error: ")).append(err).append('\n')
    val log = m.tail("core/xray-error.log", 3000)
    if (log.isNotBlank()) out.append(L.t("\nЖурнал ядра:\n", "\nCore log:\n")).append(log)
    return out.toString()
}

private fun subscriptionSheet(m: AppModel, sub: JSONObject) {
    val id = sub.optString("id")
    val support = sub.optString("support-url").ifEmpty { sub.optString("profile-web-page-url") }
    val actions = mutableListOf(
        Action(L.t("Обновить", "Update"), Ic.Refresh) { m.task(L.t("Обновляем…", "Updating…")) { Subscriptions.update(m.ctx, sub); L.t("Подписка обновлена", "Subscription updated") } },
        Action(L.t("Переименовать", "Rename"), Ic.Edit) { m.input = Input(L.t("Название", "Name"), sub.optString("name")) { v -> if (v.isBlank()) throw IllegalArgumentException(L.t("Введите название", "Enter a name")); m.store.updateSubscription(id) { it.put("name", v.trim()) } } },
        Action(L.t("Изменить адрес", "Change address"), Ic.Link) { m.input = Input(L.t("HTTPS-адрес подписки", "Subscription HTTPS address"), sub.optString("url")) { v -> if (!v.trim().startsWith("https://")) throw IllegalArgumentException(L.t("Нужен HTTPS-адрес", "An HTTPS address is required")); m.store.updateSubscription(id) { it.put("url", v.trim()) } } },
        Action(L.t("Скопировать ссылку", "Copy link"), Ic.Copy) { m.copy(sub.optString("url")) },
    )
    if (support.isNotEmpty()) actions += Action(L.t("Поддержка провайдера", "Provider support"), Ic.Info) { m.openLink(support) }
    actions += Action(L.t("Удалить", "Delete"), Ic.Delete, danger = true) {
        m.confirm = Confirm(L.t("Удалить подписку?", "Delete the subscription?"), "«" + sub.optString("name") + L.t("» и все её серверы будут удалены.", "» and all its servers will be removed."), L.t("Удалить", "Delete"), true) {
            val wasSelected = m.store.selected()?.optString("group") == id
            m.store.removeSubscription(id)
            if (wasSelected && Tunnel.status.value.state != Tunnel.OFF) CoreRouter.stop(m.ctx)
        }
    }
    m.sheet = Sheet(sub.optString("name"), sub.optString("url").substringAfter("://").substringBefore('/'), actions)
}


fun serversCount(n: Int): String = n.toString() + " " + if (L.en()) (if (n == 1) "server" else "servers") else L.plural(n.toLong(), "сервер", "сервера", "серверов")
