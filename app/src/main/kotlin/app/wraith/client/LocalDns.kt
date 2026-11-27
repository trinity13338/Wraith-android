package app.wraith.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A DNS endpoint on loopback for the Go core (libXray's SetDNS needs an ip:port) and Xray's DNS module.
 * With a Private DNS host set on the phone, every query goes to Android's own resolver on the physical
 * network, i.e. only to that host. Without it, queries go over DNS-over-TLS to 1.1.1.1 (1.0.0.1 as the
 * second address); only if both fail does the network's own resolver answer.
 */
class LocalDns private constructor(private val context: Context, private val udp: DatagramSocket, private val tcp: ServerSocket) {
    val endpoint: String get() = "127.0.0.1:${udp.localPort}"
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "wraith-dns").apply { isDaemon = true } }
    @Volatile private var closed = false
    /** Open DoT connections, reused for a short while: (socket, when it was last used). */
    private val idle = java.util.ArrayDeque<Pair<SSLSocket, Long>>()

    private fun start(): LocalDns {
        pool.execute {
            val buf = ByteArray(4096)
            while (!closed) try {
                val p = DatagramPacket(buf, buf.size); udp.receive(p)
                val query = p.data.copyOfRange(p.offset, p.offset + p.length); val from = p.socketAddress
                pool.execute {
                    val answer = forUdp(query, answer(query) ?: return@execute)
                    try { udp.send(DatagramPacket(answer, answer.size, from)) } catch (_: Exception) {}
                }
            } catch (e: Exception) { if (!closed) Log.w(TAG, "udp", e) }
        }
        pool.execute {
            while (!closed) try { val s = tcp.accept(); pool.execute { serve(s) } } catch (e: Exception) { if (!closed) Log.w(TAG, "tcp", e) }
        }
        return this
    }

    /** DNS over TCP: two-byte length, then the message; Go falls back to it for truncated answers. */
    private fun serve(s: Socket) = try {
        s.use {
            s.soTimeout = 15_000
            val input = DataInputStream(s.getInputStream()); val output = DataOutputStream(s.getOutputStream())
            while (!closed) {
                val query = ByteArray(input.readUnsignedShort()); input.readFully(query)
                val answer = answer(query) ?: break
                output.writeShort(answer.size); output.write(answer); output.flush()
            }
        }
    } catch (_: Exception) {}

    fun close() {
        closed = true
        try { udp.close() } catch (_: Exception) {}
        try { tcp.close() } catch (_: Exception) {}
        synchronized(idle) { idle.forEach { try { it.first.close() } catch (_: Exception) {} }; idle.clear() }
        pool.shutdownNow()
    }

    private fun answer(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val network = Networks.physical(context) ?: return failure(query)
        if (!privateDns(network)) {
            for (server in DOT_SERVERS) try { return dot(network, server, query) } catch (e: Exception) { Log.w(TAG, "DoT $server", e) }
        }
        return system(network, query)
    }

    /** A Private DNS host is set for this network («Частный DNS-сервер: имя хоста»). */
    private fun privateDns(network: android.net.Network): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return !cm.getLinkProperties(network)?.privateDnsServerName.isNullOrEmpty()
    }

    /** One query over DNS-over-TLS (RFC 7858), on a reused connection when one is fresh. */
    private fun dot(network: android.net.Network, server: String, query: ByteArray): ByteArray {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val reused = synchronized(idle) {
                var c = idle.pollLast()
                while (c != null && now - c.second > IDLE_MS) { try { c.first.close() } catch (_: Exception) {}; c = idle.pollLast() }
                c?.first
            }
            val s = reused ?: openDot(network, server)
            try {
                val out = DataOutputStream(s.outputStream); out.writeShort(query.size); out.write(query); out.flush()
                val input = DataInputStream(s.inputStream); val answer = ByteArray(input.readUnsignedShort()); input.readFully(answer)
                synchronized(idle) { if (!closed && idle.size < 4) idle.addLast(s to SystemClock.elapsedRealtime()) else s.close() }
                return answer
            } catch (e: java.io.IOException) {
                try { s.close() } catch (_: Exception) {}
                if (reused == null) throw e // a fresh connection failed; a stale reused one is simply replaced
            }
        }
    }

    private fun openDot(network: android.net.Network, server: String): SSLSocket {
        val raw = network.socketFactory.createSocket()
        try {
            raw.connect(InetSocketAddress(InetAddress.getByName(server), 853), 5000)
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, DOT_NAME, 853, true) as SSLSocket
            s.soTimeout = 5000; s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(DOT_NAME, s.session)) { s.close(); throw javax.net.ssl.SSLPeerUnverifiedException(DOT_NAME) }
            return s
        } catch (e: Exception) { try { raw.close() } catch (_: Exception) {}; throw e }
    }

    /** Android's own resolver on the physical network (Private DNS when set, otherwise the network's DNS). */
    private fun system(network: android.net.Network, query: ByteArray): ByteArray {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val result = CompletableFuture<ByteArray>()
                DnsResolver.getInstance().rawQuery(network, query, DnsResolver.FLAG_EMPTY, Runnable::run, CancellationSignal(), object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) { result.complete(answer) }
                    override fun onError(error: DnsResolver.DnsException) { result.completeExceptionally(error) }
                })
                result.get(10, TimeUnit.SECONDS)
            } else {
                // Android 8 has no raw resolver API (and no Private DNS): the query goes to the network's own DNS.
                val server = DnsChoice.read(context).firstOrNull() ?: return failure(query)
                DatagramSocket().use { sock ->
                    network.bindSocket(sock); sock.soTimeout = 5000
                    sock.send(DatagramPacket(query, query.size, InetSocketAddress(InetAddress.getByName(server), 53)))
                    val buf = ByteArray(65_535); val p = DatagramPacket(buf, buf.size); sock.receive(p)
                    buf.copyOf(p.length)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "query", e); failure(query) }
    }

    companion object {
        private const val TAG = "WraithDns"
        /** Settings key the tunnel puts the endpoint under while building the core configuration. */
        const val KEY = "_localDns"
        private val DOT_SERVERS = listOf("1.1.1.1", "1.0.0.1")
        private const val DOT_NAME = "cloudflare-dns.com"
        private const val IDLE_MS = 20_000L
        /** What Go's resolver reads over UDP; anything longer is answered with the truncation bit set. */
        private const val UDP_LIMIT = 1232

        /** UDP and TCP on the same loopback port, since the core dials the one endpoint for both. */
        fun open(context: Context): LocalDns {
            val loopback = InetAddress.getByName("127.0.0.1")
            repeat(20) {
                val udp = DatagramSocket(InetSocketAddress(loopback, 0))
                try { return LocalDns(context.applicationContext, udp, ServerSocket(udp.localPort, 32, loopback)).start() }
                catch (_: java.io.IOException) { udp.close() }
            }
            throw java.io.IOException(L.t("Не удалось открыть локальный DNS.", "Could not open the local DNS."))
        }

        /** The header and question of the query, with the given flags and no records. */
        private fun reply(query: ByteArray, flags: Int): ByteArray {
            var end = 12
            while (end < query.size) { val n = query[end].toInt() and 0xff; if (n == 0) { end++; break }; end += if (n >= 0xc0) 2 else n + 1 }
            end = minOf(query.size, end + 4)
            val out = query.copyOf(end)
            out[2] = (flags shr 8).toByte(); out[3] = flags.toByte()
            out[4] = 0; out[5] = 1; for (i in 6 until 12) out[i] = 0
            return out
        }

        /** SERVFAIL, keeping the query's opcode and RD bit. */
        private fun failure(query: ByteArray) = reply(query, 0x8000 or ((query[2].toInt() and 0x79) shl 8) or 0x80 or 2)

        private fun forUdp(query: ByteArray, answer: ByteArray): ByteArray =
            if (answer.size <= UDP_LIMIT) answer else reply(query, 0x8000 or ((query[2].toInt() and 0x79) shl 8) or 0x0200 or 0x80)
    }
}
