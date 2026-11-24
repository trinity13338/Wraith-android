package app.wraith.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * What the interface and the server identity need to know about one server: protocol, address,
 * credential, transport and security. The core never reads it — Xray runs the original link or
 * JSON — so nothing a provider sends is rejected just because it cannot be summarised.
 *
 * Shape: {type, server, server_port, secret, network, security}. `type` is the Xray protocol name.
 */
object Endpoint {
    /** Protocols the bundled Xray core can dial. */
    val PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "wireguard")

    fun of(type: String, server: String, port: Int, secret: String = "", network: String = "tcp", security: String = "none"): JSONObject =
        jsonOf("type" to type, "server" to server, "server_port" to port, "secret" to secret,
            "network" to network.lowercase(Locale.ROOT).ifEmpty { "tcp" }, "security" to security.lowercase(Locale.ROOT).ifEmpty { "none" })

    /** Xray JSON outbound → endpoint; null for local helpers (freedom, blackhole, dns…). */
    fun fromXray(x: JSONObject): JSONObject? {
        val protocol = x.optString("protocol").lowercase(Locale.ROOT).let { if (it == "hysteria2") "hysteria" else it }
        if (protocol.isEmpty() || protocol in setOf("freedom", "blackhole", "dns", "loopback", "direct", "block")) return null
        if (protocol !in PROTOCOLS) throw IllegalArgumentException(L.t("Протокол Xray $protocol не поддерживается.", "Xray protocol $protocol is not supported."))
        val s = x.optJSONObject("settings") ?: JSONObject()
        val stream = x.optJSONObject("streamSettings")
        val peer: JSONObject; val secret: String
        when (protocol) {
            "vless", "vmess" -> {
                peer = firstOf(s, "vnext", "servers") ?: if (s.has("address")) s else throw IllegalArgumentException(L.t("В Xray-исходящем $protocol нет адреса сервера.", "Xray outbound $protocol has no server address."))
                secret = firstOf(peer, "users")?.str("id", "uuid", "password") ?: s.str("id", "uuid")
            }
            "wireguard" -> {
                peer = firstOf(s, "peers")?.let { p -> JSONObject().put("address", p.str("endpoint").substringBeforeLast(':').trim('[', ']')).put("port", p.str("endpoint").substringAfterLast(':', "51820")) }
                    ?: throw IllegalArgumentException(L.t("В WireGuard нет peers.", "WireGuard has no peers."))
                secret = s.str("secretKey")
            }
            "hysteria" -> {
                peer = if (s.has("address")) s else firstOf(s, "servers", "vnext") ?: throw IllegalArgumentException(L.t("В Hysteria нет адреса сервера.", "Hysteria has no server address."))
                secret = stream?.optJSONObject("hysteriaSettings").str("auth", "password").ifEmpty { s.str("auth", "password") }
            }
            else -> {
                peer = firstOf(s, "servers") ?: if (s.has("address")) s else throw IllegalArgumentException(L.t("В Xray-исходящем $protocol нет серверов.", "Xray outbound $protocol has no servers."))
                val user = firstOf(peer, "users")
                secret = peer.str("password").ifEmpty { user?.let { u -> listOf(u.str("user", "username"), u.str("pass", "password")).filter { it.isNotEmpty() }.joinToString(":") } ?: "" }
            }
        }
        val network = when (protocol) { "hysteria" -> "quic"; "wireguard" -> "udp"; else -> stream.str("network", "type").ifEmpty { "tcp" } }
        val security = when (protocol) { "hysteria" -> "tls"; "wireguard" -> "none"; else -> stream.str("security").ifEmpty { "none" } }
        return of(protocol, host(peer), port(peer), secret, network, security)
    }

    /** Endpoint of a share link. Throws only for what Xray itself cannot run. */
    fun fromLink(text: String): JSONObject {
        val scheme = text.substringBefore("://").lowercase(Locale.ROOT)
        if (scheme == "vmess") return vmess(text)
        if (scheme == "ss") return shadowsocks(text)
        val l = ImportParser.split(socksBase64(scheme, text))
        val q = l.query
        val type = when (l.scheme) {
            "hy2", "hysteria2" -> "hysteria"
            "socks4", "socks5" -> "socks"
            "wg" -> "wireguard"
            "tuic", "anytls", "ssh", "hysteria", "naive", "juicity" -> throw IllegalArgumentException(l.scheme.uppercase(Locale.ROOT) + L.t(" не поддерживается ядром Xray.", " is not supported by the Xray core."))
            else -> l.scheme
        }
        if (type !in PROTOCOLS) throw IllegalArgumentException(L.t("Неизвестный протокол: ", "Unknown protocol: ") + l.scheme)
        if (type == "hysteria" && q.first("obfs").isNotEmpty()) throw IllegalArgumentException(L.t("Hysteria2 с obfs не поддерживается Xray.", "Hysteria2 with obfs is not supported by Xray."))
        val security = when {
            type == "hysteria" -> "tls"
            q.first("pbk", "publicKey", "public_key").isNotEmpty() -> "reality"
            else -> q.first("security").ifEmpty { if (type == "trojan") "tls" else "none" }
        }
        val port = if (l.port > 0) l.port else when {
            type == "wireguard" -> 51820
            type in setOf("trojan", "hysteria") || security in setOf("tls", "reality") -> 443
            else -> -1
        }
        if (port !in 1..65535) throw IllegalArgumentException(L.t("В ссылке отсутствует или некорректен порт сервера.", "The link has a missing or invalid server port."))
        val secret = ImportParser.decode(l.userInfo)
        if (type == "vless" && secret.isEmpty()) throw IllegalArgumentException(L.t("В ссылке VLESS нет UUID.", "The VLESS link has no UUID."))
        if (type == "wireguard" && secret.isEmpty()) throw IllegalArgumentException(L.t("В ссылке WireGuard нет приватного ключа.", "The WireGuard link has no private key."))
        val network = when (type) { "hysteria" -> "quic"; "wireguard" -> "udp"; else -> q.first("type", "net").ifEmpty { "tcp" } }
        return of(type, l.host, port, secret, network, security)
    }

    /** socks://<base64 of user:pass@host:port> — the whole address encoded in one blob. */
    private fun socksBase64(scheme: String, text: String): String {
        if (scheme !in setOf("socks", "socks4", "socks5", "http")) return text
        val start = text.indexOf("://") + 3
        val body = text.substring(start).split('#', '?')[0]
        if ('@' in body || ':' in body || !body.matches(Regex("[A-Za-z0-9+/=_-]{8,}"))) return text
        return try {
            val decoded = ImportParser.base64(body)
            if (ImportParser.readable(decoded) && ':' in decoded) scheme + "://" + decoded.trim() + text.substring(start + body.length) else text
        } catch (_: Exception) { text }
    }

    private fun vmess(s: String): JSONObject {
        val payload = s.substring(8).substringBefore('#')
        val v = try { JSONObject(ImportParser.base64(payload)) } catch (_: Exception) { null }
        if (v == null) {
            val l = ImportParser.split(s)
            if (l.port < 1) throw IllegalArgumentException(L.t("Не удалось разобрать ссылку VMess.", "Could not read the VMess link."))
            val auth = ImportParser.decode(l.userInfo)
            return of("vmess", l.host, l.port, auth.substringAfter(':'), l.query.first("type").ifEmpty { "tcp" }, l.query.first("security").ifEmpty { "none" })
        }
        val address = v.str("add", "address", "host").trim('[', ']')
        if (address.isEmpty()) throw IllegalArgumentException(L.t("В ссылке VMess нет адреса сервера.", "The VMess link has no server address."))
        val id = v.str("id", "uuid")
        if (id.isEmpty()) throw IllegalArgumentException(L.t("В ссылке VMess нет UUID.", "The VMess link has no UUID."))
        val port = v.str("port").toIntOrNull() ?: throw IllegalArgumentException(L.t("Некорректный порт в ссылке VMess.", "Invalid port in the VMess link."))
        val tls = v.str("tls").lowercase(Locale.ROOT)
        return of("vmess", address, port, id, v.str("net").ifEmpty { "tcp" }, if (tls == "tls" || tls == "reality") tls else "none")
    }

    /** vmess:// JSON keeps its name and description inside the payload. */
    fun vmessMeta(s: String): Pair<String, String> {
        val label = s.substring(8).substringAfter('#', "").let { ImportParser.decode(it) }
        val v = try { JSONObject(ImportParser.base64(s.substring(8).substringBefore('#'))) } catch (_: Exception) { return label to "" }
        return label.ifEmpty { v.str("ps", "remarks", "name") } to ImportParser.describe(v.str("serverDescription"))
    }

    private fun shadowsocks(s: String): JSONObject {
        var data = s.substring(5).substringBefore('#')
        val question = data.indexOf('?')
        if (question >= 0) {
            val q = ImportParser.query(data.substring(question + 1))
            if (q["plugin"].orEmpty().isNotEmpty()) throw IllegalArgumentException(L.t("Shadowsocks plugin не поддерживается.", "Shadowsocks plugin is not supported."))
            data = data.substring(0, question)
        }
        if ('@' !in data) data = ImportParser.base64(data)
        val at = data.lastIndexOf('@')
        if (at < 0) throw IllegalArgumentException(L.t("Некорректная Shadowsocks-ссылка.", "Invalid Shadowsocks link."))
        val userPart = data.substring(0, at)
        val user = if (':' !in userPart) ImportParser.base64(userPart) else ImportParser.decode(userPart)
        if (':' !in user) throw IllegalArgumentException(L.t("Нет пароля Shadowsocks.", "Shadowsocks password is missing."))
        val l = ImportParser.split("ss://" + data.substring(at + 1))
        if (l.port < 1) throw IllegalArgumentException(L.t("Некорректный адрес Shadowsocks.", "Invalid Shadowsocks address."))
        return of("shadowsocks", l.host, l.port, user.substringAfter(':'))
    }

    /** Converts the endpoint stored by builds before 0.13 (a sing-box outbound) to this shape. */
    fun migrate(old: JSONObject): JSONObject {
        if (old.has("secret") && old.has("network")) return old
        val type = when (val t = old.optString("type")) { "hysteria2" -> "hysteria"; else -> t }
        val secret = old.str("uuid", "password", "private_key").ifEmpty { old.str("username") }
        val tls = old.optJSONObject("tls")
        val security = when {
            tls?.optJSONObject("reality")?.optBoolean("enabled", true) == true -> "reality"
            tls != null && tls.optBoolean("enabled", true) -> "tls"
            type == "hysteria" -> "tls"
            else -> "none"
        }
        val network = old.optJSONObject("transport").str("type").ifEmpty { when (type) { "hysteria" -> "quic"; "wireguard" -> "udp"; else -> "tcp" } }
        return of(type, old.optString("server"), old.optInt("server_port"), secret, network, security).also { e -> old.str("tag").takeIf { it.isNotEmpty() }?.let { e.put("tag", it) } }
    }

    fun protocolLabel(type: String): String = when (type) {
        "hysteria" -> "HYSTERIA2"; "shadowsocks" -> "SS"; "wireguard" -> "WIREGUARD"; else -> type.uppercase(Locale.ROOT)
    }

    /** "VLESS · XHTTP · REALITY" */
    fun label(e: JSONObject?): String {
        if (e == null) return L.t("Сервер", "Server")
        val parts = mutableListOf(protocolLabel(e.optString("type")))
        val net = e.optString("network").ifEmpty { "tcp" }
        if (e.optString("type") !in setOf("wireguard")) parts += when (net) { "raw" -> "TCP"; "splithttp" -> "XHTTP"; else -> net.uppercase(Locale.ROOT) }
        when (e.optString("security")) { "reality" -> parts += "REALITY"; "tls", "xtls" -> if (e.optString("type") != "hysteria") parts += "TLS" }
        return parts.joinToString(" · ")
    }

    fun host(peer: JSONObject): String = peer.str("address", "server", "host").trim('[', ']')
    fun port(peer: JSONObject): Int {
        val value = peer.opt("port")
        val p = when (value) { is Number -> value.toInt(); null -> 0; else -> value.toString().trim().toIntOrNull() ?: 0 }
        if (p !in 1..65535) throw IllegalArgumentException(L.t("Некорректный порт в конфигурации Xray.", "Invalid port in the Xray configuration."))
        return p
    }
    fun firstOf(holder: JSONObject, vararg keys: String): JSONObject? {
        for (key in keys) holder.optJSONArray(key).objects().firstOrNull()?.let { return it }
        return null
    }
}

