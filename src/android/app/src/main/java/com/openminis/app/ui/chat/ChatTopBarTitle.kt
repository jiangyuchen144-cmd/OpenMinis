package com.openminis.app.ui.chat

// [T-android-topbar-title-slot] The chat title bar's title slot.
//
// Idle: the conversation title (the caller passes the existing title row
// through `titleContent` unchanged — its fallback chain, single-line ellipsis
// and tap-to-rename all keep working).
// Working: the title fades out and a drawn activity indicator takes its place.
// Done: the indicator pops once into a check, holds, then fades back to title.
//
// The two model metadata rows that used to sit under the title are gone from
// the bar and live in the ⋮ menu (see ChatModelMenuItems), which is why the
// fallback pulse that used to tint row 3 is now drawn behind this slot.

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/** A sub-second blip of streaming must not flash the indicator on and off. */
private const val ON_DELAY_MS = 150L

/** Tool-call turns briefly drop isStreaming between rounds; absorb that. */
private const val OFF_DELAY_MS = 300L

/** Once shown, the indicator stays at least this long, or it just flickers. */
private const val MIN_WORKING_MS = 700L

/** How long the finished check stays before fading back to the title. */
private const val DONE_HOLD_MS = 900L

private const val FADE_MS = 220

private enum class TitlePhase { Idle, Working, Done }

/**
 * The title slot.
 *
 * @param sessionKey the session identity. Two-pane re-binds this composable to
 *   another session in place, so phase state is keyed on it — a transfer must
 *   not carry a half-finished "done" check into the other chat.
 * @param pulseProvider 0..1 fallback-pulse intensity. Pass your existing
 *   `Animatable<Float, *>.value`; this only reads it.
 * @param titleContent the existing title row, verbatim.
 */
@Composable
fun ChatTopBarTitle(
    sessionKey: Any,
    isStreaming: Boolean,
    pulseProvider: () -> Float,
    workingDescription: String,
    doneDescription: String,
    /**
     * Whether the run that just ended succeeded. Evaluated once, when the
     * Working phase ends — a cancelled or failed turn drops straight back to
     * the title instead of showing a completion check, because "isStreaming
     * went false" is equally true of a user stop and an error.
     */
    completedSuccessfully: () -> Boolean,
    modifier: Modifier = Modifier,
    /** Per-model artwork for the working state; null falls back to the rings. */
    @DrawableRes workingIconRes: Int? = null,
    titleContent: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion()
    // Read inside the effect without making `reduced` a restart key: toggling
    // the system setting mid-stream must not restart the phase machine.
    val reducedState = rememberUpdatedState(reduced)
    // [T-android-topbar-title-slot] The success verdict is deliberately NOT
    // wrapped in rememberUpdatedState, and deliberately not a LaunchedEffect
    // key. Both would break it:
    //
    //   * as a KEY it restarts this coroutine whenever the verdict changes,
    //     resetting the phase timing (out-delay, minimum-display remainder);
    //   * as rememberUpdatedState(completedSuccessfully()) it would only refresh
    //     on RECOMPOSITION, and `lastTurnEndReason` is read through .value here
    //     rather than collected, so it never forces one. The snapshot would
    //     freeze at whatever the PREVIOUS run's verdict was — exactly the stale
    //     read this is meant to avoid.
    //
    // Calling completedSuccessfully() inside the effect reads the live .value
    // instead, and it is only called after the delays below (300 ms out-delay
    // plus the minimum-display remainder), which is far longer than a StateFlow
    // emit takes to reach its own collector.

    var phase by remember(sessionKey) { mutableStateOf(TitlePhase.Idle) }
    var showDone by remember(sessionKey) { mutableStateOf(false) }
    var workingSince by remember(sessionKey) { mutableLongStateOf(0L) }

    LaunchedEffect(sessionKey, isStreaming) {
        if (isStreaming) {
            // Entering: hold Idle briefly so a 40 ms reply never flashes.
            if (phase == TitlePhase.Idle) delay(ON_DELAY_MS)
            if (phase != TitlePhase.Working) workingSince = SystemClock.elapsedRealtime()
            showDone = false
            phase = TitlePhase.Working
        } else if (phase == TitlePhase.Working) {
            // Leaving: a short gap between tool rounds is not the end.
            delay(OFF_DELAY_MS)
            val remaining = MIN_WORKING_MS - (SystemClock.elapsedRealtime() - workingSince)
            if (remaining > 0L) delay(remaining)
            // [T-android-topbar-title-slot] Only a SUCCESSFUL run gets the check.
            // Cancelled and failed runs share this transition (isStreaming goes
            // false either way), and showing "reply finished" over a turn the
            // user deliberately stopped — or one that errored — is a lie. A
            // failed run may still be flashing the fallback pulse right here,
            // which a check would contradict.
            if (reducedState.value || !completedSuccessfully()) {
                // Explicit, not implied by the Working branch above: `showDone`
                // survives into Idle after a Done phase, so leaving it set here
                // would make the NEXT transition read a stale "done" and skip
                // the indicator's re-arm.
                showDone = false
                phase = TitlePhase.Idle
            } else {
                showDone = true
                phase = TitlePhase.Done
                delay(DONE_HOLD_MS)
                showDone = false
                phase = TitlePhase.Idle
            }
        }
    }

    val titleHidden = phase != TitlePhase.Idle
    val titleAlpha by animateFloatAsState(
        targetValue = if (titleHidden) 0f else 1f,
        animationSpec = tween<Float>(durationMillis = if (reduced) 0 else FADE_MS),
        label = "chatTitleAlpha",
    )

    val enter: EnterTransition = if (reduced) {
        EnterTransition.None
    } else {
        fadeIn(tween(FADE_MS)) + scaleIn(tween(FADE_MS), initialScale = 0.6f)
    }
    val exit: ExitTransition = if (reduced) {
        ExitTransition.None
    } else {
        fadeOut(tween(FADE_MS)) + scaleOut(tween(FADE_MS), targetScale = 0.6f)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // The title always participates in layout so the bar height never
        // changes on a phase switch. The fallback pulse is drawn OUTSIDE the
        // faded layer, so a red flash is visible even while the title is hidden.
        Box(
            modifier = Modifier
                .drawBehind {
                    val p = pulseProvider().coerceIn(0f, 1f)
                    if (p > 0f) {
                        drawRoundRect(
                            // Same red and intensity the model row used before
                            // this refactor, so a fallback still reads the same.
                            color = Color.Red.copy(alpha = 0.35f * p),
                            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                        )
                    }
                }
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = titleAlpha }
                    // While invisible the title must not be a screen-reader
                    // target either, or the bar reads as two titles.
                    .then(if (titleHidden) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                titleContent()
            }
        }

        AnimatedVisibility(
            visible = titleHidden,
            enter = enter,
            exit = exit,
            modifier = Modifier.semantics {
                contentDescription = if (showDone) doneDescription else workingDescription
            },
        ) {
            WorkingIndicator(done = showDone, animated = !reduced, iconRes = workingIconRes)
        }

        // The title keeps its rename clickable even at alpha 0, so swallow taps
        // while it is hidden — otherwise a tap on the indicator opens the
        // rename sheet for a title the user cannot see.
        if (titleHidden) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures() },
            )
        }
    }
}

