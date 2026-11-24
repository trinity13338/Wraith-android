package app.wraith.client.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.VpnService
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import app.wraith.client.AppLog
import app.wraith.client.CoreRouter
import app.wraith.client.ExitInfo
import app.wraith.client.ImportParser
import app.wraith.client.L
import app.wraith.client.Ping
import app.wraith.client.RoutingConfig
import app.wraith.client.Store
import app.wraith.client.Subscriptions
import app.wraith.client.Tunnel
import app.wraith.client.UpdateChecker
import app.wraith.client.bounded
import app.wraith.client.copy
import app.wraith.client.objects
import app.wraith.client.safe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What the activity can do for the model: system screens and pickers. */
interface Host {
    fun requestVpn(intent: Intent)
    fun scanQr()
    fun openDocument(purpose: Int)
    fun export(name: String, data: String)
    fun pickPhoto()
    fun open(intent: Intent)
    fun install(file: File)
}

data class PingResult(val ms: Int, val error: String = "")
data class Confirm(val title: String, val text: String, val action: String, val danger: Boolean = false, val run: () -> Unit)
data class Input(val title: String, val value: String, val hint: String = "", val multiline: Boolean = false, val number: Boolean = false, val save: (String) -> Unit)
data class Choice(val title: String, val labels: List<String>, val selected: Int, val pick: (Int) -> Unit)
data class Action(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val danger: Boolean = false, val run: () -> Unit)
data class Sheet(val title: String, val subtitle: String = "", val actions: List<Action>)
data class Editor(val title: String, val value: String, val save: (String) -> Unit)

class AppModel(app: Application) : AndroidViewModel(app) {
    val ctx: Application = app
    val storeError: String?
    private val storeOrNull: Store?
    val store: Store get() = storeOrNull!!

    init {
        var error: String? = null
        storeOrNull = try { Store.get(app) } catch (e: Exception) { error = e.message; null }
        storeError = error
    }

    val revision: StateFlow<Long> = storeOrNull?.revision ?: MutableStateFlow(0L)
    var host: Host? = null

    // Navigation: 0 home, 1 servers, 2 stats; settings is its own layer with a page stack.
    var tab by mutableIntStateOf(0)
    var inSettings by mutableStateOf(false)
    val pages = mutableStateListOf<String>()
    var search by mutableStateOf("")
    val collapsed = mutableStateMapOf<String, Boolean>()

    // Feedback.
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var confirm by mutableStateOf<Confirm?>(null)
    var importText by mutableStateOf<String?>(null)
    var info by mutableStateOf<Pair<String, String>?>(null)
    var input by mutableStateOf<Input?>(null)
    var choice by mutableStateOf<Choice?>(null)
    var sheet by mutableStateOf<Sheet?>(null)
    var editor by mutableStateOf<Editor?>(null)
    var qr by mutableStateOf<String?>(null)
    var ruleEdit by mutableStateOf<Pair<Int, JSONObject?>?>(null)
    var update by mutableStateOf<UpdateChecker.Result?>(null)
    var download by mutableStateOf<Pair<Long, Long>?>(null)
    var pendingUpdate: UpdateChecker.Result? = null

    // Latency.
    val pingResults = mutableStateMapOf<String, PingResult>()
    val pinging = mutableStateMapOf<String, Boolean>()
    var pingRunning by mutableStateOf(false)
    private var pingJob: Job? = null
    private var pingSession = ""
    private var pingCancel = AtomicBoolean()

    // Live statistics of this session (only in memory, only on this phone).
    val speed = mutableStateListOf<Pair<Long, Long>>()
    var exit by mutableStateOf(ExitInfo.Info())
    var now by mutableStateOf(SystemClock.elapsedRealtime())

    // Appearance.
    var photo by mutableStateOf<ImageBitmap?>(null)
    var photoAccent by mutableStateOf<Color?>(null)

    private var watched = Tunnel.OFF
    private var shownError = ""

