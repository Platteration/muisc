package dev.muisc.app.playback

import android.content.Context
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.stems.MlStemSeparator
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.dsp.stems.StemQuality
import dev.muisc.dsp.stems.StemSeparator
import dev.muisc.dsp.stems.Stems
import java.io.File

/**
 * **STUB — no ONNX Runtime dependency is in the build.** The app ships with [available] = false and every call
 * falls through to [fallback] (the always-available [PseudoStemSeparator]), so nothing in the engine has to know
 * whether a model is installed. It exists so the wiring (registry → renderer → strategies) is already correct the
 * day a model is added, and so the Lab can show "ML stems: not installed" instead of nothing.
 *
 * ## How to plug a real Demucs-class model in
 *
 * 1. **Dependency.** Add `implementation("com.microsoft.onnxruntime:onnxruntime-android:<version>")` to
 *    `app/build.gradle.kts` (≈ 15 MB of native libs; consider an ABI split or a dynamic feature module).
 * 2. **Model.** Do not ship weights in the APK — let the user point at a file. Expect a 4-stem Demucs export
 *    (`htdemucs` / `mdx_extra_q`) converted to ONNX with a fixed input shape `[1, 2, N]` at 44.1 kHz and an output
 *    shape `[1, 4, 2, N]` in the order drums, bass, other, vocals (check the export: the order is a property of the
 *    conversion, not of ONNX). [modelFile] is the conventional location; [available] becomes `modelFile.isFile`.
 * 3. **Session.** Create one `OrtEnvironment` and one `OrtSession` lazily and keep them for the process; set
 *    `SessionOptions.setIntraOpNumThreads(max(1, cores / 2))` and, when present, the NNAPI or XNNPACK execution
 *    provider. Guard the whole thing with try/catch: an unsupported provider must degrade to [fallback], never
 *    throw into the render.
 * 4. **Chunking.** Demucs works on ~7–10 s windows with ~1 s overlap and a raised-cosine cross-fade between
 *    windows; the engine only ever asks for transition windows (≤ 32 s, DESIGN §7.4), so two to four inferences
 *    per call. Resample to the model's rate (44.1 kHz) and back with `dev.muisc.dsp.resample.Resampler` when the
 *    engine runs at 48 kHz.
 * 5. **Invariant.** [Stems] requires `drums + bass + vocals + other == original` within −60 dBFS. Take the model's
 *    drums, bass and vocals, then compute `other = original − (drums + bass + vocals)` — never the model's own
 *    `other` — so the invariant holds regardless of the model's residual.
 * 6. **Budget.** Real-time factor on a mid-range phone is ~0.3–1.5× per window; a stem-based transition must stay
 *    inside the coordinator's render deadline (DESIGN §7.4), so keep [available] false while the device is in
 *    power-save and let the planner's `stemQuality` fall back to PSEUDO.
 */
class MlStemSeparatorOnnx(
    context: Context,
    /** Used for every call while [available] is false — which, in this build, is always. */
    private val fallback: StemSeparator = PseudoStemSeparator(),
) : MlStemSeparator {

    private val appContext: Context = context.applicationContext

    /** Where a user-installed model is expected: `Android/data/<pkg>/files/models/demucs.onnx`. */
    val modelFile: File = File(File(appContext.getExternalFilesDir(null), MODEL_DIR), MODEL_FILE)

    override val modelId: String
        get() = if (available) MODEL_FILE else "none"

    /**
     * Always false in this build: the ONNX Runtime dependency is not part of the APK, so even a model file on disk
     * cannot be loaded. Flip this to `modelFile.isFile && runtimeLoads()` together with step 1 above.
     */
    override val available: Boolean
        get() = false

    /** PSEUDO while [available] is false — callers use this to decide whether stem strategies are worth planning. */
    override val quality: StemQuality
        get() = if (available) StemQuality.ML else fallback.quality

    override fun separate(audio: AudioBuffer): Stems = fallback.separate(audio)

    /** Human-readable state for the Lab / Settings ("ML stems: model not installed"). */
    fun status(): String = when {
        available -> "ML stems: $modelId"
        modelFile.isFile -> "ML stems: model found but the ONNX runtime is not in this build"
        else -> "ML stems: not installed (using pseudo-stems)"
    }

    companion object {
        const val MODEL_DIR = "models"
        const val MODEL_FILE = "demucs.onnx"

        /** The separator the renderer should use: the ML one when it is really available, else pseudo-stems. */
        fun best(context: Context): StemSeparator {
            val ml = MlStemSeparatorOnnx(context)
            return if (ml.available) ml else PseudoStemSeparator()
        }
    }
}
