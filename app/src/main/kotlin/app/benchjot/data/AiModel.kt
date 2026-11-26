package app.benchjot.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which Claude model this person asks (the web's lib/ai/model-choice.ts): Sonnet by default, or
 * Haiku. Kept on the phone and sent with every request to the web as X-Benchjot-Model.
 */
object AiModel {
    enum class Choice(val id: String, val label: String, val what: String) {
        Sonnet("sonnet", "Sonnet", "Best answers. The default."),
        Haiku("haiku", "Haiku", "Faster, and uses far fewer credits."),
    }

    private const val PREFS = "benchjot"
    private const val KEY = "ai_model"
    private val _current = MutableStateFlow(Choice.Sonnet)
    val current: StateFlow<Choice> = _current.asStateFlow()

    fun load(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _current.value = Choice.entries.firstOrNull { it.id == id } ?: Choice.Sonnet
    }

    fun set(context: Context, choice: Choice) {
        _current.value = choice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.id).apply()
    }
}
