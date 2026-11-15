# HARK-REVIEW — Android markdown renderer, native OOM crash fix

Branch `android-md-parse-tailcap`, commit `2e57041`, on a fork of `OpenMinis/OpenMinis` (GPLv3).
The functional change is `+72 / -3` in
`src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt`.

This file is self-contained: the diff, then every piece of surrounding code the
diff depends on, so the change can be reviewed without opening a 255 KB source
file. It also records what a first review round found and what changed in
response — see §0.

```
HARK-REVIEW.md                                     | 727 +++++++++++++++++++++
 hark-md-parse-tailcap.patch                        | 211 ++++++
 .../openminis/app/ui/chat/StreamingMarkdownText.kt | 146 ++++-
 3 files changed, 1081 insertions(+), 3 deletions(-)
```

---

## 0. Changes since the first review round

A first read of this branch raised six points. Five were accepted, one was
rejected as mis-scoped. All of them are reflected in `2e57041`.

| # | Raised | Verdict | What changed |
|---|---|---|---|
| 1 | `isStreaming` is a parse input but not a `LaunchedEffect` key, so a stream that ends without the text changing keeps the capped tail | **Accepted — real bug** | `LaunchedEffect(displayContent, isStreaming)` in `StreamingMarkdownTextBody` (§4) |
| 2 | The same fix is needed at the second call site | **Rejected — mis-scoped** | That call site is inside `MarkdownBlockBody`, only reachable *after* `if (!isStreaming) { … return }`, so `isStreaming` is always `true` there. Passing it was dead code; it now uses the default, with a comment saying why (§4) |
| 3 | The char cap does not bound lines, and cost is per-line | **Accepted** | Added `MAX_INCREMENTAL_LIVE_TAIL_LINES = 512` and an allocation-free `countNewlinesUpTo` short-circuit (§5a) |
| 4 | `tailCapLogged` is not cleared when parse state resets | **Accepted, with a correction** | Cleared on reset, but only when a *real* prefix was discarded — clearing unconditionally re-logs every tick while a huge tail never seals (found by the test suite, §6) |
| 5 | Incremental-vs-full invariant is no longer tested | **Accepted** | 16 assertions now pin the invariant, the boundary behaviour, and that the synthesized block never enters the prefix cache (§6) |
| 6 | 24 000 vs 8 000 lacks measurement | **Partially accepted** | Kept 24 000 *with* the line budget, rather than lowering to 8 000 and degrading ordinary replies. Explicitly labelled unmeasured in both constants |

Still open and deliberately not fixed: a single full parse of a very large
*finished* message is not proven safe (§7). Capping it would show a finished
reply as preformatted text, which is worse than the risk it removes.

---

## 1. What is being asked of a reviewer

Does capping the *streaming* live tail — by characters and by lines — and
rendering the capped portion as one preformatted `CodeBlock`, actually bound the
per-tick native allocation, without breaking the incremental-parse invariant
(prefix reuse must produce the same block split as a full parse)?

Worth attacking specifically:

1. **Invariant.** The synthesized `CodeBlock` is returned but never stored in
   `state.prefixBlocks`. Is that enough, or can a capped tick still poison a
   later freeze?
2. **Effect keys.** Is `isStreaming` the only state that gates the cap? Are
   there other inputs whose change should restart the parse?
3. **Boundaries.** Exactly at either cap must be a no-op (`>` not `>=`).
4. **Numbers.** Both constants are budgets, not measurements.
5. **`markdownParseGate`.** The cap decision is inside `withPermit`; `prewarm`
   is outside it. Is that scoping still right?

---

## 2. The crash being fixed

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
the same signature the upstream `markdownParseGate` comment already documents
(native heap to 2.09 GB on a Pixel 6 -> `std::bad_alloc` -> SIGABRT).

## 3. Root cause

`markdownParseGate` + the incremental prefix parse fixed the concurrent-stream
case. What remained uncapped is the **live tail**:

- `stableParsePrefixEnd()` refuses to freeze a prefix inside a fence or a table.
- So one unsplittable block that is still growing keeps the whole of itself live.
- `parseMarkdownIncremental()` handed that whole tail to `parseMarkdownBlocks()`
  on **every** throttle tick.
- `parseMarkdownBlocks` splits on lines and runs ~8 `Regex.matches` per line,
  each allocating a fresh native ICU `MatcherState`.

