package app.wraith.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64
import java.util.Locale

/** Builds the native Xray JSON for the tunnel while keeping provider configuration verbatim. */
object XrayRuntimeConfig {
    const val DNS_IP = "1.1.1.1"
    const val AUTO_BALANCER = "wraith-auto"
    private val LOCAL = setOf("freedom", "blackhole", "dns", "loopback")

    fun build(state: JSONObject, profile: JSONObject, tunFd: Int, xray: IsolatedXray?): JSONObject {
        val settings = state.optJSONObject("settings") ?: JSONObject()
        val source = profile.optString("source").trim()
        val root = prepare(profile, settings, source(profile, xray))

        // A provider "api" block opens its own gRPC listener that can add or remove outbounds, and
        // "metrics"/"stats" open an HTTP listener any app on the phone could read. Android owns
        // every entrypoint, so none of them reach the core.
        for (k in listOf("api", "metrics", "stats", "remarks", "name", "meta", "version", "reverse", "fakedns")) root.remove(k)
        if (root.optJSONObject("dns") == null || !settings.optBoolean("dnsFromJson", false)) root.put("dns", dns(profile, settings))
        root.child("env").put("xray.tun.fd", tunFd.toString())
        // Provider inlets (socks/http for desktop clients) are replaced by the TUN; rules that sent
        // traffic from those inlets to a balancer must see the TUN as well.
        val clientInlets = clientInboundTags(root.optJSONArray("inbounds"))
        root.put("inbounds", inbounds(settings))
        if (clientInlets.isNotEmpty()) remapInboundRules(root, clientInlets)
        val tags = ensureAppOutbounds(root, profile)
        val dnsTag = ensureDnsOutbound(root)
        val routing = root.child("routing")
        if (!routing.has("domainStrategy")) routing.put("domainStrategy", "IPIfNonMatch")
        var rules = routing.childArray("rules")
        // Observatory/balancers resolve their candidates without going through the balancer itself.
        val resolverIps = observatoryDnsIps(root)
        if (resolverIps.length() > 0) rules = insertRule(rules, 0, jsonOf("type" to "field", "ip" to resolverIps, "outboundTag" to tags.direct))
        // The phone's own resolvers live in the local or carrier network and are queried directly.
        if (!settings.optBoolean("dnsFromJson", false) && DnsChoice.custom(settings).isEmpty() && DnsChoice.system(settings).isNotEmpty())
            rules = insertRule(rules, 0, jsonOf("type" to "field", "ip" to DnsChoice.system(settings).toJsonArray(), "port" to "53", "outboundTag" to tags.direct))
        var head = 0
        rules = insertRule(rules, head++, jsonOf("type" to "field", "inboundTag" to jsonArrayOf("tun-in"), "network" to "tcp,udp", "port" to "53", "outboundTag" to dnsTag))
        // «Обработка QUIC: reject»: browsers fall back to TCP, where fragmentation and mux apply.
        if (settings.optString("quic", "allow") == "reject")
            rules = insertRule(rules, head++, jsonOf("type" to "field", "inboundTag" to jsonArrayOf("tun-in", "socks", "http"), "network" to "udp", "port" to "443", "outboundTag" to tags.block))
        if (settings.optBoolean("bypassLan", true))
            rules = insertRule(rules, head++, jsonOf("type" to "field", "ip" to jsonArrayOf("geoip:private"), "outboundTag" to tags.direct))
        routing.put("rules", rules)
        if (source.startsWith("{")) { appendUserRules(rules, state, tags); appendAutoCatchAll(rules, root) } else appendRules(rules, state, profile, settings, tags)
        val mode = settings.optString("mode", "rules")
        if (mode == "direct" || mode == "global") {
            val all = jsonOf("type" to "field", "inboundTag" to jsonArrayOf("tun-in", "socks", "http"))
            val balancer = if (mode != "global") "" else if (hasBalancer(root, AUTO_BALANCER)) AUTO_BALANCER else if (providerBalancing(root)) mainBalancer(root) else ""
            if (balancer.isNotEmpty()) all.put("balancerTag", balancer) else all.put("outboundTag", if (mode == "direct") tags.direct else tags.proxy)
            routing.put("rules", insertRule(routing.getJSONArray("rules"), head, all))
        }
        val log = root.child("log")
        // Imported log paths could overwrite the app's own files; the service sets its own.
        log.remove("access"); log.remove("error")
        if (!log.has("loglevel")) log.put("loglevel", if (settings.optBoolean("debugLogs")) "debug" else "warning")
        return root
    }

