package app.wraith.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.util.Locale

/**
 * Resolves the routing profile attached to a server (an imported Xray configuration) or to its
 * subscription (a happ://routing/ link) into Wraith's rule list, which XrayRuntimeConfig turns
 * into Xray routing rules.
 *
 * A rule is {name, kind, value, action, enabled}: kind is one of [KINDS], value holds one entry
 * per line, action is proxy / direct / block.
 */
object RoutingConfig {
    val KINDS = listOf("domain_suffix", "domain", "domain_keyword", "domain_regex", "ip_cidr", "geosite", "geoip")

    fun kindLabel(kind: String): String = when (kind) {
        "domain_suffix" -> L.t("Домен и поддомены", "Domain and subdomains")
        "domain" -> L.t("Точный домен", "Exact domain")
        "domain_keyword" -> L.t("Домен содержит", "Domain contains")
        "domain_regex" -> L.t("Регулярное выражение", "Regular expression")
        "ip_cidr" -> "IP / CIDR"
        "geosite" -> "geosite"
        "geoip" -> "geoip"
        "ip_is_private" -> L.t("Частные IP", "Private IPs")
        else -> kind
    }

    /** Inline profile of an imported Xray config first, then the subscription's Happ profile. */
    fun profileFor(state: JSONObject, profile: JSONObject?): JSONObject? {
        profile?.optJSONObject("routing")?.takeIf { it.length() > 0 }?.let { return it }
        val group = profile?.optString("group").orEmpty()
        if (group.isEmpty()) return null
        val sub = state.optJSONArray("subscriptions").objects().firstOrNull { it.optString("id") == group } ?: return null
        // Happ "routing-enable: 0" — the provider switched routing off for this subscription.
        if (sub.has("routingEnabled") && !sub.optBoolean("routingEnabled", true)) return null
        // JSON subscriptions: each server's own JSON routing applies, not a header profile.
        if (sub.optString("format") == "json") return null
        val link = sub.optString("routing")
        if (link.isEmpty() || link == "happ://routing/off") return null
        return try { decode(link) } catch (e: Exception) {
            AppLog.add(L.t("Профиль маршрутизации подписки не разобран: ", "Subscription routing profile could not be parsed: ") + e.safe()); null
        }
    }

    /** Happ DNS address from the documented triple (type + domain + ip) or the short RemoteDns form. */
    fun dnsAddress(routing: JSONObject?, prefix: String): String {
        if (routing == null) return ""
        val legacy = routing.str("${prefix}Dns", "${prefix}DNS")
        val kind = routing.str("${prefix}DNSType", "${prefix}DnsType").uppercase(Locale.ROOT)
        val domain = routing.str("${prefix}DNSDomain", "${prefix}DnsDomain")
        val ip = routing.str("${prefix}DNSIP", "${prefix}DnsIp", "${prefix}DNSIp")
        if (domain.isEmpty() && ip.isEmpty()) return legacy
        val host = domain.ifEmpty { ip }
        if ("://" in host) return host
        return when (kind) {
            "DOH", "HTTPS" -> "https://$host/dns-query"
            "DOQ", "QUIC" -> "quic+local://$host"
            "DOH3", "H3" -> "h3://$host/dns-query"
            else -> host
        }
    }

    fun dnsPinnedIp(routing: JSONObject?, prefix: String): String {
        if (routing == null) return ""
        if (routing.str("${prefix}DNSDomain", "${prefix}DnsDomain").isEmpty()) return ""
        val ip = routing.str("${prefix}DNSIP", "${prefix}DnsIp", "${prefix}DNSIp")
        return if (isIpLiteral(ip)) ip else ""
    }

    /** Happ "GlobalProxy": false means unmatched traffic stays direct — unless there are no proxy rules at all. */
    fun globalProxy(routing: JSONObject?): Boolean {
        if (routing == null) return true
        val value = routing.opt("GlobalProxy")
        if (value == null || value == JSONObject.NULL || !value.toString().trim().equals("false", true)) return true
        val hasProxyRules = (routing.optJSONArray("ProxySites")?.length() ?: 0) > 0 || (routing.optJSONArray("ProxyIp")?.length() ?: 0) > 0
        if (!hasProxyRules) AppLog.add(L.t("Профиль «", "Profile «") + routing.optString("Name") + L.t("»: GlobalProxy=false без правил Proxy — игнорируется.", "»: GlobalProxy=false without Proxy rules — ignored."))
        return !hasProxyRules
    }

