package app.wraith.client

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Subscription download and refresh. While the VPN is on, Wraith itself goes through the tunnel,
 * so a provider address blocked on the local network still updates.
 */
object Subscriptions {
    /** Remnawave returns per-host Xray JSON for this Happ Android signature. */
    const val USER_AGENT = "Happ/4.4.1/Android"
    private class CompactResponse : IOException()

    fun create(c: Context, url: String): JSONObject {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host == null || uri.userInfo != null) throw IllegalArgumentException(L.t("Подписка должна быть обычной HTTPS-ссылкой без логина в адресе.", "The subscription must be a plain HTTPS link without a login in the address."))
        val sub = jsonOf("id" to UUID.randomUUID().toString(), "url" to url, "name" to uri.host, "interval" to 24, "updated" to 0)
        update(c, sub)
        return sub
    }

    fun update(c: Context, original: JSONObject) {
        val sub = original.copy()
        try { fetchAutomatic(c, sub, sub.getString("url")) } catch (primary: Exception) {
            // Happ behaviour: an unavailable main URL falls back to the backup one.
            val fallback = sub.optString("fallback-url")
            if (fallback.isEmpty() || fallback == sub.optString("url")) throw primary
            AppLog.add(L.t("Основной адрес подписки недоступен, пробуем резервный: ", "The main subscription address is unavailable, trying the fallback: ") + primary.safe())
            fetchAutomatic(c, sub, fallback)
        }
    }

    private fun fetchAutomatic(c: Context, sub: JSONObject, url: String) {
        val store = Store.get(c)
        val custom = store.settings().optString("userAgent").trim()
        if (custom.isNotEmpty()) { fetch(c, sub, url, custom, true); return }
        val id = clientNumericId(store)
        try { fetch(c, sub, url, "$USER_AGENT/$id", false) } catch (_: CompactResponse) {
            // Some panels expose their complete per-host Xray document only to this signature.
            fetch(c, sub, url, "Happ/4.4.1/Android/$id", true)
        }
    }

    private fun clientNumericId(store: Store): String {
        var id = store.settings().optString("clientNumericId")
        if (!id.matches(Regex("[0-9]{19,20}"))) {
            id = java.lang.Long.toUnsignedString(SecureRandom().nextLong()).padStart(19, '0')
            store.setting("clientNumericId", id)
        }
        return id
    }

    private fun fetch(c: Context, sub: JSONObject, startUrl: String, userAgent: String, acceptCompact: Boolean) {
        val store = Store.get(c)
        var url = startUrl
        var connection: HttpURLConnection? = null
        try {
            repeat(6) {
                val u = URL(url)
                if (u.protocol != "https") throw IOException(L.t("Небезопасное перенаправление подписки на HTTP отклонено.", "Insecure redirect of the subscription to HTTP was refused."))
                val settings = store.settings()
                val conn = (u.openConnection() as HttpURLConnection).also { connection = it }
                val timeout = settings.optInt("subTimeout", 10).coerceIn(5, 15)
                conn.connectTimeout = timeout * 1000; conn.readTimeout = timeout * 2000; conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("X-Wraith-Version", WraithApp.VERSION)
                if (settings.optBoolean("sendHwid", true)) {
                    var hwid = settings.optString("deviceId")
                    if (hwid.isEmpty()) { hwid = UUID.randomUUID().toString(); store.setting("deviceId", hwid) }
                    conn.setRequestProperty("X-Hwid", hwid); conn.setRequestProperty("X-Device-Os", "Android")
                    conn.setRequestProperty("X-Ver-Os", android.os.Build.VERSION.RELEASE); conn.setRequestProperty("X-Device-Model", android.os.Build.MODEL)
                }
                val status = conn.responseCode
                if (status in 300..399) {
                    val target = conn.getHeaderField("Location") ?: throw IOException(L.t("Пустое перенаправление.", "Empty redirect."))
                    url = URL(u, target).toString(); conn.disconnect(); connection = null
                    return@repeat
                }
                if (status != 200) throw IOException(L.t("Сервер подписки вернул HTTP ", "The subscription server returned HTTP ") + status + ".")
                var body = String(conn.inputStream.bounded(), Charsets.UTF_8)
                conn.getHeaderField("encrypt-tag")?.let { tag -> body = decryptHapp(u, body, tag) }
                if (!body.contains("://") && !body.trim().startsWith("{") && !body.trim().startsWith("[")) try { body = ImportParser.base64(body) } catch (_: Exception) {}
                val response = body.trim()
                val compact = !response.startsWith("{") && !response.startsWith("[") && listOf("vless://", "vmess://", "trojan://", "hy2://", "hysteria2://").any { it in response }
                if (compact && !acceptCompact) throw CompactResponse()
                body.lines().map { it.trim() }.firstOrNull { it.startsWith("happ://routing/") }?.let { sub.put("routing", it) }
                val meta = HashMap<String, String>()
                for (line in body.lines()) if (line.startsWith("#") && ':' in line) meta[line.substring(1, line.indexOf(':')).trim().lowercase(Locale.ROOT)] = line.substring(line.indexOf(':') + 1).trim()
                val skipped = mutableListOf<String>()
                val parsed = ImportParser.parse(body, skipped)
                sub.put("format", if (response.startsWith("[") || response.startsWith("{")) "json" else "links")
                if (skipped.isEmpty()) sub.remove("skipped") else {
                    sub.put("skipped", L.t("Пропущено серверов: ", "Servers skipped: ") + skipped.size + ". " + skipped[0])
                    AppLog.add(L.t("Подписка «", "Subscription «") + sub.optString("name") + L.t("»: пропущено ", "»: skipped ") + skipped.size + ". " + skipped[0])
                }
                // Documented Happ subscription parameters, as headers or "#key: value" body lines.
                for (key in listOf("profile-title", "subscription-userinfo", "profile-update-interval", "support-url", "profile-web-page-url", "announce", "routing",
                    "routing-enable", "fallback-url", "new-url", "new-domain", "sub-info-text", "sub-info-color", "sub-info-button-text", "sub-info-button-link", "sub-expire", "sub-expire-button-link")) {
                    var value = conn.getHeaderField(key) ?: meta[key] ?: continue
                    if (value.startsWith("base64:")) value = ImportParser.base64(value.substring(7))
                    when (key) {
                        "profile-title" -> sub.put("name", value.take(25))
                        "profile-update-interval" -> value.trim().toIntOrNull()?.let { sub.put("interval", it.coerceIn(1, 168)) }
                        "routing-enable" -> sub.put("routingEnabled", enabled(value))
                        "new-url", "fallback-url" -> value.trim().takeIf { it.startsWith("https://") }?.let { sub.put(key, it) }
                        "new-domain" -> value.trim().takeIf { it.matches(Regex("[A-Za-z0-9.-]+")) }?.let { sub.put(key, it) }
                        "sub-info-text" -> sub.put(key, value.take(200))
                        "sub-expire" -> sub.put(key, enabled(value))
                        else -> sub.put(key, value)
                    }
                }
                val moved = sub.optString("new-url")
                if (moved.isNotEmpty() && moved != sub.optString("url")) { sub.put("url", moved); sub.remove("new-url"); AppLog.add(L.t("Провайдер сменил адрес подписки «", "The provider changed the address of subscription «") + sub.optString("name") + "».") }
                val newDomain = sub.optString("new-domain")
                if (newDomain.isNotEmpty()) {
                    val current = URL(sub.getString("url"))
                    if (newDomain != current.host) sub.put("url", URL(current.protocol, newDomain, current.port, current.file).toString())
                    sub.remove("new-domain")
                }
                sub.put("updated", System.currentTimeMillis()); sub.put("parsed", Store.PARSE_VERSION); sub.remove("error")
                store.replaceSubscription(sub, parsed)
                UpdateJob.schedule(c)
                // A running tunnel keeps the rules it started with; restart when they changed.
                val selected = store.selected()
                if (Tunnel.status.value.state == Tunnel.CONNECTED && selected != null && Tunnel.routingDiffers(c, selected)) {
                    AppLog.add(L.t("Правила подписки «", "Rules of subscription «") + sub.optString("name") + L.t("» изменились — перезапуск туннеля.", "» changed — restarting the tunnel."))
                    CoreRouter.restart(c)
                }
                return
            }
            throw IOException(L.t("Слишком много перенаправлений подписки.", "Too many subscription redirects."))
        } finally { connection?.disconnect() }
    }

    /** Happ's "encrypt-tag" responses with the documented public key02. */
    private fun decryptHapp(u: URL, body: String, encrypt: String): String {
        if (ImportParser.query(u.query)["key"].orEmpty() != "key02") throw IOException(L.t("Неизвестный ключ шифрования. Нужна обычная подписка.", "Unknown encryption key. A plain subscription is required."))
        val data = Base64.getDecoder().decode(body.trim()); val tag = Base64.getDecoder().decode(encrypt)
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec("key02:+]%4ij#P\"/".toByteArray(), "AES"), GCMParameterSpec(128, "kkkkkkkkkkkk".toByteArray()))
        return String(aes.doFinal(data + tag), Charsets.UTF_8)
    }

    fun updateAll(c: Context, onlyDue: Boolean) {
        val store = Store.get(c)
        val forced = store.settings().optInt("updateInterval", 0)
        for (sub in store.subscriptions()) {
            val stale = sub.optInt("parsed", 0) < Store.PARSE_VERSION
            val hours = if (forced > 0) forced.toLong() else sub.optLong("interval", 24).coerceAtLeast(1)
            if (onlyDue && !stale && sub.optLong("updated") > 0 && System.currentTimeMillis() - sub.optLong("updated") < hours * 3600000L) continue
            try { update(c, sub) } catch (e: Exception) {
                store.updateSubscription(sub.optString("id")) { it.put("error", e.safe()) }
                AppLog.add(L.t("Обновление подписки: ", "Subscription update: ") + e.safe())
            }
        }
    }

    /** Happ convention: "true" or "1" enables, any other non-empty value disables. */
    fun enabled(value: String?): Boolean { val v = value.orEmpty().trim().lowercase(Locale.ROOT); return v.isEmpty() || v == "true" || v == "1" }

    /** "upload=..; download=..; total=..; expire=.." */
    fun usage(info: String): Map<String, Long> = info.split(';').mapNotNull { p -> p.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { kv -> kv[1].trim().toLongOrNull()?.let { kv[0].trim() to it } } }.toMap()
}