private fun Map<String, String>.first(vararg keys: String): String = keys.firstNotNullOfOrNull { this[it]?.trim()?.takeIf { v -> v.isNotEmpty() } } ?: ""

/** Parses public share formats, subscription bodies and Xray JSON locally — no converter service. */
object ImportParser {
    fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    /** Providers send unpadded, URL-safe, line-wrapped base64; normalise before decoding. */
    fun base64(s: String): String {
        var v = s.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/')
        val cut = v.indexOf('=')
        if (cut >= 0) v = v.substring(0, cut)
        if (v.isEmpty()) throw IllegalArgumentException(L.t("Пустые данные Base64.", "Empty Base64 data."))
        while (v.length % 4 != 0) v += "="
        return String(Base64.getMimeDecoder().decode(v), Charsets.UTF_8)
    }

    fun unwrap(input: String): String {
        var s = input.trim()
        if (s.startsWith("happ://crypt") || s.startsWith("happ://limited") || s.startsWith("happ://hwid"))
            throw IllegalArgumentException(L.t("Эта ссылка зашифрована сторонним клиентом. Нужна обычная ссылка подписки от провайдера.", "This link is encrypted for another client. Ask your provider for a regular subscription link."))
        for (prefix in listOf("happ://add/", "wraith://add/", "wraith://import/")) if (s.startsWith(prefix)) {
            s = s.substring(prefix.length)
            if (!s.startsWith("https://") && !s.startsWith("http://")) s = decode(s)
            break
        }
        if (s.startsWith("clash://") || s.startsWith("sing-box://import-remote-profile?"))
            s = s.substringAfter('?', "").let { query(it)["url"].orEmpty() }
        return s
    }

