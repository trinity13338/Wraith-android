package app.wraith.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Socket
import java.net.URL
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real latency through a server: one short-lived Xray instance per batch in its own process, with
 * one loopback SOCKS inlet per server. The inlets require a per-batch login, so no other app on
 * the phone can borrow them while a test runs.
 */
class XrayProbeService : VpnService() {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private val pending = ArrayDeque<Job>()
    private val cancelledSessions = HashSet<String>()
    private var draining = false; private var closed = false; private var lastStartId = 0
    private var xray: IsolatedXray? = null
    private var localDns: LocalDns? = null

    private class Job(intent: Intent, val receiver: ResultReceiver) {
        val profile: String = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
        val settings: String? = intent.getStringExtra(EXTRA_SETTINGS)
        val url: String = intent.getStringExtra(EXTRA_URL).orEmpty()
        val session: String = intent.getStringExtra(EXTRA_SESSION).orEmpty()
        val method: String = intent.getStringExtra(EXTRA_METHOD) ?: "GET"
        val mode: String = intent.getStringExtra(EXTRA_MODE) ?: "keepalive"
        val timeout: Int = intent.getIntExtra(EXTRA_TIMEOUT, 5000).coerceAtLeast(1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            lastStartId = startId
            if (closed) return START_NOT_STICKY
            if (intent?.action == ACTION_CANCEL) {
                intent.getStringExtra(EXTRA_SESSION)?.let { session ->
                    cancelledSessions += session
                    val it = pending.iterator()
                    while (it.hasNext()) { val job = it.next(); if (job.session == session) { it.remove(); reply(job, -1, L.t("Проверка отменена.", "Test cancelled.")) } }
                }
            } else {
                val receiver = intent?.let { receiver(it) }
                if (intent?.action == ACTION && receiver != null) {
                    val job = Job(intent, receiver)
                    if (job.session in cancelledSessions) reply(job, -1, L.t("Проверка отменена.", "Test cancelled.")) else pending.add(job)
                }
            }
            if (!draining && pending.isNotEmpty()) { draining = true; worker.schedule({ drain() }, 120, TimeUnit.MILLISECONDS) }
            else if (!draining) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun drain() {
        val batch = mutableListOf<Job>()
        synchronized(lock) {
            if (closed) return
            pending.removeFirstOrNull()?.let { first ->
                batch += first
                val it = pending.iterator()
                while (it.hasNext() && batch.size < MAX_BATCH) { val next = it.next(); if (next.timeout == first.timeout && next.url == first.url) { batch += next; it.remove() } }
            }
        }
        val probing = mutableListOf<Job>(); val ports = IdentityHashMap<Job, Int>(); var started = false
        val user = "probe-" + Store.randomSecret(8); val pass = Store.randomSecret(24)
        try {
            val core = xray ?: IsolatedXray(this).also { xray = it }
            core.setDNS((localDns ?: LocalDns.open(this).also { localDns = it }).endpoint)
            batch.filterTo(probing) { !cancelled(it) }
            if (probing.isEmpty()) return
            val runtime = runtimeConfig(probing, ports, core, user, pass)
            if (probing.isEmpty()) return
            val start = JSONObject(core.invoke(jsonOf("apiVersion" to 3, "method" to "runXray", "payload" to jsonOf("xrayJson" to runtime.toString())).toString()))
            if (!start.optBoolean("success")) throw IllegalStateException(start.optString("error", L.t("Не удалось запустить Xray для проверки.", "Could not start Xray for the test.")))
            started = true
            Authenticator.setDefault(object : Authenticator() { override fun getPasswordAuthentication() = PasswordAuthentication(user, pass.toCharArray()) })
            // runXray returns before every SOCKS listener is bound; never race the first request.
            for (job in probing) awaitLoopback(ports[job]!!, job.timeout) { cancelled(job) }
            for (job in probing) job.receiver.send(STARTED, Bundle())
            val checks = Executors.newFixedThreadPool(probing.size.coerceAtMost(MAX_BATCH))
            try {
                val futures = probing.associateWith { job -> checks.submit(Callable { XrayHttpProbe.measure(job.url, job.method, job.mode, job.timeout, ports[job]!!) { cancelled(job) } }) }
                for (job in probing) if (!cancelled(job)) try { reply(job, futures[job]!!.get(job.timeout * 2L + 5000, TimeUnit.MILLISECONDS), "") }
                catch (e: Throwable) { reply(job, -1, ((e as? ExecutionException)?.cause ?: e).safe()) }
            } finally { checks.shutdownNow() }
        } catch (e: Throwable) {
            (if (probing.isEmpty()) batch else probing).filter { !cancelled(it) }.forEach { reply(it, -1, e.safe()) }
        } finally {
            if (started) try { xray?.invoke(jsonOf("apiVersion" to 3, "method" to "stopXray", "payload" to JSONObject()).toString()) } catch (_: Throwable) {}
            Authenticator.setDefault(null)
            xray?.resetDNS()
            synchronized(lock) {
                if (!closed) {
                    if (pending.isNotEmpty()) worker.schedule({ drain() }, 30, TimeUnit.MILLISECONDS) else { draining = false; stopSelf(lastStartId) }
                }
            }
        }
    }

    private fun cancelled(job: Job) = synchronized(lock) { closed || job.session in cancelledSessions }

    private fun runtimeConfig(jobs: MutableList<Job>, ports: MutableMap<Job, Int>, core: IsolatedXray, user: String, pass: String): JSONObject {
        val outbounds = JSONArray(); val inbounds = JSONArray(); val rules = JSONArray(); var firstDns: JSONObject? = null
        val valid = mutableListOf<Job>()
        jobs.forEachIndexed { index, job ->
            try {
                val profile = JSONObject(job.profile); val settings = job.settings?.let { JSONObject(it) } ?: JSONObject()
                val port = freeLoopbackPort()
                val dns = appendProbe(profile, settings, XrayRuntimeConfig.source(profile, core), valid.size, port, outbounds, inbounds, rules, user, pass)
                if (firstDns == null) firstDns = dns
                ports[job] = port; valid += job
            } catch (e: Throwable) { reply(job, -1, e.safe()) }
        }
        jobs.clear(); jobs += valid
        if (valid.isEmpty()) return JSONObject()
        return assemble(inbounds, outbounds, rules, firstDns, XrayAssets.ensure(this).absolutePath)
    }

    override fun onDestroy() {
        synchronized(lock) { closed = true; pending.forEach { reply(it, -1, L.t("Проверка отменена.", "Test cancelled.")) }; pending.clear() }
        worker.execute { xray?.resetDNS(); localDns?.close(); localDns = null }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "app.wraith.client.XRAY_PROBE"; const val ACTION_CANCEL = "app.wraith.client.XRAY_PROBE_CANCEL"
        const val EXTRA_PROFILE = "profile"; const val EXTRA_SETTINGS = "settings"; const val EXTRA_URL = "url"; const val EXTRA_TIMEOUT = "timeout"
        const val EXTRA_METHOD = "method"; const val EXTRA_MODE = "mode"; const val EXTRA_RECEIVER = "receiver"; const val EXTRA_SESSION = "session"
        const val RESULT = 0; const val STARTED = 1
        const val MAX_BATCH = 32

        /** One server (and the dialers it depends on) behind its own authenticated loopback SOCKS inlet. */
        fun appendProbe(profile: JSONObject, settings: JSONObject, source: JSONObject, index: Int, port: Int, outbounds: JSONArray, inbounds: JSONArray, rules: JSONArray, user: String, pass: String): JSONObject? {
            val root = XrayRuntimeConfig.prepare(profile, settings.copy().put("pinCandidate", true), source)
            val byTag = LinkedHashMap<String, JSONObject>()
            for (o in root.optJSONArray("outbounds").objects()) {
                val tag = o.optString("tag"); if (tag.isEmpty()) continue
                if (byTag.put(tag, o) != null) throw IllegalArgumentException(L.t("Повторяется outbound tag: ", "Duplicate outbound tag: ") + tag)
            }
            val selected = XrayRuntimeConfig.proxyTag(root, profile)
            val deps = LinkedHashSet<String>(); collect(selected, byTag, deps, HashSet())
            val renamed = deps.withIndex().associate { (i, t) -> t to "wraith-probe-$index-$i" }
            for (tag in deps) {
                val copy = byTag[tag]!!.copy(); copy.put("tag", renamed[tag])
                copy.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.let { s -> s.optString("dialerProxy").takeIf { it.isNotEmpty() }?.let { s.put("dialerProxy", renamed[it]) } }
                copy.optJSONObject("proxySettings")?.let { l -> renamed[l.optString("tag")]?.let { l.put("tag", it) } }
                outbounds.put(copy)
            }
            val inTag = "wraith-probe-in-$index"
            inbounds.put(jsonOf("tag" to inTag, "listen" to "127.0.0.1", "port" to port, "protocol" to "socks",
                "settings" to jsonOf("auth" to "password", "accounts" to jsonArrayOf(jsonOf("user" to user, "pass" to pass)), "udp" to false)))
            rules.put(jsonOf("type" to "field", "inboundTag" to jsonArrayOf(inTag), "outboundTag" to renamed[selected]))
            return probeDns(root.optJSONObject("dns"))
        }

        fun assemble(inbounds: JSONArray, outbounds: JSONArray, rules: JSONArray, dns: JSONObject?, assets: String): JSONObject {
            // Xray's own DNS queries and anything unmatched leave directly, never through a probed server.
            val ordered = JSONArray().put(jsonOf("tag" to "wraith-probe-direct", "protocol" to "freedom"))
            outbounds.objects().forEach { ordered.put(it) }
            val runtime = jsonOf("log" to jsonOf("loglevel" to "none"), "inbounds" to inbounds, "outbounds" to ordered, "routing" to jsonOf("domainStrategy" to "AsIs", "rules" to rules))
            if (dns != null) runtime.put("dns", dns)
            runtime.put("env", jsonOf("xray.location.asset" to assets))
            return runtime
        }

        /** Only plain resolver addresses from a provider DNS section are useful (and safe) for a probe. */
        fun probeDns(dns: JSONObject?): JSONObject? {
            if (dns == null) return null
            val servers = JSONArray()
            val list = dns.optJSONArray("servers")
            for (i in 0 until (list?.length() ?: 0)) {
                val item = list!!.opt(i)
                val address = (if (item is JSONObject) item.optString("address") else item.toString()).trim()
                if (address.isEmpty() || address.equals("localhost", true) || address.startsWith("fakedns") || "+local" in address) continue
                servers.put(address); if (servers.length() >= 3) break
            }
            if (servers.length() == 0) return null
            val out = jsonOf("servers" to servers)
            dns.optString("queryStrategy").takeIf { it.isNotEmpty() }?.let { out.put("queryStrategy", it) }
            dns.optJSONObject("hosts")?.let { h -> val plain = JSONObject(); h.keys().forEach { k -> if (':' !in k) plain.put(k, h.get(k)) }; if (plain.length() > 0) out.put("hosts", plain) }
            return out
        }

        private fun collect(tag: String, byTag: Map<String, JSONObject>, ordered: LinkedHashSet<String>, visiting: MutableSet<String>) {
            if (tag in ordered) return
            if (!visiting.add(tag)) throw IllegalArgumentException(L.t("Циклическая зависимость Xray outbound: ", "Circular Xray outbound dependency: ") + tag)
            val o = byTag[tag] ?: throw IllegalArgumentException(L.t("Не найдена зависимость Xray outbound: ", "Xray outbound dependency not found: ") + tag)
            o.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")?.takeIf { it.isNotEmpty() }?.let { collect(it, byTag, ordered, visiting) }
            visiting.remove(tag); ordered += tag
        }

        private fun freeLoopbackPort() = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        private fun awaitLoopback(port: Int, timeoutMs: Int, cancelled: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(1000, 5000)
            var last: IOException? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException(L.t("Проверка отменена.", "Test cancelled."))
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; return } catch (e: IOException) { last = e; Thread.sleep(20) }
            }
            throw IOException(L.t("Xray не открыл локальный вход для проверки.", "Xray did not open the local inbound for the test."), last)
        }

