package app.benchjot.core

// The example note on the signed-out landing, the same note as the web's
// src/lib/sample-note.ts (SampleNoteTest checks it against the web fixture).

const val SAMPLE_TITLE = "Exp 3: dropout 0.1 vs 0.3"
const val SAMPLE_NOTES = "Lower dropout should help on the small set. Mean accuracy over three seeds:"

private const val SAMPLE_CODE = """acc = {0.1: [91.8, 92.4, 92.1],
       0.3: [90.9, 90.7, 91.1]}
for p, r in acc.items():
    mean = sum(r) / len(r)
    print(p, round(mean, 1))"""

val SAMPLE_SNIPPET = Snippet(
    id = "sample-dropout",
    userId = "",
    entryId = "",
    filename = "dropout.py",
    language = "python",
    content = SAMPLE_CODE,
    position = 0,
    lastRun = LastRun(
        outcome = RunOutcome.Ok,
        stdout = "0.1 92.1\n0.3 90.9\n",
        stderr = "",
        errorLine = null,
        line = null,
        ms = 42,
        where = "browser",
        codeHash = codeHash(SAMPLE_CODE),
        ranAt = "2026-11-04T09:00:00.000Z",
    ),
    createdAt = "",
    updatedAt = "",
)
