package app.wraith.client

import android.app.Application
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class WraithApp : Application() {
    companion object {
        val IO: ExecutorService = Executors.newFixedThreadPool(4)
        /** Single source of truth for the version shown in the UI and sent in User-Agents. */
        val VERSION: String = BuildConfig.VERSION_NAME
    }

    override fun onCreate() {
        super.onCreate()
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else packageName
        // The Xray processes need none of the app-process setup.
        if (process.endsWith(":xray") || process.endsWith(":xray_probe")) return
        CoreRouter.restore(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Tunnel.lastSignal = SystemClock.elapsedRealtime()
                Tunnel.set(CoreRouter.fromIntent(intent))
                QuickTile.sync(context)
            }
        }, IntentFilter(Tunnel.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            File(filesDir, "core").mkdirs()
            AppLog.add("Wraith $VERSION · Xray")
            UpdateJob.schedule(this); XrayAssets.scheduleUpdate(this)
        } catch (e: Throwable) { AppLog.add(L.t("Ошибка инициализации: ", "Initialisation error: ") + e.safe()) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateJob.schedule(c)
        // An app update is not a reboot: only a real boot may start the tunnel on its own.
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        try { val s = Store.get(c); if (s.settings().optBoolean("autoConnect") && s.selected() != null && VpnService.prepare(c) == null) CoreRouter.start(c) }
        catch (e: Exception) { AppLog.add(L.t("Автозапуск: ", "Autostart: ") + e.safe()) }
    }
}

