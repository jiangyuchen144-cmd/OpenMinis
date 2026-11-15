# HARK-REVIEW — Android markdown renderer, native OOM crash fix

One commit, `+72 / -3`, in `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt`.
This file is self-contained: the diff, then every piece of surrounding code the
diff depends on, so it can be reviewed without opening a 255 KB source file.

Branch: `android-md-parse-tailcap` · commit `3d6c0ee`
Upstream this forks: `OpenMinis/OpenMinis` (GPLv3)

---

## 0. What is being asked of a reviewer

Does capping the *streaming* live tail at 24 k chars, and rendering the capped
portion as one preformatted `CodeBlock`, actually bound the per-tick native
allocation, without breaking the incremental-parse invariant
(prefix reuse must produce the same block split as a full parse)?

Specific things worth attacking:

1. **Correctness of the invariant.** `parseMarkdownIncremental` caches a frozen
   prefix and only re-parses what follows it. Is a `CodeBlock` substitute for
   the tail consistent with that, or does it poison the cache for the next tick?
2. **Off-by-one / boundary behaviour.** Exactly at the cap must be a no-op.
3. **The `streaming = false` case.** The cap is off for finished text. Is there
   a path where finished-but-huge text re-parses repeatedly and still OOMs?
4. **Is 24 k the right number**, or should it follow `LIVE_FRAGMENT_DEGRADE_CHARS`
   (8 k) / `streamingThrottleFor()` tiers?
5. **Interaction with `markdownParseGate`** (`Semaphore(1)`, process-wide).

---

## 1. The crash being fixed

Four native crashes in two days, one device (Redmi, Android 16, 15.6 GB RAM).
Process PSS at exit climbed each time: 1.1 GB, 2.2 GB, then **4.6 GB**.
Identical signature every time:

````
Fatal signal 6 (SIGABRT) in tid NNNN (DefaultDispatch)
Abort message: 'Scudo ERROR: internal map failure (error desc=Out of memory)'

  scudo::reportMapError -> scudo::MemMapLinux::remapImpl
  scudo::MapAllocator<SecondaryConfig>::allocate -> scudo_malloc -> malloc
  utext_setup_76 / icu_76::RegexPattern::matcher
  MatcherState::updateInput / PatternNative_openMatcherImpl    (libicu_jni)
  java.util.regex.Matcher.reset / Matcher.usePattern
  (app frames) -> Dispatchers.Default
````

`MemAvailable` was 6.6 GB at the moment of death: the process failed to `mmap`
after growing to 4.6 GB, it was not the system running out of memory.

`Matcher.reset` + `MatcherState::updateInput` is the shape of `Regex.matches()`,
which is the same signature the upstream `markdownParseGate` comment already
documents (native heap to 2.09 GB on a Pixel 6 -> `std::bad_alloc` -> SIGABRT).

## 2. Root cause

`markdownParseGate` + the incremental prefix parse fixed the concurrent-stream
case. What remained uncapped is the **live tail**:

- `stableParsePrefixEnd()` refuses to freeze a prefix inside a fence or a table.
- So one unsplittable block that is still growing keeps the whole of itself live.
- `parseMarkdownIncremental()` handed that whole tail to `parseMarkdownBlocks()`
  on **every** throttle tick.
- `parseMarkdownBlocks` runs ~8 `Regex.matches` per line, each allocating a
  fresh native ICU `MatcherState`.

A 100 KB paste is ~1.7 k lines -> ~13 k native matcher allocations **per tick**.
Over a 60 s stream that is hundreds of MB of native churn from one block. The
shard path (`StreamingMarkdownTextBody`) had no size guard at all, unlike the
fragment path which degrades at `LIVE_FRAGMENT_DEGRADE_CHARS = 8_000`.

## 3. The change (diff)

