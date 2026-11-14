package com.nuvio.tv.livetv.lucid

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.random.Random

/**
 * A tiny one-shot web receiver for packs coming from a phone on the same Wi-Fi.
 *
 * The TV shows a QR code (and a PIN). The phone opens the page, picks a `.lucid` file or pastes a
 * share code, and sends it back. The page is served by the TV itself, so there is no account,
 * no cloud, and nothing leaves the local network.
 */
class LucidShareServer {

    data class Session(val url: String, val pin: String, val port: Int)

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var pin = ""
    @Volatile private var onPack: ((String) -> Unit)? = null
    @Volatile private var downloadPack: String? = null

    /** Receive a pack from a phone. */
    fun start(onPack: (String) -> Unit): Session? {
        this.onPack = onPack
        this.downloadPack = null
        return startServer()
    }

    /** Offer this pack to a phone, so it can be saved and shared from there. */
    fun startDownload(packJson: String): Session? {
        this.onPack = null
        this.downloadPack = packJson
        return startServer()
    }

    @Synchronized
    private fun startServer(): Session? {
        stop()
        val ip = localIp() ?: return null
        val socket = runCatching {
            ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        }.getOrNull() ?: return null
        val p = (100000 + Random.nextInt(900000)).toString()
        pin = p
        server = socket
        running = true
        val session = Session("http://$ip:${socket.localPort}/?pin=$p", p, socket.localPort)
        thread = Thread({
            while (running) {
                val client = try { socket.accept() } catch (_: SocketException) { break } catch (_: Exception) { continue }
                try {
                    client.use { handle(it, session) }
                } catch (_: Exception) {
                    // One bad request must not take the receiver down.
                }
            }
        }, "lucid-share").apply {
            isDaemon = true
            start()
        }
        return session
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        thread = null
        onPack = null
        downloadPack = null
    }

    private fun handle(socket: Socket, session: Session) {
        socket.soTimeout = 15_000
        val input = socket.inputStream
        val header = readHeader(input) ?: return
        val requestLine = header.lineSequence().firstOrNull().orEmpty()
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val headers = header.lineSequence().drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()

        if (method == "GET" && (path == "/" || path == "/index.html")) {
            respond(socket, 200, "text/html; charset=utf-8", page(session))
            return
        }
        if (method == "GET" && path == "/pack.lucid") {
            val sentPin = queryParam(query, "pin") ?: headers["x-lucid-pin"]
            if (sentPin != pin) {
                respond(socket, 403, "text/plain", "Wrong PIN")
                return
            }
            val pack = downloadPack
            if (pack == null) {
                respond(socket, 404, "text/plain", "No pack")
                return
            }
            respondDownload(socket, pack)
            return
        }
        if (method != "POST" || path != "/lucid" || onPack == null) {
            respond(socket, 404, "text/plain", "Not found")
            return
        }
        val sentPin = headers["x-lucid-pin"] ?: queryParam(query, "pin")
        if (sentPin != pin) {
            respond(socket, 403, "text/plain", "Wrong PIN")
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) {
            respond(socket, 400, "text/plain", "Empty pack")
            return
        }
        if (length > 4 * 1024 * 1024) {
            respond(socket, 413, "text/plain", "Pack too large")
            return
        }
        val body = readBody(input, length) ?: run {
            respond(socket, 400, "text/plain", "Incomplete pack")
            return
        }
        val text = body.toString(Charsets.UTF_8)
        val parsed = LucidShareCodec.decode(text)
        if (parsed.isNullOrEmpty()) {
            respond(socket, 400, "text/plain", "That is not a Lucid TV pack")
            return
        }
        respond(socket, 200, "text/plain", "OK ${parsed.size}")
        onPack?.invoke(text)
        stop()
    }