    fun summary(state: JSONObject, profile: JSONObject?): String = try {
        val routing = profileFor(state, profile)
        if (routing == null) L.t("без правил провайдера", "no provider rules") else {
            val rules = JSONArray().also { append(it, routing) }
            val entries = rules.objects().sumOf { split(it.optString("value"), it.optString("kind")).size }
            L.t("правила «", "rules «") + routing.optString("Name", L.t("провайдер", "provider")) + "» · " + entries + L.t(" записей", " entries")
        }
    } catch (_: Exception) { L.t("правила провайдера: ошибка", "provider rules: error") }

    fun rulesFor(state: JSONObject, profile: JSONObject?): JSONArray {
        val result = JSONArray()
        profileFor(state, profile)?.let { append(result, it) }
        // User rules stay after provider rules: the provider decides first, the user refines.
        state.optJSONArray("rules").objects().forEach { result.put(it.copy()) }
        return result
    }

    fun decode(link: String): JSONObject {
        if (!link.startsWith("happ://routing/add/") && !link.startsWith("happ://routing/onadd/")) throw IllegalArgumentException(L.t("Некорректный профиль маршрутизации", "Invalid routing profile"))
        val slash = link.indexOf('/', "happ://routing/".length)
        return JSONObject(ImportParser.base64(link.substring(slash + 1).trim()))
    }

    fun append(out: JSONArray, json: JSONObject) {
        val name = json.optString("Name", L.t("Профиль провайдера", "Provider profile"))
        val ordered = json.optJSONArray("Rules")
        if (ordered != null && ordered.length() > 0) {
            for (rule in ordered.objects()) {
                val action = rule.optString("action", "proxy")
                emit(out, name, action, rule.optJSONArray("domain"), false, rule)
                emit(out, name, action, rule.optJSONArray("ip"), true, rule)
            }
            return
        }
        for (prefix in listOf("Block", "Direct", "Proxy")) for (field in listOf("Sites", "Ip")) {
            val values = json.optJSONArray(prefix + field) ?: continue
            emit(out, "$name · $prefix", prefix.lowercase(Locale.ROOT), values, field == "Ip", null)
        }
    }

    private fun emit(out: JSONArray, name: String, action: String, values: JSONArray?, addresses: Boolean, source: JSONObject?) {
        if (values == null || values.length() == 0) return
        val grouped = LinkedHashMap<String, LinkedHashSet<String>>()
        fun add(kind: String, value: String) {
            var v = value.trim(); if (v.isEmpty()) return
            if (kind in setOf("domain", "domain_suffix", "domain_keyword")) v = asciiDomain(v)
            grouped.getOrPut(kind) { LinkedHashSet() }.add(v)
        }
        for (raw in values.strings()) {
            when {
                raw == "null" -> {}
                raw.startsWith("ext:") -> AppLog.add(L.t("Правило пропущено: внешние geo-файлы (", "Rule skipped: external geo files (") + raw + L.t(") не поддерживаются.", ") are not supported."))
                raw.startsWith("geoip:") -> raw.substring(6).trim().let { if (it.equals("private", true)) add("ip_is_private", "true") else add("geoip", it) }
                raw.startsWith("geosite:") -> add("geosite", raw.substring(8))
                addresses -> add("ip_cidr", raw)
                raw.startsWith("full:") -> add("domain", raw.substring(5))
                raw.startsWith("regexp:") -> add("domain_regex", raw.substring(7))
                raw.startsWith("keyword:") -> add("domain_keyword", raw.substring(8))
                raw.startsWith("domain:") -> add("domain_suffix", raw.substring(7))
                else -> add("domain_keyword", raw) // a bare Xray domain entry is a substring match
            }
        }
        for ((kind, set) in grouped) {
            val rule = jsonOf("name" to name, "kind" to kind, "value" to set.joinToString("\n"), "action" to action, "enabled" to true, "provider" to true)
            source?.let { s ->
                s.optString("port").trim().takeIf { it.isNotEmpty() }?.let { rule.put("port", it) }
                s.optString("network").trim().takeIf { it.isNotEmpty() }?.let { rule.put("network", it) }
                s.optJSONArray("protocol")?.takeIf { it.length() > 0 }?.let { rule.put("protocol", it.copy()) }
            }
            out.put(rule)
        }
    }

