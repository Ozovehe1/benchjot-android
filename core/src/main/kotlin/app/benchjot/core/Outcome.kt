package app.benchjot.core

// A note's outcome. Mirrors the web's src/lib/outcome.ts.
// Rule (the user's): only the author decides. Nothing sets or changes it
// automatically (not run results, exit codes, errors or Fix).

enum class Outcome(val value: String, val label: String) {
    Success("success", "Successful"),
    Failed("failed", "Failed");

    companion object {
        fun of(value: String?): Outcome? = entries.firstOrNull { it.value == value }
    }
}

/** The note page's 3-way control: unmarked ("—"), Successful, Failed. */
data class OutcomeChoice(val value: Outcome?, val label: String, val name: String)

val OUTCOME_CHOICES = listOf(
    OutcomeChoice(null, "—", "Unmarked"),
    OutcomeChoice(Outcome.Success, Outcome.Success.label, Outcome.Success.label),
    OutcomeChoice(Outcome.Failed, Outcome.Failed.label, Outcome.Failed.label),
)

/** The list filter; it narrows search and project views alike. */
enum class OutcomeFilter(val label: String, val param: String?) {
    All("All", null),
    Success("Successful", "success"),
    Failed("Failed", "failed"),
    Unmarked("Unmarked", "unmarked"),
}

/** One sentence of context for the Fix prompt, or nothing when unmarked. */
fun outcomeContext(outcome: Outcome?): String = when (outcome) {
    Outcome.Failed -> "The author marked this experiment as failed."
    Outcome.Success -> "The author marked this experiment as successful."
    null -> ""
}
