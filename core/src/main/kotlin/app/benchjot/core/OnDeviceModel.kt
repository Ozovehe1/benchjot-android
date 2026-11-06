package app.benchjot.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The on-device coding model: what file it is, where it comes from, and the limits every
 * request runs under. One place, so swapping the model is a change here and nowhere else.
 *
 * Qwen2.5-Coder-0.5B-Instruct (Apache-2.0), llama.cpp Q4_0, as published by Qwen. The hash
 * is the file's SHA-256 (it matches Hugging Face's own record of it); a file that doesn't
 * match is deleted and never loaded, so only this exact file ever reaches the GGUF parser.
 */
object OnDeviceModel {
    const val NAME = "Qwen2.5-Coder-0.5B-Instruct"
    const val FILE_NAME = "qwen2.5-coder-0.5b-instruct-q4_0.gguf"
    const val URL = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_0.gguf"
    const val SHA256 = "9739055e046d62a937e5b7879012209ef40ebea8a1569a96028de491f3f091d5"
    const val SIZE_BYTES = 428_730_240L

    /** Tokens the model sees at once: prompt plus answer. KV cache ≈ 12 KB per token (≈ 50 MB here). */
    const val CONTEXT_TOKENS = 4096
    /** Longest answer, and the wall-clock cap, so a stuck or rambling run always ends. */
    const val MAX_NEW_TOKENS = 512
    const val TIMEOUT_MS = 90_000L

    /** Generation threads: all cores but one, so the UI always keeps a core (at least 1, at most 4). */
    fun threads(cores: Int): Int = (cores - 1).coerceIn(1, 4)
}

/** Status codes from the native generate() (app/src/main/cpp/bj_llm.cpp). */
enum class GenerateStatus(val code: Int) {
    Done(0), Stopped(1), Limit(2), PromptTooLong(3), Failed(4), BadArgs(5);

    companion object {
        fun of(code: Int): GenerateStatus = entries.firstOrNull { it.code == code } ?: Failed
    }
}

/**
 * Turns the model's output bytes into text as they arrive. A token can end partway through a
 * multi-byte character (é, 中, emoji), so the incomplete tail waits for the next bytes rather
 * than turning into replacement characters. Invalid bytes become U+FFFD.
 */
class Utf8Stream {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun push(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        return out.flip().toString()
    }

    /** The end of the stream: any incomplete tail becomes U+FFFD. */
    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, out, true)
        decoder.flush(out)
        pending = ByteArray(0)
        decoder.reset()
        return out.flip().toString()
    }
}

/**
 * The model's chat format (ChatML, Qwen's template). The native side tokenizes with special
 * tokens on, so text from a note could otherwise smuggle in its own "<|im_start|>system …"
 * turn. [neutralize] breaks every control-token marker in untrusted text first.
 */
object ChatPrompt {
    private val marker = Regex("<\\|")

    /** "<|" becomes "< |": still readable, never a control token. */
    fun neutralize(text: String): String = text.replace(marker, "< |")

    fun build(system: String, user: String): String = buildString {
        append("<|im_start|>system\n").append(neutralize(system)).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(neutralize(user)).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }
}
