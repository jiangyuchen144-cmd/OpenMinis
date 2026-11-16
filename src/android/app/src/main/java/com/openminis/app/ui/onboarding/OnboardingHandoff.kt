package com.openminis.app.ui.onboarding

/**
 * [T-android-onboarding-v2] One-shot handoff of an API key from the welcome
 * screen to the provider form.
 *
 * The key deliberately does NOT travel as a navigation argument: route
 * arguments land in the back stack, in saved instance state, and in anything
 * that dumps either. This object is read once and cleared, exactly like
 * ShareCoordinator's pending-share buffer.
 *
 * Single-threaded use only — it is written from a click handler and read from
 * the provider screen's first composition, both on the main thread.
 */
object OnboardingHandoff {

    @Volatile
    private var pendingKey: String? = null

    fun offer(apiKey: String) {
        pendingKey = apiKey.trim().ifBlank { null }
    }

    /** Returns the key and clears it, so a second visit to the form gets none. */
    fun consume(): String? {
        val k = pendingKey
        pendingKey = null
        return k
    }
}
