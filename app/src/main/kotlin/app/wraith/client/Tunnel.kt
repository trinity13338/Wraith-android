package app.wraith.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.IpPrefix
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Snapshot of the tunnel as the app process sees it. */
data class TunnelStatus(
    val state: Int = Tunnel.OFF, val error: String = "", val profileName: String = "",
    val since: Long = 0, val rx: Long = 0, val tx: Long = 0, val rxRate: Long = 0, val txRate: Long = 0,
)

/** Tunnel state in the app process; the tunnel itself is XrayVpnService in :xray. */
object Tunnel {
    const val OFF = 0; const val CONNECTING = 1; const val CONNECTED = 2; const val STOPPING = 3
    const val ACTION_STATE = "app.wraith.client.CORE_STATE"
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status
    @Volatile var lastSignal = 0L
    /** Routing compiled into the running core, to restart only when a subscription changed it. */
    @Volatile var runningRouting = ""

    fun set(value: TunnelStatus) { _status.value = value }
    fun clearError() { _status.value = _status.value.copy(error = "") }

    fun routingOf(state: JSONObject, profile: JSONObject): String = RoutingConfig.profileFor(state, profile)?.toString().orEmpty()
    fun routingDiffers(c: Context, profile: JSONObject): Boolean = try { routingOf(Store.get(c).snapshot(), profile) != runningRouting } catch (_: Exception) { true }
}

/** Starts, stops and restarts XrayVpnService. */
object CoreRouter {
    private const val PREFS = "wraith_core"

    private fun active(c: Context): Boolean = File(c.filesDir, "active-engine").let { f ->
        try { AtomicFile(f).let { if (it.baseFile.exists()) String(it.readFully()).isNotEmpty() else false } } catch (_: IOException) { false }
    }
    private fun active(c: Context, on: Boolean) {
        val f = AtomicFile(File(c.filesDir, "active-engine"))
        try { val out = f.startWrite(); try { out.write(if (on) "xray".toByteArray() else ByteArray(0)); f.finishWrite(out) } catch (e: IOException) { f.failWrite(out); throw e } }
        catch (e: IOException) { AppLog.add(L.t("Не удалось сохранить состояние ядра: ", "Could not save core state: ") + e.message) }
    }

    private fun rememberRouting(c: Context) {
        val routing = try { val s = Store.get(c); s.selected()?.let { Tunnel.routingOf(s.snapshot(), it) }.orEmpty() } catch (_: Exception) { "" }
        Tunnel.runningRouting = routing
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("running_routing", routing).apply()
    }

    fun start(context: Context) {
        val c = context.applicationContext
        active(c, true); rememberRouting(c)
        AppLog.add(L.t("Запуск подключения · Wraith ", "Starting connection · Wraith ") + WraithApp.VERSION)
        Tunnel.clearError()
        c.startForegroundService(Intent(c, XrayVpnService::class.java))
    }

    fun stop(context: Context) {
        val c = context.applicationContext
        // A plain startService from the background throws when the service is gone; a foreground
        // start is always allowed for a VPN app and the service stops itself right away.
        try { c.startService(Intent(c, XrayVpnService::class.java).setAction("stop")) }
        catch (_: IllegalStateException) { c.startForegroundService(Intent(c, XrayVpnService::class.java).setAction("stop")) }
    }

    fun restart(context: Context) {
        val c = context.applicationContext
        if (!active(c)) { start(c); return }
        rememberRouting(c)
        c.startForegroundService(Intent(c, XrayVpnService::class.java).setAction("restart"))
    }

    fun stopped(c: Context) = active(c, false)

    /** A killed :xray process stops broadcasting; never leave the UI stuck on "connected". */
    fun verify(c: Context) {
        val s = Tunnel.status.value
        if (s.state != Tunnel.CONNECTED && s.state != Tunnel.CONNECTING) return
        val last = Tunnel.lastSignal; val limit = if (s.state == Tunnel.CONNECTED) 10000 else 45000
        if (last == 0L || SystemClock.elapsedRealtime() - last < limit || !active(c)) return
        AppLog.add(L.t("Процесс Xray перестал сообщать состояние; туннель сброшен.", "The Xray process stopped reporting its state; the tunnel was reset."))
        Tunnel.set(TunnelStatus())
        stopped(c); QuickTile.sync(c)
    }

