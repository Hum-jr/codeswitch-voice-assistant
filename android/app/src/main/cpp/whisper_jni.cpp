// JNI bridge between Kotlin (WhisperNative.kt) and whisper.cpp.
// Keeps decisions (language rule, prompt) in Kotlin; this file only exposes
// language probabilities and transcription with token confidences.
#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static std::string json_escape(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n";  break;
            case '\r': o += "\\r";  break;
            case '\t': o += "\\t";  break;
            default:
                if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
                else o += static_cast<char>(c);  // UTF-8 bytes pass through
        }
    }
    return o;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ben_karmaasistan_WhisperNative_loadModel(JNIEnv *env, jobject, jstring model_path) {
    std::string path = to_std(env, model_path);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) LOGE("failed to load model %s", path.c_str());
    else LOGI("loaded model %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ben_karmaasistan_WhisperNative_freeModel(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

// Returns {p_tr, p_en}: Whisper's language-ID probabilities for Turkish and English.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ben_karmaasistan_WhisperNative_detectLanguage(JNIEnv *env, jobject, jlong handle,
                                                       jfloatArray audio, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    float result[2] = {0.0f, 0.0f};
    if (ctx != nullptr) {
        jsize n = env->GetArrayLength(audio);
        jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
        int rc = whisper_pcm_to_mel(ctx, samples, n, threads);
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        if (rc == 0) {
            std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
            if (whisper_lang_auto_detect(ctx, 0, threads, probs.data()) >= 0) {
                result[0] = probs[whisper_lang_id("tr")];
                result[1] = probs[whisper_lang_id("en")];
            }
        }
    }
    jfloatArray out = env->NewFloatArray(2);
    env->SetFloatArrayRegion(out, 0, 2, result);
    return out;
}

// Runs one short transcription of silence with the given encoder window. This (a) warms up
// the CPU backend so the first real command isn't slower, and (b) sets the window that the
// next detectLanguage() call will use. Returns elapsed milliseconds.
extern "C" JNIEXPORT jlong JNICALL
Java_com_ben_karmaasistan_WhisperNative_warmUp(JNIEnv *, jobject, jlong handle, jint threads, jint audio_ctx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return -1;
    std::vector<float> silence(16000, 0.0f);  // 1 s
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = "tr";
    p.no_context = true;
    p.single_segment = true;
    p.no_timestamps = true;
    p.print_progress = p.print_realtime = p.print_special = p.print_timestamps = false;
    p.max_tokens = 1;
    p.temperature_inc = 0.0f;          // no retries during warm-up
    p.logprob_thold = -INFINITY;
    p.audio_ctx = audio_ctx;
    auto t0 = std::chrono::steady_clock::now();
    whisper_full(ctx, p, silence.data(), (int) silence.size());
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    LOGI("warm-up (audio_ctx=%d): %lld ms", audio_ctx, (long long) ms);
    return (jlong) ms;
}

// CPU features whisper.cpp was built with (NEON, DOTPROD, FP16 ...), for checking the build.
extern "C" JNIEXPORT jstring JNICALL
Java_com_ben_karmaasistan_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Transcribes 16 kHz mono float audio. language: "tr", "en" or "auto"; prompt may be empty.
// Returns JSON: {"ok", "text", "language", "n_tokens", "mean_logprob", "min_p"}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ben_karmaasistan_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle,
                                                   jfloatArray audio, jstring language,
                                                   jstring prompt, jint threads, jint audio_ctx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("{\"ok\":false,\"error\":\"no model\"}");

    std::string lang = to_std(env, language);
    std::string init_prompt = to_std(env, prompt);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = threads;
    p.language         = lang.empty() ? "auto" : lang.c_str();
    p.detect_language  = false;
    p.initial_prompt   = init_prompt.empty() ? nullptr : init_prompt.c_str();
    p.no_context       = true;   // commands are independent
    p.single_segment   = true;   // commands are a few seconds long
    p.no_timestamps    = true;
    p.suppress_blank   = true;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_special    = false;
    p.print_timestamps = false;
    // Latency-bounded safeguards for short commands:
    //  - retry at a higher temperature only when the output looks like a repetition loop
    //    (entropy threshold), at most 2 retries (0.0 -> 0.4 -> 0.8);
    //  - no retry just for low confidence (that tripled the time on short words and the
    //    confidence is used later by the uncertainty module anyway);
    //  - a command never needs more than ~48 tokens, so cap runaway outputs.
    p.temperature      = 0.0f;
    p.temperature_inc  = 0.4f;
    p.entropy_thold    = 2.4f;
    p.logprob_thold    = -INFINITY;
    p.no_speech_thold  = 0.6f;
    p.max_tokens       = 48;
    // Encoder window in frames (50 per second; 1500 = Whisper's full 30 s, 0 = default).
    // Short commands don't need 30 s; a smaller window makes the encoder several times faster.
    // whisper.cpp keeps this value in its state, so language detection (detectLanguage)
    // also uses the window of the last call -- see warmUp().
    p.audio_ctx        = audio_ctx;

    jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    int rc = whisper_full(ctx, p, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (rc != 0) return env->NewStringUTF("{\"ok\":false,\"error\":\"whisper_full failed\"}");

    std::string text;
    double sum_log = 0.0;
    float min_p = 1.0f;
    int n_tok = 0;
    const whisper_token eot = whisper_token_eot(ctx);
    const int n_seg = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_seg; ++i) {
        text += whisper_full_get_segment_text(ctx, i);
        const int nt = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < nt; ++j) {
            if (whisper_full_get_token_id(ctx, i, j) >= eot) continue;  // skip special tokens
            float tp = whisper_full_get_token_p(ctx, i, j);
            sum_log += std::log(std::max(tp, 1e-6f));
            if (tp < min_p) min_p = tp;
            ++n_tok;
        }
    }
    const char *detected = whisper_lang_str(whisper_full_lang_id(ctx));

    std::string json = "{\"ok\":true,\"text\":\"" + json_escape(text) + "\""
        + ",\"language\":\"" + std::string(detected ? detected : "") + "\""
        + ",\"n_tokens\":" + std::to_string(n_tok)
        + ",\"mean_logprob\":" + std::to_string(n_tok ? sum_log / n_tok : 0.0)
        + ",\"min_p\":" + std::to_string(n_tok ? min_p : 0.0f) + "}";
    return env->NewStringUTF(json.c_str());
}
