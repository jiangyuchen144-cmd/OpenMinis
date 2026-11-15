package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-md-parse-incremental] The incremental streaming parse freezes
 * every block before the newest "sealed" blank-line boundary and re-parses
 * only the tail. A boundary wrongly declared sealed would freeze a half-open
 * code fence or math span and corrupt the render for the rest of the stream,
 * so the boundary rule is pinned here.
 */
class MarkdownIncrementalParseTest {

    /** Literal "$$" without tripping Kotlin string interpolation. */
    private val D = "$" + "$"

    @Test
    fun `a blank line between paragraphs is a sealed boundary`() {
        val md = "first para\n\nsecond para"
        val end = stableParsePrefixEnd(md, 0)
        assertEquals("first para\n\n".length, end)
        assertEquals("first para\n\n", md.substring(0, end))
    }

    @Test
    fun `a blank line INSIDE an open code fence is not sealed`() {
        // The fence opens and never closes — everything after it is still live.
        val md = "intro\n\n```kotlin\nval a = 1\n\nval b = 2"
        val end = stableParsePrefixEnd(md, 0)
        assertEquals("only the pre-fence boundary may freeze", "intro\n\n".length, end)
    }

    @Test
    fun `a blank line after a CLOSED fence is sealed`() {
        val md = "intro\n\n```\ncode\n```\n\ntail"
        val end = stableParsePrefixEnd(md, 0)
        assertEquals("intro\n\n```\ncode\n```\n\n".length, end)
    }

    @Test
    fun `a blank line inside an open display-math span is not sealed`() {
        val md = "intro\n\n" + D + "\nx = 1\n\ny = 2"
        assertEquals("intro\n\n".length, stableParsePrefixEnd(md, 0))
    }

    @Test
    fun `single-line display math does not count as an open span`() {
        val md = "intro\n\n" + D + "x = 1" + D + "\n\ntail"
        assertEquals(("intro\n\n" + D + "x = 1" + D + "\n\n").length, stableParsePrefixEnd(md, 0))
    }

    @Test
    fun `the prefix only ever grows`() {
        val md = "a\n\nb\n\nc"
        // Already frozen past the first boundary → never hand back a smaller one.
        val already = "a\n\nb\n\n".length
        assertEquals(already, stableParsePrefixEnd(md, already))
    }

    @Test
    fun `content with no blank line has nothing sealed`() {
        assertEquals(0, stableParsePrefixEnd("one continuous line of text", 0))
    }

    @Test
    fun `isSealedBoundary counts only line-leading delimiters`() {
        // A ``` appearing mid-sentence is not a fence opener.
        assertTrue(isSealedBoundary("see the ``` marker inline\n\n", "see the ``` marker inline\n\n".length))
        assertFalse(isSealedBoundary("```\nopen\n\n", "```\nopen\n\n".length))
    }

    @Test
    fun `indented fence still counts as a fence`() {
        val md = "  ```\ncode\n\nmore"
        assertFalse(isSealedBoundary(md, md.length))
    }

    // ─── Equivalence: incremental replay must equal a single full parse ────

    /** Feed [full] in growing prefixes, as a stream would arrive. */
    private fun chunked(full: String, step: Int): List<String> =
        generateSequence(step) { it + step }.takeWhile { it < full.length }
            .map { full.substring(0, it) }.toList() + listOf(full)

    private fun assertIncrementalMatchesFull(full: String, step: Int = 37) {
        assertEquals(
            "incremental replay must produce the same blocks as one full parse",
            debugParseBlockRaws(full),
            debugParseIncrementalRaws(chunked(full, step)),
        )
    }

    @Test
    fun `streamed paragraphs match a full parse`() {
        assertIncrementalMatchesFull(
            (1..12).joinToString("\n\n") { "Paragraph number $it with some trailing words." },
        )
    }

    @Test
    fun `streamed markdown with fences lists table and math matches a full parse`() {
        val d = D
        val full = buildString {
            append("# Title\n\n")
            append("Intro paragraph with `inline code` and **bold**.\n\n")
            append("## Section\n\n")
            append("- bullet one\n- bullet two\n- bullet three\n\n")
            append("1. first\n2. second\n\n")
            append("```kotlin\nfun main() {\n\n    println(\"blank line inside fence\")\n}\n```\n\n")
            append("| a | b |\n|---|---|\n| 1 | 2 |\n\n")
            append(d).append("\nE = mc^2\n\n").append(d).append("\n\n")
            append("Closing paragraph.\n")
        }
        assertIncrementalMatchesFull(full, step = 29)
    }

    @Test
    fun `a non-append edit invalidates the cached prefix`() {
        // Grow, then replace the text entirely — the result must match a full
        // parse of the NEW text, not a splice of the old prefix.
        val a = "alpha para\n\nbeta para\n\ngamma"
        val b = "totally different\n\ncontent here"
        assertEquals(debugParseBlockRaws(b), debugParseIncrementalRaws(listOf(a, b)))
    }

    // ─── [T-android-md-parse-tailcap] live-tail budgets ────────────────────

    /** `n` lines of one character, optionally terminated by a newline. */
    private fun shortLines(n: Int, trailing: Boolean): String =
        (1..n).joinToString("\n") { "x" } + if (trailing) "\n" else ""