    private fun readHeader(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < 24_000) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            state = when {
                (state == 0 || state == 2) && b == '\r'.code -> state + 1
                (state == 1 || state == 3) && b == '\n'.code -> state + 1
                else -> 0
            }
            if (state == 4) return out.toString(Charsets.UTF_8.name())
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): ByteArray? {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n <= 0) return null
            read += n
        }
        return data
    }

    private fun respond(socket: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Too Large"; else -> "Error" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            socket.outputStream.write(head.toByteArray(Charsets.UTF_8))
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }
    }

    private fun respondDownload(socket: Socket, pack: String) {
        val bytes = pack.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Disposition: attachment; filename=\"lucid-tv.lucid\"\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            socket.outputStream.write(head.toByteArray(Charsets.UTF_8))
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }
    }

    private fun queryParam(query: String, key: String): String? = query.split('&').firstNotNullOfOrNull { part ->
        val i = part.indexOf('=')
        if (i <= 0 || !part.substring(0, i).equals(key, true)) null
        else java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
    }

    private fun localIp(): String? {
        fun from(name: String): String? = runCatching {
            NetworkInterface.getByName(name)?.inetAddresses?.toList()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
        from("wlan0")?.let { return it }
        from("eth0")?.let { return it }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun page(session: Session): String {
        val download = downloadPack
        if (download != null) {
            return """
                <!doctype html>
                <html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Lucid TV channel pack</title>
                <style>
                  body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:24px}
                  main{max-width:560px;margin:0 auto}
                  h1{font-size:22px;margin:0 0 6px} p{color:#aaa;line-height:1.45}
                  a{display:block;text-align:center;text-decoration:none;background:#e8e8e8;color:#111;font-weight:700;border-radius:10px;padding:14px;margin-top:18px}
                  small{color:#888}
                </style></head><body><main>
                <h1>Lucid TV channel pack</h1>
                <p>Save this pack on your phone, then send it to another TV or share it in a message.</p>
                <a href="/pack.lucid?pin=__PIN__">Download .lucid pack</a>
                <p><small>It is sent directly from this TV over your Wi-Fi. Nothing is uploaded to the internet.</small></p>
                </main></body></html>
            """.trimIndent().replace("__PIN__", session.pin)
        }
        return """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Send Lucid TV channels</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:24px}
          main{max-width:560px;margin:0 auto}
          h1{font-size:22px;margin:0 0 6px} p{color:#aaa;line-height:1.45}
          input,textarea,button{width:100%;box-sizing:border-box;font:inherit;border-radius:10px;border:1px solid #444;background:#1c1c1c;color:#fff;padding:12px;margin:8px 0}
          textarea{min-height:130px} button{background:#e8e8e8;color:#111;font-weight:700;border:0}
          .ok{color:#7fdc9a}.bad{color:#ff8d8d} small{color:#888}
        </style></head><body><main>
        <h1>Send to Lucid TV</h1>
        <p>Choose a <b>.lucid</b> file exported from the app, or paste a Lucid share code.</p>
        <label>TV PIN</label><input id="pin" inputmode="numeric" value="__PIN__">
        <label>Pack file</label><input id="file" type="file" accept=".lucid,.json,application/json,text/plain">
        <p>or paste a code / link</p><textarea id="code" placeholder="lucid1:… or nuvio://lucid?pack=…"></textarea>
        <button id="send">Send to TV</button>
        <p id="status"></p>
        <small>The file is sent directly to this TV over your Wi-Fi. Nothing is uploaded to the internet.</small>
        <script>
          const status = document.getElementById('status');
          const pin = document.getElementById('pin');
          async function send(text) {
            if (!pin.value.trim()) { status.className='bad'; status.textContent='Enter the PIN shown on the TV.'; return; }
            status.className=''; status.textContent='Sending…';
            try {
              const r = await fetch('/lucid', {method:'POST', headers:{'Content-Type':'text/plain','X-Lucid-Pin':pin.value.trim()}, body:text});
              const t = await r.text();
              if (r.ok) { status.className='ok'; status.textContent='Sent. Check the TV.'; }
              else { status.className='bad'; status.textContent=t || 'Could not send.'; }
            } catch(e) { status.className='bad'; status.textContent='Could not reach the TV. Are both on the same Wi-Fi?'; }
          }
          document.getElementById('file').addEventListener('change', async (e) => {
            const f = e.target.files[0]; if (!f) return;
            send(await f.text());
          });
          document.getElementById('send').addEventListener('click', () => send(document.getElementById('code').value));
        </script></main></body></html>
    """.trimIndent().replace("__PIN__", session.pin)
    }
}
