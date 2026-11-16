package com.openminis.app.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-onboarding-gate] First-launch flag.
 *
 * Its own SharedPreferences file, deliberately NOT the appearance prefs: those
 * are cosmetic settings a user may reset, back up or restore, and this flag
 * must not move with them.
 *
 * Read synchronously — it is consulted before the first frame to decide the
 * navigation graph's start destination, so an async read would mean rendering
 * the wrong screen first.
 */
object OnboardingPrefs {
    private const val FILE = "onboarding_state"
    private const val KEY_DONE = "onboarding_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}

/** How long to wait for the provider config before deciding "not first launch". */
private const val CONFIG_LOAD_TIMEOUT_MS = 5_000L

/**
 * [T-android-onboarding-gate] Should the first-launch guide be shown?
 *
 * True only when ALL of these hold: the flag was never written, the provider
 * config finished loading, and there are no instances at all.
 *
 * Everything ambiguous resolves to false. A timeout, a failed load, or a store
 * the loader refused to read must never put an existing user in a guide —
 * and must never let someone save a provider over a config that has not
 * actually been read.
 *
 * A user who already has instances but no flag (an upgrade from a build that
 * predates this) writes the flag here, so the check costs one read afterwards.
 *
 * @param awaitConfigLoaded suspends until the initial config load has been
 *   attempted, success or failure. Completes on both paths, so this cannot
 *   hang; the timeout is a second line of defence.
 * @param configLoaded whether that attempt actually produced a config. False
 *   means the store was unreadable and is left unloaded for retry — NOT the
 *   same as "no providers".
 */
suspend fun shouldShowOnboarding(
    context: Context,
    awaitConfigLoaded: suspend () -> Unit,
    configLoaded: () -> Boolean,
    hasAnyInstance: () -> Boolean,
): Boolean {
    if (OnboardingPrefs.isDone(context)) return false
    val finished = withTimeoutOrNull(CONFIG_LOAD_TIMEOUT_MS) {
        awaitConfigLoaded()
        true
    } ?: false
    if (!finished || !configLoaded()) return false
    if (hasAnyInstance()) {
        // Existing user from a build without this flag: record it so the next
        // launch takes the synchronous path and never flashes a blank frame.
        OnboardingPrefs.markDone(context)
        return false
    }
    return true
}

/**
 * [T-android-onboarding-gate] Placeholder for the real guide.
 *
 * Exists to prove the routing before any onboarding UI is written: it can be
 * reached on a fresh install, Skip persists the flag, and restarting does not
 * show it again.
 */
@Composable
fun OnboardingGatePlaceholder(onSkip: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.onboarding_gate_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSkip) {
                Text(stringResource(R.string.onboarding_gate_skip))
            }
        }
    }
}