    private fun shards(text: String): List<String> =
        debugParseIncrementalShapes(listOf(text))

    /** Make \r and \n visible in an assertion message. */
    private fun String.escape(): String =
        replace("\r", "\\r").replace("\n", "\\n")

    @Test
    fun `the line counter means what String lines means`() {
        // The budget exists to bound the per-line regex battery over
        // content.lines(), so its count must be lines(), not newlines. These
        // are the shapes where the two differ.
        val cases = listOf(
            "a\nb\nc",                    // 3 lines, 2 LF
            "a\nb\nc\n",                  // trailing LF opens one more empty line
            "a\r\nb\r\nc",                // CRLF is ONE separator
            "a\rb\rc",                    // bare CR
            "progress\rprogress\rdone",   // terminal progress: 3 lines, ZERO LF
            "\r\r\r",
            "\n\r\n\r",
            "a",
            "",
        )
        for (text in cases) {
            assertEquals(
                "countLinesUpTo must equal lines().size for ${text.escape()}",
                text.lines().size,
                debugLiveTailLineCount(text),
            )
        }
    }

    @Test
    fun `a live tail at the line budget is still parsed as markdown`() {
        val atCap = shortLines(DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY, trailing = false)
        assertEquals(DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY, atCap.lines().size)
        assertEquals(
            "exactly at the budget must not degrade",
            listOf("Paragraph"),
            shards(atCap),
        )
    }

    @Test
    fun `one line over the line budget degrades to a preformatted block`() {
        val over = shortLines(DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY + 1, trailing = false)
        assertEquals(DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY + 1, over.lines().size)
        assertEquals(listOf("CodeBlock"), shards(over))
    }

    @Test
    fun `a trailing newline pushes the count one line over the budget`() {
        // Same 512 visible rows, but lines() sees 513 — and the budget follows
        // lines(), so this degrades. Pinned so the boundary cannot drift.
        val withTrailing = shortLines(DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY, trailing = true)
        assertEquals(DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY + 1, withTrailing.lines().size)
        assertEquals(listOf("CodeBlock"), shards(withTrailing))
    }

    @Test
    fun `CRLF rows are one line each, not two`() {
        val crlf = (1..DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY).joinToString("\r\n") { "x" }
        assertEquals(DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY, crlf.lines().size)
        assertEquals(
            "CRLF must not double-count and trip the budget early",
            listOf("Paragraph"),
            shards(crlf),
        )
    }

    @Test
    fun `bare-CR content degrades on the line budget`() {
        // A terminal progress bar: many lines, no LF at all. A '\n'-only
        // counter would score this as one line and never cap it.
        val progress = (1..DEBUG_MAX_LIVE_TAIL_LINES_TEST_ONLY + 1).joinToString("\r") { "x" }
        assertEquals(0, progress.count { it == '\n' })
        assertEquals(listOf("CodeBlock"), shards(progress))
    }

    @Test
    fun `the character budget still applies on its own`() {
        val big = "x".repeat(DEBUG_MAX_LIVE_TAIL_CHARS_TEST_ONLY + 1)
        assertEquals(listOf("CodeBlock"), shards(big))
        assertEquals(
            "exactly at the character budget must not degrade",
            listOf("Paragraph"),
            shards("y".repeat(DEBUG_MAX_LIVE_TAIL_CHARS_TEST_ONLY)),
        )
    }

    @Test
    fun `a finished message gets its markdown back after being capped live`() {
        // The regression this cap could introduce: capped while streaming, then
        // the stream ends with the SAME text, so the effect key is the only
        // thing that can re-trigger the parse. The parse itself must return
        // real blocks for streaming = false.
        val big = "x".repeat(DEBUG_MAX_LIVE_TAIL_CHARS_TEST_ONLY + 1)
        assertEquals(listOf("CodeBlock"), debugParseIncrementalShapes(listOf(big), streaming = true))
        assertEquals(listOf("Paragraph"), debugParseIncrementalShapes(listOf(big), streaming = false))
    }

    @Test
    fun `the synthesized block never enters the cached prefix`() {
        // If it did, a later freeze would append real blocks beside a fake one.
        // Layout: a frozen prefix of paragraphs, then an over-budget tail — so
        // the result is Paragraph*N + CodeBlock, and the CodeBlock must stay out
        // of the cache.
        val head = (1..10).joinToString("\n\n") { "h$it" }
        val tail = "x".repeat(DEBUG_MAX_LIVE_TAIL_CHARS_TEST_ONLY + 1)
        val whole = head + "\n\n" + tail
        val shapes = debugParseIncrementalShapes(listOf(whole, whole), streaming = true)
        assertEquals("the frozen prefix keeps its real blocks", 10, shapes.count { it == "Paragraph" })
        assertEquals("only the tail degrades", 1, shapes.count { it == "CodeBlock" })
        assertEquals("the degraded tail is last", "CodeBlock", shapes.last())
        assertEquals(
            "a capped segment must not poison the prefix: finishing must equal a full parse",
            debugParseBlockRaws(whole),
            debugParseIncrementalRaws(listOf(whole)),
        )
    }
}
