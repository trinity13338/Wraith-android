package app.wraith.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Profiles, subscriptions, rules and settings in one AES-GCM document whose key lives in the
 * Android Keystore. The UI mutates the cached document; the tunnel process reads fresh snapshots.
 */
class Store private constructor(private val context: Context, initial: JSONObject?, private val memory: Boolean) {
    private var state: JSONObject
    private val _revision = MutableStateFlow(0L)
    /** Bumped on every change, so the interface can recompose. */
    val revision: StateFlow<Long> = _revision

    init {
        state = initial ?: try {
            val f = file(context)
            val loaded = if (f.baseFile.exists()) decrypt(f.readFully()) else empty()
            loaded
        } catch (e: Exception) {
            throw IllegalStateException(L.t("Не удалось открыть зашифрованное хранилище. Данные сохранены; не очищайте приложение.", "Could not open the encrypted storage. Your data is kept; do not clear the app."), e)
        }
        if (!memory && (migrate() or repairDuplicateIds())) save()
    }

    companion object {
        @Volatile private var instance: Store? = null
        private const val KEY_ALIAS = "wraith.storage.v1"
        /** Bumped when the import format changes; older subscriptions are refetched once. */
        const val PARSE_VERSION = 12

        fun get(c: Context): Store = instance ?: synchronized(this) { instance ?: Store(c.applicationContext, null, false).also { instance = it } }
        /** Test hook: an unencrypted store that never touches disk. */
        fun useInMemory(c: Context, initial: JSONObject) { synchronized(this) { instance = Store(c.applicationContext, initial, true) } }

        fun readOnlySnapshot(c: Context): JSONObject {
            val f = file(c)
            return if (f.baseFile.exists()) decrypt(f.readFully()) else empty()
        }

        private fun empty() = jsonOf("profiles" to JSONArray(), "subscriptions" to JSONArray(), "rules" to JSONArray(), "settings" to JSONObject(), "selected" to "")
        private fun file(c: Context) = AtomicFile(File(c.filesDir, "wraith.enc"))

        private fun decrypt(bytes: ByteArray): JSONObject {
            if (bytes.size < 29) throw IOException(L.t("Повреждён файл профилей", "Profiles file is damaged"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOf(12)))
            return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        }

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(KEY_ALIAS)) {
                KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                }.generateKey()
            }
            return ks.getKey(KEY_ALIAS, null) as SecretKey
        }

        fun randomSecret(length: Int): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
            val r = SecureRandom()
            return (1..length).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        }

        /** Stable identity of a subscription node: endpoint + credential, not its (often changing) name. */
        private fun identity(p: JSONObject): String {
            val o = p.optJSONObject("outbound") ?: return "name|" + p.optString("name")
            return o.optString("type") + "|" + o.optString("server") + "|" + o.optInt("server_port") + "|" + o.optString("secret")
        }

        fun reconcile(previous: List<JSONObject>, incoming: List<JSONObject>, usedIds: MutableSet<String>, group: String) {
            val byIdentity = HashMap<String, ArrayDeque<JSONObject>>()
            previous.forEach { byIdentity.getOrPut(identity(it)) { ArrayDeque() }.addLast(it) }
            for (p in incoming) {
                p.put("group", group)
                val prev = byIdentity[identity(p)]?.removeFirstOrNull()
                if (prev != null) {
                    val oldId = prev.optString("id")
                    if (oldId.isNotEmpty() && oldId !in usedIds) p.put("id", oldId)
                    p.put("favorite", prev.optBoolean("favorite"))
                }
                var id = p.optString("id")
                if (id.isEmpty() || !usedIds.add(id)) { do { id = UUID.randomUUID().toString() } while (!usedIds.add(id)); p.put("id", id) }
            }
        }
    }

    /**
     * Brings data written by older builds to the current shape: sing-box outbounds become
     * endpoints, sing-box-only profiles, rules and settings are dropped.
     */
    private fun migrate(): Boolean {
        var changed = false
        val settings = state.optJSONObject("settings") ?: JSONObject().also { state.put("settings", it); changed = true }
        val agent = settings.optString("userAgent")
        if (agent.startsWith("Wraith/") || agent.startsWith("singbox-Wraith/") || agent.startsWith("Happ/4.4.1/Android")) { settings.remove("userAgent"); changed = true }
        if (!settings.has("customDns") && settings.optString("tunnelDns").isNotBlank()) { settings.put("customDns", true); changed = true }
        if (settings.optString("tunnelDns").startsWith("tls://")) { settings.remove("tunnelDns"); settings.put("customDns", false); changed = true }
        if (!settings.has("muxConcurrency") && (settings.has("muxStreams") || settings.has("muxConnections"))) {
            settings.put("muxConcurrency", if (settings.optString("muxLimit") == "streams") settings.optInt("muxStreams", 8) else settings.optInt("muxConnections", 8)); changed = true
        }
        if (settings.optString("noiseType") == "hex" && settings.optString("noisePacket").isEmpty()) { settings.put("noiseType", "array"); changed = true }
        for (dead in listOf("fragmentDelayRange", "preferredIp", "fakeDns", "remoteDns", "domesticDns", "ipv6", "anonymousStats", "anonymousStatsConsent", "anonymousStatsId",
            "tunStack", "fragmentType", "fragmentDelay", "recordFragment", "muxProtocol", "muxPadding", "muxLimit", "muxStreams", "muxConnections", "noiseRange", "theme", "fontSize"))
            if (settings.has(dead)) { settings.remove(dead); changed = true }
        // The local proxy is never open: an older build may have run it without a login.
        if (settings.optBoolean("localProxy") && (settings.optString("proxyUser").isEmpty() || settings.optString("proxyPassword").length < 12)) {
            settings.put("proxyUser", "wraith-" + randomSecret(6).lowercase()); settings.put("proxyPassword", randomSecret(20)); changed = true
        }
        context.deleteSharedPreferences("wraith_telemetry")

        val profiles = state.optJSONArray("profiles") ?: JSONArray()
        val kept = JSONArray()
        for (p in profiles.objects()) {
            if (p.optBoolean("full")) { AppLog.add(L.t("Удалён профиль sing-box JSON «", "Removed sing-box JSON profile «") + p.optString("name") + "»"); changed = true; continue }
            p.optJSONObject("outbound")?.let { o -> if (!o.has("secret") || !o.has("network")) { p.put("outbound", Endpoint.migrate(o)); changed = true } }
            p.optJSONArray("xrayCandidates")?.let { c ->
                if (c.objects().any { !it.has("secret") }) { p.put("xrayCandidates", c.objects().map { Endpoint.migrate(it) }.toJsonArray()); changed = true }
            }
            for (dead in listOf("full", "latency", "latencyType", "pingError", "xrayNativeOnly")) if (p.has(dead)) { p.remove(dead); changed = true }
            kept.put(p)
        }
        state.put("profiles", kept)
        val rules = state.optJSONArray("rules") ?: JSONArray()
        val keptRules = rules.objects().filter { it.optString("kind") in RoutingConfig.KINDS || it.optString("kind") == "ip_is_private" }
        if (keptRules.size != rules.length()) { AppLog.add(L.t("Удалены правила sing-box (rule_set, package_name): ", "Removed sing-box rules (rule_set, package_name): ") + (rules.length() - keptRules.size)); changed = true }
        state.put("rules", keptRules.toJsonArray())
        if (selected() == null && kept.length() > 0) { state.put("selected", kept.getJSONObject(0).optString("id")); changed = true }
        return changed
    }

    /** Outbound tags derive from profile IDs, so duplicate IDs would make core startup fail. */
    private fun repairDuplicateIds(): Boolean {
        val used = HashSet<String>(); var changed = false
        for (p in state.optJSONArray("profiles").objects()) {
            val id = p.optString("id")
            if (id.isEmpty() || !used.add(id)) { var r: String; do { r = UUID.randomUUID().toString() } while (!used.add(r)); p.put("id", r); changed = true }
        }
        return changed
    }

    @Synchronized fun save() {
        if (!memory) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
            val f = file(context)
            var out: FileOutputStream? = null
            try { out = f.startWrite(); out.write(cipher.iv); out.write(data); f.finishWrite(out) } catch (e: Exception) { if (out != null) f.failWrite(out); throw e }
        }
        _revision.value++
    }

    @Synchronized fun snapshot(): JSONObject = state.copy()
    @Synchronized fun profiles(): List<JSONObject> = state.optJSONArray("profiles").objects()
    @Synchronized fun subscriptions(): List<JSONObject> = state.optJSONArray("subscriptions").objects()
    @Synchronized fun rules(): List<JSONObject> = state.optJSONArray("rules").objects()
    /** A copy: concurrent readers never see a document another thread is changing. */
    @Synchronized fun settings(): JSONObject = state.child("settings").copy()
    @Synchronized fun selectedId(): String = state.optString("selected")
    @Synchronized fun selected(): JSONObject? = profiles().firstOrNull { it.optString("id") == selectedId() }
    @Synchronized fun profile(id: String): JSONObject? = profiles().firstOrNull { it.optString("id") == id }

    @Synchronized fun setting(key: String, value: Any?) { if (value == null) state.child("settings").remove(key) else state.child("settings").put(key, value); save() }
    @Synchronized fun settings(change: (JSONObject) -> Unit) { change(state.child("settings")); save() }

    @Synchronized fun select(id: String) { state.put("selected", id); save(); QuickTile.sync(context) }

    @Synchronized fun update(id: String, change: (JSONObject) -> Unit) {
        profiles().firstOrNull { it.optString("id") == id }?.let { change(it); save() }
    }

    @Synchronized fun replaceProfile(profile: JSONObject) {
        val a = state.getJSONArray("profiles"); val id = profile.getString("id")
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) { a.put(i, profile); save(); return }
        throw IllegalArgumentException(L.t("Сервер больше не найден в списке.", "The server is no longer in the list."))
    }

    @Synchronized fun addProfiles(list: List<JSONObject>) {
        val a = state.getJSONArray("profiles"); list.forEach { a.put(it) }
        if (selected() == null && list.isNotEmpty()) state.put("selected", list[0].getString("id"))
        save()
    }

    @Synchronized fun deleteProfile(id: String) {
        val a = state.getJSONArray("profiles")
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).optString("id") == id) a.remove(i)
        if (selected() == null) state.put("selected", if (a.length() > 0) a.getJSONObject(0).getString("id") else "")
        save()
    }

    @Synchronized fun updateSubscription(id: String, change: (JSONObject) -> Unit) {
        subscriptions().firstOrNull { it.optString("id") == id }?.let { change(it); save() }
    }

    @Synchronized fun removeSubscription(id: String) {
        var a = state.getJSONArray("subscriptions")
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).optString("id") == id) a.remove(i)
        a = state.getJSONArray("profiles")
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).optString("group") == id) a.remove(i)
        if (selected() == null) state.put("selected", if (a.length() > 0) a.getJSONObject(0).getString("id") else "")
        save()
    }

    @Synchronized fun replaceSubscription(sub: JSONObject, incoming: List<JSONObject>) {
        val id = sub.getString("id")
        val previous = mutableListOf<JSONObject>(); val used = HashSet<String>(); val next = JSONArray()
        for (p in profiles()) if (p.optString("group") == id) previous += p else { used += p.optString("id"); next.put(p) }
        reconcile(previous, incoming, used, id)
        incoming.forEach { next.put(it) }
        state.put("profiles", next)
        if (selected() == null && next.length() > 0) state.put("selected", next.getJSONObject(0).getString("id"))
        val subs = state.getJSONArray("subscriptions")
        val index = (0 until subs.length()).firstOrNull { subs.getJSONObject(it).optString("id") == id }
        if (index != null) subs.put(index, sub) else subs.put(sub)
        save()
    }

    @Synchronized fun addRule(r: JSONObject) { state.getJSONArray("rules").put(r); save() }
    @Synchronized fun replaceRule(index: Int, r: JSONObject) { state.getJSONArray("rules").put(index, r); save() }
    @Synchronized fun removeRule(index: Int) { state.getJSONArray("rules").remove(index); save() }

    @Synchronized fun backup(): String = snapshot().put("format", "wraith-backup-v1").toString(2)

    @Synchronized fun restore(input: String) {
        val restored = JSONObject(input)
        if (restored.optString("format") != "wraith-backup-v1" || restored.optJSONArray("profiles") == null || restored.optJSONArray("subscriptions") == null || restored.optJSONObject("settings") == null || restored.optJSONArray("rules") == null)
            throw IllegalArgumentException(L.t("Это не резервная копия Wraith.", "This is not a Wraith backup."))
        if (restored.getJSONArray("profiles").objects().any { it.optJSONObject("outbound") == null || it.optString("id").isEmpty() })
            throw IllegalArgumentException(L.t("Некорректный профиль в резервной копии.", "Invalid profile in the backup."))
        val previous = state
        state = restored
        try { migrate(); repairDuplicateIds(); save() } catch (e: Exception) { state = previous; throw e }
    }

    /** Back to defaults, keeping servers, subscriptions, rules and the installation identity. */
    @Synchronized fun resetSettings() {
        val keep = setOf("deviceId", "clientNumericId")
        val s = state.child("settings")
        s.keys().asSequence().toList().filter { it !in keep }.forEach { s.remove(it) }
        save()
    }

    /** Turns the local proxy on with fresh credentials; it never runs without a login. */
    @Synchronized fun ensureProxyCredentials() {
        val s = state.child("settings")
        if (s.optString("proxyUser").isEmpty()) s.put("proxyUser", "wraith-" + randomSecret(6).lowercase())
        if (s.optString("proxyPassword").length < 12) s.put("proxyPassword", randomSecret(20))
        save()
    }
}
