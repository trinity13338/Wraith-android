package app.wraith.client.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wraith.client.DnsChoice
import app.wraith.client.L
import app.wraith.client.Ping
import app.wraith.client.RoutingConfig
import app.wraith.client.Store
import app.wraith.client.Subscriptions
import app.wraith.client.WraithApp
import app.wraith.client.XrayOutboundOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

@Composable
fun SettingsScreen(m: AppModel) {
    val rev by m.revision.collectAsState()
    val s = remember(rev) { m.store.settings() }
    when (m.pages.lastOrNull()) {
        "routing" -> RoutingPage(m, s)
        "tunnel" -> TunnelPage(m, s)
        "apps" -> AppsPage(m, s)
        "subscriptions" -> SubscriptionsPage(m, s)
        "ping" -> PingPage(m, s)
        "appearance" -> AppearancePage(m, s)
        "logs" -> LogsPage(m)
        "schemes" -> SchemesPage(m)
        else -> RootPage(m, s)
    }
}

@Composable
private fun Page(m: AppModel, title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Back, L.t("Назад", "Back")) { m.back() }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = W.text, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
                    if (subtitle != null) Text(subtitle, color = W.muted, style = MaterialTheme.typography.bodySmall)
                }
                actions()
            }
        }
        content()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    item { SectionTitle(title); Group(content = content) }
}

private fun label(value: String, values: List<String>, labels: List<String>) = labels.getOrElse(values.indexOf(value)) { value }

private fun AppModel.pick(title: String, labels: List<String>, values: List<String>, key: String, current: String) {
    choice = Choice(title, labels, values.indexOf(current).coerceAtLeast(0)) { setting(key, values[it]) }
}

private fun AppModel.number(title: String, key: String, current: Int, min: Int, max: Int) {
    input = Input("$title ($min–$max)", current.toString(), number = true) { v ->
        val n = v.trim().toIntOrNull() ?: throw IllegalArgumentException(L.t("Введите целое число", "Enter a whole number"))
        if (n !in min..max) throw IllegalArgumentException(L.t("Допустимо ", "Allowed ") + "$min–$max")
        store.setting(key, n); changed()
    }
}

/** A text setting validated by building the real core option, so a typo is caught here. */
private fun AppModel.rangeSetting(title: String, key: String, current: String) {
    input = Input(title, current) { v ->
        val probe = store.settings().put(key, v.trim())
        if (key.startsWith("noise")) XrayOutboundOptions.noise(probe) else XrayOutboundOptions.fragment(probe)
        store.setting(key, v.trim()); changed()
    }
}