    init {
        if (storeOrNull != null) {
            loadPhoto()
            viewModelScope.launch { ticker() }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    fun setting(key: String, value: Any?) = try { store.setting(key, value); changed() } catch (e: Exception) { error(e) }
    fun error(e: Throwable) { message = e.safe() }
    fun toast(text: String) { message = text }

    /** Settings that only the core reads apply on the next connection. */
    fun changed() { if (Tunnel.status.value.state == Tunnel.CONNECTED) message = L.t("Применится после переподключения", "Applies after reconnecting") }

    fun task(label: String, block: suspend () -> String?) {
        if (busy != null) { message = L.t("Дождитесь текущей операции", "Wait for the current operation"); return }
        busy = label
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() }?.let { message = it } } catch (e: Throwable) { error(e) } finally { busy = null }
        }
    }

    fun copy(text: String, label: String = L.t("Скопировано", "Copied")) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Wraith", text)); message = label
    }

    fun openLink(url: String) {
        try {
            val u = Uri.parse(url)
            if (u.scheme !in setOf("https", "tg")) throw IllegalArgumentException(L.t("Провайдер не указал безопасную ссылку.", "The provider did not give a safe link."))
            host?.open(Intent(Intent.ACTION_VIEW, u))
        } catch (e: Exception) { error(e) }
    }

    fun openSettings(page: String? = null) { inSettings = true; pages.clear(); if (page != null) pages += page }
    fun back(): Boolean {
        if (pages.isNotEmpty()) { pages.removeAt(pages.lastIndex); return true }
        if (inSettings) { inSettings = false; return true }
        if (tab != 0) { tab = 0; return true }
        return false
    }

    // ---- tunnel ----------------------------------------------------------------------------------

    private suspend fun ticker() {
        var exitDue = 0L
        while (viewModelScope.isActive) {
            CoreRouter.verify(ctx)
            val s = Tunnel.status.value
            now = SystemClock.elapsedRealtime()
            if ((watched == Tunnel.CONNECTING || watched == Tunnel.CONNECTED) && s.state == Tunnel.OFF && s.error.isNotEmpty() && s.error != shownError) {
                shownError = s.error; message = L.t("Не удалось подключиться: ", "Could not connect: ") + s.error
            }
            if (s.state == Tunnel.CONNECTED) {
                speed += s.rxRate to s.txRate
                while (speed.size > 60) speed.removeAt(0)
                if (watched != Tunnel.CONNECTED) { exitDue = now + 1500; exit = ExitInfo.Info() }
                if (now >= exitDue && store.settings().optBoolean("showExitIp", true)) {
                    exitDue = now + 60_000
                    val settings = store.settings()
                    viewModelScope.launch { exit = withContext(Dispatchers.IO) { ExitInfo.fetch(settings) } }
                }
            } else if (watched == Tunnel.CONNECTED) { speed.clear(); exit = ExitInfo.Info() }
            watched = s.state
            delay(1000)
        }
    }

    fun toggleConnection() {
        if (pingRunning) { cancelPing(); return }
        if (Tunnel.status.value.state != Tunnel.OFF) { CoreRouter.stop(ctx); return }
        if (store.selected() == null) { importText = ""; return }
        shownError = ""
        val request = VpnService.prepare(ctx)
        if (request != null) host?.requestVpn(request) else CoreRouter.start(ctx)
    }

    fun select(p: JSONObject) {
        try {
            val id = p.getString("id")
            if (id == store.selectedId()) return
            store.select(id)
            val st = Tunnel.status.value.state
            if (st == Tunnel.CONNECTING || st == Tunnel.CONNECTED) CoreRouter.restart(ctx)
        } catch (e: Exception) { error(e) }
    }

    /** wraith:// and happ:// control links. Turning the VPN off from a link always asks first. */
    fun handleIntent(intent: Intent?) {
        if (intent == null || storeOrNull == null) return
        val input = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else intent.dataString
        if (input.isNullOrBlank()) return
        if (urlCommand(input.trim())) return
        importText = input.trim()
    }

    private fun urlCommand(link: String): Boolean {
        var cmd = when { link.startsWith("wraith://") -> link.substring(9); link.startsWith("happ://") -> link.substring(7); else -> return false }.trimEnd('/')
        val quiet = cmd.endsWith("_without_ui")
        if (quiet) cmd = cmd.removeSuffix("_without_ui")
        if (cmd !in setOf("connect", "open", "disconnect", "close", "toggle")) return false
        val on = Tunnel.status.value.state != Tunnel.OFF
        val start = cmd == "connect" || cmd == "open" || (cmd == "toggle" && !on)
        if (start && !on) toggleConnection()
        else if (!start && on) confirm = Confirm(L.t("Отключить VPN?", "Disconnect VPN?"), L.t("Другое приложение или сайт просит отключить VPN.", "Another app or website asks to disconnect the VPN."), L.t("Отключить", "Disconnect"), true) { CoreRouter.stop(ctx) }
        return true
    }

    fun onOpen() {
        val s = store.settings()
        viewModelScope.launch {
            try { val r = withContext(Dispatchers.IO) { UpdateChecker.check() }; if (r.available) update = r } catch (_: Exception) {}
        }
        if (s.optBoolean("connectOnOpen") && Tunnel.status.value.state == Tunnel.OFF && store.selected() != null) toggleConnection()
        val updateSubs = s.optBoolean("updateOnOpen", false); val ping = s.optBoolean("pingOnOpen")
        if (updateSubs || ping) viewModelScope.launch {
            if (updateSubs) withContext(Dispatchers.IO) { Subscriptions.updateAll(ctx, true) }
            if (ping) pingAll()
        }
    }

    // ---- import ----------------------------------------------------------------------------------

    fun doImport(raw: String) {
        val text = raw.trim()
        if (text.startsWith("happ://routing/")) { importRouting(text); return }
        task(L.t("Добавляем…", "Adding…")) {
            val input = ImportParser.unwrap(text)
            if (input.startsWith("http://")) throw IllegalArgumentException(L.t("Для подписок требуется HTTPS.", "Subscriptions require HTTPS."))
            if (input.startsWith("https://")) {
                val sub = Subscriptions.create(ctx, input)
                withContext(Dispatchers.Main) { tab = 1; inSettings = false }
                return@task L.t("Подписка «", "Subscription «") + sub.optString("name") + L.t("» добавлена", "» added")
            }
            val skipped = mutableListOf<String>()
            val parsed = ImportParser.parse(input, skipped)
            store.addProfiles(parsed)
            withContext(Dispatchers.Main) { tab = 1; inSettings = false }
            L.t("Добавлено серверов: ", "Servers added: ") + parsed.size + if (skipped.isEmpty()) "" else L.t(". Пропущено: ", ". Skipped: ") + skipped.size + " — " + skipped[0]
        }
    }

    fun importRouting(text: String) {
        try {
            val json = RoutingConfig.decode(text)
            val converted = JSONArray().also { RoutingConfig.append(it, json) }
            val rules = converted.objects()
            if (rules.isEmpty()) throw IllegalArgumentException(L.t("В профиле нет правил доменов или IP.", "The profile has no domain or IP rules."))
            val entries = rules.sumOf { RoutingConfig.split(it.optString("value"), it.optString("kind")).size }
            confirm = Confirm(L.t("Импортировать правила?", "Import the rules?"), L.t("Правил: ", "Rules: ") + rules.size + L.t(", записей: ", ", entries: ") + entries + ".", L.t("Импорт", "Import")) {
                rules.forEach { it.remove("provider"); store.addRule(it) }
                openSettings("routing"); changed()
            }
        } catch (e: Exception) { error(e) }
    }

    fun importFile(uri: Uri, restore: Boolean) = task(L.t("Читаем файл…", "Reading file…")) {
        val text = String(ctx.contentResolver.openInputStream(uri)!!.bounded(), Charsets.UTF_8)
        if (restore) {
            if (Tunnel.status.value.state != Tunnel.OFF) CoreRouter.stop(ctx)
            store.restore(text)
            L.t("Резервная копия восстановлена", "Backup restored")
        } else { withContext(Dispatchers.Main) { importText = text }; null }
    }

    // ---- latency ---------------------------------------------------------------------------------

    fun pingAll() = startPing(store.profiles())
    fun pingGroup(group: String) = startPing(store.profiles().filter { it.optString("group") == group })

    fun startPing(targets: List<JSONObject>) {
        if (targets.isEmpty()) return
        if (!pingRunning) {
            pingRunning = true; pingSession = UUID.randomUUID().toString(); pingCancel = AtomicBoolean()
            if (targets.size > 1) pingResults.clear()
        }
        val session = pingSession; val cancelled = pingCancel
        val settings = store.settings()
        val todo = targets.filter { pinging[it.optString("id")] != true }
        todo.forEach { pinging[it.optString("id")] = true }
        val previous = pingJob
        pingJob = viewModelScope.launch {
            val io = Dispatchers.IO.limitedParallelism(24)
            val jobs = todo.map { p ->
                launch(io) {
                    val id = p.optString("id")
                    val result = try { PingResult(Ping.measure(ctx, p, settings, session, cancelled)) } catch (e: Throwable) { PingResult(-1, e.safe()) }
                    withContext(Dispatchers.Main) { if (!cancelled.get()) { pingResults[id] = result; pinging.remove(id) } }
                }
            }
            previous?.join()
            jobs.forEach { it.join() }
            if (session == pingSession && pinging.isEmpty()) pingRunning = false
        }
    }

    fun cancelPing() {
        pingCancel.set(true); pingJob?.cancel(); Ping.cancel(ctx, pingSession)
        pinging.clear(); pingRunning = false
    }

    // ---- appearance ------------------------------------------------------------------------------

    private fun photoFile() = File(ctx.filesDir, "background.jpg")

    fun loadPhoto() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val f = photoFile(); if (!f.exists()) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 2400) sample *= 2
                val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
                bmp to accentOf(bmp)
            }
            photo = result?.first?.asImageBitmap(); photoAccent = result?.second
        }
    }

    private fun accentOf(bmp: Bitmap): Color? {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val sw = p.lightVibrantSwatch ?: p.vibrantSwatch ?: p.lightMutedSwatch ?: p.dominantSwatch ?: return null
        return Color(sw.rgb)
    }

    fun setPhoto(uri: Uri) = task(L.t("Ставим фон…", "Setting the background…")) {
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 40 * 1024 * 1024) throw IllegalArgumentException(L.t("Фото больше 40 МБ", "The photo is larger than 40 MB"))
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
            if (probe.outWidth <= 0) throw IllegalArgumentException(L.t("Это не изображение", "This is not an image"))
            val tmp = File(ctx.filesDir, "background.tmp"); tmp.writeBytes(bytes); tmp.renameTo(photoFile())
        }
        withContext(Dispatchers.Main) { loadPhoto() }
        L.t("Фон обновлён", "Background updated")
    }

    fun removePhoto() { photoFile().delete(); photo = null; photoAccent = null; if (store.settings().optString("accent") == "photo") setting("accent", "sakura") }

    // ---- updates ---------------------------------------------------------------------------------

    fun checkUpdates() = task(L.t("Проверяем обновления…", "Checking for updates…")) {
        val r = UpdateChecker.check()
        if (r.available) { withContext(Dispatchers.Main) { update = r }; null } else L.t("Установлена последняя версия ", "The latest version is installed: ") + app.wraith.client.WraithApp.VERSION
    }

    fun downloadUpdate(r: UpdateChecker.Result) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            pendingUpdate = r; message = L.t("Разрешите Wraith устанавливать обновления", "Allow Wraith to install updates")
            host?.open(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))); return
        }
        update = null; download = 0L to 0L
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { UpdateChecker.download(ctx, r) { done, total -> viewModelScope.launch(Dispatchers.Main) { download = done to total } } }
                download = null; host?.install(apk)
            } catch (e: Exception) { download = null; error(e) }
        }
    }

    fun resumeUpdate() {
        val r = pendingUpdate ?: return
        if (ctx.packageManager.canRequestPackageInstalls()) { pendingUpdate = null; downloadUpdate(r) }
    }

    // ---- logs ------------------------------------------------------------------------------------

    fun tail(relative: String, max: Long = 30000): String {
        val f = File(ctx.filesDir, relative); if (!f.exists()) return ""
        return try { java.io.RandomAccessFile(f, "r").use { it.seek((it.length() - max).coerceAtLeast(0)); val b = ByteArray((it.length() - it.filePointer).toInt()); it.readFully(b); String(b) } } catch (e: Exception) { e.safe() }
    }

    fun logs(): String = AppLog.text() + L.t("\n\n── XRAY · СЛУЖБА ──\n", "\n\n── XRAY · SERVICE ──\n") + tail("core/xray-service.log") +
        L.t("\n\n── XRAY · ОШИБКИ ──\n", "\n\n── XRAY · ERRORS ──\n") + tail("core/xray-error.log")

    fun profileCopy(p: JSONObject): JSONObject = p.copy()
}