````diff
diff --git a/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt b/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt
index a19010a..ad9d259 100644
--- a/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt
+++ b/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt
@@ -873,7 +873,7 @@ private fun StreamingMarkdownTextBody(
     val parseState = remember { IncrementalParseState() }
     LaunchedEffect(displayContent) {
         val computed = withContext(Dispatchers.Default) {
-            parseMarkdownIncremental(displayContent, parseState).also {
+            parseMarkdownIncremental(displayContent, parseState, streaming = isStreaming).also {
                 MarkdownParseCaches.prewarm(it, mdColors)
             }
         }
@@ -1339,7 +1339,7 @@ private fun MarkdownBlockBody(
         // incremental optimization shrinks.
         val parseStartNs = System.nanoTime()
         val computed = withContext(Dispatchers.Default) {
-            parseMarkdownIncremental(displayContent, parseState).also {
+            parseMarkdownIncremental(displayContent, parseState, streaming = isStreaming).also {
                 // [T-android-streaming-incremental-inline] Prewarm the frozen
                 // blocks (all but the last) normally. The last block is the
                 // growing live tail: when it's a Paragraph, warm it
@@ -1984,6 +1984,26 @@ private fun findDisplayMathClose(lines: List<String>, from: Int): Int? {
  */
 private val markdownParseGate = Semaphore(1)
 
+/**
+ * [T-android-md-parse-tailcap] Ceiling on the unsealed ("live") text one
+ * incremental parse pass may hand to the block parser, applied only while the
+ * content is still streaming.
+ *
+ * Streaming content is re-parsed on every throttle tick, so a live tail with
+ * no ceiling makes the cost of a tick grow with the size of the block being
+ * streamed. The final text is parsed once, so it is deliberately NOT capped
+ * here: rendering a finished reply as one preformatted block would be a
+ * visible regression, and a single unbounded parse is the cost the cold-parse
+ * path already pays everywhere. Same asymmetry as
+ * [T-android-live-block-degrade], which degrades a live fragment at 8 k and
+ * parses it in full once it freezes.
+ *
+ * 24 k is well above the 8 k point at which the fragment path already stops
+ * rendering markdown, so this only changes what a tick does to a genuinely
+ * large unsplittable block (a pasted log, a shell dump, a giant table).
+ */
+private const val MAX_INCREMENTAL_LIVE_TAIL_CHARS = 24_000
+
 /**
  * [T-android-md-parse-incremental] Longest already-parsed prefix of a growing
  * stream, so an append-only update re-parses only the tail.
@@ -2016,6 +2036,13 @@ private class IncrementalParseState {
      * O(1)-ish and false-positives only if an edit preserved both.
      */
     var prefixHash: Int = 0
+    /**
+     * [T-android-md-parse-tailcap] Whether the over-cap notice was already
+     * logged for the current tail. A capped stream is parsed every throttle
+     * interval, so logging unconditionally would turn one degrade into
+     * dozens of identical lines in a log users are asked to attach.
+     */
+    var tailCapLogged: Boolean = false
 }
 
 /** Fingerprint over the tail of [content] up to [end] (bounded work). */
@@ -2181,6 +2208,15 @@ private fun isListContinuationLine(line: String): Boolean {
 private suspend fun parseMarkdownIncremental(
     content: String,
     state: IncrementalParseState,
+    /**
+     * Whether [content] is still growing. Only a growing tail is worth
+     * capping, because only a growing tail is re-parsed on every throttle
+     * tick; the final text is parsed once, and rendering a finished reply as
+     * one giant preformatted block would be a regression the user would see.
+     * Same asymmetry the fragment path already has: degrade while live, full
+     * parse once when frozen.
+     */
+    streaming: Boolean = true,
 ): List<MdBlock> = markdownParseGate.withPermit {
     // Growth must be append-only for the cached prefix to remain valid. Any
     // other edit (retry, edit-message, a shorter re-render) resets the state.
@@ -2203,7 +2239,40 @@ private suspend fun parseMarkdownIncremental(
         emptyList()
     }
     val liveTail = if (sealedEnd < content.length) {
-        parseMarkdownBlocks(content.substring(sealedEnd))
+        // [T-android-md-parse-tailcap] Bound the per-tick block-parse work.
+        //
+        // stableParsePrefixEnd() refuses to freeze anything inside a fence or a
+        // table, so a single unsplittable block that is still streaming keeps
+        // the WHOLE of it live: every throttle tick re-parsed it from its own
+        // start. parseMarkdownBlocks runs ~8 `Regex.matches` per line, and each
+        // of those allocates a fresh native ICU MatcherState (libicu_jni
+        // MatcherState::updateInput). A 100 KB paste -- a shell dump, a
+        // logcat tail, a giant table -- is ~1.7 k lines, so one tick is
+        // ~13 k native matcher allocations; at one tick per throttle interval
+        // over a 60 s stream that is hundreds of MB of churn, and with N
+        // concurrent streams it lands in the same place the parse gate was
+        // added to defuse: native heap into the GBs and, in the field,
+        // `Scudo ERROR: internal map failure` -> SIGABRT.
+        //
+        // Above the cap we still render every byte, but as one preformatted
+        // block: no inline parse, no per-line matcher, so the cost of a tick
+        // stops depending on how big the paste is. Same trade-off
+        // [T-android-live-block-degrade] already makes for the fragment path.
+        val liveLen = content.length - sealedEnd
+        if (streaming && liveLen > MAX_INCREMENTAL_LIVE_TAIL_CHARS) {
+            val tail = content.substring(sealedEnd)
+            if (!state.tailCapLogged) {
+                state.tailCapLogged = true
+                com.openminis.app.logging.AppLogger.info(
+                    "StreamDegrade",
+                    "incremental live tail $liveLen chars over $MAX_INCREMENTAL_LIVE_TAIL_CHARS rendered as one preformatted block",
+                )
+            }
+            listOf(MdBlock.CodeBlock(tail, "text", tail))
+        } else {
+            state.tailCapLogged = false
+            parseMarkdownBlocks(content.substring(sealedEnd))
+        }
     } else {
         emptyList()
     }
````

## 4. The two call sites (changed)

````kotlin
    // append-only stream re-parses only its newly-arrived tail.
    val parseState = remember { IncrementalParseState() }
    LaunchedEffect(displayContent) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState, streaming = isStreaming).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        // If the LE was cancelled while parseMarkdownBlocks was still running
        // (a newer chunk arrived), don't publish stale blocks.
        coroutineContext.ensureActive()
        blocks = computed
    }

...

        // (block split + prewarm/incremental inline+math) — this is what the
        // incremental optimization shrinks.
        val parseStartNs = System.nanoTime()
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState, streaming = isStreaming).also {
                // [T-android-streaming-incremental-inline] Prewarm the frozen
                // blocks (all but the last) normally. The last block is the
                // growing live tail: when it's a Paragraph, warm it
                // incrementally (closed prefix reused + tiny fresh suffix) so
                // the main-thread RenderBlock resolves to an exact HIT without
                // re-scanning the whole accumulated paragraph; when it's a
                // table/list/etc. (which RenderBlock parses non-incrementally)
                // fall back to the normal per-block prewarm for it.
````

## 5. Surrounding code the change depends on

### 5a. The cap constant and the process-wide parse gate

````kotlin
 * Streaming content is re-parsed on every throttle tick, so a live tail with
 * no ceiling makes the cost of a tick grow with the size of the block being
 * streamed. The final text is parsed once, so it is deliberately NOT capped
 * here: rendering a finished reply as one preformatted block would be a
 * visible regression, and a single unbounded parse is the cost the cold-parse
 * path already pays everywhere. Same asymmetry as
 * [T-android-live-block-degrade], which degrades a live fragment at 8 k and
 * parses it in full once it freezes.
 *
 * 24 k is well above the 8 k point at which the fragment path already stops
 * rendering markdown, so this only changes what a tick does to a genuinely
 * large unsplittable block (a pasted log, a shell dump, a giant table).
 */
private const val MAX_INCREMENTAL_LIVE_TAIL_CHARS = 24_000

/**
 * [T-android-md-parse-incremental] Longest already-parsed prefix of a growing
 * stream, so an append-only update re-parses only the tail.
 *
 * Streaming content is append-only, and markdown block structure is
 * prefix-stable up to the last blank-line boundary: everything before the
 * final `

` can no longer change no matter what arrives next (the parser
 * never looks backwards past a blank line — each `when` branch consumes
 * forward from its opening line). So we cache the blocks for that stable
 * prefix and, on the next pass, parse only what follows it.
 *
 * The saving is what makes long streams survivable: without it, a 60 KB reply
 * is re-parsed from byte 0 on every throttle tick — O(n²) native matcher
 * allocations over the life of the stream. With it, each tick parses only the
 * newly-arrived tail.
 */
private class IncrementalParseState {
    var prefixLength: Int = 0
    var prefixBlocks: List<MdBlock> = emptyList()
    /**
     * Cheap fingerprint of the frozen prefix, to detect that growth really was
     * append-only (a retry / edit / shorter re-render must invalidate).
     *
     * Deliberately NOT the prefix text itself: holding a copy would retain a
     * second full-size String per live composable and make the check O(n) on
     * every tick — reintroducing exactly the memory and CPU pressure this
     * change exists to remove. A length + hash of the boundary region is
````

### 5b. `IncrementalParseState` and the prefix fingerprint

````kotlin
     * O(1)-ish and false-positives only if an edit preserved both.
     */
    var prefixHash: Int = 0
    /**
     * [T-android-md-parse-tailcap] Whether the over-cap notice was already
     * logged for the current tail. A capped stream is parsed every throttle
     * interval, so logging unconditionally would turn one degrade into
     * dozens of identical lines in a log users are asked to attach.
     */
    var tailCapLogged: Boolean = false
}

/** Fingerprint over the tail of [content] up to [end] (bounded work). */
private fun prefixFingerprint(content: String, end: Int): Int {
    if (end <= 0) return 0
    val from = (end - 512).coerceAtLeast(0)
    var h = end
    for (i in from until end) h = h * 31 + content[i].code
    return h
}

/**
 * Offset of the end of the last "sealed" block boundary — the final blank line
 * that is followed by more content. Returns 0 when nothing is sealed yet.
 *
 * Deliberately conservative: a fenced code block can contain blank lines, so
 * an odd number of ``` fences before the candidate boundary means the boundary
 * is inside a fence and is not safe to freeze. Same for `$$` display math.
 */
/**
 * [T-android-md-parse-incremental] Test hook: the `raw` text of each block a
 * parse produces. Lets a unit test assert that feeding a stream in chunks
 * through [parseMarkdownIncremental] yields exactly the same block split as
 * one full parse of the final text — the invariant the whole optimization
 * rests on.
 */
internal fun debugParseBlockRaws(content: String): List<String> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content).map { it.raw } }

/**
 * [T-android-list-nested-table] Test hook: each block's type, with a list's
 * item children in brackets, e.g. `OrderedList[Paragraph,Table]`.
 */
internal fun debugParseBlockShapes(content: String): List<String> =
    kotlinx.coroutines.runBlocking {
        parseMarkdownBlocks(content).map { b ->
            val children = when (b) {
                is MdBlock.UnorderedList -> b.items.flatMap { it.children }
                is MdBlock.OrderedList -> b.items.flatMap { it.children }
                else -> emptyList()
````

### 5c. What may be frozen: `stableParsePrefixEnd`

````kotlin
            }
            val name = b::class.simpleName.orEmpty()
            if (children.isEmpty()) name else "$name[${children.joinToString(",") { it::class.simpleName.orEmpty() }}]"
        }
    }

/** Test hook: same, but replayed incrementally as the text grows. */
internal fun debugParseIncrementalRaws(chunks: List<String>): List<String> =
    kotlinx.coroutines.runBlocking {
        val state = IncrementalParseState()
        var last: List<MdBlock> = emptyList()
        for (c in chunks) last = parseMarkdownIncremental(c, state)
        last.map { it.raw }
    }

internal fun stableParsePrefixEnd(content: String, searchFrom: Int): Int {
    var idx = content.lastIndexOf("\n\n")
    while (idx >= searchFrom) {
        val candidate = idx + 2
        if (isSealedBoundary(content, candidate)) return candidate
        if (idx == 0) break
        idx = content.lastIndexOf("\n\n", idx - 1)
    }
    return searchFrom
}

/**
 * True when [end] is not inside an open ``` fence, `$$` / `\[` display-math
 * span, or a list that can still continue past the blank line.
 * Counts delimiters that START a line, matching how [parseMarkdownBlocks]
 * recognises them (it tests `trimmed.startsWith`).
 *
 * [T-android-md-sealed-boundary-lists] A blank line is only a safe freeze
 * point if no block construct in [parseMarkdownBlocks] crosses it. Fences and
 * `$$` were covered; two more constructs DO cross a blank line and were not:
 *  - `\[ … \]` display math: its loop scans forward over blank lines to the
 *    first line containing `\]`, so a blank line inside it is not a boundary.
 *  - bullet / ordered lists: both list loops `continue` over blank lines and
 *    keep consuming the next list item or indented line. A loose list or an
 *    item with an indented continuation paragraph is ONE block in a full
 *    parse; freezing at its blank line split it into several while streaming,
 *    and the block re-laid out at the streaming -> frozen edge.
 * Refusing costs only re-parse work; accepting wrongly changes the render.
 */
internal fun isSealedBoundary(content: String, end: Int): Boolean {
    var fences = 0
    var maths = 0
    var bracketMathOpen = false
    // Last non-blank line before [end] — a list only ever ends on a list item
    // or an indented line, so this is what decides whether one is still open.
    var lastNonBlankStart = -1
    var lastNonBlankEnd = -1
    var lineStart = 0
    while (lineStart < end) {
        var lineEnd = content.indexOf('\n', lineStart)
        if (lineEnd < 0 || lineEnd > end) lineEnd = end
        var p = lineStart
        while (p < lineEnd && (content[p] == ' ' || content[p] == '\t')) p++
        if (bracketMathOpen) {
            // Mirrors the parser's `\[` loop: the first line containing `\]`
            // anywhere closes the span.
            if (content.indexOf("\\]", lineStart).let { it in lineStart until lineEnd }) {
                bracketMathOpen = false
            }
        } else if (content.startsWith("\\[", p)) {
            bracketMathOpen = content.indexOf("\\]", p + 2).let { it !in 0 until lineEnd }
        }
        if (content.startsWith("```", p)) fences++
        else if (content.startsWith("$$", p)) {
            // A single-line `$$ … $$` opens and closes on the same line.
            maths += if (content.indexOf("$$", p + 2).let { it in 0 until lineEnd }) 0 else 1
        }
        if (p < lineEnd && content.substring(p, lineEnd).isNotBlank()) {
            lastNonBlankStart = lineStart
            lastNonBlankEnd = lineEnd
````

### 5d. `parseMarkdownIncremental` in full (after the change)

````kotlin
/**
 * [T-android-md-parse-gate] The entry point every streaming parse must use.
 *
 * Combines the two protections: it takes the global [markdownParseGate]
 * permit (so N concurrent sub-agent streams cannot multiply peak native ICU
 * allocation) and reuses the already-parsed stable prefix carried in [state]
 * (so an append-only stream is not re-parsed from byte 0 on every tick).
 *
 * The permit is taken around the parse only. Suspending here is the desired
 * back-pressure: a caller whose LaunchedEffect is cancelled while queued
 * never runs its parse, which is correct — a newer chunk has superseded it.
 */
private suspend fun parseMarkdownIncremental(
    content: String,
    state: IncrementalParseState,
    /**
     * Whether [content] is still growing. Only a growing tail is worth
     * capping, because only a growing tail is re-parsed on every throttle
     * tick; the final text is parsed once, and rendering a finished reply as
     * one giant preformatted block would be a regression the user would see.
     * Same asymmetry the fragment path already has: degrade while live, full
     * parse once when frozen.
     */
    streaming: Boolean = true,
): List<MdBlock> = markdownParseGate.withPermit {
    // Growth must be append-only for the cached prefix to remain valid. Any
    // other edit (retry, edit-message, a shorter re-render) resets the state.
    val reusable = state.prefixLength in 1..content.length &&
        prefixFingerprint(content, state.prefixLength) == state.prefixHash
    if (!reusable) {
        state.prefixLength = 0
        state.prefixBlocks = emptyList()
        state.prefixHash = 0
    }

    val head = state.prefixLength
    // Split the unparsed remainder at its newest sealed boundary, so the part
    // that can never change again is parsed exactly once, here, and then
    // frozen into the prefix for every later tick.
    val sealedEnd = stableParsePrefixEnd(content, head)
    val sealedTail = if (sealedEnd > head) {
        parseMarkdownBlocks(content.substring(head, sealedEnd))
    } else {
        emptyList()
    }
    val liveTail = if (sealedEnd < content.length) {
        // [T-android-md-parse-tailcap] Bound the per-tick block-parse work.
        //
        // stableParsePrefixEnd() refuses to freeze anything inside a fence or a
        // table, so a single unsplittable block that is still streaming keeps
        // the WHOLE of it live: every throttle tick re-parsed it from its own
        // start. parseMarkdownBlocks runs ~8 `Regex.matches` per line, and each
        // of those allocates a fresh native ICU MatcherState (libicu_jni
        // MatcherState::updateInput). A 100 KB paste -- a shell dump, a
        // logcat tail, a giant table -- is ~1.7 k lines, so one tick is
        // ~13 k native matcher allocations; at one tick per throttle interval
        // over a 60 s stream that is hundreds of MB of churn, and with N
        // concurrent streams it lands in the same place the parse gate was
        // added to defuse: native heap into the GBs and, in the field,
        // `Scudo ERROR: internal map failure` -> SIGABRT.
        //
        // Above the cap we still render every byte, but as one preformatted
        // block: no inline parse, no per-line matcher, so the cost of a tick
        // stops depending on how big the paste is. Same trade-off
        // [T-android-live-block-degrade] already makes for the fragment path.
        val liveLen = content.length - sealedEnd
        if (streaming && liveLen > MAX_INCREMENTAL_LIVE_TAIL_CHARS) {
            val tail = content.substring(sealedEnd)
            if (!state.tailCapLogged) {
                state.tailCapLogged = true
                com.openminis.app.logging.AppLogger.info(
                    "StreamDegrade",
                    "incremental live tail $liveLen chars over $MAX_INCREMENTAL_LIVE_TAIL_CHARS rendered as one preformatted block",
                )
            }
            listOf(MdBlock.CodeBlock(tail, "text", tail))
        } else {
            state.tailCapLogged = false
            parseMarkdownBlocks(content.substring(sealedEnd))
        }
    } else {
        emptyList()
    }

    if (sealedEnd > head) {
        state.prefixBlocks = state.prefixBlocks + sealedTail
        state.prefixLength = sealedEnd
        state.prefixHash = prefixFingerprint(content, sealedEnd)
    }
    state.prefixBlocks + liveTail
}
````

## 6. What was verified, and what was not

Verified — Kotlin 2.1.0 / OpenJDK 17, the edited function extracted verbatim and
compiled against stub signatures:

- compiles clean;
- 8/8 behaviour cases pass: over-cap live tail collapses to one `CodeBlock`;
  exactly at the cap is untouched; one char over collapses; finished 30 k text
  still parses as markdown; a small live tail is untouched; empty input yields
  no blocks; three capped ticks log once; dropping back under the cap re-arms
  the notice.

**Not verified** — please weigh accordingly:

- **No full Android build.** No Android SDK / Compose dependencies were
  available where this was produced, so `assembleDebug` was never run and the
  Compose call sites were never type-checked against the real project.
- **No instrumented test.** The existing test hook
  `debugParseIncrementalRaws(chunks)` asserts "incremental == full parse";
  the new cap deliberately breaks that equality for a capped tail, and no test
  was added to pin the new expected behaviour.
- **No on-device confirmation** that the crash stops. The crashing build is a
  repackaged distribution with a different `applicationId` and its own signing
  key, so a build of this tree cannot be installed over it.

Treat this as a diagnosis with a plausible, locally-tested fix — not a verified
field fix.

## 7. One more data point, deliberately not "fixed" here

`dumpsys activity exit-info`, 20 minutes after a restart, idle process:

````
VmSize 17,271,200 kB   (17.2 GB address space)
VmData  2,006,052 kB   (2.0 GB)
VmRSS     364,844 kB
Threads   74
````

An idle process holding 17 GB of address space is worth a look on its own, but
it could not be attributed to the markdown path, so it is reported rather than
patched.

## 8. Plain-text entry points (no JavaScript required)

GitHub's compare/blob pages are client-rendered, so the diff may not appear in
their initial HTML. These URLs return the raw text directly:

- Patch (whole commit): `https://github.com/jiangyuchen144-cmd/OpenMinis/commit/3d6c0ee.patch`
- Diff only:            `https://github.com/jiangyuchen144-cmd/OpenMinis/commit/3d6c0ee.diff`
- This file:            `https://raw.githubusercontent.com/jiangyuchen144-cmd/OpenMinis/android-md-parse-tailcap/HARK-REVIEW.md`
- Patch as a file:      `https://raw.githubusercontent.com/jiangyuchen144-cmd/OpenMinis/android-md-parse-tailcap/hark-md-parse-tailcap.patch`