@Composable
private fun RootPage(m: AppModel, s: JSONObject) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 12.dp)) {
                Text(L.t("Настройки", "Settings"), color = W.text, style = MaterialTheme.typography.headlineMedium)
                Text("Wraith ${WraithApp.VERSION} · Xray", color = W.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        section(L.t("Подключение", "Connection")) {
            SettingRow(L.t("Маршрутизация", "Routing"), L.t("Своих правил: ", "Custom rules: ") + m.store.rules().size, Ic.Route) { m.pages += "routing" }
            Divider()
            SettingRow(L.t("Туннель и обход блокировок", "Tunnel and DPI bypass"), null, Ic.Shield) { m.pages += "tunnel" }
            Divider()
            SettingRow(L.t("Приложения через VPN", "Apps through VPN"), appModeSummary(s), Ic.Apps) { m.pages += "apps" }
            Divider()
            SwitchRow(L.t("Автозапуск", "Autostart"), null, Ic.Power, s.optBoolean("autoConnect")) { m.setting("autoConnect", it) }
            Divider()
            SwitchRow(L.t("Быстрый сервер автоматически", "Fastest server automatically"), null, Ic.Bolt, s.optBoolean("autoSelect")) { m.setting("autoSelect", it) }
        }
        section(L.t("Серверы", "Servers")) {
            SettingRow(L.t("Подписки", "Subscriptions"), null, Ic.Refresh) { m.pages += "subscriptions" }
            Divider()
            SettingRow(L.t("Пинг", "Ping"), Ping.label(s.optString("pingType", "proxyGet")), Ic.Pulse) { m.pages += "ping" }
        }
        section(L.t("Оформление", "Appearance")) {
            SettingRow(L.t("Фон, цвета и приветствие", "Background, colours and greeting"), if (m.photo != null) L.t("Своё фото", "Own photo") else L.t("Живой градиент", "Live gradient"), Ic.Palette) { m.pages += "appearance" }
        }
        section(L.t("Данные", "Data")) {
            SettingRow(L.t("Экспорт резервной копии", "Export a backup"), null, Ic.Download) {
                m.confirm = Confirm(L.t("Экспорт содержит ключи", "The export contains keys"), L.t("Резервная копия — обычный JSON-файл с вашими серверами. Не отправляйте его другим.", "The backup is a plain JSON file with your servers. Do not send it to anyone."), L.t("Экспорт", "Export")) { m.host?.export("wraith-backup.json", m.store.backup()) }
            }
            Divider()
            SettingRow(L.t("Восстановить из файла", "Restore from a file"), null, Ic.Layers) {
                m.confirm = Confirm(L.t("Заменить текущие данные?", "Replace current data?"), L.t("Серверы, подписки и настройки будут заменены. Подключение остановится.", "Servers, subscriptions and settings will be replaced. The connection stops."), L.t("Выбрать файл", "Pick a file")) { m.host?.openDocument(PURPOSE_RESTORE) }
            }
            Divider()
            SettingRow(L.t("Журнал", "Log"), null, Ic.Log) { m.pages += "logs" }
            Divider()
            SettingRow(L.t("Сбросить настройки", "Reset settings"), null, Ic.Refresh, danger = true) {
                m.confirm = Confirm(L.t("Сбросить настройки?", "Reset the settings?"), L.t("Настройки вернутся к значениям по умолчанию.", "Settings return to defaults."), L.t("Сбросить", "Reset"), true) { m.store.resetSettings(); m.changed() }
            }
        }
        section(L.t("О приложении", "About")) {
            SettingRow(L.t("Проверить обновления", "Check for updates"), L.t("Установлена ", "Installed ") + WraithApp.VERSION, Ic.Sparkle) { m.checkUpdates() }
            Divider()
            SettingRow(L.t("Команды wraith://", "wraith:// commands"), null, Ic.Link) { m.pages += "schemes" }
            Divider()
            SettingRow(L.t("О Wraith", "About Wraith"), null, Ic.Info) {
                m.info = "Wraith ${WraithApp.VERSION}" to L.t("Клиент для VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и WireGuard на ядре Xray.\n\nНикакой статистики об использовании не собирается. Обновления проверяются через GitHub, в запросе только версия приложения.\n\nЛицензия GPL-3.0-or-later. Xray-core — MPL-2.0, libXray — MIT, флаги — flag-icons (MIT).",
                    "A client for VLESS, VMess, Trojan, Shadowsocks, Hysteria2 and WireGuard on the Xray core.\n\nNo usage statistics are collected. Updates are checked on GitHub; the request carries only the app version.\n\nLicence GPL-3.0-or-later. Xray-core — MPL-2.0, libXray — MIT, flags — flag-icons (MIT).")
            }
        }
    }
}

fun routingLabel(mode: String) = when (mode) { "global" -> L.t("Всё через VPN", "Everything through VPN"); "direct" -> L.t("Напрямую", "Direct"); else -> L.t("По правилам", "By rules") }

private fun appModeSummary(s: JSONObject): String {
    val mode = s.optString("appMode", "off"); val apps = s.optString("apps").lines().count { it.isNotBlank() }
    return when { mode == "off" || apps == 0 -> L.t("Все приложения", "All apps"); mode == "include" -> L.t("Только выбранные: ", "Only selected: ") + apps; else -> L.t("Кроме выбранных: ", "Except selected: ") + apps }
}

