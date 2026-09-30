package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.muisc.cli.lab.LabContext
import dev.muisc.cli.lab.LabError
import dev.muisc.cli.lab.LabServer
import java.net.BindException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `muisc lab [FILES|DIRS...] [--fixtures] [--port N]` — the Transition Lab: a local web page for designing, tuning
 * and blind-testing transitions by ear. Serves on http://127.0.0.1:8765/ (loopback only) until interrupted; renders
 * are written to a temporary session directory that is deleted on exit. See docs/CLI.md ("Lab").
 */
class LabCommand : MuiscCommand("lab") {
    override fun help(context: Context) = "Open the Transition Lab: a local web page to design, tune and blind-test transitions by ear."

    private val paths by argument("PATH", help = "Audio files or folders to load (more can be added from the page).").file(mustExist = true).multiple()
    private val fixtures by option("--fixtures", help = "Also load the four synthetic fixture songs (works with no music at all).").flag()
    private val port by option("--port", metavar = "N", help = "Port on 127.0.0.1 (default 8765; 0 picks a free one).").int().restrictTo(0..65535).default(LabServer.DEFAULT_PORT)

    override fun execute(ctx: CliContext) {
        val sessionDir = Files.createTempDirectory("muisc-lab-").toFile()
        // What the shutdown hook closes; set as each part is built. (Volatile: the hook runs on another thread.)
        val opened = object {
            @Volatile var lab: LabContext? = null
            @Volatile var server: LabServer? = null
        }
        val closed = AtomicBoolean(false)
        val stop = CountDownLatch(1)
        // Idempotent: run by the shutdown hook (Ctrl-C, SIGTERM) or by the failure path below, whichever comes first.
        val shutdown = {
            if (closed.compareAndSet(false, true)) {
                opened.server?.close()
                opened.lab?.close()
                sessionDir.deleteRecursively() // also when the LabContext was never built
            }
            stop.countDown()
        }
        // Registered before anything is written into the session directory: writing the fixtures and analysing a
        // folder can take minutes, and Ctrl-C during that must delete the directory too.
        val hook = Thread { shutdown() }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            val lab = LabContext(ctx, sessionDir).also { opened.lab = it }
            val server = try {
                LabServer(lab, port).also { opened.server = it }
            } catch (e: BindException) {
                throw CliktError("port $port is in use (another Lab running?). Try --port 0 for a free one.")
            }
            if (fixtures) {
                echo("writing and analysing the fixture songs…")
                for (t in lab.addFixtures()) echo("  ${t.id}  ${t.file.name}")
            }
            for (p in paths) {
                echo("analysing ${p.path}…")
                try {
                    val r = lab.addPath(p) { _, _, name -> echo("  $name") }
                    for (s in r.skipped) echo("  skipped: $s", err = true)
                } catch (e: LabError) {
                    echo("  skipped: ${e.message}", err = true)
                }
            }
            server.start()
            echo("")
            echo("Transition Lab: ${server.url}")
            echo("(loopback only; renders go to ${lab.renderDir.path} and are deleted when you stop the Lab with Ctrl-C)")
            stop.await()
        } catch (e: Exception) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // The JVM is already shutting down, so the hook is running; shutdown() below is then a no-op.
            }
            shutdown()
            throw e
        }
    }
}
