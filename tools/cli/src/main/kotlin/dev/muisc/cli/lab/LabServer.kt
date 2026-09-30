package dev.muisc.cli.lab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Transition Lab's web server: the JDK's built-in [HttpServer], bound to the loopback address only.
 *
 * What it serves:
 *  - `/` and the page's own files (`index.html`, `app.js`, `app.css`) from the classpath (`lab/`);
 *  - `/files/<name>.wav` — renders from the session's render directory, nothing else (see [LabContext.servedFile]),
 *    with HTTP range support so the browser's audio element can seek;
 *  - `/api/...` — the JSON API ([LabApi]).
 *
 * Every request passes [LabGuard] first. See there for the threats each check stops.
 */
class LabServer(
    val lab: LabContext,
    port: Int = DEFAULT_PORT,
    random: Random = SecureRandom(),
) : AutoCloseable {

    val jobs = LabJobs(1)
    val api = LabApi(lab, jobs, random)
    private val http: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32)
    private val pool: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "muisc-lab-http").apply { isDaemon = true } }

    val port: Int get() = http.address.port
    val address: InetAddress get() = http.address.address
    val url: String get() = "http://127.0.0.1:$port/"

    init {
        http.executor = pool
        http.createContext("/") { ex -> handle(ex) }
    }

    fun start(): LabServer {
        http.start()
        return this
    }

    override fun close() {
        http.stop(0)
        pool.shutdownNow()
        pool.awaitTermination(2, TimeUnit.SECONDS)
        jobs.close()
    }

    private fun handle(ex: HttpExchange) {
        try {
            ex.use { serve(it) }
        } catch (_: IOException) {
            // The browser went away mid-response (a seek, a closed tab). Nothing to tell anyone.
        }
    }

    private fun serve(ex: HttpExchange) {
        val method = ex.requestMethod.uppercase()
        LabGuard.refusal(ex.requestHeaders.getFirst("Host"), ex.requestHeaders.getFirst("Origin"), method, ex.requestHeaders.getFirst("Content-Type"), port)?.let { why ->
            sendJson(ex, 403, buildJsonObject { put("error", why) })
            return
        }
        val path = ex.requestURI.rawPath ?: "/"
        try {
            when {
                path.startsWith("/api/") -> {
                    val body = if (method == "POST" || method == "PUT" || method == "DELETE") readBody(ex) else ""
                    sendJson(ex, 200, route(method, path, LabJson.obj(body)))
                }
                path.startsWith("/files/") -> {
                    if (method != "GET" && method != "HEAD") throw LabError(405, "method not allowed")
                    val name = path.removePrefix("/files/")
                    val file = lab.servedFile(name) ?: throw LabError(404, "no such render")
                    sendFile(ex, file, "audio/wav", method == "HEAD")
                }
                else -> {
                    if (method != "GET" && method != "HEAD") throw LabError(405, "method not allowed")
                    val name = if (path == "/") "index.html" else path.removePrefix("/")
                    val type = STATIC[name] ?: throw LabError(404, "not found")
                    val bytes = LabServer::class.java.classLoader.getResourceAsStream("lab/$name")?.use { it.readBytes() }
                        ?: throw LabError(404, "not found")
                    sendBytes(ex, 200, type, bytes, method == "HEAD")
                }
            }
        } catch (e: LabError) {
            sendJson(ex, e.status, buildJsonObject { put("error", e.message ?: "error") })
        } catch (e: com.github.ajalt.clikt.core.CliktError) {
            sendJson(ex, 400, buildJsonObject { put("error", e.message ?: "error") })
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            sendJson(ex, 500, buildJsonObject { put("error", LabJobs.errorText(e)) })
        }
    }

    /** Method + path → handler. Path segments are matched after URL-decoding each one. */
    private fun route(method: String, rawPath: String, body: JsonObject): JsonElement {
        val seg = rawPath.removePrefix("/api/").split('/').map { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        fun methodIs(m: String) { if (method != m) throw LabError(405, "use $m for /api/${seg.joinToString("/")}") }
        return when (seg[0]) {
            "session" -> { methodIs("GET"); api.session() }
            "tracks" -> when {
                seg.size == 1 && method == "GET" -> api.tracks()
                seg.size == 1 && method == "POST" -> api.addTracks(body)
                seg.size == 2 -> { methodIs("GET"); api.trackAnalysis(seg[1]) }
                else -> throw LabError(404, "not found")
            }
            "strategies" -> { methodIs("GET"); api.strategies() }
            "plan" -> { methodIs("POST"); api.plan(body) }
            "render" -> { methodIs("POST"); api.render(body) }
            "jobs" -> { methodIs("GET"); if (seg.size != 2) throw LabError(404, "not found"); api.job(seg[1]) }
            "recipes" -> when {
                seg.size == 1 -> { methodIs("GET"); api.recipes() }
                seg.size == 2 && seg[1] == "validate" -> { methodIs("POST"); api.validateRecipe(body) }
                seg.size == 2 && seg[1] == "lanes" -> { methodIs("POST"); api.recipeLanes(body) }
                seg.size == 2 && seg[1] == "save" -> { methodIs("POST"); api.saveRecipe(body) }
                seg.size == 2 && seg[1] == "template" -> { methodIs("GET"); api.recipeTemplate() }
                seg.size == 3 && seg[1] == "text" -> { methodIs("GET"); api.recipeText(seg[2]) }
                else -> throw LabError(404, "not found")
            }
            "presets" -> when {
                seg.size == 1 && method == "GET" -> api.presets()
                seg.size == 1 && method == "POST" -> api.savePreset(body)
                seg.size == 2 && method == "DELETE" -> api.deletePreset(seg[1])
                else -> throw LabError(404, "not found")
            }
            "styles" -> { methodIs("GET"); api.styles() }
            "pins" -> when {
                seg.size == 1 && method == "GET" -> api.pins()
                seg.size == 1 && method == "POST" -> api.setPin(body)
                seg.size == 2 && seg[1] == "clear" -> { methodIs("POST"); api.clearPin(body) }
                else -> throw LabError(404, "not found")
            }
            "ratings" -> when (method) {
                "GET" -> api.ratings()
                "POST" -> api.rate(body)
                else -> throw LabError(405, "method not allowed")
            }
            "blind" -> when {
                seg.size == 1 -> { methodIs("POST"); api.blind(body) }
                seg.size == 3 && seg[2] == "vote" -> { methodIs("POST"); api.blindVote(seg[1], body) }
                else -> throw LabError(404, "not found")
            }
            "sweep" -> { methodIs("POST"); api.sweep(body) }
            else -> throw LabError(404, "no API endpoint /api/${seg[0]}")
        }
    }

    private fun readBody(ex: HttpExchange): String {
        val bytes = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) throw LabError(413, "the request is larger than ${MAX_BODY / 1024} kB")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun sendJson(ex: HttpExchange, status: Int, body: JsonElement) {
        val bytes = LabJson.json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Cache-Control", "no-store")
        sendBytes(ex, status, "application/json; charset=utf-8", bytes, ex.requestMethod.equals("HEAD", true))
    }

    private fun sendBytes(ex: HttpExchange, status: Int, type: String, bytes: ByteArray, head: Boolean) {
        securityHeaders(ex)
        ex.responseHeaders.set("Content-Type", type)
        if (head) {
            ex.responseHeaders.set("Content-Length", bytes.size.toString())
            ex.sendResponseHeaders(status, -1)
            return
        }
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
    }

    /** Serves [file] whole or, for `Range: bytes=a-b`, the requested part (206) — browsers need this to seek audio. */
    private fun sendFile(ex: HttpExchange, file: File, type: String, head: Boolean) {
        securityHeaders(ex)
        val length = file.length()
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Accept-Ranges", "bytes")
        ex.responseHeaders.set("Cache-Control", "no-cache")
        val range = ex.requestHeaders.getFirst("Range")?.let { parseRange(it, length) }
        if (range == INVALID_RANGE) {
            ex.responseHeaders.set("Content-Range", "bytes */$length")
            ex.sendResponseHeaders(416, -1)
            return
        }
        val (start, end) = range ?: (0L to length - 1)
        val count = end - start + 1
        if (range != null) ex.responseHeaders.set("Content-Range", "bytes $start-$end/$length")
        val status = if (range != null) 206 else 200
        if (head) {
            ex.responseHeaders.set("Content-Length", count.toString())
            ex.sendResponseHeaders(status, -1)
            return
        }
        ex.sendResponseHeaders(status, if (count == 0L) -1 else count)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                ex.responseBody.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun securityHeaders(ex: HttpExchange) {
        val h = ex.responseHeaders
        // Everything the page needs is served from here; no inline script, no third-party anything.
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
        h.set("X-Content-Type-Options", "nosniff")
        h.set("Referrer-Policy", "no-referrer")
        h.set("Cross-Origin-Resource-Policy", "same-origin")
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val MAX_BODY = 1 shl 20
        private val INVALID_RANGE = -1L to -1L

        /** The page's own files and their types; nothing else on the classpath is reachable. */
        val STATIC: Map<String, String> = mapOf(
            "index.html" to "text/html; charset=utf-8",
            "app.js" to "text/javascript; charset=utf-8",
            "app.css" to "text/css; charset=utf-8",
        )

        /** `bytes=a-b`, `bytes=a-`, `bytes=-n` → inclusive (start, end); null for no/unsupported range; [INVALID_RANGE] when unsatisfiable. */
        internal fun parseRange(header: String, length: Long): Pair<Long, Long>? {
            val h = header.trim()
            if (!h.startsWith("bytes=") || h.contains(',')) return null
            val spec = h.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (length <= 0) return INVALID_RANGE
            return when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) INVALID_RANGE else maxOf(0L, length - n) to length - 1
                }
                else -> {
                    val s = a.toLongOrNull() ?: return null
                    val e = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(length - 1)
                    if (s >= length || s > e) INVALID_RANGE else s to e
                }
            }
        }
    }
}