    fun query(raw: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw?.split('&', ';')?.forEach { p -> if (p.isNotEmpty()) { val kv = p.split("=", limit = 2); out[decode(kv[0])] = if (kv.size == 2) decode(kv[1]) else "" } }
        return out
    }

    /**
     * One link, a subscription body or an Xray JSON document. Lines that cannot be used are
     * collected in [skipped] so one bad node never costs the user the other fifty.
     */
    fun parse(raw: String, skipped: MutableList<String> = mutableListOf()): List<JSONObject> {
        var s = raw.trim().replace("\uFEFF", "")
        if (s.isEmpty()) throw IllegalArgumentException(L.t("Вставьте ссылку или конфигурацию.", "Paste a link or a configuration."))
        val results = mutableListOf<JSONObject>()
        if (s.startsWith("{")) {
            val o = JSONObject(s)
            if (!XrayConfig.looksLike(o)) throw IllegalArgumentException(L.t("Это не конфигурация Xray.", "This is not an Xray configuration."))
            results += XrayConfig.profile(o, s).put("jsonSource", true)
            return results
        }
        if (s.startsWith("[")) {
            for (o in JSONArray(s).objects()) try {
                if (!XrayConfig.looksLike(o)) throw IllegalArgumentException(L.t("Это не конфигурация Xray.", "This is not an Xray configuration."))
                results += XrayConfig.profile(o, o.toString()).put("jsonSource", true)
            } catch (e: Exception) { skipped += e.safe() }
        } else {
            if (!s.contains("://")) {
                s = try { base64(s) } catch (_: Exception) { throw IllegalArgumentException(L.t("Не удалось распознать ссылку или Base64-подписку.", "Could not recognise the link or Base64 subscription.")) }
                if (s.trim().startsWith("{") || s.trim().startsWith("[")) return parse(s, skipped)
            }
            var routingLink = ""
            for (rawLine in s.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                // Happ ships the routing profile next to the hosts; dropping it lost the provider's rules.
                if (line.startsWith("happ://routing/")) { if (routingLink.isEmpty()) routingLink = line; continue }
                if (!line.contains("://")) continue
                try { results += parseLink(line) } catch (e: Exception) { skipped += e.safe() }
            }
            attachRouting(results, routingLink)
        }
        if (results.isEmpty()) {
            if (skipped.isNotEmpty()) throw IllegalArgumentException(L.t("Ни одна строка не поддерживается (", "No line is supported (") + skipped.size + "). " + skipped[0])
            throw IllegalArgumentException(L.t("В подписке не найдено серверов поддерживаемого формата.", "No servers in a supported format were found in the subscription."))
        }
        return results
    }

