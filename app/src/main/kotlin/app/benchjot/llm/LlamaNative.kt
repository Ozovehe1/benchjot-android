package app.benchjot.llm

/**
 * The JNI bridge to llama.cpp (libbj_llm.so, app/src/main/cpp/bj_llm.cpp). Five calls and two
 * helpers, nothing more. Not thread-safe: one generate() at a time per handle, and free() only
 * when no generate() runs on it; [LocalModel] is the only caller and keeps to that.
 */
internal object LlamaNative {
    init { System.loadLibrary("bj_llm") }

    /** Receives the answer as raw UTF-8 bytes, a token at a time. Return false to stop. */
    fun interface ByteSink { fun onBytes(bytes: ByteArray): Boolean }

    /** A handle, or 0 if the file couldn't be loaded ([lastError] says why). */
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    /** The prompt's length in tokens, exactly as [generate] tokenizes it; -1 on failure. */
    external fun countTokens(handle: Long, prompt: ByteArray): Int
    /**
     * A [app.benchjot.core.GenerateStatus] code. The prompt is UTF-8 in the model's chat format.
     * The answer never goes past the context's end, whatever [maxTokens] says (it ends as Limit).
     */
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, sink: ByteSink): Int
    /** Ends a running generate() after its current token. Safe from any thread. */
    external fun stop(handle: Long)
    external fun free(handle: Long)
    external fun lastError(): String
    external fun systemInfo(): String
}
