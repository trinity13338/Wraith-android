package app.wraith.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** UI language follows the system: Russian for ru/uk/be/kk, English otherwise. */
object L {
    @Volatile private var english: Boolean? = null
    fun en(): Boolean = english ?: Locale.getDefault().language.let { it !in setOf("ru", "uk", "be", "kk") }.also { english = it }
    fun reset() { english = null }
    fun t(ru: String, en: String): String = if (en()) en else ru
    fun plural(n: Long, one: String, few: String, many: String): String {
        if (en()) return if (n == 1L) one else many
        val m = n % 100
        if (m in 11..14) return many
        return when (n % 10) { 1L -> one; 2L, 3L, 4L -> few; else -> many }
    }
}

// ---- org.json helpers -------------------------------------------------------------------------

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { for ((k, v) in pairs) put(k, v ?: JSONObject.NULL) }
fun jsonArrayOf(vararg values: Any?): JSONArray = JSONArray().apply { values.forEach { put(it) } }
fun JSONObject.copy(): JSONObject = JSONObject(toString())
fun JSONArray.copy(): JSONArray = JSONArray(toString())
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { opt(it) }.filter { it != null && it != JSONObject.NULL }.map { it.toString().trim() }.filter { it.isNotEmpty() }
fun Iterable<Any?>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

/** First non-blank value of the given keys, trimmed; "" when none. */
fun JSONObject?.str(vararg keys: String): String {
    if (this == null) return ""
    for (key in keys) {
        val value = opt(key)
        if (value != null && value != JSONObject.NULL && value.toString().isNotBlank()) return value.toString().trim()
    }
    return ""
}

/** Child object, created when missing. */
fun JSONObject.child(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }
fun JSONObject.childArray(key: String): JSONArray = optJSONArray(key) ?: JSONArray().also { put(key, it) }

/** A string or an array of strings, flattened. */
fun JSONObject.list(vararg keys: String): JSONArray {
    val out = JSONArray()
    for (key in keys) when (val value = opt(key)) {
        is JSONArray -> value.strings().forEach { out.put(it) }
        null, JSONObject.NULL -> {}
        else -> value.toString().trim().takeIf { it.isNotEmpty() }?.let { out.put(it) }
    }
    return out
}

fun hex(bytes: ByteArray, upper: Boolean = false): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) sb.append(String.format(Locale.ROOT, if (upper) "%02X" else "%02x", b.toInt() and 255))
    return sb.toString()
}

const val MAX_DOWNLOAD = 8 * 1024 * 1024

/** Reads at most 8 MB. */
fun InputStream.bounded(limit: Int = MAX_DOWNLOAD): ByteArray = use { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size() + n > limit) throw IOException(L.t("Размер файла превышает 8 МБ.", "The file is larger than 8 MB."))
        out.write(buf, 0, n)
    }
    out.toByteArray()
}

/** Error text for the UI and the log: links and UUIDs are never echoed back. */
fun Throwable.safe(): String {
    val msg = message ?: return javaClass.simpleName
    return msg.replace(Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+"), L.t("[адрес скрыт]", "[address hidden]"))
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}"), "[id]")
}

fun isIpLiteral(value: String?): Boolean = value != null && value.matches(Regex("[0-9a-fA-F:.]+")) && value.matches(Regex(".*[.:].*"))

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b " + L.t("Б", "B")
    b < 1024 * 1024 -> String.format(Locale.ROOT, L.t("%.1f КБ", "%.1f KB"), b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, L.t("%.1f МБ", "%.1f MB"), b / 1048576.0)
    else -> String.format(Locale.ROOT, L.t("%.2f ГБ", "%.2f GB"), b / 1073741824.0)
}

fun formatDuration(secs: Long): String = String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)

/**
 * In-memory log of one process. The tunnel runs in :xray, so that process mirrors its lines into a
 * file the Log page reads.
 */
object AppLog {
    private val lines = ArrayDeque<String>()
    private var mirror: File? = null

    @Synchronized fun mirrorTo(file: File) {
        mirror = file
        try {
            file.parentFile?.mkdirs()
            if (file.length() > 256 * 1024) FileOutputStream(file, false).use { }
        } catch (_: IOException) {}
    }

    @Synchronized fun add(text: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + "  " + text
        lines.addLast(line)
        while (lines.size > 200) lines.removeFirst()
        mirror?.let { f -> try { FileOutputStream(f, true).use { it.write("$line\n".toByteArray()) } } catch (_: IOException) {} }
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}
