package com.ben.karmaasistan

/** JNI bindings to whisper.cpp (implemented in src/main/cpp/whisper_jni.cpp). */
object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Loads a ggml model file; returns a native handle, or 0 on failure. */
    external fun loadModel(modelPath: String): Long

    external fun freeModel(ctx: Long)

    /** Returns [pTurkish, pEnglish] from Whisper's language identification. */
    external fun detectLanguage(ctx: Long, audio: FloatArray, threads: Int): FloatArray

    /**
     * Returns a JSON string: ok, text, language, n_tokens, mean_logprob, min_p.
     * audioCtx = encoder window in frames (50/s; 0 = full 30 s).
     */
    external fun transcribe(ctx: Long, audio: FloatArray, language: String, prompt: String, threads: Int, audioCtx: Int): String

    /** Short dummy run: warms up the CPU backend and sets the window detectLanguage() will use. Returns ms. */
    external fun warmUp(ctx: Long, threads: Int, audioCtx: Int): Long

    /** CPU features whisper.cpp was compiled with (e.g. "NEON = 1 | DOTPROD = 1 ..."). */
    external fun systemInfo(): String
}
