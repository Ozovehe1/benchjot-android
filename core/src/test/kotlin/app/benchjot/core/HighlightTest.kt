package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTest {
    private fun kinds(code: String, lang: String) = highlight(code, lang).map { code.substring(it.start, it.end) to it.kind }

    @Test fun python() {
        val got = kinds("def f(x):  # note\n    return \"s # not\" + 12", "python")
        assertEquals(
            listOf("def" to TokenKind.Keyword, "# note" to TokenKind.Comment, "return" to TokenKind.Keyword, "\"s # not\"" to TokenKind.Str, "12" to TokenKind.Number),
            got,
        )
    }

    @Test fun jsBlockCommentsAndTemplates() {
        val got = kinds("/* a */ const s = `x\ny`; Math.max(1)", "javascript")
        assertEquals(TokenKind.Comment, got[0].second)
        assertTrue(got.contains("`x\ny`" to TokenKind.Str))
        assertTrue(got.contains("Math" to TokenKind.Keyword))
    }

    @Test fun tokensNeverOverlapAndPlainTextIsPlain() {
        val code = "x = 'unterminated\nfor i in range(10): print(i)"
        val toks = highlight(code, "python")
        toks.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        assertEquals(emptyList<Token>(), highlight("anything 1 2", "text"))
    }
}