    fun publish(c: Context, s: TunnelStatus) {
        c.sendBroadcast(Intent(Tunnel.ACTION_STATE).setPackage(c.packageName)
            .putExtra("state", s.state).putExtra("error", s.error).putExtra("name", s.profileName).putExtra("since", s.since)
            .putExtra("rx", s.rx).putExtra("tx", s.tx).putExtra("rxRate", s.rxRate).putExtra("txRate", s.txRate))
    }

    fun fromIntent(i: Intent) = TunnelStatus(i.getIntExtra("state", Tunnel.OFF), i.getStringExtra("error").orEmpty(), i.getStringExtra("name").orEmpty(),
        i.getLongExtra("since", 0), i.getLongExtra("rx", 0), i.getLongExtra("tx", 0), i.getLongExtra("rxRate", 0), i.getLongExtra("txRate", 0))

    /** A tunnel in :xray survives an app-process restart; keep knowing its routing. */
    fun restore(c: Context) {
        Tunnel.set(TunnelStatus())
        if (active(c)) Tunnel.runningRouting = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("running_routing", "").orEmpty()
    }
}

/**
 * The tunnel. Runs in :xray so the Go runtime is isolated from the UI. Wraith itself is routed
 * through the tunnel too; only the core's own sockets are protected from it.
 */
class XrayVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var wanted = false
    @Volatile private var commandId = 0L
    @Volatile private var current = TunnelStatus()
    private var tun: ParcelFileDescriptor? = null
    private var xray: IsolatedXray? = null
    @Volatile private var notifySpeed = false
    private var baseRx = 0L; private var baseTx = 0L; private var lastRx = 0L; private var lastTx = 0L

    override fun onCreate() {
        super.onCreate()
        AppLog.mirrorTo(File(filesDir, "core/xray-service.log"))
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("connection", L.t("Подключение Wraith", "Wraith connection"), NotificationManager.IMPORTANCE_LOW))
        ticker.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty(); val command = ++commandId
        if (action == "stop") {
            wanted = false
            // Started with startForegroundService when the app was in the background: Android
            // requires the foreground promise to be kept even for a stop.
            try { startForeground(NOTIFICATION, notification(L.t("Отключение…", "Disconnecting…"))) } catch (_: Exception) {}
            worker.execute { stopCore(); if (command == commandId) { CoreRouter.stopped(this); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) } }
            return START_NOT_STICKY
        }
        val restart = action == "restart"
        wanted = true
        startForeground(NOTIFICATION, notification(if (restart) L.t("Переключение сервера…", "Switching server…") else L.t("Подключение…", "Connecting…")))
        worker.execute { if (command != commandId) return@execute; if (restart || xray != null || tun != null) stopCore(); if (wanted) connect(command, startId) }
        return START_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, XrayVpnService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "connection").setSmallIcon(R.drawable.ic_tile).setContentTitle("Wraith").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, L.t("Отключить", "Disconnect"), stop).build()).build()
    }

    private fun connect(command: Long, startId: Int) {
        var name = ""
        try {
            if (prepare(this) != null) throw IllegalStateException(L.t("Нужно разрешение Android на VPN-подключение.", "Android VPN permission is required."))
            val snapshot = Store.readOnlySnapshot(this)
            val selected = snapshot.optJSONArray("profiles").objects().firstOrNull { it.optString("id") == snapshot.optString("selected") }
                ?: throw IllegalStateException(L.t("Сначала выберите сервер.", "Select a server first."))
            name = PingInfo.displayName(selected.optString("name"))
            setState(TunnelStatus(Tunnel.CONNECTING, profileName = name))
            val settings = snapshot.child("settings")
            DnsChoice.inject(this, settings)
            notifySpeed = settings.optBoolean("notifySpeed", true)
            val dns = XrayRuntimeConfig.androidDns(snapshot, selected, settings)
            val geodata = XrayAssets.ensure(this)
            val core = IsolatedXray(this).also { xray = it }
            core.setDNS(XrayRuntimeConfig.dnsEndpoint(dns))
            // The configuration is built before the TUN exists: a broken profile must not leave
            // the phone with a VPN interface that blackholes every app.
            XrayRuntimeConfig.build(snapshot, selected, 0, core)
            val fd = establish(settings, dns) ?: throw IOException(L.t("Android не создал VPN-интерфейс.", "Android did not create the VPN interface."))
            tun = fd
            val config = XrayRuntimeConfig.build(snapshot, selected, fd.fd, core)
            config.child("env").put("xray.location.asset", geodata.absolutePath)
            configureLogs(config)
            val result = JSONObject(core.invoke(jsonOf("apiVersion" to 3, "method" to "runXray", "payload" to jsonOf("xrayJson" to config.toString())).toString()))
            if (!result.optBoolean("success")) throw IllegalStateException(result.optString("error", L.t("Xray не запустился.", "Xray did not start.")))
            if (!wanted || command != commandId) { stopCore(); return }
            baseRx = uidRx(); baseTx = uidTx(); lastRx = baseRx; lastTx = baseTx
            setState(TunnelStatus(Tunnel.CONNECTED, profileName = name, since = SystemClock.elapsedRealtime()))
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(name + L.t(" · подключено", " · connected")))
            AppLog.add(L.t("Туннель запущен: ", "Tunnel started: ") + name)
        } catch (e: Throwable) {
            val message = e.safe()
            AppLog.add("Xray: $message")
            stopCore()
            if (command == commandId) { wanted = false; setState(TunnelStatus(Tunnel.OFF, error = message, profileName = name)); CoreRouter.stopped(this); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        }
    }

    private fun configureLogs(config: JSONObject) {
        val dir = File(filesDir, "core"); if (!dir.exists() && !dir.mkdirs()) throw IOException(L.t("Не удалось создать папку логов Xray.", "Could not create the Xray log folder."))
        val error = File(dir, "xray-error.log"); val access = File(dir, "xray-access.log")
        for (f in listOf(error, access)) if (f.length() > 1024 * 1024) FileOutputStream(f, false).use { }
        config.child("log").put("error", error.absolutePath).put("access", access.absolutePath)
    }

    private fun establish(s: JSONObject, dns: String): ParcelFileDescriptor? {
        val mtu = s.optInt("mtu", 1500)
        if (mtu !in 1280..9000) throw IllegalArgumentException(L.t("MTU должен быть от 1280 до 9000.", "MTU must be between 1280 and 9000."))
        val b = Builder().setSession("Wraith").setMtu(mtu)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.addAddress("172.19.0.1", 30).addAddress("fdfe:dcba:9876::1", 126).addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer(dns)
        if (Build.VERSION.SDK_INT >= 33) for (row in s.optString("excludedRoutes").lines()) {
            val cidr = row.trim(); if (cidr.isEmpty()) continue
            try { val parts = cidr.split('/', limit = 2); b.excludeRoute(IpPrefix(InetAddress.getByName(parts[0]), parts[1].toInt())) }
            catch (_: Exception) { AppLog.add(L.t("Пропущен маршрут исключения: ", "Exclusion route skipped: ") + cidr) }
        }
        // Wraith is never excluded: with the VPN on, subscription updates, update checks and the
        // exit-IP lookup go through the tunnel like every other app.
        val mode = s.optString("appMode", "off")
        val include = mode == "include"
        var added = 0
        if (mode != "off") for (app in s.optString("apps").split('\n', ',')) {
            val pkg = app.trim(); if (pkg.isEmpty() || pkg == packageName) continue
            try { if (include) b.addAllowedApplication(pkg) else b.addDisallowedApplication(pkg); added++ }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { AppLog.add(L.t("Приложение удалено: ", "App removed: ") + pkg) }
        }
        if (include) { if (added == 0) throw IllegalStateException(L.t("В списке VPN нет установленных приложений.", "No installed apps in the VPN list.")); b.addAllowedApplication(packageName) }
        return b.establish()
    }

    private fun stopCore() {
        val core = xray
        try { core?.invoke(jsonOf("apiVersion" to 3, "method" to "stopXray", "payload" to JSONObject()).toString()) } catch (_: Throwable) {}
        core?.resetDNS(); xray = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        flushTraffic()
        if (current.state != Tunnel.OFF) setState(TunnelStatus(Tunnel.OFF, profileName = current.profileName))
    }

    private fun setState(s: TunnelStatus) { current = s; CoreRouter.publish(this, s) }

    // Traffic is read from the kernel's per-app counters: the core's sockets carry every byte the
    // tunnel moves. No local metrics listener is opened, so no other app can query the tunnel.
    private fun uidRx() = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0)
    private fun uidTx() = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0)

    private var unsaved = 0
    private fun tick() {
        val s = current
        if (s.state != Tunnel.CONNECTED || !wanted) return
        val rx = uidRx(); val tx = uidTx()
        val rxRate = (rx - lastRx).coerceAtLeast(0); val txRate = (tx - lastTx).coerceAtLeast(0)
        lastRx = rx; lastTx = tx
        TrafficHistory.add(rxRate, txRate)
        if (++unsaved >= 30) flushTraffic()
        setState(s.copy(rx = (rx - baseRx).coerceAtLeast(0), tx = (tx - baseTx).coerceAtLeast(0), rxRate = rxRate, txRate = txRate))
        if (notifySpeed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
            notification(s.profileName + "  ↓ " + formatBytes(rxRate) + L.t("/с  ↑ ", "/s  ↑ ") + formatBytes(txRate) + L.t("/с", "/s")))
    }

    private fun flushTraffic() { unsaved = 0; TrafficHistory.save(this) }

    override fun onRevoke() { wanted = false; setState(TunnelStatus(profileName = current.profileName)); worker.execute { stopCore(); CoreRouter.stopped(this); stopSelf() } }
    override fun onDestroy() { wanted = false; ticker.shutdownNow(); worker.execute { stopCore() }; worker.shutdown(); super.onDestroy() }

    companion object { private const val NOTIFICATION = 7 }
}