A 100 KB paste is ~1.7 k lines -> ~13 k native matcher allocations **per tick**.
Over a 60 s stream that is hundreds of MB of native churn from a single block.
The shard path had no size guard at all, unlike the fragment path which degrades
at `LIVE_FRAGMENT_DEGRADE_CHARS = 8_000`.

## 4. The change (diff)

````diff
diff --git a/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt b/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt
index a19010a..862696f 100644
--- a/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt
+++ b/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt
@@ -871,9 +871,18 @@ private fun StreamingMarkdownTextBody(
     // [T-android-md-parse-incremental] Per-composable parse state, so an
     // append-only stream re-parses only its newly-arrived tail.
     val parseState = remember { IncrementalParseState() }
-    LaunchedEffect(displayContent) {
+    // [T-android-md-parse-tailcap] `isStreaming` is a physical INPUT to the
+    // parse (it decides whether the live tail may be capped), so it has to be
+    // a restart key as well: flipping true -> false does not necessarily
+    // change `displayContent` — when the last throttled publish already equals
+    // the final content, produceState re-emits the same value and this effect
+    // would otherwise never run again, leaving the capped preformatted tail on
+    // screen for a message that has finished. Re-parsing on the flip is the
+    // point: the frozen text gets its real markdown structure back exactly
+    // once.
+    LaunchedEffect(displayContent, isStreaming) {
         val computed = withContext(Dispatchers.Default) {
-            parseMarkdownIncremental(displayContent, parseState).also {
+            parseMarkdownIncremental(displayContent, parseState, streaming = isStreaming).also {
                 MarkdownParseCaches.prewarm(it, mdColors)
             }
         }
@@ -1333,6 +1342,11 @@ private fun MarkdownBlockBody(
     // A plain holder, not snapshot state: it is written from the parse
     // coroutine and must not invalidate anything.
     val liveDepositKey = remember { arrayOf<String?>(null) }
+    // Reached only after the `if (!isStreaming) { … return }` frozen branch
+    // above, so this composable is live here by construction: `streaming` is
+    // true and needs no key of its own. (The live -> frozen flip is handled by
+    // that branch taking over entirely, which is why only the shard-scoped
+    // StreamingMarkdownTextBody needs `isStreaming` in its effect key.)
     LaunchedEffect(displayContent) {
         // [T-android-stream-render-profile] Time the whole off-main tick
         // (block split + prewarm/incremental inline+math) — this is what the
@@ -1984,6 +1998,59 @@ private fun findDisplayMathClose(lines: List<String>, from: Int): Int? {
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
+/**
+ * [T-android-md-parse-tailcap] Second budget for the same decision: how many
+ * LINES the live tail may span before it is rendered preformatted.
+ *
+ * The character cap alone does not bound the work. `parseMarkdownBlocks` splits
+ * on `content.lines()` and runs its regex battery once per line, so 24 k
+ * characters of full-width prose is a few hundred lines, while 24 k characters
+ * of short lines (a table, a log dump) is ~8 k lines and ~8 k matcher
+ * allocations *per tick* — the number the character cap was supposed to bound.
+ *
+ * 512 is a starting budget, not a measured one: it is high enough that ordinary
+ * streamed replies never reach it, and low enough that the per-tick matcher
+ * count stays in the thousands. Same caveat as the character cap — if this ever
+ * needs tuning, it needs device measurements, not a guess.
+ */
+private const val MAX_INCREMENTAL_LIVE_TAIL_LINES = 512
+
+/**
+ * Count newlines in `content[from until content.length]`, stopping as soon as
+ * the count exceeds [limit]. Returns a value greater than [limit] to signal
+ * "too many"; the exact number is not needed and walking a huge tail to get it
+ * would defeat the purpose. Allocation-free, no regex.
+ */
+private fun countNewlinesUpTo(content: String, from: Int, limit: Int): Int {
+    var n = 0
+    var i = from
+    while (i < content.length && n <= limit) {
+        if (content[i] == '\n') n++
+        i++
+    }
+    return n
+}
+
 /**
  * [T-android-md-parse-incremental] Longest already-parsed prefix of a growing
  * stream, so an append-only update re-parses only the tail.
@@ -2016,6 +2083,13 @@ private class IncrementalParseState {
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
@@ -2181,15 +2255,33 @@ private fun isListContinuationLine(line: String): Boolean {
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
     val reusable = state.prefixLength in 1..content.length &&
         prefixFingerprint(content, state.prefixLength) == state.prefixHash
     if (!reusable) {
+        // [T-android-md-parse-tailcap] A non-append edit throws the frozen
+        // prefix away, so the over-cap notice is owed again for whatever
+        // replaces it. Guarded on "there was a prefix to throw away": while a
+        // large unsplittable tail is still streaming, nothing ever seals, so
+        // prefixLength stays 0 and every tick re-enters this branch — clearing
+        // the flag unconditionally here would re-log the same degrade on every
+        // throttle interval, which is exactly the log spam the flag prevents.
+        val hadPrefix = state.prefixLength > 0 || state.prefixBlocks.isNotEmpty()
         state.prefixLength = 0
         state.prefixBlocks = emptyList()
         state.prefixHash = 0
+        if (hadPrefix) state.tailCapLogged = false
     }
 
     val head = state.prefixLength
@@ -2203,8 +2295,56 @@ private suspend fun parseMarkdownIncremental(
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
+        // [T-android-md-parse-tailcap] Two budgets, because the cost driver is
+        // LINES, not characters: parseMarkdownBlocks splits on lines and runs
+        // its regex battery per line. A 24 k tail of full-width prose is a few
+        // hundred lines, but a 24 k tail of short lines (a table, a log) is
+        // ~8 k lines and as many matcher allocations per tick. Counting stops
+        // as soon as the limit is exceeded, so the check never walks the whole
+        // tail for a normal stream.
+        val overCap = streaming && (
+            liveLen > MAX_INCREMENTAL_LIVE_TAIL_CHARS ||
+                countNewlinesUpTo(content, sealedEnd, MAX_INCREMENTAL_LIVE_TAIL_LINES) >
+                MAX_INCREMENTAL_LIVE_TAIL_LINES
+            )
+        if (overCap) {
+            val tail = content.substring(sealedEnd)
+            if (!state.tailCapLogged) {
+                state.tailCapLogged = true
+                com.openminis.app.logging.AppLogger.info(
+                    "StreamDegrade",
+                    "incremental live tail ${liveLen}ch over the parse budget " +
+                        "(${MAX_INCREMENTAL_LIVE_TAIL_CHARS}ch/${MAX_INCREMENTAL_LIVE_TAIL_LINES}ln) " +
+                        "rendered as one preformatted block",
+                )
+            }
+            listOf(MdBlock.CodeBlock(tail, "text", tail))
+        } else {
+            state.tailCapLogged = false
+            parseMarkdownBlocks(content.substring(sealedEnd))
+        }
     } else {
+        state.tailCapLogged = false
         emptyList()
     }
````

## 5. Surrounding code the change depends on

### 5a. The process-wide gate, the budgets, and the line counter

````kotlin
/**
 * [T-android-md-parse-gate] Global cap on how many markdown parses may run at
 * once, across every ChatViewModel in the process.
 *
 * Background: a multi-subtask agent run streams N child sessions at the same
 * time. Each visible transcript owns its own throttled parse loop, and every
 * pass re-parses that session's WHOLE accumulated reply. `Regex.matches` is
 * used ~8 times per line, and each call allocates a fresh native ICU
 * `MatcherState` (libicu_jni `MatcherState::updateInput`). With N streams the
 * native allocation rate multiplies by N while `Dispatchers.Default` happily
 * runs one parse per core — on a Pixel 6 that reached 2.09 GB native heap, a
 * 512 MB Dalvik heap pinned at its largeHeap ceiling, `ReferenceQueueDaemon`
 * spinning a full core on GC, and finally `std::bad_alloc` inside
 * `operator new` → SIGABRT.
 *
 * Serialising parses does NOT slow the visible stream down: a parse is at most
 * a few tens of ms and the display content is already throttled to
 * 200-2000 ms per session ([streamingThrottleFor]). What it does is bound
 * peak concurrent ICU matcher + block-list allocation to one pass instead of
 * `min(N, coreCount)`, which is what turns the OOM into ordinary back-pressure.
 *
 * Permit 1 by design: with 2 the peak halves but does not bound, and the
 * throttle already leaves each stream far more wall-clock budget than it
 * needs. Callers that are cancelled while queued never start a parse at all,
 * which is exactly the behaviour we want for superseded chunks.
 */
private val markdownParseGate = Semaphore(1)

/**
 * [T-android-md-parse-tailcap] Ceiling on the unsealed ("live") text one
 * incremental parse pass may hand to the block parser, applied only while the
 * content is still streaming.
 *
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
 * [T-android-md-parse-tailcap] Second budget for the same decision: how many
 * LINES the live tail may span before it is rendered preformatted.
 *
 * The character cap alone does not bound the work. `parseMarkdownBlocks` splits
 * on `content.lines()` and runs its regex battery once per line, so 24 k
 * characters of full-width prose is a few hundred lines, while 24 k characters
 * of short lines (a table, a log dump) is ~8 k lines and ~8 k matcher
 * allocations *per tick* — the number the character cap was supposed to bound.
 *
 * 512 is a starting budget, not a measured one: it is high enough that ordinary
 * streamed replies never reach it, and low enough that the per-tick matcher
 * count stays in the thousands. Same caveat as the character cap — if this ever
 * needs tuning, it needs device measurements, not a guess.
 */
private const val MAX_INCREMENTAL_LIVE_TAIL_LINES = 512

/**
 * Count newlines in `content[from until content.length]`, stopping as soon as
 * the count exceeds [limit]. Returns a value greater than [limit] to signal
 * "too many"; the exact number is not needed and walking a huge tail to get it
 * would defeat the purpose. Allocation-free, no regex.
 */
private fun countNewlinesUpTo(content: String, from: Int, limit: Int): Int {
    var n = 0
    var i = from
    while (i < content.length && n <= limit) {
        if (content[i] == '\n') n++
        i++
    }
    return n
}
````

````kotlin
/**
 * [T-android-md-parse-tailcap] Ceiling on the unsealed ("live") text one
 * incremental parse pass may hand to the block parser, applied only while the
 * content is still streaming.
 *
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
````

````kotlin
/**
 * [T-android-md-parse-tailcap] Second budget for the same decision: how many
 * LINES the live tail may span before it is rendered preformatted.
 *
 * The character cap alone does not bound the work. `parseMarkdownBlocks` splits
 * on `content.lines()` and runs its regex battery once per line, so 24 k
 * characters of full-width prose is a few hundred lines, while 24 k characters
 * of short lines (a table, a log dump) is ~8 k lines and ~8 k matcher
 * allocations *per tick* — the number the character cap was supposed to bound.
 *
 * 512 is a starting budget, not a measured one: it is high enough that ordinary
 * streamed replies never reach it, and low enough that the per-tick matcher
 * count stays in the thousands. Same caveat as the character cap — if this ever
 * needs tuning, it needs device measurements, not a guess.
 */
private const val MAX_INCREMENTAL_LIVE_TAIL_LINES = 512
````

````kotlin
/**
 * Count newlines in `content[from until content.length]`, stopping as soon as
 * the count exceeds [limit]. Returns a value greater than [limit] to signal
 * "too many"; the exact number is not needed and walking a huge tail to get it
 * would defeat the purpose. Allocation-free, no regex.
 */
private fun countNewlinesUpTo(content: String, from: Int, limit: Int): Int {
    var n = 0
    var i = from
    while (i < content.length && n <= limit) {
        if (content[i] == '\n') n++
        i++
    }
    return n
}
````

### 5b. `IncrementalParseState` and the prefix fingerprint

````kotlin
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
````

### 5c. What may be frozen: `stableParsePrefixEnd`

````kotlin
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
````

### 5d. `parseMarkdownIncremental` in full

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
        // [T-android-md-parse-tailcap] A non-append edit throws the frozen
        // prefix away, so the over-cap notice is owed again for whatever
        // replaces it. Guarded on "there was a prefix to throw away": while a
        // large unsplittable tail is still streaming, nothing ever seals, so
        // prefixLength stays 0 and every tick re-enters this branch — clearing
        // the flag unconditionally here would re-log the same degrade on every
        // throttle interval, which is exactly the log spam the flag prevents.
        val hadPrefix = state.prefixLength > 0 || state.prefixBlocks.isNotEmpty()
        state.prefixLength = 0
        state.prefixBlocks = emptyList()
        state.prefixHash = 0
        if (hadPrefix) state.tailCapLogged = false
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
        // [T-android-md-parse-tailcap] Two budgets, because the cost driver is
        // LINES, not characters: parseMarkdownBlocks splits on lines and runs
        // its regex battery per line. A 24 k tail of full-width prose is a few
        // hundred lines, but a 24 k tail of short lines (a table, a log) is
        // ~8 k lines and as many matcher allocations per tick. Counting stops
        // as soon as the limit is exceeded, so the check never walks the whole
        // tail for a normal stream.
        val overCap = streaming && (
            liveLen > MAX_INCREMENTAL_LIVE_TAIL_CHARS ||
                countNewlinesUpTo(content, sealedEnd, MAX_INCREMENTAL_LIVE_TAIL_LINES) >
                MAX_INCREMENTAL_LIVE_TAIL_LINES
            )
        if (overCap) {
            val tail = content.substring(sealedEnd)
            if (!state.tailCapLogged) {
                state.tailCapLogged = true
                com.openminis.app.logging.AppLogger.info(
                    "StreamDegrade",
                    "incremental live tail ${liveLen}ch over the parse budget " +
                        "(${MAX_INCREMENTAL_LIVE_TAIL_CHARS}ch/${MAX_INCREMENTAL_LIVE_TAIL_LINES}ln) " +
                        "rendered as one preformatted block",
                )
            }
            listOf(MdBlock.CodeBlock(tail, "text", tail))
        } else {
            state.tailCapLogged = false
            parseMarkdownBlocks(content.substring(sealedEnd))
        }
    } else {
        state.tailCapLogged = false
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

### 5e. The effect that now keys on `isStreaming`

````kotlin
    // [T-android-md-parse-tailcap] `isStreaming` is a physical INPUT to the
    // parse (it decides whether the live tail may be capped), so it has to be
    // a restart key as well: flipping true -> false does not necessarily
    // change `displayContent` — when the last throttled publish already equals
    // the final content, produceState re-emits the same value and this effect
    // would otherwise never run again, leaving the capped preformatted tail on
    // screen for a message that has finished. Re-parsing on the flip is the
    // point: the frozen text gets its real markdown structure back exactly
    // once.
    LaunchedEffect(displayContent, isStreaming) {
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

    ShardSubIndexScope {
````

## 6. What the test suite pins

Compiled with Kotlin 2.1.0 / OpenJDK 17, the function under test extracted
verbatim from the file and compiled against stub signatures. `parseMarkdownBlocks`
is a real miniature parser in the harness (blank-line separated paragraphs), so
the incremental-vs-full comparison is meaningful. 16/16 checks pass:

- 30 k char live tail collapses to one `CodeBlock`; exactly at the char cap is
  untouched; one char over collapses;
- 600 short lines (1 199 chars, well under the char cap) collapse — this is the
  case the character budget alone cannot catch; exactly at the line cap untouched;
- finished 30 k text parses as markdown;
- **the reviewer's scenario**: live-capped, then finished with the *same* text,
  restores real blocks;
- the synthesized `CodeBlock` never enters `state.prefixBlocks`;
- incremental block split == full parse split for content under budget;
- a capped segment does not poison a later freeze: after appending more text and
  finishing, the result equals a full parse of the whole document;
- three capped ticks log once; the notice re-arms when the tail stops being
  capped; it also re-arms after a non-append edit that discarded a real prefix;
- content with no unsealed remainder still yields blocks; empty input yields none.

**Not verified:**

- **No full Android build.** No Android SDK / Compose dependencies were
  available where this was produced, so `assembleDebug` was never run and the
  Compose call sites were never type-checked against the real project. The
  `LaunchedEffect` key fix in particular is only reasoned about, not observed on
  a device.
- **No on-device confirmation** that the crash stops, and no measurement of the
  two budget constants.
- **No instrumented test** in the project's own test tree; the assertions above
  live in a standalone harness.

## 7. Deliberately not fixed

**A finished message is still parsed in full, unbounded.** A 500 KB reply is one
large parse. That is the cost the cold-parse path already pays for every message
on session open, and it is off-main with a plain-text preview. Capping it would
mean showing a finished reply as one preformatted block — worse than the risk.
The honest statement is "the cap removes *repeated* unbounded parses, not the
*single* one".

**An idle process holds 17 GB of address space.**

````
VmSize 17,271,200 kB   (17.2 GB)
VmData  2,006,052 kB   (2.0 GB)
VmRSS     364,844 kB
Threads   74
````

Worth a look on its own, but it could not be attributed to the markdown path, so
it is reported rather than patched.

## 8. Plain-text entry points (no JavaScript required)

GitHub's compare/blob pages are client-rendered, so a diff may not appear in
their initial HTML. These return raw text:

- This file: `https://raw.githubusercontent.com/jiangyuchen144-cmd/OpenMinis/android-md-parse-tailcap/HARK-REVIEW.md`
- Patch:     `https://raw.githubusercontent.com/jiangyuchen144-cmd/OpenMinis/android-md-parse-tailcap/hark-md-parse-tailcap.patch`
- Commit patch: `https://github.com/jiangyuchen144-cmd/OpenMinis/commit/2e57041.patch`
- Commit diff:  `https://github.com/jiangyuchen144-cmd/OpenMinis/commit/2e57041.diff`