/**
 * Updates come from the project's GitHub Releases; the APK must match the SHA-256 GitHub computed
 * on upload, and Android itself refuses an APK signed with another key.
 */
object UpdateChecker {
    const val REPO = "trinity13338/Wraith-android"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private val HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    class Result(val name: String, val url: String, val sha256: String, val notes: String, val available: Boolean)

    fun check(): Result {
        val connection = open(LATEST_URL, 8000, 12000)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            when (val status = connection.responseCode) {
                404 -> throw IOException(L.t("На GitHub пока нет опубликованных релизов", "There are no published releases on GitHub yet"))
                403, 429 -> throw IOException(L.t("GitHub временно ограничил запросы, попробуйте позже", "GitHub is rate limiting requests, try later"))
                200 -> {}
                else -> throw IOException(L.t("GitHub вернул HTTP ", "GitHub returned HTTP ") + status)
            }
            val data = connection.inputStream.bounded(512 * 1024)
            return parse(String(data, Charsets.UTF_8), WraithApp.VERSION)
        } finally { connection.disconnect() }
    }

    fun parse(body: String, current: String): Result {
        val json = JSONObject(body)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw IOException(L.t("Последний релиз ещё не опубликован", "The latest release is not published yet"))
        val name = json.getString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (!name.matches(Regex("[0-9]+(\\.[0-9]+){1,3}"))) throw IOException(L.t("Некорректная версия в релизе: ", "Invalid version in the release: ") + name)
        val apk = json.optJSONArray("assets").objects().firstOrNull { it.optString("name").lowercase(Locale.ROOT).endsWith(".apk") }
            ?: throw IOException(L.t("В релизе $name нет APK", "Release $name has no APK"))
        val url = apk.getString("browser_download_url").trim()
        val uri = URI(url)
        if (!"https".equals(uri.scheme, true) || !"github.com".equals(uri.host, true) || uri.userInfo != null || !uri.path.lowercase(Locale.ROOT).startsWith("/${REPO.lowercase(Locale.ROOT)}/releases/download/"))
            throw IOException(L.t("APK должен лежать в релизах ", "The APK must be in the releases of ") + REPO)
        val digest = apk.optString("digest").trim()
        if (!digest.lowercase(Locale.ROOT).startsWith("sha256:")) throw IOException(L.t("GitHub не сообщил SHA-256 для APK", "GitHub did not report the APK SHA-256"))
        val sha = digest.substring(7).uppercase(Locale.ROOT)
        if (!sha.matches(Regex("[0-9A-F]{64}"))) throw IOException(L.t("Некорректная SHA-256 в релизе", "Invalid SHA-256 in the release"))
        var notes = json.optString("body").split(Regex("(?m)^#+\\s*Установка"))[0].replace(Regex("(?m)^#+\\s*"), "").replace("`", "").replace("**", "").trim()
        if (notes.length > 500) notes = notes.take(500).trim() + "…"
        return Result(name, url, sha, notes, compare(name, current) > 0)
    }

    /** Numeric dotted-version comparison: 0.10.41 > 0.10.9; a suffix like "-debug" is ignored. */
    fun compare(a: String, b: String): Int {
        val x = a.replace(Regex("[^0-9.].*$"), "").split('.'); val y = b.replace(Regex("[^0-9.].*$"), "").split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)?.toLongOrNull() ?: 0; val q = y.getOrNull(i)?.toLongOrNull() ?: 0
            if (p != q) return if (p < q) -1 else 1
        }
        return 0
    }

    fun download(context: Context, result: Result, progress: (Long, Long) -> Unit): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException(L.t("Хранилище загрузок недоступно", "Download storage unavailable"))
        if (!dir.exists() && !dir.mkdirs()) throw IOException(L.t("Не удалось создать каталог загрузок", "Could not create the download folder"))
        val target = File(dir, "Wraith-" + result.name.replace(Regex("[^0-9A-Za-z._-]"), "_") + ".apk")
        val part = File(dir, target.name + ".part")
        var connection = open(result.url, 10000, 30000)
        try {
            var hops = 0
            while (true) {
                connection.setRequestProperty("Accept", "application/octet-stream")
                val status = connection.responseCode
                if (status in 300..399) {
                    if (++hops > 5) throw IOException(L.t("Слишком много перенаправлений при загрузке APK", "Too many redirects while downloading the APK"))
                    val location = connection.getHeaderField("Location") ?: throw IOException(L.t("GitHub не указал адрес APK", "GitHub did not give the APK address"))
                    val next = URL(connection.url, location).toString(); connection.disconnect(); connection = open(next, 10000, 30000); continue
                }
                if (status != 200) throw IOException(L.t("Сервер APK вернул HTTP ", "The APK server returned HTTP ") + status)
                break
            }
            val declared = connection.contentLengthLong
            if (declared > 250L * 1024 * 1024) throw IOException(L.t("APK больше 250 МБ", "The APK is larger than 250 MB"))
            val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
            progress(0, declared)
            BufferedInputStream(connection.inputStream).use { input -> BufferedOutputStream(FileOutputStream(part)).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > 250L * 1024 * 1024) throw IOException(L.t("APK больше 250 МБ", "The APK is larger than 250 MB"))
                    digest.update(buf, 0, n); out.write(buf, 0, n); progress(total, declared)
                }
            } }
            if (hex(digest.digest(), upper = true) != result.sha256) { part.delete(); throw IOException(L.t("SHA-256 загруженного APK не совпадает", "SHA-256 of the downloaded APK does not match")) }
            if (target.exists() && !target.delete()) throw IOException(L.t("Не удалось заменить старый APK", "Could not replace the old APK"))
            if (!part.renameTo(target)) throw IOException(L.t("Не удалось завершить загрузку APK", "Could not finish downloading the APK"))
            return target
        } finally { connection.disconnect(); if (part.exists()) part.delete() }
    }

    /** HTTPS to an allowed GitHub host only; only the app version is sent. */
    private fun open(url: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection {
        val u = URL(url)
        if (!"https".equals(u.protocol, true) || u.host?.lowercase(Locale.ROOT) !in HOSTS || u.userInfo != null) throw IOException(L.t("Обновления загружаются только с GitHub по HTTPS", "Updates are downloaded only from GitHub over HTTPS"))
        return (u.openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout; this.readTimeout = readTimeout; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Wraith/${WraithApp.VERSION}/Android")
        }
    }
}