/**
 * Daily traffic totals, counted in the tunnel process and kept only on this phone
 * (files/traffic.json, the last 90 days). Nothing is ever sent anywhere.
 */
object TrafficHistory {
    private val pending = HashMap<String, LongArray>()
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    @Synchronized fun add(rx: Long, tx: Long) { val d = pending.getOrPut(today()) { LongArray(2) }; d[0] += rx; d[1] += tx }

    @Synchronized fun save(c: Context) {
        if (pending.isEmpty()) return
        try {
            val all = read(c)
            for ((day, v) in pending) { val o = all.optJSONArray(day); all.put(day, jsonArrayOf((o?.optLong(0) ?: 0) + v[0], (o?.optLong(1) ?: 0) + v[1])) }
            pending.clear()
            val keys = all.keys().asSequence().sorted().toList()
            keys.dropLast(90).forEach { all.remove(it) }
            val f = AtomicFile(File(c.filesDir, "traffic.json")); val out = f.startWrite()
            try { out.write(all.toString().toByteArray()); f.finishWrite(out) } catch (e: IOException) { f.failWrite(out) }
        } catch (_: Exception) {}
    }

    fun read(c: Context): JSONObject = try { AtomicFile(File(c.filesDir, "traffic.json")).let { if (it.baseFile.exists()) JSONObject(String(it.readFully())) else JSONObject() } } catch (_: Exception) { JSONObject() }

    fun clear(c: Context) { File(c.filesDir, "traffic.json").delete() }
}

/** libXray's gomobile bindings, loaded only in the :xray / :xray_probe processes. */
class IsolatedXray(context: Context) {
    private val bridge: Class<*>; private val controller: Class<*>; private val protector: Any

