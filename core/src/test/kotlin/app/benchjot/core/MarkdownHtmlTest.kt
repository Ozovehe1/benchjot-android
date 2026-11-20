package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownHtmlTest {
    @Test fun blocks() {
        assertEquals(
            "<h2>Aim</h2>\n<p>Does <strong>warmup</strong> help, <em>really</em>?</p>\n<ul>\n<li>a</li>\n<li>b <code>x &lt; 1</code></li>\n</ul>\n<pre><code>print(1 &lt; 2)</code></pre>\n<blockquote>quoted</blockquote>",
            markdownToHtml("## Aim\n\nDoes **warmup** help, *really*?\n\n- a\n- b `x < 1`\n\n```python\nprint(1 < 2)\n```\n> quoted"),
        )
        assertEquals("<ol>\n<li>one</li>\n<li>two</li>\n</ol>", markdownToHtml("1. one\n2. two"))
    }

    @Test fun nothingInANoteBecomesMarkup() {
        val html = markdownToHtml("<script>alert(1)</script> [x](javascript:alert(1)) [ok](https://example.com/a?b=1&c=2) \$E=mc^2\$")
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("href=\"javascript"))
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt; [x](javascript:alert(1)) <a href=\"https://example.com/a?b=1&amp;c=2\">ok</a> \$E=mc^2\$</p>", html)
    }
}
