package app.benchjot.llm

import android.content.Context
import app.benchjot.core.AssistError
import app.benchjot.core.AssistPrompts
import app.benchjot.core.AssistTask
import app.benchjot.core.FixAnswer
import app.benchjot.core.FixRequest
import app.benchjot.core.FriendlyException
import app.benchjot.core.readCodeAnswer
import app.benchjot.data.WebApi
import kotlinx.coroutines.CoroutineScope

/** Where a Fix came from, so the diff can say so. */
enum class FixSource { Phone, Server }

/**
 * Fix: on the phone when the model is ready and the code fits, otherwise the server, as before.
 * A phone attempt that can't give a whole program (low memory, too long, too slow, cut short,
 * no code) falls back to the server rather than offering half a fix. Errors reach the UI as the shared
 * table's text (server) or the helper's own plain text (phone), never as engine output.
 */
object Assistant {
    /** Errors where the server should take over silently: the user asked for a fix, not for a model. */

    suspend fun fix(context: Context, scope: CoroutineScope, request: FixRequest, onPhase: (String) -> Unit = {}): Pair<FixAnswer, FixSource> {
        val ready = ModelStore.state.value is ModelState.Ready
        if (ready && request.code.length <= AssistTask.Fix.maxInputChars) {
            onPhase("Fixing on your phone…")
            val prompt = AssistPrompts.fix(request.language, request.code, request.error)
            when (val r = LocalModel.generate(context, scope, AssistTask.Fix, prompt, onPhase = { if (it.isNotEmpty()) onPhase(it) }).result.await()) {
                is LocalResult.Done -> {
                    val read = readCodeAnswer(r.text)
                    if (read != null && read.second && !r.cutShort) return FixAnswer(read.first.code, read.first.summary) to FixSource.Phone
                    android.util.Log.w("Assistant", "phone fix unusable (code=${read != null}, cut=${r.cutShort}); using the server")
                }
                is LocalResult.Stopped -> throw FriendlyException("Fix stopped.")
                is LocalResult.Failed -> {
                    // Every phone failure hands over to the server: the user asked for a fix, not for a model.
                    if (r.error == AssistError.ModelCorrupt) ModelStore.discard()
                    android.util.Log.w("Assistant", "phone fix failed (${r.error.id}); using the server")
                }
            }
        }
        onPhase("Fixing…")
        return WebApi.fix(request) to FixSource.Server
    }
}