/**
 * Who may talk to the Lab. The server socket is bound to 127.0.0.1 only ([LabServer]), so other machines cannot
 * connect at all (threat: someone on the same network listening to the user's music or triggering renders). The
 * checks here cover what a loopback bind alone does not, because a browser on this computer can be steered by any
 * web page it has open:
 *
 *  - **Host** must be `127.0.0.1:<port>`, `localhost:<port>` or `[::1]:<port>` — stops DNS rebinding, where a web
 *    page on a hostile domain re-points its name at 127.0.0.1 and then reads the API as if it were same-origin;
 *  - **Origin**, when the browser sends one, must be this server — stops another site open in the same browser
 *    from posting to the API (saving recipes, recording votes, filling the disk with renders);
 *  - **POST/PUT/DELETE bodies must be `application/json`** — a cross-site HTML form cannot send that content type
 *    without a CORS preflight, which this server never approves (it sends no CORS headers at all).
 */
object LabGuard {
    fun refusal(host: String?, origin: String?, method: String, contentType: String?, port: Int): String? {
        val allowedHosts = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")
        if (host == null || host.lowercase() !in allowedHosts) return "unexpected Host header; open the Lab at http://127.0.0.1:$port/"
        if (origin != null && origin.lowercase() !in allowedHosts.map { "http://$it" }) return "requests from other sites are not accepted"
        if (method == "POST" || method == "PUT" || method == "DELETE") {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type != "application/json") return "API requests must be sent as application/json"
        }
        return null
    }
}
