package org.dftz.androidproxy

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A small forward proxy that serves BOTH protocols on one port:
 *   - HTTP proxy (absolute-URI GET/POST/... and CONNECT tunneling for HTTPS)
 *   - SOCKS5 (CONNECT command, no-auth)
 *
 * It peeks the first byte of each connection: 0x05 => SOCKS5, anything else => HTTP.
 * Browsers can therefore point at <device-ip>:<port> as either an HTTP or a SOCKS5 proxy.
 *
 * If [username] is non-empty, clients must authenticate: SOCKS5 via username/password
 * (RFC 1929) and HTTP via Basic "Proxy-Authorization". Otherwise the proxy is open.
 */
class ProxyServer(
    private val port: Int,
    private val username: String? = null,
    private val password: String? = null,
    private val log: (String) -> Unit = {}
) {
    private val authRequired = !username.isNullOrEmpty()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    fun start() {
        if (running) return
        running = true
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        val p = Executors.newCachedThreadPool()
        pool = p
        p.execute {
            log("listening on 0.0.0.0:$port (HTTP + SOCKS5)")
            while (running) {
                try {
                    val c = ss.accept()
                    p.execute { handle(c) }
                } catch (e: Exception) {
                    if (running) log("accept error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        pool?.shutdownNow()
        serverSocket = null
        pool = null
        log("stopped")
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val pin = PushbackInputStream(client.getInputStream(), 1)
            val first = pin.read()
            if (first == -1) { close(client); return }
            pin.unread(first)
            if (first == 0x05) handleSocks5(client, pin)
            else handleHttp(client, pin)
        } catch (e: Exception) {
            log("conn error: ${e.message}")
            close(client)
        }
    }

    // ---------------- HTTP / HTTPS ----------------
    private fun handleHttp(client: Socket, pin: PushbackInputStream) {
        val cout = client.getOutputStream()
        val requestLine = readLine(pin)
        if (requestLine.isNullOrEmpty()) { close(client); return }
        val sp = requestLine.split(" ")
        if (sp.size < 3) { close(client); return }
        val method = sp[0]
        val target = sp[1]
        val version = sp[2]

        val headers = ArrayList<String>()
        while (true) {
            val line = readLine(pin) ?: break
            if (line.isEmpty()) break
            headers.add(line)
        }

        if (authRequired && !httpAuthOk(headers)) {
            send(
                cout,
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"android-proxy\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            )
            log("HTTP 407 (auth required)")
            close(client); return
        }

        if (method.equals("CONNECT", true)) {
            val (host, hport) = splitHostPort(target, 443)
            val up = try { connect(host, hport) } catch (e: Exception) {
                send(cout, "HTTP/1.1 502 Bad Gateway\r\n\r\n"); close(client); return
            }
            send(cout, "HTTP/1.1 200 Connection Established\r\n\r\n")
            log("CONNECT $host:$hport")
            tunnel(client, up, pin)
        } else {
            val uri = try { URI(target) } catch (e: Exception) { close(client); return }
            val host = uri.host ?: run { close(client); return }
            val hport = if (uri.port == -1) 80 else uri.port
            var path = uri.rawPath ?: ""
            if (path.isEmpty()) path = "/"
            if (uri.rawQuery != null) path += "?" + uri.rawQuery
            val up = try { connect(host, hport) } catch (e: Exception) {
                send(cout, "HTTP/1.1 502 Bad Gateway\r\n\r\n"); close(client); return
            }
            val uout = up.getOutputStream()
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
            for (h in headers) {
                if (h.startsWith("Proxy-Connection", true)) continue
                if (h.startsWith("Proxy-Authorization", true)) continue
                sb.append(h).append("\r\n")
            }
            sb.append("\r\n")
            uout.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            uout.flush()
            log("$method $host:$hport$path")
            tunnel(client, up, pin)
        }
    }

    // ---------------- SOCKS5 ----------------
    private fun handleSocks5(client: Socket, pin: PushbackInputStream) {
        val out = client.getOutputStream()

        // greeting: VER NMETHODS METHODS...
        if (pin.read() != 0x05) { close(client); return }
        val n = pin.read()
        if (n < 0) { close(client); return }
        val methods = ByteArray(n)
        readFully(pin, methods)

        if (authRequired) {
            if (!methods.contains(0x02.toByte())) {           // client can't do user/pass
                out.write(byteArrayOf(0x05, 0xFF.toByte())); out.flush(); close(client); return
            }
            out.write(byteArrayOf(0x05, 0x02)); out.flush()   // select username/password
            // RFC 1929 sub-negotiation: VER ULEN UNAME PLEN PASSWD
            if (pin.read() != 0x01) { close(client); return }
            val ul = pin.read(); val ub = ByteArray(if (ul < 0) 0 else ul); readFully(pin, ub)
            val pl = pin.read(); val pb = ByteArray(if (pl < 0) 0 else pl); readFully(pin, pb)
            val ok = String(ub, Charsets.UTF_8) == username && String(pb, Charsets.UTF_8) == password
            out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01)); out.flush()
            if (!ok) { log("SOCKS5 auth failed"); close(client); return }
        } else {
            out.write(byteArrayOf(0x05, 0x00)); out.flush()   // no-auth
        }

        // request: VER CMD RSV ATYP DST.ADDR DST.PORT
        if (pin.read() != 0x05) { close(client); return }
        val cmd = pin.read()
        pin.read() // RSV
        val atyp = pin.read()
        val host: String = when (atyp) {
            0x01 -> { val a = ByteArray(4); readFully(pin, a); ipv4(a) }
            0x03 -> { val len = pin.read(); val a = ByteArray(len); readFully(pin, a); String(a, Charsets.US_ASCII) }
            0x04 -> { val a = ByteArray(16); readFully(pin, a); InetAddress.getByAddress(a).hostAddress ?: "" }
            else -> { socksReply(out, 0x08); close(client); return } // address type not supported
        }
        val dport = ((pin.read() and 0xff) shl 8) or (pin.read() and 0xff)

        if (cmd != 0x01) { socksReply(out, 0x07); close(client); return } // only CONNECT

        val up = try { connect(host, dport) } catch (e: Exception) {
            socksReply(out, 0x05); close(client); return // connection refused
        }
        socksReply(out, 0x00) // success
        log("SOCKS5 $host:$dport")
        tunnel(client, up, pin)
    }

    private fun socksReply(out: OutputStream, rep: Int) {
        // VER REP RSV ATYP=IPv4 BND.ADDR=0.0.0.0 BND.PORT=0
        out.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    // ---------------- helpers ----------------
    private fun connect(host: String, p: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, p), 30_000)
        return s
    }

    /** Pipe bytes both directions until either side closes, then close both. */
    private fun tunnel(client: Socket, upstream: Socket, clientIn: InputStream) {
        val cOut = client.getOutputStream()
        val uOut = upstream.getOutputStream()
        val uIn = upstream.getInputStream()
        val t = thread(isDaemon = true) {
            try { copy(clientIn, uOut) } catch (_: Exception) {}
            try { upstream.shutdownOutput() } catch (_: Exception) {}
        }
        try { copy(uIn, cOut) } catch (_: Exception) {}
        try { client.shutdownOutput() } catch (_: Exception) {}
        try { t.join(1000) } catch (_: Exception) {}
        close(client); close(upstream)
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r == -1) break
            output.write(buf, 0, r)
            output.flush()
        }
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = input.read(b, off, b.size - off)
            if (r == -1) throw EOFException()
            off += r
        }
    }

    /** Reads one CRLF/LF-terminated line as ISO-8859-1, without consuming extra bytes. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (c == 0x0A) return buf.toString("ISO-8859-1") // \n
            if (c == 0x0D) continue                          // skip \r
            buf.write(c)
        }
    }

    private fun splitHostPort(s: String, def: Int): Pair<String, Int> {
        val i = s.lastIndexOf(':')
        return if (i > 0 && i < s.length - 1) {
            Pair(s.substring(0, i), s.substring(i + 1).toIntOrNull() ?: def)
        } else Pair(s, def)
    }

    private fun ipv4(a: ByteArray): String =
        "${a[0].toInt() and 0xff}.${a[1].toInt() and 0xff}.${a[2].toInt() and 0xff}.${a[3].toInt() and 0xff}"

    private fun httpAuthOk(headers: List<String>): Boolean {
        val h = headers.firstOrNull { it.startsWith("Proxy-Authorization:", true) } ?: return false
        val token = h.substringAfter(":").trim()
        if (!token.regionMatches(0, "Basic ", 0, 6, ignoreCase = true)) return false
        return try {
            val decoded = String(Base64.decode(token.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
            decoded == "$username:$password"
        } catch (e: Exception) {
            false
        }
    }

    private fun send(out: OutputStream, s: String) {
        out.write(s.toByteArray(Charsets.ISO_8859_1)); out.flush()
    }

    private fun close(s: Socket) { try { s.close() } catch (_: Exception) {} }
}