    /** Source loading shared by the tunnel and the latency probe. */
    fun source(profile: JSONObject, xray: IsolatedXray?): JSONObject {
        val source = profile.optString("source").trim()
        if (source.startsWith("{")) {
            val root = JSONObject(source)
            if (!XrayConfig.looksLike(root)) throw IllegalArgumentException(L.t("Это не конфигурация Xray.", "This is not an Xray configuration."))
            if (root.optJSONArray("outbounds") == null && root.has("protocol")) {
                val outbound = root.copy()
                for (f in listOf("dns", "routing", "log", "remarks", "name", "meta", "env")) outbound.remove(f)
                if (outbound.optString("tag").isEmpty()) outbound.put("tag", "proxy")
                val result = jsonOf("outbounds" to jsonArrayOf(outbound))
                for (f in listOf("dns", "routing", "log", "env")) if (root.has(f)) result.put(f, root.get(f))
                return result
            }
            return root
        }
        if (source.isEmpty()) throw IllegalArgumentException(L.t("У профиля нет исходной ссылки.", "The profile has no source link."))
        XrayLinks.outbound(source)?.let { return jsonOf("outbounds" to jsonArrayOf(it)) }
        val core = xray ?: throw IllegalStateException(L.t("Ядро Xray недоступно.", "The Xray core is unavailable."))
        val response = JSONObject(core.invoke(jsonOf("apiVersion" to 3, "method" to "convertShareLinksToXrayJson", "payload" to jsonOf("text" to source)).toString()))
        if (!response.optBoolean("success")) throw IllegalArgumentException(response.optString("error", L.t("Не удалось преобразовать ссылку в Xray.", "Could not convert the link to Xray.")))
        val outbounds = response.optJSONObject("data")?.optJSONArray("outbounds")
        if (outbounds == null || outbounds.length() == 0) throw IllegalArgumentException(L.t("Ссылка не содержит Xray-серверов.", "The link contains no Xray servers."))
        return jsonOf("outbounds" to outbounds.copy())
    }