/**
 * [T-android-topbar-title-slot] Which icon to show for the working model, or
 * null to fall back to the drawn rings.
 *
 * Matched on the model's API slug first (stable, not user-editable, and
 * unambiguous when one aggregator entry serves many models), then on the
 * display name as a courtesy. Null means "no artwork for this model", which is
 * every model but DeepSeek today — the drawn indicator still works, so a model
 * without art is not a broken state.
 */
@DrawableRes
internal fun modelWorkingIconRes(slug: String, modelName: String): Int? {
    val key = "$slug $modelName".lowercase()
    return when {
        "deepseek" in key -> R.drawable.ic_model_working_default
        else -> null
    }
}

/**
 * Drawn, not an image asset, for every model without art: the project ships no
 * per-model icons beyond the one placeholder, and a vector keeps this working
 * before any artwork exists.
 *
 * With [iconRes] the artwork is drawn as a circular avatar over the same
 * expanding rings, with a slow float + sway. Without it, the rings and a
 * breathing core stand alone.
 */
@Composable
private fun WorkingIndicator(
    done: Boolean,
    animated: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Per-model artwork. Only used while working; the done check stays drawn so
     * it is legible at 28dp regardless of what the art looks like.
     */
    @DrawableRes iconRes: Int? = null,
) {
    val accent = ChatColors.link
    val onAccent = Color.White

    // A single 0..1 driver for both the ring expansion and the core breathing.
    val t: State<Float> = if (animated && !done) {
        rememberInfiniteTransition(label = "titleIndicator").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "titleIndicatorT",
        )
    } else {
        // Reduced motion (or the done state): freeze the driver mid-cycle.
        remember { mutableFloatStateOf(0.5f) }
    }

    val pop = remember { Animatable(1f) }
    LaunchedEffect(done) {
        if (done) {
            pop.animateTo(1.28f, tween(110))
            pop.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        } else {
            // Re-arm so the NEXT done still pops from rest.
            pop.snapTo(1f)
        }
    }

    val breathe = if (animated) 0.5f + 0.5f * sin(2f * PI.toFloat() * t.value) else 0.5f
    val sway = if (animated) 3f * sin(2f * PI.toFloat() * t.value) else 0f

    if (iconRes == null) {
        Canvas(
            modifier = modifier
                .size(28.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        ) {
            val maxR = size.minDimension / 2f
            if (done) {
                drawCircle(color = accent, radius = maxR * 0.62f)
                val c = center
                val r = maxR * 0.62f
                val check = Path().apply {
                    moveTo(c.x - r * 0.45f, c.y + r * 0.02f)
                    lineTo(c.x - r * 0.10f, c.y + r * 0.38f)
                    lineTo(c.x + r * 0.50f, c.y - r * 0.30f)
                }
                drawPath(
                    path = check,
                    color = onAccent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else {
                // Two offset rings chasing outward, plus a breathing core.
                val ringCount = if (animated) 2 else 1
                repeat(ringCount) { i ->
                    val p = if (animated) (t.value + i * 0.5f) % 1f else 0.5f
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.55f),
                        radius = maxR * (0.4f + 0.6f * p),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                drawCircle(color = accent, radius = maxR * (0.28f + 0.10f * breathe))
            }
        }
        return
    }

    // Per-model artwork: the same rings behind a circular avatar, with the
    // float/sway borrowed from the character's pose in the artwork.
    Box(
        modifier = modifier
            .size(28.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
        contentAlignment = Alignment.Center,
    ) {
        // Rings stay drawn (not art) so the indicator still animates even with
        // a still image sitting on top of it.
        Canvas(modifier = Modifier.matchParentSize()) {
            val maxR = size.minDimension / 2f
            if (done) {
                drawCircle(color = accent, radius = maxR * 0.62f)
                val c = center
                val r = maxR * 0.62f
                val check = Path().apply {
                    moveTo(c.x - r * 0.45f, c.y + r * 0.02f)
                    lineTo(c.x - r * 0.10f, c.y + r * 0.38f)
                    lineTo(c.x + r * 0.50f, c.y - r * 0.30f)
                }
                drawPath(
                    path = check,
                    color = onAccent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else {
                val ringCount = if (animated) 2 else 1
                repeat(ringCount) { i ->
                    val p = if (animated) (t.value + i * 0.5f) % 1f else 0.5f
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.55f),
                        radius = maxR * (0.4f + 0.6f * p),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
        if (!done) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        rotationZ = sway
                        translationY = if (animated) -1.5f * breathe else 0f
                    }
                    .clip(CircleShape),
            )
        }
    }
}

/**
 * True when the user has switched animations off — the system "Remove
 * animations" toggle or a 0x animator scale in developer options both land on
 * ANIMATOR_DURATION_SCALE = 0.
 *
 * Observed, not sampled once: the setting must take effect without a restart.
 * TRANSITION_ANIMATION_SCALE is deliberately NOT consulted — that one governs
 * window/activity transitions, not in-composition animation.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(readReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readReducedMotion(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The two model-metadata entries that used to be rows 2 and 3 of the title bar,
 * now menu items: switching the model (carrying the group / model fallback label
 * and the provider·model detail as sub-lines) and opening the thinking level
 * sheet (carrying the existing badge as `trailingIcon`).
 *
 * [showThinkingEntry] takes the caller's original display condition unchanged —
 * this file must not re-derive it.
 */
@Composable
fun ChatModelMenuItems(
    switchModelText: String,
    modelLabel: String,
    modelDetail: String,
    onPickModel: () -> Unit,
    thinkingText: String,
    showThinkingEntry: Boolean,
    thinkingBadge: @Composable () -> Unit,
    onOpenThinking: () -> Unit,
    /**
     * Optional trailing content on the switch-model row. The speed-tier badge
     * (Fast / Ultrafast) used to sit before the model name in the title bar; it
     * keeps a home here rather than being dropped in the move.
     */
    modelTrailing: (@Composable () -> Unit)? = null,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(text = switchModelText, style = MaterialTheme.typography.bodyLarge)
                if (modelLabel.isNotBlank()) {
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (modelDetail.isNotBlank()) {
                    Text(
                        text = modelDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onPickModel,
        trailingIcon = modelTrailing,
    )
    if (showThinkingEntry) {
        DropdownMenuItem(
            text = { Text(text = thinkingText, style = MaterialTheme.typography.bodyLarge) },
            onClick = onOpenThinking,
            trailingIcon = thinkingBadge,
        )
    }
}