    private fun attachRouting(results: List<JSONObject>, link: String) {
        if (link.isEmpty() || results.isEmpty()) return
        if (link.startsWith("happ://routing/off")) { results.forEach { it.remove("routing") }; return }
        try { val routing = RoutingConfig.decode(link); results.forEach { it.put("routing", routing) } }
        catch (e: Exception) { AppLog.add(L.t("Профиль маршрутизации из импорта не разобран: ", "Routing profile from the import could not be parsed: ") + e.safe()) }
    }

    fun profile(name: String, endpoint: JSONObject, raw: String): JSONObject =
        jsonOf("id" to UUID.randomUUID().toString(), "name" to name.ifEmpty { L.t("Сервер", "Server") }, "outbound" to endpoint, "source" to raw, "group" to "", "favorite" to false)

    fun parseLink(text: String): JSONObject {
        val endpoint = Endpoint.fromLink(text)
        val (name, description) = if (text.lowercase(Locale.ROOT).startsWith("vmess://") && !text.contains('@')) Endpoint.vmessMeta(text)
        else if (text.lowercase(Locale.ROOT).startsWith("ss://")) ssMeta(text)
        else split(text).let { it.fragment to it.description }
        val p = profile(name.ifEmpty { endpoint.optString("server") }, endpoint, text)
        if (description.isNotEmpty()) p.put("description", description)
        return p
    }