    /** Keeps provider tags and dialer chains intact while choosing a deterministic default server. */
    fun prepare(profile: JSONObject, settings: JSONObject, source: JSONObject): JSONObject {
        val root = source.copy()
        val tag = proxyTag(root, profile)
        val outbounds = root.getJSONArray("outbounds")
        // A provider balancer is the automatic server choice the user asked for; latency probes
        // still pin one candidate ("pinCandidate").
        val pin = settings.optBoolean("pinCandidate") || (!settings.optBoolean("autoSelect") && !providerBalancing(root))
        if (pin) {
            val chosen = outbounds.objects().firstOrNull { it.optString("tag") == tag } ?: throw IllegalArgumentException(L.t("Не найден выбранный Xray outbound.", "The selected Xray outbound was not found."))
            val expected = profile.optJSONObject("outbound")
            val peers = chosen.optJSONObject("settings")
            if (expected != null && peers != null) for (key in listOf("vnext", "servers")) {
                val list = peers.optJSONArray(key) ?: continue
                if (list.length() < 2) continue
                list.objects().firstOrNull { Endpoint.host(it).equals(expected.optString("server"), true) && portOf(it) == expected.optInt("server_port") }?.let { peers.put(key, jsonArrayOf(it)) }
            }
            val ordered = JSONArray().put(chosen)
            outbounds.objects().filter { it !== chosen }.forEach { ordered.put(it) }
            root.put("outbounds", ordered)
            val routing = root.optJSONObject("routing")
            val rules = routing?.optJSONArray("rules")
            if (rules != null) for (balancer in routing.optJSONArray("balancers").objects()) {
                if (balancer.optJSONArray("selector").strings().none { tag.startsWith(it) }) continue
                for (rule in rules.objects()) if (balancer.optString("tag") == rule.optString("balancerTag")) { rule.remove("balancerTag"); rule.put("outboundTag", tag) }
            }
        }
        for (outbound in root.optJSONArray("outbounds").objects()) {
            val legacy = outbound.optJSONObject("proxySettings") ?: continue
            val dependency = legacy.optString("tag")
            if (dependency.isEmpty()) outbound.remove("proxySettings")
            else if (legacy.optBoolean("transportLayer")) {
                val sockopt = outbound.child("streamSettings").child("sockopt")
                if (sockopt.has("dialerProxy") && dependency != sockopt.optString("dialerProxy")) throw IllegalArgumentException(L.t("В JSON конфликтуют proxySettings и dialerProxy.", "proxySettings and dialerProxy conflict in the JSON."))
                sockopt.put("dialerProxy", dependency); outbound.remove("proxySettings")
            }
        }
        // Xray 26 refuses to start on the removed "allowInsecure"; drop it so the rest still runs.
        for (outbound in root.optJSONArray("outbounds").objects()) {
            val tls = outbound.optJSONObject("streamSettings")?.optJSONObject("tlsSettings") ?: continue
            if (tls.remove("allowInsecure") != null) AppLog.add(L.t("allowInsecure удалён: Xray 26 его не поддерживает, сертификат проверяется.", "allowInsecure removed: Xray 26 does not support it, the certificate is verified."))
        }
        val candidates = proxyTags(root)
        if (pin) XrayOutboundOptions.apply(root, settings, tag) else candidates.forEach { XrayOutboundOptions.apply(root, settings, it) }
        if (!pin && !providerBalancing(root) && candidates.size > 1) addAutoBalancer(root, candidates, tag, settings)
        return root
    }

    /** "Fastest server automatically" for a multi-server document without its own balancer. */
    private fun addAutoBalancer(root: JSONObject, candidates: List<String>, fallback: String, settings: JSONObject) {
        val routing = root.child("routing")
        routing.childArray("balancers").put(jsonOf("tag" to AUTO_BALANCER, "selector" to candidates.toJsonArray(), "strategy" to jsonOf("type" to "leastPing"), "fallbackTag" to fallback))
        if (root.optJSONObject("observatory") == null)
            root.put("observatory", jsonOf("subjectSelector" to candidates.toJsonArray(), "probeUrl" to settings.optString("pingUrl", "https://www.gstatic.com/generate_204"), "probeInterval" to "3m", "enableConcurrency" to true))
        for (rule in routing.childArray("rules").objects()) if (fallback == rule.optString("outboundTag")) { rule.remove("outboundTag"); rule.put("balancerTag", AUTO_BALANCER) }
    }

    private fun appendAutoCatchAll(rules: JSONArray, root: JSONObject) {
        if (hasBalancer(root, AUTO_BALANCER)) rules.put(jsonOf("type" to "field", "inboundTag" to jsonArrayOf("tun-in", "socks", "http"), "balancerTag" to AUTO_BALANCER))
    }

    fun providerBalancing(root: JSONObject): Boolean {
        val routing = root.optJSONObject("routing") ?: return false
        val balancers = routing.optJSONArray("balancers").objects().map { it.optString("tag") }.filter { it.isNotEmpty() }.toSet()
        return balancers.isNotEmpty() && routing.optJSONArray("rules").objects().any { it.optString("balancerTag") in balancers }
    }