/** Hourly background subscription refresh. */
class UpdateJob : JobService() {
    private var work: Future<*>? = null
    companion object {
        fun schedule(c: Context) {
            try {
                c.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(310, ComponentName(c, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(60 * 60 * 1000L).setPersisted(true).build())
            } catch (e: Exception) { AppLog.add(L.t("Планировщик: ", "Scheduler: ") + e.message) }
        }
    }
    override fun onStartJob(p: JobParameters): Boolean {
        work = WraithApp.IO.submit {
            try { if (Store.get(this).settings().optBoolean("autoUpdate", true)) Subscriptions.updateAll(this, true) }
            catch (e: Exception) { AppLog.add(L.t("Фоновое обновление: ", "Background update: ") + e.safe()) }
            finally { jobFinished(p, false) }
        }
        return true
    }
    override fun onStopJob(p: JobParameters): Boolean { work?.cancel(true); return true }
}

/** One Quick Settings tile that toggles the selected server. */
class QuickTile : TileService() {
    companion object {
        fun sync(context: Context) {
            // Some vendor ROMs throw here; a tile refresh must never break the caller.
            try { requestListeningState(context, ComponentName(context, QuickTile::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        CoreRouter.verify(this)
        val tile = qsTile ?: return
        val selected = try { Store.get(this).selected() } catch (_: Exception) { null }
        val selectedName = selected?.let { PingInfo.displayName(it.optString("name")) } ?: L.t("Сервер не выбран", "No server selected")
        val s = Tunnel.status.value
        val active = s.profileName.ifBlank { selectedName }
        tile.label = if (s.state == Tunnel.CONNECTED) active else "Wraith"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when (s.state) { Tunnel.CONNECTED -> L.t("Подключено", "Connected"); Tunnel.CONNECTING -> L.t("Подключение…", "Connecting…"); else -> selectedName }
        tile.state = if (selected == null) Tile.STATE_UNAVAILABLE else if (s.state == Tunnel.CONNECTED || s.state == Tunnel.CONNECTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        CoreRouter.verify(this)
        if (Tunnel.status.value.state != Tunnel.OFF) { CoreRouter.stop(this); sync(this); return }
        val selected = try { Store.get(this).selected() } catch (_: Exception) { null }
        if (selected != null && VpnService.prepare(this) == null) { CoreRouter.start(this); sync(this); return }
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending) else try { pending.send() } catch (_: PendingIntent.CanceledException) {}
    }
}

/** Names of provider servers: flags, emoji and the text after them. */
object PingInfo {
    fun displayName(name: String): String = name.replaceFirst(Regex("^[\\x{1F1E6}-\\x{1F1FF}]{2}\\s*"), "")
    private fun regional(cp: Int) = cp in 0x1F1E6..0x1F1FF
    private fun emojiBase(cp: Int) = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || (cp in 0x1F000..0x1F2FF && !regional(cp))

    /** The first emoji of a name: a flag or an emoji with its modifiers; "" when none. */
    fun firstEmoji(name: String?): String {
        if (name == null) return ""
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i); val n = Character.charCount(cp)
            if (regional(cp) && i + n < name.length && regional(name.codePointAt(i + n))) return name.substring(i, i + n + Character.charCount(name.codePointAt(i + n)))
            if (emojiBase(cp)) {
                var end = i + n
                while (end < name.length) {
                    val c = name.codePointAt(end)
                    if (c == 0xFE0F || c in 0x1F3FB..0x1F3FF) { end += Character.charCount(c); continue }
                    if (c == 0x200D && end + 1 < name.length) { end += 1 + Character.charCount(name.codePointAt(end + 1)); continue }
                    break
                }
                return name.substring(i, end)
            }
            i += n
        }
        return ""
    }

    fun isFlag(s: String?) = s != null && s.codePointCount(0, s.length) == 2 && regional(s.codePointAt(0)) && regional(s.codePointBefore(s.length))

    /** "de" for 🇩🇪, or a guess from the country name. */
    fun countryCode(name: String): String {
        val flag = firstEmoji(name)
        if (isFlag(flag)) return String(charArrayOf('a' + (flag.codePointAt(0) - 0x1F1E6), 'a' + (flag.codePointBefore(flag.length) - 0x1F1E6)))
        val n = " " + name.lowercase() + " "
        val guesses = mapOf("german" to "de", "герман" to "de", "finland" to "fi", "финлянд" to "fi", "nether" to "nl", "нидерл" to "nl", "голланд" to "nl",
            "united states" to "us", "usa" to "us", "сша" to "us", "америк" to "us", "france" to "fr", "франц" to "fr", "poland" to "pl", "польш" to "pl",
            "sweden" to "se", "швец" to "se", "latvia" to "lv", "латв" to "lv", "eston" to "ee", "эстон" to "ee", "kazakh" to "kz", "казах" to "kz",
            "turkey" to "tr", "турц" to "tr", "united kingdom" to "gb", "london" to "gb", "англ" to "gb", "британ" to "gb", "japan" to "jp", "япон" to "jp",
            "singapore" to "sg", "сингап" to "sg", "russia" to "ru", "росси" to "ru", "lithuan" to "lt", "литв" to "lt", "austria" to "at", "австри" to "at",
            "switzerland" to "ch", "швейцар" to "ch", "norway" to "no", "норвег" to "no", "spain" to "es", "испан" to "es", "italy" to "it", "итал" to "it",
            "canada" to "ca", "канад" to "ca", "armenia" to "am", "армен" to "am", "georgia" to "ge", "грузи" to "ge", "serbia" to "rs", "серби" to "rs",
            "moldova" to "md", "молдов" to "md", "ukrain" to "ua", "украин" to "ua", "czech" to "cz", "чехи" to "cz", "hong kong" to "hk", "гонконг" to "hk")
        return guesses.entries.firstOrNull { it.key in n }?.value.orEmpty()
    }
}

/** Round flags from the bundled square flag-icons atlas (MIT, assets/licenses/flag-icons.txt). */
object Flags {
    private const val CELL = 64; private const val COLS = 16
    private var atlas: Bitmap? = null
    private var codes: List<String>? = null
    private val cells = HashMap<String, Bitmap>()

    @Synchronized fun bitmap(c: Context, code: String): Bitmap? {
        if (code.isEmpty()) return null
        cells[code]?.let { return it }
        return try {
            if (codes == null) {
                codes = c.assets.open("flags.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
                atlas = c.assets.open("flags.png").use { BitmapFactory.decodeStream(it) }
            }
            val k = codes!!.indexOf(code); val a = atlas
            if (k < 0 || a == null) null else Bitmap.createBitmap(a, (k % COLS) * CELL, (k / COLS) * CELL, CELL, CELL).also { cells[code] = it }
        } catch (_: Exception) { null }
    }
}

/**
 * Exit address and country of the running tunnel, plus the real round-trip through it. Asked
 * only while the tunnel is up and the switch is on; the request goes through the tunnel itself.
 */
object ExitInfo {
    data class Info(val ip: String = "", val country: String = "", val delay: Int = -1, val checked: Long = 0)

    fun fetch(settings: JSONObject): Info {
        var ip = ""; var country = ""
        // api.2ip.io has only an IPv4 address, so a server with IPv6 still shows its IPv4 exit.
        try {
            val c = URL("https://api.2ip.io/").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000; c.useCaches = false; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Wraith/${WraithApp.VERSION}"); c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Connection", "close")
            try {
                val o = JSONObject(String(c.inputStream.bounded(16 * 1024)))
                ip = o.optString("ip").trim(); country = o.optString("code").trim().lowercase()
            } finally { c.disconnect() }
        } catch (_: Exception) {}
        return Info(ip, country, delay(settings), SystemClock.elapsedRealtime())
    }

    /** One HTTPS request through the tunnel, timed: what a browser would feel. */
    fun delay(settings: JSONObject): Int = try {
        val url = settings.optString("pingUrl", "https://www.gstatic.com/generate_204")
        var best = Int.MAX_VALUE
        repeat(2) {
            val start = SystemClock.elapsedRealtime()
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false; c.useCaches = false
            try { c.responseCode } finally { c.disconnect() }
            best = minOf(best, (SystemClock.elapsedRealtime() - start).toInt())
        }
        best
    } catch (_: Exception) { -1 }
}
