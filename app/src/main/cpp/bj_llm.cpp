// JNI bridge between the app (app.benchjot.llm.LlamaNative) and llama.cpp.
//
// Contract, kept small on purpose (four calls cross the bridge):
//   load(path, nCtx, nThreads) -> handle (0 on failure; lastError() says why)
//   generate(handle, prompt, maxTokens, sink) -> status (see Status below)
//   stop(handle)    : asks a running generate() to end after its current token; any thread
//   free(handle)    : releases the model; never while generate() runs on it
// One generate() at a time per handle (the app runs a single worker thread).
// Text crosses as raw UTF-8 bytes, not jstring: JNI's NewStringUTF takes "modified UTF-8",
// which mangles 4-byte characters (emoji), and a token can end in the middle of a character.
// The Kotlin side decodes the byte stream.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * TAG = "bj_llm";

enum Status : jint {
    OK = 0,             // ended on its own (end-of-turn token)
    STOPPED = 1,        // stop() or the sink asked to stop
    LIMIT = 2,          // reached maxTokens
    PROMPT_TOO_LONG = 3,// prompt + room to answer exceeds the context
    FAILED = 4,         // llama.cpp failed (lastError says how)
    BAD_ARGS = 5,
};

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> stop{false};
};

std::mutex g_err_mutex;
std::string g_last_error;

void set_error(const std::string & e) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_error = e;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", e.c_str());
}

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::once_flag g_backend_once;

std::string to_utf8(JNIEnv * env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

Session * session_of(jlong handle) { return reinterpret_cast<Session *>(handle); }

// Feeds tokens to the context in chunks of at most n_batch, from position pos0.
bool decode(Session * s, llama_batch_ext * batch, const llama_token * tokens, int n, int pos0) {
    const int n_batch = static_cast<int>(llama_n_batch(s->ctx));
    for (int off = 0; off < n; off += n_batch) {
        const int k = std::min(n_batch, n - off);
        llama_batch_ext_clear(batch);
        for (int i = 0; i < k; ++i) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[off + i]);
            if (idx < 0) { set_error("batch add failed: " + std::to_string(idx)); return false; }
            const llama_pos pos = pos0 + off + i;
            llama_batch_ext_set_pos(batch, idx, &pos);
        }
        llama_batch_ext_set_output_logits(batch, k - 1, off + k == n);
        const int32_t rc = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) { set_error("decode failed: " + std::to_string(rc)); return false; }
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_benchjot_llm_LlamaNative_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    if (jpath == nullptr || n_ctx < 256 || n_threads < 1) { set_error("bad load arguments"); return 0; }
    std::call_once(g_backend_once, [] { llama_log_set(log_cb, nullptr); llama_backend_init(); });

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP; // mapped, not copied: the weights live in the page cache, which Android can reclaim
    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) { set_error("could not load the model file"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) { llama_model_free(model); set_error("could not create the context (out of memory?)"); return 0; }

    auto * s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_app_benchjot_llm_LlamaNative_generate(JNIEnv * env, jobject, jlong handle, jbyteArray jprompt, jint max_tokens, jobject sink) {
    Session * s = session_of(handle);
    if (s == nullptr || jprompt == nullptr || sink == nullptr || max_tokens < 1) { set_error("bad generate arguments"); return BAD_ARGS; }
    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_bytes = env->GetMethodID(sink_cls, "onBytes", "([B)Z");
    if (on_bytes == nullptr) { set_error("sink has no onBytes([B)Z"); return BAD_ARGS; }

    s->stop.store(false);
    llama_memory_clear(llama_get_memory(s->ctx), true); // every request starts from an empty context

    const std::string prompt = to_utf8(env, jprompt);
    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    // parse_special = true: the prompt carries the chat template's control tokens (<|im_start|> …)
    int n = -llama_tokenize(s->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n <= 0) { set_error("could not tokenize the prompt"); return FAILED; }
    if (n + 16 > n_ctx) return PROMPT_TOO_LONG;
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    if (llama_tokenize(s->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), n, true, true) < 0) {
        set_error("could not tokenize the prompt");
        return FAILED;
    }

    llama_batch_ext * batch = llama_batch_ext_init(s->ctx);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy()); // deterministic: the same note gives the same answer

    jint status = OK;
    int pos = 0;
    if (!decode(s, batch, tokens.data(), n, 0)) status = FAILED;
    pos = n;
    const int budget = std::min(static_cast<int>(max_tokens), n_ctx - n);
    for (int i = 0; status == OK; ++i) {
        if (s->stop.load()) { status = STOPPED; break; }
        if (i >= budget) { status = LIMIT; break; }
        const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        char buf[256];
        const int len = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, false); // special = false: no control text in the answer
        if (len < 0) { set_error("token too long to print"); status = FAILED; break; }
        if (len > 0) {
            jbyteArray piece = env->NewByteArray(len);
            if (piece == nullptr) { status = FAILED; break; } // OutOfMemoryError pending in Java
            env->SetByteArrayRegion(piece, 0, len, reinterpret_cast<const jbyte *>(buf));
            const jboolean more = env->CallBooleanMethod(sink, on_bytes, piece);
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck()) { status = FAILED; break; } // rethrown in Kotlin when we return
            if (!more) { status = STOPPED; break; }
        }
        if (!decode(s, batch, &tok, 1, pos)) { status = FAILED; break; }
        pos += 1;
    }

    llama_sampler_free(smpl);
    llama_batch_ext_free(batch);
    env->DeleteLocalRef(sink_cls);
    return status;
}

JNIEXPORT void JNICALL
Java_app_benchjot_llm_LlamaNative_stop(JNIEnv *, jobject, jlong handle) {
    if (Session * s = session_of(handle)) s->stop.store(true);
}

JNIEXPORT void JNICALL
Java_app_benchjot_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    Session * s = session_of(handle);
    if (s == nullptr) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT jstring JNICALL
Java_app_benchjot_llm_LlamaNative_lastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    return env->NewStringUTF(g_last_error.c_str()); // ASCII only, so modified UTF-8 is fine
}

JNIEXPORT jstring JNICALL
Java_app_benchjot_llm_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