    fun hasBalancer(root: JSONObject, tag: String) = root.optJSONObject("routing")?.optJSONArray("balancers").objects().any { it.optString("tag") == tag }

    fun mainBalancer(root: JSONObject): String {
        var found = ""
        for (rule in root.optJSONObject("routing")?.optJSONArray("rules").objects()) {
            val b = rule.optString("balancerTag"); if (b.isEmpty()) continue
            found = b
            if (!rule.has("domain") && !rule.has("ip") && !rule.has("port") && !rule.has("protocol")) return b
        }
        return found
    }

    /** Real proxy outbounds in provider order, without local helpers and dialer-only hops. */
    fun proxyTags(root: JSONObject): List<String> {
        val outbounds = root.optJSONArray("outbounds").objects()
        val dialers = outbounds.mapNotNull { it.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")?.takeIf { d -> d.isNotEmpty() } }.toSet()
        return outbounds.filter { o -> val t = o.optString("tag"); t.isNotEmpty() && t !in dialers && o.optString("protocol").lowercase(Locale.ROOT) !in LOCAL }.map { it.optString("tag") }
    }

    private fun clientInboundTags(inbounds: JSONArray?): Set<String> = inbounds.objects().filter { i ->
        val p = i.optString("protocol").lowercase(Locale.ROOT)
        i.optString("tag").isNotEmpty() && (p in setOf("socks", "http", "mixed", "tun") || (p == "dokodemo-door" && i.optJSONObject("settings")?.optBoolean("followRedirect") == true))
    }.map { it.optString("tag") }.toSet()

    private fun remapInboundRules(root: JSONObject, inlets: Set<String>) {
        for (rule in root.optJSONObject("routing")?.optJSONArray("rules").objects()) {
            val tags = rule.optJSONArray("inboundTag") ?: continue
            val list = tags.strings()
            if (list.any { it in inlets } && "tun-in" !in list) tags.put("tun-in")
        }
    }

    fun proxyTag(root: JSONObject, profile: JSONObject?): String {
        val outbounds = root.optJSONArray("outbounds") ?: throw IllegalArgumentException(L.t("В конфигурации Xray нет outbounds.", "The Xray configuration has no outbounds."))
        val expected = profile?.optJSONObject("outbound")
        var first: JSONObject? = null; var chosen: JSONObject? = null
        for (o in outbounds.objects()) {
            val protocol = o.optString("protocol").lowercase(Locale.ROOT).let { if (it == "hysteria2") "hysteria" else it }
            if (protocol in LOCAL) continue
            if (first == null) first = o
            if (expected != null && protocol.equals(expected.optString("type"), true) && containsEndpoint(o, expected.optString("server"), expected.optInt("server_port"))) { chosen = o; break }
        }
        val c = chosen ?: first ?: throw IllegalArgumentException(L.t("В конфигурации Xray нет прокси-outbound.", "The Xray configuration has no proxy outbound."))
        var tag = c.optString("tag")
        if (tag.isEmpty()) { tag = uniqueTag(outbounds, "proxy"); c.put("tag", tag) }
        return tag
    }

    private fun observatoryDnsIps(root: JSONObject): JSONArray {
        val balancers = root.optJSONObject("routing")?.optJSONArray("balancers")
        if ((root.optJSONObject("observatory") == null && root.optJSONObject("burstObservatory") == null) || balancers == null || balancers.length() == 0) return JSONArray()
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return JSONArray()
        val seen = LinkedHashSet<String>()
        for (i in 0 until servers.length()) {
            val s = servers.opt(i)
            val host = dnsHost(if (s is JSONObject) s.optString("address", s.optString("server")) else s.toString())
            if (isIpLiteral(host)) seen += host
        }
        return seen.toJsonArray()
    }

    private fun containsEndpoint(outbound: JSONObject, host: String, port: Int): Boolean {
        val s = outbound.optJSONObject("settings") ?: return false
        for (key in listOf("vnext", "servers")) for (peer in s.optJSONArray(key).objects()) if (Endpoint.host(peer).equals(host, true) && portOf(peer) == port) return true
        if (s.optString("address").equals(host, true) && s.optInt("port") == port) return true
        // WireGuard keeps its address in peers[].endpoint.
        return s.optJSONArray("peers").objects().any { it.optString("endpoint").let { e -> e.substringBeforeLast(':').trim('[', ']').equals(host, true) && e.substringAfterLast(':').toIntOrNull() == port } }
    }

    private fun portOf(peer: JSONObject) = try { Endpoint.port(peer) } catch (_: Exception) { 0 }

    private fun insertRule(rules: JSONArray, index: Int, rule: JSONObject): JSONArray {
        val next = JSONArray(); val at = index.coerceIn(0, rules.length())
        for (i in 0..rules.length()) { if (i == at) next.put(rule); if (i < rules.length()) next.put(rules.get(i)) }
        return next
    }

    /**
     * TUN plus, when enabled, a local SOCKS5/HTTP proxy. The proxy always requires a login: an
     * open loopback proxy lets any app on the phone use the tunnel and see that a VPN is running.
     */
    fun inbounds(s: JSONObject): JSONArray {
        val mtu = s.optInt("mtu", 1500)
        if (mtu !in 1280..9000) throw IllegalArgumentException(L.t("MTU должен быть от 1280 до 9000.", "MTU must be between 1280 and 9000."))
        val sniff = s.optBoolean("sniff", true)
        val a = jsonArrayOf(jsonOf("tag" to "tun-in", "port" to 0, "protocol" to "tun", "settings" to jsonOf("name" to "wraith", "mtu" to mtu),
            "sniffing" to jsonOf("enabled" to sniff, "routeOnly" to true, "destOverride" to jsonArrayOf("http", "tls", "quic"))))
        if (s.optBoolean("localProxy", false)) {
            val user = s.optString("proxyUser"); val pass = s.optString("proxyPassword")
            if (user.isEmpty() || pass.length < 12) throw IllegalArgumentException(L.t("Локальному прокси нужны логин и пароль (от 12 символов). Откройте настройки прокси.", "The local proxy needs a login and a password (12+ characters). Open the proxy settings."))
            val listen = if (s.optBoolean("allowLan")) "0.0.0.0" else "127.0.0.1"
            val accounts = jsonArrayOf(jsonOf("user" to user, "pass" to pass))
            a.put(jsonOf("tag" to "socks", "listen" to listen, "port" to s.optInt("proxyPort", 2081), "protocol" to "socks", "settings" to jsonOf("auth" to "password", "accounts" to accounts, "udp" to true),
                "sniffing" to jsonOf("enabled" to sniff, "routeOnly" to true, "destOverride" to jsonArrayOf("http", "tls"))))
            a.put(jsonOf("tag" to "http", "listen" to listen, "port" to s.optInt("httpProxyPort", 2082), "protocol" to "http", "settings" to jsonOf("accounts" to jsonArrayOf(jsonOf("user" to user, "pass" to pass)))))
        }
        return a
    }

    private fun ensureDnsOutbound(root: JSONObject): String {
        val outbounds = root.getJSONArray("outbounds")
        outbounds.objects().firstOrNull { it.optString("protocol") == "dns" }?.let { o -> return o.optString("tag").ifEmpty { "wraith-dns".also { o.put("tag", it) } } }
        val tag = uniqueTag(outbounds, "wraith-dns")
        outbounds.put(jsonOf("protocol" to "dns", "tag" to tag, "settings" to jsonOf("nonIPQuery" to "skip")))
        return tag
    }

    class AppTags(val proxy: String, val direct: String, val block: String)

    private fun ensureAppOutbounds(root: JSONObject, profile: JSONObject): AppTags {
        val outbounds = root.getJSONArray("outbounds")
        val proxy = proxyTag(root, profile)
        var direct = outbounds.objects().firstOrNull { it.optString("protocol") == "freedom" && it.optString("tag").isNotEmpty() }?.optString("tag").orEmpty()
        var block = outbounds.objects().firstOrNull { it.optString("protocol") == "blackhole" && it.optString("tag").isNotEmpty() }?.optString("tag").orEmpty()
        if (direct.isEmpty()) { direct = uniqueTag(outbounds, "direct"); outbounds.put(jsonOf("protocol" to "freedom", "tag" to direct, "settings" to jsonOf("domainStrategy" to "UseIPv4"))) }
        if (block.isEmpty()) { block = uniqueTag(outbounds, "block"); outbounds.put(jsonOf("protocol" to "blackhole", "tag" to block, "settings" to JSONObject())) }
        return AppTags(proxy, direct, block)
    }

    fun uniqueTag(outbounds: JSONArray, base: String): String {
        var c = base; var n = 2
        while (outbounds.objects().any { it.optString("tag") == c }) c = "$base-${n++}"
        return c
    }

    /** The resolver announced to Android for the TUN; all port-53 traffic is hijacked anyway. */
    fun androidDns(state: JSONObject, profile: JSONObject, settings: JSONObject): String {
        if (settings.optBoolean("dnsFromJson", false)) {
            val routing = RoutingConfig.profileFor(state, profile)
            RoutingConfig.dnsPinnedIp(routing, "Remote").takeIf { it.isNotEmpty() }?.let { return it }
            val source = profile.optString("source")
            if (source.startsWith("{")) try {
                val first = JSONObject(source).optJSONObject("dns")?.optJSONArray("servers")?.opt(0)
                val host = dnsHost(if (first is JSONObject) first.optString("address") else first?.toString().orEmpty())
                if (isIpLiteral(host)) return host
            } catch (_: Exception) {}
            dnsHost(RoutingConfig.dnsAddress(routing, "Remote")).takeIf { isIpLiteral(it) }?.let { return it }
        }
        dnsHost(DnsChoice.custom(settings)).takeIf { isIpLiteral(it) }?.let { return it }
        DnsChoice.firstSystem(settings).takeIf { isIpLiteral(it) && ':' !in it }?.let { return it }
        return DNS_IP
    }

    fun dnsEndpoint(address: String?): String {
        var v = address?.trim().orEmpty().ifEmpty { DNS_IP }
        if (':' in v && !v.startsWith("[")) v = "[$v]"
        return "$v:53"
    }

    private fun dnsHost(value: String): String = try { val s = value.trim(); if (isIpLiteral(s)) s else if ("://" in s) URI.create(s).host.orEmpty() else s } catch (_: Exception) { "" }

    /** Provider DNS (with «DNS из JSON»), else the user's own DNS, else the phone's resolvers. */
    private fun dns(p: JSONObject, s: JSONObject): JSONObject {
        var value = if (s.optBoolean("dnsFromJson", false)) RoutingConfig.dnsAddress(p.optJSONObject("routing"), "Remote") else ""
        if (value.isEmpty()) value = DnsChoice.custom(s)
        val servers = JSONArray()
        if (value.isNotEmpty()) servers.put(value) else DnsChoice.system(s).forEach { servers.put(it) }
        if (servers.length() == 0) servers.put(DnsChoice.DEFAULT_CUSTOM)
        val strategy = when (s.optString("ipPreference", "auto")) { "ipv4" -> "UseIPv4"; "ipv6" -> "UseIPv6"; else -> "UseIP" }
        return jsonOf("servers" to servers, "queryStrategy" to strategy)
    }

    private fun appendRules(out: JSONArray, state: JSONObject, profile: JSONObject, settings: JSONObject, tags: AppTags) {
        val routing = RoutingConfig.profileFor(state, profile)
        val mode = settings.optString("mode", "rules")
        val global = mode == "global" || RoutingConfig.globalProxy(routing)
        if (mode != "direct") RoutingConfig.rulesFor(state, profile).objects().forEach { appendRule(out, it, tags) }
        if (!global && routing != null) out.put(jsonOf("type" to "field", "inboundTag" to jsonArrayOf("tun-in", "socks", "http"), "outboundTag" to tags.direct))
    }

    private fun appendUserRules(out: JSONArray, state: JSONObject, tags: AppTags) = state.optJSONArray("rules").objects().forEach { appendRule(out, it, tags) }

    private fun appendRule(out: JSONArray, rule: JSONObject, tags: AppTags) {
        if (!rule.optBoolean("enabled", true)) return
        val r = jsonOf("type" to "field")
        val domains = JSONArray(); val ips = JSONArray()
        val kind = rule.optString("kind")
        for (item in RoutingConfig.split(rule.optString("value"), kind)) when (kind) {
            "domain" -> domains.put("full:$item")
            "domain_suffix" -> domains.put("domain:$item")
            "domain_keyword" -> domains.put("keyword:$item")
            "domain_regex" -> domains.put("regexp:$item")
            "geosite" -> domains.put("geosite:$item")
            "ip_cidr" -> ips.put(item)
            "ip_is_private" -> ips.put("geoip:private")
            "geoip" -> ips.put("geoip:$item")
        }
        if (domains.length() > 0) r.put("domain", domains)
        if (ips.length() > 0) r.put("ip", ips)
        rule.optString("port").takeIf { it.isNotEmpty() }?.let { r.put("port", it) }
        rule.optString("network").takeIf { it.isNotEmpty() }?.let { r.put("network", it) }
        rule.optJSONArray("protocol")?.takeIf { it.length() > 0 }?.let { r.put("protocol", it) }
        if (r.length() == 1) return
        r.put("outboundTag", when (rule.optString("action", "proxy")) { "direct" -> tags.direct; "block" -> tags.block; else -> tags.proxy })
        out.put(r)
    }
}

/** App options expressed in the freedom/mux fields of the bundled Xray core. */
object XrayOutboundOptions {
    fun apply(root: JSONObject, settings: JSONObject?, primaryTag: String) {
        if (settings == null) return
        val outbounds = root.optJSONArray("outbounds") ?: return
        val primary = outbounds.objects().firstOrNull { it.optString("tag") == primaryTag } ?: return
        val protocol = primary.optString("protocol").lowercase(Locale.ROOT)
        if (protocol in setOf("freedom", "direct", "blackhole", "block", "dns", "loopback")) return
        val fragment = settings.optBoolean("fragment"); val noise = settings.optBoolean("noise")
        if (fragment || noise) {
            val sockopt = primary.child("streamSettings").child("sockopt")
            val previous = sockopt.optString("dialerProxy")
            val tag = XrayRuntimeConfig.uniqueTag(outbounds, "wraith-dialer")
            val options = JSONObject()
            if (fragment) options.put("fragment", fragment(settings))
            if (noise) options.put("noises", jsonArrayOf(noise(settings)))
            val helper = jsonOf("tag" to tag, "protocol" to "freedom", "settings" to options)
            // Keep the provider's own dialer in the chain.
            if (previous.isNotEmpty()) helper.put("streamSettings", jsonOf("sockopt" to jsonOf("dialerProxy" to previous)))
            outbounds.put(helper); sockopt.put("dialerProxy", tag)
        }
        if (settings.optBoolean("mux") && !vision(primary) && protocol in setOf("vless", "vmess", "trojan", "shadowsocks")) {
            val concurrency = settings.optInt("muxConcurrency", 8)
            if (concurrency !in 1..1024) throw IllegalArgumentException(L.t("Мультиплексор: 1–1024 потоков", "Multiplexer: 1–1024 streams"))
            primary.child("mux").put("enabled", true).put("concurrency", concurrency)
        }
    }

