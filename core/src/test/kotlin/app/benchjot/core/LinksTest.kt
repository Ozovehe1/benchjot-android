package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    private val id = "0b6f2a4e-1c3d-4e5f-8a9b-0c1d2e3f4a5b"
    private data class N(override val id: String, override val title: String) : NoteRef

    @Test fun patternIsTheSameAsTheDatabaseAndWeb() {
        // note_links() in the web's migration; the web's own test pins LINK_PATTERN in links.ts to it.
        assertEquals(Fixtures.text("link_pattern.txt").trim(), LINK_PATTERN)
    }

    @Test fun parsesLinksAndKeepsText() {
        val text = "See [[Exp 3|${id.uppercase()}]] and [[ Loose title ]]."
        val segs = parseLinks(text)
        assertEquals(5, segs.size)
        assertEquals(Segment.Link("[[Exp 3|${id.uppercase()}]]", "Exp 3", id), segs[1])
        assertEquals(Segment.Link("[[ Loose title ]]", "Loose title", null), segs[3])
        assertEquals(text, segs.joinToString("") { if (it is Segment.Text) it.text else (it as Segment.Link).raw })
    }

    @Test fun ignoresBrokenLinks() {
        for (t in listOf("[[]]", "[[a\nb]]", "[[a|not-a-uuid]]", "[a]", "[[a]")) {
            assertEquals(t, listOf(Segment.Text(t)), parseLinks(t))
        }
    }

    @Test fun formatsAndResolves() {
        assertEquals("[[a b c|$id]]", formatLink("a [b] | c", id))
        assertEquals("[[Untitled|$id]]", formatLink("  ", id))
        val notes = listOf(N(id, "Exp 3"), N("x", "Other"))
        assertEquals(id, resolveLink(NoteLink("whatever", id), notes)?.id)
        assertEquals("x", resolveLink(NoteLink("other", null), notes)?.id)
        assertNull(resolveLink(NoteLink("missing", null), notes))
    }

    @Test fun typingALinkAndCompletingIt() {
        val text = "go [[exp"
        assertEquals(LinkQuery(3, "exp"), linkQueryAt(text, text.length))
        assertNull(linkQueryAt("go [[a|b", 8))
        assertNull(linkQueryAt("no link", 7))
        val done = completeLink("go [[exp]] after", 3, 8, N(id, "Exp 3"))
        assertEquals("go [[Exp 3|$id]] after", done.text)
        assertEquals(3 + "[[Exp 3|$id]]".length, done.caret)
    }

    @Test fun suggestionsPutPrefixMatchesFirst() {
        val notes = listOf(N("1", "The exp"), N("2", "Exp 2"), N("3", ""), N("4", "Exp 4"))
        assertEquals(listOf("2", "4", "1"), linkSuggestions("exp", notes, excludeId = "9").map { it.id })
        assertEquals(listOf("4", "1"), linkSuggestions("exp", notes, excludeId = "2").map { it.id })
    }

    @Test fun backlinksOncePerSourceNeverSelf() {
        val notes = listOf(N("a", "A"), N("b", "B"), N(id, "Target"))
        val links = listOf(
            SourcedLink("a", "Target", id), SourcedLink("a", "target", null), SourcedLink("b", "target", null), SourcedLink(id, "Target", id),
        )
        assertEquals(listOf("a", "b"), backlinksTo(id, links, notes).map { it.id })
    }
}