/** Offline bootstrap and silent daily refresh of Xray's geoip/geosite files. */
object XrayAssets {
    private val NAMES = arrayOf("geoip.dat", "geosite.dat")
    private val URLS = arrayOf("https://github.com/v2fly/geoip/releases/latest/download/geoip.dat", "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat")
    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val UPDATE_INTERVAL = 24L * 60 * 60 * 1000
    private val UPDATING = AtomicBoolean()
    @Volatile private var bundledGeneration: String? = null

    /** No network: a first connection can use geosite/geoip rules immediately. */
    @Synchronized fun ensure(context: Context): File {
        val dir = directory(context)
        RandomAccessFile(File(dir, ".lock"), "rw").use { lockFile -> lockFile.channel.lock().use {
            val generation = bundleGeneration(context)
            val newBundle = generation != readMarker(File(dir, ".bundle-generation"))
            for (name in NAMES) {
                val target = File(dir, name)
                if (newBundle || !valid(target)) context.assets.open("xray-geodata/$name").use { input ->
                    val pending = File.createTempFile("$name-", ".tmp", dir)
                    try { copy(input, pending); if (!valid(pending)) throw IOException(L.t("Повреждён встроенный файл Xray GeoData.", "The bundled Xray GeoData file is damaged.")); replace(pending, target) }
                    finally { pending.delete() }
                }
            }
            if (newBundle) { writeMarker(File(dir, ".bundle-generation"), generation); writeMarker(File(dir, ".update-attempt"), "0") }
        } }
        return dir
    }