    fun fragment(s: JSONObject): JSONObject {
        var packets = s.optString("fragmentPackets", "tlshello").trim().lowercase(Locale.ROOT)
        if (packets != "tlshello" && packets.isNotEmpty()) packets = range(packets, L.t("Фрагментирование пакетов", "Packet fragmentation"), 1, 100)
        val o = jsonOf("packets" to packets, "length" to range(s.optString("fragmentLength", "50-100"), L.t("Длина фрагмента", "Fragment length"), 1, 16384),
            "interval" to range(s.optString("fragmentInterval", "10-20"), L.t("Задержка фрагмента", "Fragment delay"), 0, 1000))
        s.optString("fragmentMaxSplit").trim().takeIf { it.isNotEmpty() }?.let { o.put("maxSplit", range(it, L.t("Максимальное разделение", "Maximum split"), 1, 10000)) }
        return o
    }

    fun noise(s: JSONObject): JSONObject {
        var type = s.optString("noiseType", "array").trim().lowercase(Locale.ROOT)
        var packet = s.optString("noisePacket", "1A,2B,3C").trim()
        when (type) {
            "array" -> {
                if (!packet.matches(Regex("(?i)(0x)?[0-9a-f]{1,2}(\\s*,\\s*(0x)?[0-9a-f]{1,2})*"))) throw IllegalArgumentException(L.t("Пакет array: байты в hex через запятую, например 1A,2B,3C", "array packet: hex bytes separated by commas, e.g. 1A,2B,3C"))
                packet = packet.split(',').joinToString("") { String.format(Locale.ROOT, "%02x", it.trim().replaceFirst(Regex("(?i)^0x"), "").toInt(16)) }
                type = "hex"
            }
            "rand" -> packet = range(s.optString("noiseRand", "1-8192"), L.t("Размер шума", "Noise size"), 1, 8192)
            "str" -> if (packet.isEmpty()) throw IllegalArgumentException(L.t("Пакет шума пуст", "Noise packet is empty"))
            "hex" -> if (!packet.matches(Regex("(?i)([0-9a-f]{2})+"))) throw IllegalArgumentException(L.t("Пакет hex должен содержать целые байты", "A hex packet must contain whole bytes"))
            "base64" -> {
                val bytes = try { Base64.getDecoder().decode(packet.replace('-', '+').replace('_', '/')) } catch (e: IllegalArgumentException) { null }
                if (bytes == null || bytes.isEmpty()) throw IllegalArgumentException(L.t("Пакет шума не является корректным base64", "The noise packet is not valid base64"))
                packet = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            }
            else -> throw IllegalArgumentException(L.t("Тип шума: array, rand, str, hex или base64", "Noise type: array, rand, str, hex or base64"))
        }
        return jsonOf("type" to type, "packet" to packet, "delay" to range(s.optString("noiseDelay", "10-16"), L.t("Задержка шума", "Noise delay"), 0, 1000))
    }

    /** "a-b" or "a" with non-negative integers, as Happ writes its ranges. */
    fun range(value: String?, name: String, min: Long, max: Long): String {
        val v = value.orEmpty().trim().replace(" ", "")
        if (!v.matches(Regex("[0-9]+(-[0-9]+)?"))) throw IllegalArgumentException(name + L.t(": укажите число или диапазон вида 10-20", ": enter a number or a range like 10-20"))
        val p = v.split('-'); val a = p[0].toLong(); val b = if (p.size > 1) p[1].toLong() else a
        if (a < min || b > max || b < a) throw IllegalArgumentException(name + L.t(": допустимо ", ": allowed ") + min + "–" + max)
        return v
    }

    private fun vision(o: JSONObject) = o.optJSONObject("settings")?.optJSONArray("vnext").objects().any { peer -> peer.optJSONArray("users").objects().any { it.optString("flow").startsWith("xtls-rprx-vision") } }
}