    /** Requests carry lower-case punycode names, so "честныйзнак.рф" from a list must be converted. */
    fun asciiDomain(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        val dot = lower.startsWith(".")
        var body = if (dot) lower.substring(1) else lower
        if (!body.matches(Regex("[\\x00-\\x7F]*"))) try { body = IDN.toASCII(body, IDN.ALLOW_UNASSIGNED) } catch (_: IllegalArgumentException) {}
        return if (dot) ".$body" else body
    }

    /** Regular expressions may contain commas, so they are split by lines only. */
    fun split(value: String, kind: String): List<String> =
        value.split(if (kind == "domain_regex") Regex("\\n") else Regex("[\\n,]")).map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Which DNS the tunnel uses: the provider's (only with «DNS из JSON»), the user's own (only with
 * «Свой DNS»), otherwise the DNS of the phone's current network, read at connect time.
 */
object DnsChoice {
    const val DEFAULT_CUSTOM = "https://8.8.8.8/dns-query"
    const val SYSTEM_KEY = "_systemDns"

    fun custom(s: JSONObject?): String {
        if (s == null || !s.optBoolean("customDns")) return ""
        return s.optString("tunnelDns").trim().ifEmpty { DEFAULT_CUSTOM }
    }

    fun system(s: JSONObject?): List<String> = s?.optJSONArray(SYSTEM_KEY).strings()
    fun firstSystem(s: JSONObject?): String = system(s).firstOrNull().orEmpty()

    /** Accepts what Xray's DNS module understands; throws with a readable message otherwise. */
    fun validate(value: String) {
        val v = value.trim()
        if (v.isEmpty() || v.equals("localhost", true) || isIpLiteral(v)) return
        if (v.startsWith("tls://")) throw IllegalArgumentException(L.t("Xray не поддерживает DNS-over-TLS. Используйте https://… (DoH) или quic+local://… (DoQ).", "Xray does not support DNS over TLS. Use https://… (DoH) or quic+local://… (DoQ)."))
        val schemes = listOf("https://", "https+local://", "h2c://", "quic+local://", "tcp://", "tcp+local://", "udp://")
        if (schemes.none { v.startsWith(it) } || v.substringAfter("://").substringBefore('/').isEmpty())
            throw IllegalArgumentException(L.t("DNS: https://…, quic+local://…, tcp://… или IP-адрес", "DNS: https://…, quic+local://…, tcp://… or an IP address"))
    }

    fun inject(context: Context, settings: JSONObject) {
        try { settings.put(SYSTEM_KEY, read(context).toJsonArray()) } catch (e: Exception) { AppLog.add(L.t("DNS телефона: ", "Phone DNS: ") + e.safe()) }
    }

    /** DNS servers of the best non-VPN network, IPv4 first. */
    fun read(context: Context): List<String> {
        val out = mutableListOf<String>()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return out
        val network = Networks.physical(context) ?: return out
        cm.getLinkProperties(network)?.dnsServers?.forEach { a ->
            if (a.isLinkLocalAddress || a.isLoopbackAddress || a.isAnyLocalAddress) return@forEach
            val ip = a.hostAddress?.substringBefore('%') ?: return@forEach
            if (ip !in out && out.size < 3) out += ip
        }
        return out.sortedBy { ':' in it }
    }
}

/** The phone's real (non-VPN) network: latency probes and the core's own sockets must leave through it. */
object Networks {
    fun physical(context: Context): android.net.Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork
        var best: android.net.Network? = null; var bestScore = -1
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val cap = cm.getNetworkCapabilities(n) ?: continue
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val score = (if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 4 else 0) + (if (n == active) 2 else 0) + (if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0)
            if (score > bestScore) { best = n; bestScore = score }
        }
        return best
    }

    fun vpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
}
