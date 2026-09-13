package dev.muisc.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Engine modules are pure JVM: no `android.*` / `androidx.*` references anywhere in the sources. */
class ArchTest {
    @Test
    fun noAndroidReferences() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "src/main/kotlin/dev/muisc/player").isDirectory }
            ?: File("engine/player").absoluteFile
        val files = File(root, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources found under $root")
        val offenders = files.filter { f -> f.readLines().any { l -> l.trimStart().startsWith("import android") || l.contains("androidx.") } }
        assertTrue(offenders.isEmpty(), "android references in: $offenders")
    }
}
