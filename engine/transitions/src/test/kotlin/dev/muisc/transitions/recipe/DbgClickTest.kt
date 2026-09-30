package dev.muisc.transitions.recipe

import dev.muisc.dsp.qa.ArtifactDetector
import java.io.File
import kotlin.test.Test

class DbgClickTest {
    private val dir = "/tmp/claude-0/-home-user-muisc/ba4a12aa-d25d-56d6-944d-4c0ca253ddfe/scratchpad/r2dbg/"

    private fun check(name: String, rec: TransitionRecipe, pair: dev.muisc.transitions.strategies.BeatDomainTestSupport.Pair, silenceA: Boolean = false, silenceB: Boolean = false) {
        val r = RecipeRenderTestSupport.render(rec, pair, silenceA = silenceA, silenceB = silenceB)
        val clicks = ArtifactDetector(44100).analyze(r.out.audio).clicks
        val resolved = RecipeResolver.resolve(rec, r.plan.params, 4)
        val t = RecipeGeometry.timeline(r.plan, resolved, pair.a.analysis, pair.b.analysis, pair.features, pair.prefs)
        println("DBG $name: " + clicks.filter { it.channel == 0 }.joinToString { "frame ${it.frame} bar ${"%.4f".format(t.barAtOutFrame(it.frame.toLong()))} mag ${it.magnitude} ratio ${it.ratio}" })
    }

    @Test fun halfLevel() {
        val pair = RecipeRenderTestSupport.far
        val rec = RecipeFormat.decode(File(dir + "r2.json").readText())
        for (scale in listOf(1.0, 0.7, 0.5, 0.3)) {
            val a = rec.a.copy(level = rec.a.level.map { it.copy(v = Expr.of((it.v.literal ?: 0.0) * scale)) })
            // Keep bar 0 at unity so the boundary rule holds: scale only the later points.
            val lv = listOf(rec.a.level[0]) + a.level.drop(1)
            check("r2 A level x$scale", rec.copy(a = rec.a.copy(level = lv)), pair, silenceB = true)
        }
    }

    @Test fun hpfOnly() {
        val pair = RecipeRenderTestSupport.far
        val a = pair.a.audio
        for ((hz, q) in listOf(110.0 to 2.29, 110.0 to 0.707, 300.0 to 0.707, 1000.0 to 0.707)) {
            for (gain in listOf(1.0f, 1.17f, 1.5f)) {
                val x = Array(a.channelCount) { a[it].copyOf() }
                val f = dev.muisc.dsp.filter.StateVariableFilter(44100, x.size, hz, q)
                f.mode = dev.muisc.dsp.filter.SvfMode.HIGH_PASS
                f.process(x, x, x[0].size)
                for (c in x.indices) for (i in x[c].indices) x[c][i] *= gain
                val clicks = ArtifactDetector(44100).analyze(dev.muisc.audio.AudioBuffer(44100, x)).clicks.filter { it.channel == 0 }
                println("DBG hpf $hz q $q gain $gain: ${clicks.size} clicks ${clicks.take(3)}")
            }
        }
    }

    @Test fun dump() {
        val pair = RecipeRenderTestSupport.far
        val rec = RecipeFormat.decode(File(dir + "r2.json").readText())
        val r = RecipeRenderTestSupport.render(rec, pair, silenceB = true)
        val x = r.out.audio[0]
        val d = r.input.aAudio[0]
        val off = r.plan.aExitOffset
        val sb = StringBuilder()
        for (i in 180518 - 300 until 180518 + 100) sb.append("$i ${x[i]} ${d[off + i]}\n")
        File(dir + "dump2.txt").writeText(sb.toString())
        println("DBG metrics ${r.out.report.metrics} warnings ${r.out.report.warnings}")
    }

    @Test fun dbg() {
        for ((i, pair) in listOf(0 to RecipeRenderTestSupport.near, 2 to RecipeRenderTestSupport.far, 11 to RecipeRenderTestSupport.near)) {
            val rec = RecipeFormat.decode(File(dir + "r$i.json").readText())
            check("r$i full", rec, pair)
            check("r$i A only", rec, pair, silenceB = true)
            check("r$i B only", rec, pair, silenceA = true)
            check("r$i A no fx", rec.copy(a = rec.a.copy(echo = null, reverb = null)), pair, silenceB = true)
            check("r$i A no filters", rec.copy(a = rec.a.copy(hpf = emptyList(), lpf = emptyList())), pair, silenceB = true)
            check("r$i A no eq", rec.copy(a = rec.a.copy(low = emptyList(), mid = emptyList(), high = emptyList())), pair, silenceB = true)
            check("r$i B no filters", rec.copy(b = rec.b.copy(hpf = emptyList(), lpf = emptyList())), pair, silenceA = true)
            check("r$i B no eq", rec.copy(b = rec.b.copy(low = emptyList(), mid = emptyList(), high = emptyList())), pair, silenceA = true)
        }
    }
}