    fun scheduleUpdate(context: Context) {
        val app = context.applicationContext
        if (!UPDATING.compareAndSet(false, true)) return
        WraithApp.IO.execute {
            try {
                val dir = ensure(app); val now = System.currentTimeMillis()
                val last = readMarker(File(dir, ".update-attempt")).toLongOrNull() ?: 0
                if (last in 1..now && now - last < UPDATE_INTERVAL) return@execute
                writeMarker(File(dir, ".update-attempt"), now.toString())
                for (i in NAMES.indices) try { refresh(dir, NAMES[i], URLS[i]) } catch (_: Exception) {}
            } catch (_: Exception) {} finally { UPDATING.set(false) }
        }
    }

    private fun refresh(dir: File, name: String, address: String) {
        val checksum = open("$address.sha256sum").use { String(it.bounded(4096), Charsets.UTF_8) }.trim().split(Regex("\\s+"))[0].lowercase(Locale.ROOT)
        if (!checksum.matches(Regex("[0-9a-f]{64}"))) throw IOException("Invalid GeoData checksum.")
        val pending = File.createTempFile("$name-", ".download", dir)
        try {
            open(address).use { copy(it, pending) }
            if (!valid(pending) || checksum != hash(pending)) throw IOException("Invalid GeoData download.")
            synchronized(this) { RandomAccessFile(File(dir, ".lock"), "rw").use { f -> f.channel.lock().use { replace(pending, File(dir, name)) } } }
        } finally { pending.delete() }
    }