    private fun ssMeta(s: String): Pair<String, String> {
        val fragment = s.substringAfter('#', "")
        val params = fragment.indexOf('?')
        return if (params >= 0 && fragment.indexOf("serverDescription=", params) > 0)
            decode(fragment.substring(0, params)) to describe(query(fragment.substring(params + 1))["serverDescription"])
        else decode(fragment).ifEmpty { "Shadowsocks" } to ""
    }

    /** Provider caption shown under the server name; Happ sends base64 or plain text, max 30 chars. */
    fun describe(value: String?): String {
        if (value.isNullOrBlank()) return ""
        var text = value.trim()
        if (text.matches(Regex("[A-Za-z0-9+/=_-]{4,}"))) try { val d = base64(text); if (readable(d)) text = d } catch (_: Exception) {}
        text = text.replace(Regex("[\\r\\n\\t]"), " ").trim()
        return text.take(30)
    }

    fun readable(s: String?): Boolean = !s.isNullOrBlank() && s.none { c -> c == '�' || (c < ' ' && c != '\n' && c != '\r' && c != '\t') }

    class Link {
        var scheme = ""; var userInfo = ""; var host = ""; var fragment = ""; var description = ""; var ports = ""
        var port = -1
        var query: Map<String, String> = emptyMap()
    }

