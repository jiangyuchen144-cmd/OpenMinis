package com.openminis.app.ui.chat

/**
 * [T-android-topbar-title-slot] How a run ended.
 *
 * Exists so UI can stop treating "isStreaming went false" as "the reply
 * succeeded": a user stop and a failed turn both drop that flag. Only
 * [Completed] may show a completion affordance.
 *
 * Set once per run on the isStreaming falling edge (`ChatViewModel`), so it is
 * safe to read after the fact — unlike the one-shot `lastTurnWasCancelled`
 * flag it is derived from.
 */
enum class TurnEndReason {
    /** The run reached a normal end with no turn error. */
    Completed,

    /** The user tapped stop, or a model switch cancelled the retry ladder. */
    Cancelled,

    /** A turn error is present on this run's reply (see BackgroundTurnOutcome). */
    Failed,
}