@Composable
private fun Segmented(values: List<String>, labels: List<String>, current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CircleShape).background(W.glass).border(1.dp, W.stroke, CircleShape).padding(4.dp)) {
        values.forEachIndexed { i, v ->
            val on = v == current
            Box(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(if (on) W.accent else Color.Transparent).clickable { if (!on) onPick(v) }, contentAlignment = Alignment.Center) {
                Text(labels[i], color = if (on) W.onAccent else W.text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RoutingPage(m: AppModel, s: JSONObject) {
    val rev by m.revision.collectAsState()
    val rules = remember(rev) { m.store.rules() }
    Page(m, L.t("Маршрутизация", "Routing")) {
        section(L.t("Общие", "General")) {
            SwitchRow(L.t("Локальная сеть напрямую", "Local network direct"), null, Ic.Wifi, s.optBoolean("bypassLan", true)) { m.setting("bypassLan", it) }
            Divider()
            SwitchRow(L.t("Определять домены", "Detect domains"), null, Ic.Eye, s.optBoolean("sniff", true)) { m.setting("sniff", it) }
        }
        item { SectionTitle(L.t("Свои правила", "Custom rules")) }
        item {
            Group {
                if (rules.isEmpty()) SettingRow(L.t("Правил пока нет", "No rules yet"), null, Ic.Route) { m.ruleEdit = -1 to null }
                rules.forEachIndexed { i, r ->
                    if (i > 0) Divider()
                    val action = r.optString("action", "proxy")
                    SettingRow(r.optString("name").ifEmpty { r.optString("value") },
                        RoutingConfig.kindLabel(r.optString("kind")) + " → " + when (action) { "direct" -> L.t("напрямую", "direct"); "block" -> L.t("блокировать", "block"); else -> L.t("через VPN", "through VPN") } +
                            (if (r.optBoolean("enabled", true)) "" else L.t(" · выключено", " · off")) + "\n" + r.optString("value").replace('\n', ' ').take(120),
                        when (action) { "block" -> Ic.Close; "direct" -> Ic.Chevron; else -> Ic.Shield }) {
                        m.sheet = Sheet(r.optString("name"), RoutingConfig.kindLabel(r.optString("kind")), listOf(
                            Action(L.t("Изменить", "Edit"), Ic.Edit) { m.ruleEdit = i to r },
                            Action(if (r.optBoolean("enabled", true)) L.t("Выключить", "Turn off") else L.t("Включить", "Turn on"), Ic.Power) { m.store.replaceRule(i, r.put("enabled", !r.optBoolean("enabled", true))); m.changed() },
                            Action(L.t("Удалить", "Delete"), Ic.Delete, true) { m.store.removeRule(i); m.changed() },
                        ))
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(L.t("Добавить", "Add"), Ic.Plus, filled = true, modifier = Modifier.weight(1f)) { m.ruleEdit = -1 to null }
                Pill(L.t("Импорт Happ", "Import Happ"), Ic.Download, modifier = Modifier.weight(1f)) { m.input = Input(L.t("Ссылка happ://routing/…", "happ://routing/… link"), "", multiline = true) { m.importRouting(it.trim()) } }
            }
        }
    }
}

@Composable
private fun TunnelPage(m: AppModel, s: JSONObject) {
    Page(m, L.t("Туннель", "Tunnel")) {
        section(L.t("Обход DPI", "DPI bypass")) {
            SwitchRow(L.t("Фрагментация", "Fragmentation"), null, Ic.Layers, s.optBoolean("fragment")) { m.setting("fragment", it) }
            if (s.optBoolean("fragment")) {
                Divider()
                SettingRow(L.t("Что дробить", "What to split"), s.optString("fragmentPackets", "tlshello")) { m.pick(L.t("Какие пакеты дробить", "Which packets"), listOf(L.t("tlshello · только ClientHello", "tlshello · ClientHello only"), "1-3", "1-5"), listOf("tlshello", "1-3", "1-5"), "fragmentPackets", s.optString("fragmentPackets", "tlshello")) }
                Divider()
                SettingRow(L.t("Длина частей", "Part length"), s.optString("fragmentLength", "50-100")) { m.rangeSetting(L.t("Длина фрагмента (от-до)", "Fragment length (from-to)"), "fragmentLength", s.optString("fragmentLength", "50-100")) }
                Divider()
                SettingRow(L.t("Задержка, мс", "Delay, ms"), s.optString("fragmentInterval", "10-20")) { m.rangeSetting(L.t("Задержка фрагмента, мс", "Fragment delay, ms"), "fragmentInterval", s.optString("fragmentInterval", "10-20")) }
                Divider()
                SettingRow(L.t("Максимум частей", "Maximum parts"), s.optString("fragmentMaxSplit").ifEmpty { L.t("без ограничения", "no limit") }) { m.rangeSetting(L.t("Максимальное разделение (пусто — без ограничения)", "Maximum split (empty — no limit)"), "fragmentMaxSplit", s.optString("fragmentMaxSplit")) }
            }
            Divider()
            SwitchRow(L.t("Шумы", "Noise"), null, Ic.Sparkle, s.optBoolean("noise")) { m.setting("noise", it) }
            if (s.optBoolean("noise")) {
                val type = s.optString("noiseType", "array")
                Divider()
                SettingRow(L.t("Тип", "Type"), type) { m.pick(L.t("Тип шума", "Noise type"), listOf("array", "rand", "str", "hex", "base64"), listOf("array", "rand", "str", "hex", "base64"), "noiseType", type) }
                Divider()
                if (type == "rand") SettingRow(L.t("Размер, байт", "Size, bytes"), s.optString("noiseRand", "1-8192")) { m.rangeSetting(L.t("Размер шума (от-до)", "Noise size (from-to)"), "noiseRand", s.optString("noiseRand", "1-8192")) }
                else SettingRow(L.t("Пакет", "Packet"), s.optString("noisePacket", "1A,2B,3C")) { m.rangeSetting(L.t("Пакет шума", "Noise packet"), "noisePacket", s.optString("noisePacket", "1A,2B,3C")) }
                Divider()
                SettingRow(L.t("Задержка, мс", "Delay, ms"), s.optString("noiseDelay", "10-16")) { m.rangeSetting(L.t("Задержка шума, мс", "Noise delay, ms"), "noiseDelay", s.optString("noiseDelay", "10-16")) }
            }
            Divider()
            SwitchRow("Mux", null, Ic.Route, s.optBoolean("mux")) { m.setting("mux", it) }
            if (s.optBoolean("mux")) { Divider(); SettingRow(L.t("Потоков на соединение", "Streams per connection"), s.optInt("muxConcurrency", 8).toString()) { m.number(L.t("Потоков на соединение", "Streams per connection"), "muxConcurrency", s.optInt("muxConcurrency", 8), 1, 1024) } }
            Divider()
            SettingRow(L.t("QUIC (UDP 443)", "QUIC (UDP 443)"), if (s.optString("quic", "allow") == "reject") L.t("Блокировать", "Block") else L.t("Пропускать", "Allow")) {
                m.pick("QUIC", listOf(L.t("Пропускать", "Allow"), L.t("Блокировать, браузер перейдёт на TCP", "Block, the browser falls back to TCP")), listOf("allow", "reject"), "quic", s.optString("quic", "allow"))
            }
        }
        section("DNS") {
            SwitchRow(L.t("DNS из конфигурации", "DNS from the configuration"), null, Ic.Dns, s.optBoolean("dnsFromJson")) { m.setting("dnsFromJson", it) }
            Divider()
            val custom = s.optBoolean("customDns")
            SwitchRow(L.t("Свой DNS", "Own DNS"), null, Ic.Globe, custom) { m.setting("customDns", it) }
            if (custom) { Divider(); SettingRow(L.t("Адрес DNS", "DNS address"), s.optString("tunnelDns").ifEmpty { DnsChoice.DEFAULT_CUSTOM }) {
                m.input = Input(L.t("DNS: https://…, quic+local://… или IP", "DNS: https://…, quic+local://… or IP"), s.optString("tunnelDns")) { v -> DnsChoice.validate(v); m.store.setting("tunnelDns", v.trim()); m.changed() }
            } }
            Divider()
            SettingRow(L.t("IP-протокол", "IP family"), label(s.optString("ipPreference", "auto"), listOf("auto", "ipv4", "ipv6"), listOf(L.t("Авто", "Auto"), "IPv4", "IPv6"))) {
                m.pick(L.t("Какие адреса запрашивать", "Which addresses to query"), listOf(L.t("Авто · оба", "Auto · both"), L.t("Только IPv4 · меньше сбоев в мобильных сетях", "IPv4 only · fewer failures on mobile"), L.t("Только IPv6", "IPv6 only")), listOf("auto", "ipv4", "ipv6"), "ipPreference", s.optString("ipPreference", "auto"))
            }
        }
        section(L.t("Сеть", "Network")) {
            SettingRow("MTU", s.optInt("mtu", 1500).toString() + L.t(" байт", " bytes"), Ic.Layers) { m.number("MTU", "mtu", s.optInt("mtu", 1500), 1280, 9000) }
            Divider()
            val excluded = s.optString("excludedRoutes").trim()
            SettingRow(L.t("Исключённые подсети", "Excluded subnets"), if (excluded.isEmpty()) L.t("нет", "none") else excluded.lines().size.toString() + L.t(" в обход туннеля (Android 13+)", " bypass the tunnel (Android 13+)"), Ic.Route) {
                m.input = Input(L.t("Подсети CIDR, по одной на строку", "CIDR subnets, one per line"), excluded, multiline = true) { v ->
                    v.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { if (!it.matches(Regex("[0-9a-fA-F:.]+/[0-9]{1,3}"))) throw IllegalArgumentException(L.t("Не CIDR: ", "Not CIDR: ") + it) }
                    m.store.setting("excludedRoutes", v.trim()); m.changed()
                }
            }
            Divider()
            SwitchRow(L.t("Скорость в уведомлении", "Speed in the notification"), null, Ic.Bell, s.optBoolean("notifySpeed", true)) { m.setting("notifySpeed", it) }
            Divider()
            SettingRow(L.t("Постоянный VPN и блокировка", "Always-on VPN and lockdown"), null, Ic.Lock) { m.host?.open(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }
        }
        section(L.t("Локальный прокси", "Local proxy")) {
            val on = s.optBoolean("localProxy")
            SwitchRow(L.t("SOCKS5 / HTTP прокси", "SOCKS5 / HTTP proxy"), null, Ic.Lock, on) {
                if (it) m.store.ensureProxyCredentials()
                m.setting("localProxy", it)
            }
            if (on) {
                val lan = s.optBoolean("allowLan")
                val host = if (lan) "0.0.0.0" else "127.0.0.1"
                Divider()
                SettingRow("SOCKS5", "$host:${s.optInt("proxyPort", 2081)}", trailing = { Icon(Ic.Edit, null, Modifier.size(18.dp), tint = W.faint) }) { m.number(L.t("Порт SOCKS5", "SOCKS5 port"), "proxyPort", s.optInt("proxyPort", 2081), 1024, 65535) }
                Divider()
                SettingRow("HTTP", "$host:${s.optInt("httpProxyPort", 2082)}", trailing = { Icon(Ic.Edit, null, Modifier.size(18.dp), tint = W.faint) }) { m.number(L.t("Порт HTTP", "HTTP port"), "httpProxyPort", s.optInt("httpProxyPort", 2082), 1024, 65535) }
                Divider()
                SettingRow(L.t("Логин", "Login"), s.optString("proxyUser"), trailing = { Icon(Ic.Copy, null, Modifier.size(18.dp), tint = W.faint) }) { m.copy(s.optString("proxyUser")) }
                Divider()
                SettingRow(L.t("Пароль", "Password"), s.optString("proxyPassword").take(4) + "••••••••••••", trailing = { Icon(Ic.Copy, null, Modifier.size(18.dp), tint = W.faint) }) { m.copy(s.optString("proxyPassword"), L.t("Пароль скопирован", "Password copied")) }
                Divider()
                SettingRow(L.t("Новый пароль", "New password"), null, Ic.Refresh) {
                    m.confirm = Confirm(L.t("Сменить пароль прокси?", "Change the proxy password?"), L.t("Приложениям, которые используют прокси, нужно будет ввести новый.", "Apps using the proxy will need the new one."), L.t("Сменить", "Change")) { m.store.setting("proxyPassword", Store.randomSecret(20)); m.changed() }
                }
                Divider()
                SwitchRow(L.t("Доступ из локальной сети", "Access from the local network"), null, Ic.Wifi, lan) { m.setting("allowLan", it) }
            }
        }
    }
}

@Composable
private fun AppsPage(m: AppModel, s: JSONObject) {
    val mode = s.optString("appMode", "off")
    var apps by remember { mutableStateOf<List<Triple<String, String, Boolean>>?>(null) }
    var query by remember { mutableStateOf("") }
    val selected = remember(s.optString("apps")) { s.optString("apps").lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() }
    LaunchedEffect(mode) {
        if (mode != "off" && apps == null) apps = withContext(Dispatchers.IO) {
            val pm = m.ctx.packageManager
            pm.getInstalledApplications(0).filter { it.packageName != m.ctx.packageName }.map { Triple(it.packageName, it.loadLabel(pm).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
        }
    }
    Page(m, L.t("Приложения через VPN", "Apps through VPN")) {
        item {
            Spacer(Modifier.height(12.dp))
            Segmented(listOf("off", "include", "exclude"), listOf(L.t("Все", "All"), L.t("Только эти", "Only these"), L.t("Кроме этих", "Except these")), mode) { m.setting("appMode", it) }
            Spacer(Modifier.height(8.dp))
            if (mode == "include" && selected.isEmpty()) Text(L.t("Отметьте хотя бы одно приложение, иначе VPN не запустится.", "Check at least one app, otherwise the VPN will not start."), Modifier.padding(horizontal = 24.dp), color = W.bad, style = MaterialTheme.typography.bodySmall)
            if (mode != "off") Box(Modifier.padding(16.dp)) { SearchField(query, L.t("Поиск приложений", "Search apps")) { query = it } }
        }
        if (mode != "off") {
            val list = apps
            if (list == null) item { Text(L.t("Загружаем приложения…", "Loading apps…"), Modifier.padding(24.dp), color = W.muted) }
            else {
                val q = query.lowercase()
                val shown = list.filter { q.isEmpty() || (it.second + " " + it.first).lowercase().contains(q) }
                    .sortedWith(compareBy<Triple<String, String, Boolean>> { it.first !in selected }.thenBy { it.third }.thenBy { it.second.lowercase() }).take(400)
                items(shown, key = { it.first }) { (pkg, name, system) ->
                    val checked = pkg in selected
                    Row(Modifier.fillMaxWidth().clickable {
                        if (checked) selected.remove(pkg) else selected.add(pkg)
                        m.setting("apps", selected.joinToString("\n"))
                    }.padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = W.accent, checkmarkColor = W.onAccent, uncheckedColor = W.faint))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name + if (system) L.t(" · системное", " · system") else "", color = W.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(pkg, color = W.faint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionsPage(m: AppModel, s: JSONObject) {
    Page(m, L.t("Подписки", "Subscriptions")) {
        section(L.t("Обновление", "Updates")) {
            SwitchRow(L.t("Обновлять автоматически", "Update automatically"), null, Ic.Refresh, s.optBoolean("autoUpdate", true)) { m.setting("autoUpdate", it) }
            Divider()
            val interval = s.optInt("updateInterval", 0)
            SettingRow(L.t("Интервал", "Interval"), if (interval <= 0) L.t("как задал провайдер", "as set by the provider") else "$interval " + L.t("ч", "h"), Ic.Clock) { m.number(L.t("Часы, 0 — как у провайдера", "Hours, 0 — as the provider says"), "updateInterval", interval, 0, 730) }
            Divider()
            SwitchRow(L.t("Обновлять при открытии", "Update on open"), null, Ic.Sparkle, s.optBoolean("updateOnOpen", true)) { m.setting("updateOnOpen", it) }
            Divider()
            SwitchRow(L.t("Пинг при открытии", "Ping on open"), null, Ic.Pulse, s.optBoolean("pingOnOpen")) { m.setting("pingOnOpen", it) }
            Divider()
            SwitchRow(L.t("Подключаться при открытии", "Connect on open"), null, Ic.Power, s.optBoolean("connectOnOpen")) { m.setting("connectOnOpen", it) }
        }
        section(L.t("Порядок серверов", "Server order")) {
            SettingRow(L.t("Сортировка", "Sorting"), label(s.optString("sort", "none"), listOf("none", "ping", "name"), listOf(L.t("Как у провайдера", "As the provider sends"), L.t("По задержке", "By latency"), L.t("По имени", "By name"))), Ic.Sort) {
                m.pick(L.t("Сортировка", "Sorting"), listOf(L.t("Как у провайдера", "As the provider sends"), L.t("По задержке", "By latency"), L.t("По имени", "By name")), listOf("none", "ping", "name"), "sort", s.optString("sort", "none"))
            }
        }
        section(L.t("Что получает провайдер", "What the provider receives")) {
            SwitchRow(L.t("Передавать HWID", "Send HWID"), null, Ic.Phone, s.optBoolean("sendHwid", true)) { m.setting("sendHwid", it) }
            Divider()
            SettingRow(L.t("Время ожидания", "Timeout"), s.optInt("subTimeout", 10).toString() + L.t(" с", " s"), Ic.Clock) { m.number(L.t("Секунды", "Seconds"), "subTimeout", s.optInt("subTimeout", 10), 5, 15) }
            Divider()
            SettingRow("User-Agent", s.optString("userAgent").ifEmpty { L.t("автоматически", "automatic") }, Ic.Code) {
                m.input = Input(L.t("User-Agent (пусто — автоматически)", "User-Agent (empty — automatic)"), s.optString("userAgent")) { v -> m.store.setting("userAgent", v.trim().ifEmpty { null }) }
            }
        }
    }
}

@Composable
private fun PingPage(m: AppModel, s: JSONObject) {
    val type = s.optString("pingType", "proxyGet")
    Page(m, L.t("Пинг", "Ping")) {
        section(L.t("Способ", "Method")) {
            SettingRow(L.t("Как измерять", "How to measure"), Ping.label(type), Ic.Pulse) {
                m.pick(L.t("Способ проверки", "Test method"), listOf(L.t("Proxy GET · реальный запрос через сервер", "Proxy GET · a real request through the server"), L.t("Proxy HEAD · без тела ответа", "Proxy HEAD · without a body"), L.t("TCP · подключение к порту", "TCP · connect to the port"), "ICMP · ping"), listOf("proxyGet", "proxyHead", "tcp", "icmp"), "pingType", type)
            }
            if (type.startsWith("proxy")) {
                Divider()
                SettingRow(L.t("Соединения", "Connections"), if (s.optString("pingMode", "keepalive") == "double") L.t("Два отдельных", "Two separate") else L.t("Одно (keep-alive)", "One (keep-alive)")) {
                    m.pick(L.t("Соединения", "Connections"), listOf(L.t("Одно (keep-alive)", "One (keep-alive)"), L.t("Два отдельных", "Two separate")), listOf("keepalive", "double"), "pingMode", s.optString("pingMode", "keepalive"))
                }
                Divider()
                SettingRow(L.t("Адрес проверки", "Test address"), s.optString("pingUrl", "https://www.gstatic.com/generate_204")) {
                    m.input = Input(L.t("HTTPS-адрес", "HTTPS address"), s.optString("pingUrl", "https://www.gstatic.com/generate_204")) { v ->
                        val u = URI(v.trim()); if (u.scheme != "https" || u.host == null) throw IllegalArgumentException(L.t("Нужен HTTPS-адрес", "An HTTPS address is required"))
                        m.store.setting("pingUrl", v.trim())
                    }
                }
            }
            Divider()
            SettingRow(L.t("Тайм-аут", "Timeout"), s.optInt("pingTimeout", 5000).toString() + L.t(" мс", " ms")) { m.number(L.t("Тайм-аут, мс", "Timeout, ms"), "pingTimeout", s.optInt("pingTimeout", 5000), 1000, 30000) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearancePage(m: AppModel, s: JSONObject) {
    var blur by remember { mutableFloatStateOf(s.optDouble("bgBlur", 0.0).toFloat()) }
    var dim by remember { mutableFloatStateOf(s.optDouble("bgDim", 0.35).toFloat()) }
    Page(m, L.t("Оформление", "Appearance")) {
        section(L.t("Фон", "Background")) {
            SettingRow(if (m.photo == null) L.t("Поставить своё фото", "Use your own photo") else L.t("Сменить фото", "Change the photo"), null, Ic.Image) { m.host?.pickPhoto() }
            if (m.photo != null) {
                Divider()
                SettingRow(L.t("Убрать фото", "Remove the photo"), null, Ic.Delete, danger = true) { m.removePhoto() }
                Divider()
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(L.t("Размытие", "Blur") + if (Build.VERSION.SDK_INT < 31) L.t(" (Android 12+)", " (Android 12+)") else "", color = W.text)
                    Slider(blur, { blur = it }, onValueChangeFinished = { m.store.setting("bgBlur", blur.toDouble()) }, colors = sliderColors())
                    Text(L.t("Затемнение", "Dimming"), color = W.text)
                    Slider(dim, { dim = it }, onValueChangeFinished = { m.store.setting("bgDim", dim.toDouble()) }, colors = sliderColors())
                }
            }
        }
        item {
            SectionTitle(L.t("Акцент", "Accent"))
            val current = s.optString("accent", "system")
            val options = buildList {
                addAll(ACCENTS.keys)
                if (m.photoAccent != null) add("photo")
                if (Build.VERSION.SDK_INT >= 31) add("system")
            }
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { key ->
                    val color = when (key) { "photo" -> m.photoAccent!!; "system" -> if (Build.VERSION.SDK_INT >= 31) Color(m.ctx.getColor(android.R.color.system_accent1_200)) else W.accent; else -> ACCENTS.getValue(key) }
                    val on = key == current
                    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) W.glassHigh else W.glass).border(if (on) 2.dp else 1.dp, if (on) color else W.stroke, RoundedCornerShape(20.dp))
                        .clickable { m.setting("accent", key) }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { if (on) Icon(Ic.Check, null, Modifier.size(18.dp), tint = Color.Black.copy(alpha = 0.7f)) }
                        Spacer(Modifier.height(6.dp))
                        Text(accentName(key), color = W.muted, fontSize = 11.sp)
                    }
                }
            }
        }
        section(L.t("Главный экран", "Home screen")) {
            SettingRow(L.t("Как к вам обращаться", "What to call you"), s.optString("greetName").ifEmpty { L.t("не задано", "not set") }, Ic.User) {
                m.input = Input(L.t("Имя в приветствии", "Name in the greeting"), s.optString("greetName")) { v -> m.store.setting("greetName", v.trim().take(24)) }
            }
            Divider()
            SwitchRow(L.t("Показывать IP и пинг", "Show IP and ping"), null, Ic.Globe, s.optBoolean("showExitIp", true)) { m.setting("showExitIp", it) }
            Divider()
            SwitchRow(L.t("Чёрный фон (AMOLED)", "Pure black (AMOLED)"), null, Ic.Eye, s.optBoolean("amoled")) { m.setting("amoled", it) }
        }
    }
}

private fun accentName(key: String) = when (key) {
    "sakura" -> L.t("Сакура", "Sakura"); "lavender" -> L.t("Лаванда", "Lavender"); "mint" -> L.t("Мята", "Mint"); "ice" -> L.t("Лёд", "Ice")
    "amber" -> L.t("Янтарь", "Amber"); "coral" -> L.t("Коралл", "Coral"); "photo" -> L.t("Из фото", "From photo"); else -> L.t("Система", "System")
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = W.accent, activeTrackColor = W.accent, inactiveTrackColor = W.glassHigh)

@Composable
private fun LogsPage(m: AppModel) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { m.logs() } }
    Page(m, L.t("Журнал", "Log"), actions = {
        IconBtn(Ic.Refresh, L.t("Обновить", "Refresh")) { text = m.logs() }
        Spacer(Modifier.width(8.dp))
        IconBtn(Ic.Copy, L.t("Скопировать", "Copy")) { m.copy(text) }
    }) {
        item {
            Glass(Modifier.fillMaxWidth().padding(16.dp), RoundedCornerShape(22.dp)) {
                Text(text.ifBlank { L.t("Пусто", "Empty") }, Modifier.padding(16.dp), color = W.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun SchemesPage(m: AppModel) {
    Page(m, L.t("Команды wraith://", "wraith:// commands"), L.t("Нажмите, чтобы скопировать", "Tap to copy")) {
        val groups = listOf(
            L.t("Включить", "Turn on") to listOf("connect", "connect_without_ui"),
            L.t("Выключить (спросит подтверждение)", "Turn off (asks to confirm)") to listOf("disconnect", "disconnect_without_ui"),
            L.t("Переключить", "Toggle") to listOf("toggle", "toggle_without_ui"),
            L.t("Добавить сервер или подписку", "Add a server or subscription") to listOf("add/{url}"),
        )
        for ((title, links) in groups) section(title) {
            links.forEachIndexed { i, l -> if (i > 0) Divider(); SettingRow("wraith://$l", icon = Ic.Link) { m.copy("wraith://$l") } }
        }
        item { Text(L.t("Ссылки happ:// с теми же командами тоже работают.", "happ:// links with the same commands work too."), Modifier.padding(24.dp), color = W.muted, style = MaterialTheme.typography.bodySmall) }
    }
}

const val PURPOSE_IMPORT = 1
const val PURPOSE_RESTORE = 2