    /** Share link split without java.net.URI, which throws on the raw spaces and emoji providers send. */
    fun split(text: String): Link {
        val l = Link()
        val mark = text.indexOf("://")
        if (mark < 0) throw IllegalArgumentException(L.t("Это не ссылка подключения.", "This is not a connection link."))
        l.scheme = text.substring(0, mark).lowercase(Locale.ROOT)
        var rest = text.substring(mark + 3)
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            var fragment = rest.substring(hash + 1)
            val params = fragment.indexOf('?')
            if (params >= 0 && fragment.indexOf("serverDescription=", params) > 0) {
                l.description = describe(query(fragment.substring(params + 1))["serverDescription"])
                fragment = fragment.substring(0, params)
            }
            l.fragment = decode(fragment)
            rest = rest.substring(0, hash)
        }
        val question = rest.indexOf('?')
        if (question >= 0) { l.query = query(rest.substring(question + 1)); rest = rest.substring(0, question) }
        val slash = rest.indexOf('/')
        if (slash >= 0) rest = rest.substring(0, slash)
        val at = rest.lastIndexOf('@')
        if (at >= 0) { l.userInfo = rest.substring(0, at); rest = rest.substring(at + 1) }
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) throw IllegalArgumentException(L.t("Незакрытый IPv6-адрес в ссылке.", "Unclosed IPv6 address in the link."))
            l.host = rest.substring(1, close)
            val tail = rest.substring(close + 1)
            if (tail.startsWith(":")) l.port = number(tail.substring(1))
        } else {
            val colon = rest.lastIndexOf(':')
            if (colon >= 0) {
                l.host = rest.substring(0, colon)
                val tail = rest.substring(colon + 1).trim()
                // Hysteria port hopping writes a range list in the port position: host:5000-6000,7044
                if (tail.matches(Regex("[0-9]+([,-][0-9]+)+"))) { l.ports = tail; l.port = number(tail.split(',', '-')[0]) } else l.port = number(tail)
            } else l.host = rest
        }
        l.host = l.host.trim()
        if (l.host.isEmpty()) throw IllegalArgumentException(L.t("В ссылке отсутствует адрес сервера.", "The link has no server address."))
        return l
    }

    private fun number(v: String): Int = v.trim().toIntOrNull() ?: throw IllegalArgumentException(L.t("Некорректный порт в ссылке: ", "Invalid port in the link: ") + v)
}

/** Share links libXray's converter may not know, turned into Xray outbounds locally. */
object XrayLinks {
    fun outbound(link: String): JSONObject? {
        val l = link.trim().lowercase(Locale.ROOT)
        if (l.startsWith("hy2://") || l.startsWith("hysteria2://")) return hysteria2(link)
        if (l.startsWith("wireguard://") || l.startsWith("wg://")) return wireguard(link)
        return null
    }

    private fun Map<String, String>.q(fallback: String, vararg keys: String) = keys.firstNotNullOfOrNull { this[it]?.trim()?.takeIf { v -> v.isNotEmpty() } } ?: fallback

    fun hysteria2(link: String): JSONObject {
        val l = ImportParser.split(link); val q = l.query
        if (q.q("", "obfs").isNotEmpty()) throw IllegalArgumentException(L.t("Hysteria2 с obfs не поддерживается Xray.", "Hysteria2 with obfs is not supported by Xray."))
        val hy = jsonOf("version" to 2, "auth" to ImportParser.decode(l.userInfo))
        val hops = q.q(l.ports, "mport", "ports")
        if (hops.isNotEmpty()) {
            val hop = jsonOf("port" to hops.replace(':', '-'))
            q.q("", "mportHopInt", "hopInterval", "hop_interval").takeIf { it.isNotEmpty() }?.let { hop.put("interval", it.removeSuffix("s")) }
            hy.put("udphop", hop)
        }
        val tls = jsonOf("serverName" to q.q(l.host, "sni", "peer", "serverName"))
        val alpn = q.q("", "alpn")
        tls.put("alpn", if (alpn.isEmpty()) jsonArrayOf("h3") else alpn.split(',').toJsonArray())
        // Xray 26 removed allowInsecure: the certificate is verified (pinned when the link has a pin).
        val pin = q.q("", "pinSHA256", "pinnedPeerCertSha256", "pcs").replace(":", "").lowercase(Locale.ROOT)
        if (pin.isNotEmpty()) tls.put("pinnedPeerCertSha256", pin)
        return jsonOf("protocol" to "hysteria", "tag" to "proxy", "settings" to jsonOf("version" to 2, "address" to l.host, "port" to if (l.port > 0) l.port else 443),
            "streamSettings" to jsonOf("network" to "hysteria", "hysteriaSettings" to hy, "security" to "tls", "tlsSettings" to tls))
    }