        private fun receiver(intent: Intent): ResultReceiver? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RECEIVER)

        private fun reply(job: Job, latency: Int, error: String) = job.receiver.send(RESULT, Bundle().apply { putInt("latency", latency); putString("error", error) })
    }
}

/** The via-proxy HTTP request of a probe, through the batch's temporary SOCKS inlet. */
object XrayHttpProbe {
    fun measure(url: String, method: String, mode: String, timeoutMs: Int, socksPort: Int, cancelled: () -> Boolean): Int {
        require(method == "GET" || method == "HEAD") { L.t("Неподдерживаемый HTTP-метод проверки.", "Unsupported HTTP test method.") }
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        var best = Long.MAX_VALUE; var last: Exception? = null
        for (i in 0 until 2) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException(L.t("Проверка отменена.", "Test cancelled."))
            var c: HttpURLConnection? = null
            val started = SystemClock.elapsedRealtime()
            try {
                c = URL(url).openConnection(proxy) as HttpURLConnection
                c.requestMethod = method; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false; c.useCaches = false
                if (mode == "double") c.setRequestProperty("Connection", "close")
                val code = c.responseCode
                if (code !in 100..599) throw IOException(L.t("Сервер не вернул HTTP-ответ.", "The server returned no HTTP response."))
                if (method == "GET") (if (code >= 400) c.errorStream else c.inputStream)?.use { input -> val buf = ByteArray(8192); var total = 0; while (total < 1 shl 20) { val n = input.read(buf); if (n < 0) break; total += n } }
                best = minOf(best, (SystemClock.elapsedRealtime() - started).coerceAtLeast(1))
            } catch (e: Exception) { last = e; break } finally { if (mode == "double") c?.disconnect() }
        }
        if (best == Long.MAX_VALUE) throw last ?: IOException(L.t("Xray не получил ответ.", "Xray got no answer."))
        return best.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Latency of one server by the method chosen in the settings. */
object Ping {
    fun effectiveType(profile: JSONObject, settings: JSONObject): String {
        val type = settings.optString("pingType", "proxyGet")
        val t = profile.optJSONObject("outbound")?.optString("type")
        // UDP protocols have no TCP port to knock on.
        return if (type == "tcp" && t in setOf("hysteria", "wireguard")) "proxyGet" else type
    }