    private fun open(address: String): InputStream {
        var url = URL(address)
        repeat(6) {
            if (!"https".equals(url.protocol, true)) throw IOException("GeoData requires HTTPS.")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Wraith/${WraithApp.VERSION}")
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); url = URL(url, loc ?: throw IOException("Invalid GeoData redirect.")); return@repeat }
            if (code != 200) { c.disconnect(); throw IOException("GeoData HTTP $code") }
            if (c.contentLengthLong > MAX_BYTES) { c.disconnect(); throw IOException("GeoData too large.") }
            return object : FilterInputStream(c.inputStream) { override fun close() { try { super.close() } finally { c.disconnect() } } }
        }
        throw IOException("Too many GeoData redirects.")
    }

    /** GeoIPList / GeoSiteList: non-empty repeated length-delimited protobuf messages. */
    fun valid(file: File): Boolean {
        if (!file.isFile || file.length() < 1024 || file.length() > MAX_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { input ->
                var entries = 0
                while (input.filePointer < input.length()) {
                    if (varint(input) != 10L) return false
                    val length = varint(input); val start = input.filePointer; val end = start + length
                    if (length <= 0 || end < start || end > input.length() || varint(input) != 10L) return false
                    val label = varint(input)
                    if (label <= 0 || label > 128 || input.filePointer + label > end) return false
                    for (i in 0 until label) { val v = input.read(); if (v < 33 || v > 126) return false }
                    input.seek(end); entries++
                }
                entries > 0
            }
        } catch (_: IOException) { false }
    }

    private fun varint(input: RandomAccessFile): Long {
        var value = 0L; var shift = 0
        while (shift < 63) { val next = input.read(); if (next < 0) throw EOFException(); value = value or ((next and 127).toLong() shl shift); if (next and 128 == 0) return value; shift += 7 }
        throw IOException("Invalid protobuf length.")
    }

    private fun directory(context: Context): File = File(context.filesDir, "core/xray-geodata").also { if (!it.isDirectory && !it.mkdirs()) throw IOException(L.t("Не удалось создать каталог Xray GeoData.", "Could not create the Xray GeoData folder.")) }

    private fun bundleGeneration(context: Context): String = bundledGeneration ?: MessageDigest.getInstance("SHA-256").let { d ->
        for (name in NAMES) context.assets.open("xray-geodata/$name").use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        hex(d.digest()).also { bundledGeneration = it }
    }

    private fun copy(input: InputStream, output: File) = FileOutputStream(output).use { out ->
        val buf = ByteArray(32768); var total = 0L
        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) throw IOException("GeoData too large."); out.write(buf, 0, n) }
        out.fd.sync()
    }

    private fun replace(pending: File, target: File) { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    private fun hash(file: File): String { val d = MessageDigest.getInstance("SHA-256"); FileInputStream(file).use { i -> val b = ByteArray(32768); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }; return hex(d.digest()) }
    private fun readMarker(f: File) = try { String(Files.readAllBytes(f.toPath())).trim() } catch (_: IOException) { "" }
    private fun writeMarker(f: File, value: String) { val p = File.createTempFile("marker-", ".tmp", f.parentFile); try { Files.write(p.toPath(), value.toByteArray()); replace(p, f) } finally { p.delete() } }

}