    init {
        val b = bindings(context)
        bridge = b.bridge; controller = b.controller
        protector = Proxy.newProxyInstance(b.loader, arrayOf(controller)) { proxy, method, args ->
            when (method.name) {
                "protectFd" -> protect(context, (args[0] as Number).toLong().toInt())
                "toString" -> "Wraith VPN socket protector"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        bridge.getMethod("registerDialerController", controller).invoke(null, protector)
        bridge.getMethod("registerListenerController", controller).invoke(null, protector)
    }

    fun setDNS(endpoint: String) { bridge.getMethod("setDNS", controller, String::class.java).invoke(null, protector, endpoint) }
    fun resetDNS() { try { bridge.getMethod("resetDNS").invoke(null) } catch (e: Exception) { Log.w(TAG, "resetDNS", e) } }
    fun invoke(request: String): String = bridge.getMethod("invoke", String::class.java).invoke(null, request) as String

    private class Shared(val loader: ClassLoader, val bridge: Class<*>, val controller: Class<*>)

    private class ChildFirst(dex: String, opt: String, libs: String, parent: ClassLoader) : DexClassLoader(dex, opt, libs, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("go.") || name.startsWith("libXray.")) synchronized(this) {
                val c = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(c)
                return c
            }
            return super.loadClass(name, resolve)
        }
    }

    companion object {
        private const val TAG = "WraithXray"
        // One class loader owns one JNI/Go runtime for the lifetime of the process.
        private var shared: Shared? = null

        /** The core's sockets bypass the VPN; before the first VPN consent they are bound to the physical network instead. */
        private fun protect(context: Context, fd: Int): Boolean {
            if (context is VpnService && context.protect(fd)) return true
            val physical = Networks.physical(context)
            if (physical != null) try {
                ParcelFileDescriptor.fromFd(fd).use { physical.bindSocket(it.fileDescriptor) }
                return true
            } catch (e: IOException) { Log.w(TAG, "Unable to bind Xray socket to the underlying network", e) }
            if (!Networks.vpnActive(context)) return true
            Log.w(TAG, "Unable to protect Xray socket while a VPN is active")
            return false
        }

        @Synchronized private fun bindings(context: Context): Shared {
            shared?.let { return it }
            val dex = context.assets.open("libXray-dex.jar").use { input -> ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray() }
            val digest = hex(MessageDigest.getInstance("SHA-256").digest(dex))
            val dir = File(context.codeCacheDir, "xray-bindings")
            if (!dir.exists() && !dir.mkdirs()) throw IOException(L.t("Не удалось создать каталог Xray bindings.", "Could not create the Xray bindings folder."))
            val jar = File(dir, "bindings-$digest.jar")
            if (!jar.isFile || !matches(jar, dex)) {
                if (jar.exists() && !jar.delete()) throw IOException(L.t("Не удалось заменить повреждённые Xray bindings.", "Could not replace damaged Xray bindings."))
                val pending = File.createTempFile("bindings-", ".tmp", dir)
                try {
                    // Android 14+ requires dynamically loaded code to be read-only; the open descriptor still completes the copy.
                    FileOutputStream(pending).use { out ->
                        if (!pending.setReadOnly()) throw IOException(L.t("Не удалось защитить Xray bindings от записи.", "Could not write-protect Xray bindings."))
                        out.write(dex); out.fd.sync()
                    }
                    if (!pending.renameTo(jar)) throw IOException(L.t("Не удалось установить Xray bindings.", "Could not install Xray bindings."))
                } finally { if (pending.exists()) pending.delete() }
            }
            if (!jar.setReadOnly()) throw IOException(L.t("Не удалось защитить Xray bindings от записи.", "Could not write-protect Xray bindings."))
            val loader = ChildFirst(jar.absolutePath, dir.absolutePath, context.applicationInfo.nativeLibraryDir, context.classLoader)
            Class.forName("go.Seq", true, loader).getMethod("setContext", Context::class.java).invoke(null, context.applicationContext)
            return Shared(loader, Class.forName("libXray.LibXray", true, loader), Class.forName("libXray.DialerController", true, loader)).also { shared = it }
        }

        private fun matches(file: File, expected: ByteArray): Boolean = file.length() == expected.size.toLong() && FileInputStream(file).use { it.readBytes().contentEquals(expected) }
    }
}
