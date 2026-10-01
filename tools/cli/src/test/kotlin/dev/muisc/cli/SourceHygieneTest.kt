package dev.muisc.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No raw control bytes in source. A literal NUL (or another C0 control other than tab, LF and CR) makes `grep`
 * call the file binary and print nothing for it, so grep-based searches, lints and reviews skip it silently. Raw
 * separators have landed in source three times (MixCommand, then TransitionCoordinator twice); write `"\u0000"`.
 */
class SourceHygieneTest {

    @Test
    fun `no source file contains a raw control byte`() {
        val root = repoRoot()
        val files = sourceFiles(root)
        assertTrue(files.size > 500, "found only ${files.size} source files under $root: the walk is not seeing the repository")
        val offenders = ArrayList<String>()
        for (f in files) {
            val bytes = f.readBytes()
            var line = 1
            for ((i, b) in bytes.withIndex()) {
                val v = b.toInt() and 0xFF
                if (v == '\n'.code) line++
                if (isForbidden(v)) {
                    offenders += "${f.relativeTo(root).invariantSeparatorsPath}:$line: byte 0x${"%02X".format(v)} at offset $i"
                    break
                }
            }
        }
        assertTrue(offenders.isEmpty(), "raw control bytes in source (escape them, e.g. \"\\u0000\"):\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `the forbidden bytes are the C0 controls except tab, LF and CR`() {
        assertTrue(isForbidden(0x00) && isForbidden(0x08) && isForbidden(0x0B) && isForbidden(0x0C) && isForbidden(0x0E) && isForbidden(0x1F))
        assertTrue(!isForbidden('\t'.code) && !isForbidden('\n'.code) && !isForbidden('\r'.code) && !isForbidden(' '.code) && !isForbidden(0x7F))
    }

    private fun isForbidden(v: Int): Boolean = v in 0x00..0x08 || v == 0x0B || v == 0x0C || v in 0x0E..0x1F

    private fun sourceFiles(root: File): List<File> {
        val out = ArrayList<File>()
        root.listFiles()?.filter { it.isFile && it.extension in EXTENSIONS }?.let { out += it }
        for (dir in TREES) {
            val top = File(root, dir)
            if (!top.isDirectory) continue
            top.walkTopDown()
                .onEnter { it.name !in SKIPPED_DIRS }
                .filter { it.isFile && it.extension in EXTENSIONS }
                .forEach { out += it }
        }
        return out.sortedBy { it.path }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "engine").isDirectory) return dir
            dir = dir.parentFile
        }
        fail("cannot find the repository root above ${System.getProperty("user.dir")}")
    }

    private companion object {
        val EXTENSIONS = setOf("kt", "kts", "java", "js", "html", "css", "md", "json", "xml", "sh", "toml")
        val TREES = listOf("engine", "tools", "app", "docs", "gradle")
        val SKIPPED_DIRS = setOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules")
    }
}