    fun wireguard(link: String): JSONObject {
        val l = ImportParser.split(link); val q = l.query
        val key = ImportParser.decode(l.userInfo)
        if (key.isEmpty()) throw IllegalArgumentException(L.t("В ссылке WireGuard нет приватного ключа.", "The WireGuard link has no private key."))
        val pub = q.q("", "publickey", "publicKey", "public_key")
        if (pub.isEmpty()) throw IllegalArgumentException(L.t("В ссылке WireGuard нет публичного ключа сервера.", "The WireGuard link has no server public key."))
        val host = if (':' in l.host) "[${l.host}]" else l.host
        val peer = jsonOf("publicKey" to pub, "endpoint" to "$host:${if (l.port > 0) l.port else 51820}", "allowedIPs" to jsonArrayOf("0.0.0.0/0", "::/0"))
        q.q("", "presharedkey", "psk").takeIf { it.isNotEmpty() }?.let { peer.put("preSharedKey", it) }
        val settings = jsonOf("secretKey" to key, "peers" to jsonArrayOf(peer))
        val address = q.q("", "address", "ip").split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }
        if (address.isNotEmpty()) settings.put("address", address.toJsonArray())
        val reserved = q.q("", "reserved").split(',').mapNotNull { it.trim().toIntOrNull() }
        if (reserved.isNotEmpty()) settings.put("reserved", reserved.toJsonArray())
        q.q("", "mtu").toIntOrNull()?.let { settings.put("mtu", it) }
        return jsonOf("protocol" to "wireguard", "tag" to "proxy", "settings" to settings)
    }
}

/**
 * A full Xray document becomes one profile. Its proxy servers stay inside that profile as
 * candidates for the automatic choice; the document itself runs unchanged in the core.
 */
object XrayConfig {
    private val LOCAL = setOf("freedom", "blackhole", "dns", "loopback")

    fun looksLike(root: JSONObject): Boolean {
        if (root.has("protocol") && !root.has("type")) return true
        for (o in root.optJSONArray("outbounds").objects()) {
            if (o.has("type")) return false
            if (o.has("protocol") || o.has("streamSettings") || o.has("settings")) return true
        }
        if (root.optJSONArray("inbounds").objects().any { it.has("protocol") }) return true
        if (root.optJSONObject("routing") != null || root.optJSONObject("policy") != null || root.has("remarks")) return true
        return root.optJSONObject("log")?.has("loglevel") == true
    }

    fun profile(root: JSONObject, raw: String): JSONObject {
        val outbounds = root.optJSONArray("outbounds").objects()
        if (outbounds.isEmpty()) {
            if (root.has("protocol")) return ImportParser.profile(root.optString("tag", "Xray"), Endpoint.fromXray(root) ?: throw IllegalArgumentException(L.t("Это не прокси-сервер.", "This is not a proxy server.")), raw)
            throw IllegalArgumentException(L.t("В конфигурации Xray нет исходящих подключений.", "The Xray configuration has no outbounds."))
        }
        val proxies = outbounds.filter { it.optString("protocol").lowercase(Locale.ROOT) !in LOCAL }
        if (proxies.isEmpty()) throw IllegalArgumentException(L.t("В конфигурации Xray нет прокси-исходящего, только direct/block.", "The Xray configuration has no proxy outbound, only direct/block."))
        val baseName = root.str("remarks", "name").ifEmpty { "Xray" }
        val candidates = mutableListOf<JSONObject>(); val errors = mutableListOf<String>()
        for (proxy in proxies) for (variant in variants(proxy)) try {
            val e = Endpoint.fromXray(variant) ?: continue
            e.put("tag", String.format(Locale.ROOT, "xray-candidate-%02d", candidates.size + 1))
            candidates += e
        } catch (ex: Exception) { errors += ex.safe() }
        if (candidates.isEmpty()) throw IllegalArgumentException(errors.firstOrNull() ?: L.t("Нет поддерживаемых серверов в конфигурации Xray.", "No supported servers in the Xray configuration."))
        val profile = ImportParser.profile(baseName, candidates[0], raw)
        if (candidates.size > 1) profile.put("xrayCandidates", candidates.toJsonArray())
        val routing = routing(root, baseName)
        if (routing.length() > 1) profile.put("routing", routing)
        ImportParser.describe(root.optJSONObject("meta").str("serverDescription")).takeIf { it.isNotEmpty() }?.let { profile.put("description", it) }
        if (errors.isNotEmpty()) profile.put("xraySkipped", errors.size)
        return profile
    }