    fun label(type: String) = when (type) { "proxyGet" -> "Proxy GET"; "proxyHead" -> "Proxy HEAD"; "icmp" -> "ICMP"; else -> "TCP" }

    fun measure(c: Context, p: JSONObject, settings: JSONObject, session: String, cancelled: AtomicBoolean): Int {
        val type = effectiveType(p, settings)
        val timeout = settings.optInt("pingTimeout", 5000)
        if (type.startsWith("proxy")) {
            val url = settings.optString("pingUrl", "https://www.gstatic.com/generate_204")
            val method = if (type == "proxyHead") "HEAD" else "GET"
            val mode = settings.optString("pingMode", "keepalive")
            val candidates = p.optJSONArray("xrayCandidates")
            if (candidates != null && candidates.length() > 1) {
                // Every server of an auto config at once; the best one is the row's latency.
                val probeSettings = settings.copy().put("autoSelect", false).put("pinCandidate", true)
                val jobs = candidates.objects().map { cand -> Callable { val one = p.copy(); one.put("outbound", cand); one.remove("xrayCandidates"); viaXray(c, one, probeSettings, url, method, mode, timeout, session, cancelled) } }
                return bestOf(jobs, cancelled)
            }
            return viaXray(c, p, settings, url, method, mode, timeout, session, cancelled)
        }
        val o = p.getJSONObject("outbound")
        val host = o.getString("server")
        // With the VPN on, Wraith's own sockets would enter the tunnel; measurements must leave
        // through the phone's real network to say anything about the server.
        val network = Networks.physical(c)
        val address = network?.getAllByName(host)?.firstOrNull() ?: InetAddress.getByName(host)
        if (type == "icmp") return icmp(address, timeout, network)
        val start = SystemClock.elapsedRealtime()
        Socket().use { s -> network?.bindSocket(s); s.connect(InetSocketAddress(address, o.getInt("server_port")), timeout) }
        return (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
    }

    /** ICMP echo over an unprivileged ping socket, bound to the physical network. */
    private fun icmp(address: InetAddress, timeout: Int, network: android.net.Network?): Int {
        val v6 = address is java.net.Inet6Address
        val fd = Os.socket(if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET, OsConstants.SOCK_DGRAM, if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP)
        try {
            network?.bindSocket(fd)
            val packet = ByteArray(16).also { it[0] = if (v6) 128.toByte() else 8; it[6] = 0; it[7] = 1 }
            val start = SystemClock.elapsedRealtime()
            Os.sendto(fd, packet, 0, packet.size, 0, address, 0)
            val poll = android.system.StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            while (true) {
                val left = timeout - (SystemClock.elapsedRealtime() - start)
                if (left <= 0 || Os.poll(arrayOf(poll), left.toInt()) <= 0) throw IOException(L.t("ICMP: нет ответа", "ICMP: no reply"))
                val reply = ByteArray(64)
                val n = Os.recvfrom(fd, reply, 0, reply.size, 0, null)
                if (n >= 8 && reply[0] == (if (v6) 129.toByte() else 0.toByte())) return (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
            }
        } catch (e: android.system.ErrnoException) { throw IOException(L.t("ICMP недоступен или заблокирован", "ICMP is unavailable or blocked"), e) }
        finally { Os.close(fd) }
    }

    private fun bestOf(jobs: List<Callable<Int>>, cancelled: AtomicBoolean): Int {
        val pool = Executors.newFixedThreadPool(jobs.size.coerceAtMost(12))
        try {
            var best = Int.MAX_VALUE; var last: Throwable? = null
            for (f: Future<Int> in pool.invokeAll(jobs)) {
                if (cancelled.get()) throw InterruptedException(L.t("Проверка отменена.", "Test cancelled."))
                try { val ms = f.get(); if (ms > 0) best = minOf(best, ms) } catch (e: ExecutionException) { last = e.cause ?: e }
            }
            if (best != Int.MAX_VALUE) return best
            throw IOException(L.t("Не ответил ни один сервер из пула (", "No server in the pool answered (") + jobs.size + "). " + (last?.safe() ?: ""), last)
        } finally { pool.shutdownNow() }
    }

    private fun viaXray(c: Context, profile: JSONObject, settings: JSONObject, url: String, method: String, mode: String, timeout: Int, session: String, cancelled: AtomicBoolean): Int {
        val started = CountDownLatch(1); val done = CountDownLatch(1)
        val latency = AtomicReference<Int>(); val failure = AtomicReference<String>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if (cancelled.get()) return
                if (code == XrayProbeService.STARTED) { started.countDown(); return }
                latency.set(data?.getInt("latency", -1) ?: -1); failure.set(data?.getString("error").orEmpty())
                done.countDown(); started.countDown()
            }
        }
        if (cancelled.get()) throw InterruptedException(L.t("Проверка отменена.", "Test cancelled."))
        c.startService(Intent(c, XrayProbeService::class.java).setAction(XrayProbeService.ACTION)
            .putExtra(XrayProbeService.EXTRA_PROFILE, profile.toString()).putExtra(XrayProbeService.EXTRA_URL, url)
            .putExtra(XrayProbeService.EXTRA_SETTINGS, settings.toString()).putExtra(XrayProbeService.EXTRA_SESSION, session)
            .putExtra(XrayProbeService.EXTRA_TIMEOUT, timeout).putExtra(XrayProbeService.EXTRA_METHOD, method).putExtra(XrayProbeService.EXTRA_MODE, mode)
            .putExtra(XrayProbeService.EXTRA_RECEIVER, receiver))
        // Waiting for a previous batch is not server latency; the deadline starts with this batch.
        if (!started.await(60, TimeUnit.SECONDS)) throw IOException(L.t("Очередь проверки Xray не ответила.", "The Xray test queue did not answer."))
        if (!done.await(timeout * 2L + 5000L, TimeUnit.MILLISECONDS)) throw IOException(L.t("Xray не завершил проверку за отведённое время.", "Xray did not finish the test in time."))
        val ms = latency.get()
        if (ms == null || ms < 0) throw IOException(failure.get().orEmpty().ifEmpty { L.t("Xray не получил ответ от сервера.", "Xray got no answer from the server.") })
        return ms
    }

    fun cancel(c: Context, session: String) {
        try { c.startService(Intent(c, XrayProbeService::class.java).setAction(XrayProbeService.ACTION_CANCEL).putExtra(XrayProbeService.EXTRA_SESSION, session)) } catch (_: Exception) {}
    }

    fun shortError(error: String): String {
        val e = error.lowercase()
        return when {
            "reality" in e -> L.t("Reality: проверка не пройдена", "Reality: verification failed")
            "timeout" in e || "timed out" in e || "deadline" in e || "нет ответа" in e || "no answer" in e -> L.t("Нет ответа", "No answer")
            "refused" in e -> L.t("Отклонено", "Refused")
            "certificate" in e || "x509" in e -> L.t("Ошибка TLS", "TLS error")
            "icmp" in e -> L.t("ICMP недоступен", "ICMP unavailable")
            else -> L.t("Ошибка", "Error")
        }
    }
}