    /** Several hosts in one outbound (vnext / servers) are separate candidates too. */
    private fun variants(outbound: JSONObject): List<JSONObject> {
        val protocol = outbound.optString("protocol").lowercase(Locale.ROOT)
        val key = when (protocol) { "vless", "vmess" -> "vnext"; "trojan", "shadowsocks", "socks", "http" -> "servers"; else -> return listOf(outbound) }
        val peers = outbound.optJSONObject("settings")?.optJSONArray(key)
        if (peers == null || peers.length() < 2) return listOf(outbound)
        return peers.objects().map { peer -> outbound.copy().also { it.getJSONObject("settings").put(key, jsonArrayOf(peer.copy())) } }
    }

    /** routing + dns of an Xray config → a routing profile in Happ's shape, with ordered "Rules". */
    fun routing(root: JSONObject, name: String): JSONObject {
        val out = jsonOf("Name" to name)
        val rules = JSONArray()
        root.optJSONObject("routing")?.let { r ->
            r.str("domainStrategy").takeIf { it.isNotEmpty() }?.let { out.put("DomainStrategy", it) }
            for (rule in r.optJSONArray("rules").objects()) {
                val tag = rule.str("outboundTag", "outbound").lowercase(Locale.ROOT)
                if (tag == "dns-out" || tag == "dns") continue
                val c = jsonOf("action" to when { "block" in tag || "blackhole" in tag -> "block"; "direct" in tag || "freedom" in tag -> "direct"; else -> "proxy" })
                var matches = false
                rule.list("domain", "domains").takeIf { it.length() > 0 }?.let { c.put("domain", it); matches = true }
                rule.list("ip", "ips").takeIf { it.length() > 0 }?.let { c.put("ip", it); matches = true }
                rule.str("port").takeIf { it.isNotEmpty() }?.let { c.put("port", it); matches = true }
                rule.list("protocol").takeIf { it.length() > 0 }?.let { c.put("protocol", it); matches = true }
                rule.str("network").takeIf { it.isNotEmpty() }?.let { c.put("network", it) }
                if (matches) rules.put(c)
            }
        }
        if (rules.length() > 0) out.put("Rules", rules)
        root.optJSONObject("dns")?.let { dns ->
            dnsServer(dns.optJSONArray("servers")).takeIf { it.isNotEmpty() }?.let { out.put("RemoteDns", it) }
            dns.str("queryStrategy").takeIf { it.isNotEmpty() }?.let { out.put("QueryStrategy", it) }
            dns.optJSONObject("hosts")?.takeIf { it.length() > 0 }?.let { out.put("DnsHosts", it) }
        }
        return out
    }

    private fun dnsServer(servers: JSONArray?): String {
        var fallback = ""
        for (i in 0 until (servers?.length() ?: 0)) {
            val item = servers!!.opt(i)
            val address = (if (item is JSONObject) item.str("address", "server") else item.toString()).trim()
            if (address.isEmpty() || address == "null" || address.equals("localhost", true) || address.startsWith("fakedns")) continue
            if (address.startsWith("https://") || address.startsWith("quic") || address.startsWith("h3://")) return address
            if (fallback.isEmpty()) fallback = address
        }
        return fallback
    }
}
