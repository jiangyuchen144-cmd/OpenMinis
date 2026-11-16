package com.openminis.app.ui.onboarding

// [T-android-onboarding-deepseek] The welcome screen, in DeepSeek Harness's own
// visual language: a deep navy ground that drifts, an overline badge, two lines
// of display type, and two ways in.
//
// One screen, two states. "API 登录" does NOT push another page — the field
// appears in place and the pill underneath becomes the confirm control. That is
// why the previous separate key screen is gone: it was a second full-page
// surface for a single text field, and it lost the background with it.

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-onboarding-deepseek] The completed flag.
 *
 * Its own SharedPreferences file, deliberately not the appearance prefs: those
 * are cosmetic settings a user may reset or restore from a backup, and this
 * flag must not travel with them.
 *
 * The key is versioned, and is bumped whenever the screen is replaced rather
 * than adjusted. Any device that answered the previous screen has answered a
 * different question.
 */
object OnboardingPrefs {
    private const val FILE_NAME = "onboarding_state"
    private const val KEY_COMPLETED = "completed_v6"

    fun hasCompleted(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_COMPLETED, false)
        }.getOrDefault(false)

    fun markCompleted(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPLETED, true)
                .apply()
        }
    }
}

/**
 * [T-android-onboarding-deepseek] Whether to show the welcome screen.
 *
 * Reads the flag only, synchronously, because the answer decides the navigation
 * graph's start destination before the first frame.
 */
fun shouldShowOnboarding(context: Context): Boolean =
    !OnboardingPrefs.hasCompleted(context)

// ─── Palette ────────────────────────────────────────────────────────────────
private val NavyDeep = Color(0xFF0B1526)
private val NavyMid = Color(0xFF152A4A)
private val NavyHigh = Color(0xFF1E4270)
private val GlassWhite = Color(0xFFFFFFFF)
private val HintBlue = Color(0xFF9FB6D4)
private val WhaleBlue = Color(0xFF4A9EFF)
private val OkGreen = Color(0xFF2FBF71)
private val ErrorRed = Color(0xFFE0674F)

/** How long the green check holds before it becomes the arrow. */
private const val CHECK_HOLD_MS = 700L

/**
 * The key is checked against the vendor, not against its shape.
 *
 * "Looks like a key" was the previous rule and it is not the same question: a
 * well-formed string that DeepSeek rejects would have walked the user into a
 * provider that cannot answer. One GET to the models endpoint is cheap, runs
 * while they are still looking at the field, and answers the question they
 * actually have.
 *
 * Returns true only when the endpoint answered with a model list. Every other
 * outcome — timeout, HTTP error, malformed body, no network — is a false, with
 * no attempt to guess which.
 */
private suspend fun validateDeepSeekKey(key: String): Boolean {
    if (key.length < 8) return false
    return withTimeoutOrNull(15_000) {
        runCatching {
            val r = com.openminis.app.provider.openai.OpenAIModelsApi.fetchModelsDetailed(
                apiKey = key,
                baseURL = DEEPSEEK_API_BASE,
                // No context: the cache is keyed by key + base and a hit would
                // answer from a previous session's result instead of asking.
                context = null,
                forceRefresh = true,
            )
            r.error == null && r.models.isNotEmpty()
        }.getOrDefault(false)
    } ?: false
}

private const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

private enum class KeyState { Idle, Checking, Ok, Bad }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingGateScreen(
    onSignInWithDeepSeek: () -> Unit,
    onApiVerified: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var keyState by remember { mutableStateOf(KeyState.Idle) }
    // The pill holds a green check for a beat before it becomes the arrow, so
    // the success is seen rather than inferred from a button changing shape.
    var showArrow by remember { mutableStateOf(false) }

    // Debounced validation: typing produces one request per pause, and a new
    // keystroke cancels the in-flight one by restarting this effect.
    LaunchedEffect(apiKey, apiOpen) {
        if (!apiOpen) return@LaunchedEffect
        showArrow = false
        if (apiKey.isBlank()) {
            keyState = KeyState.Idle
            return@LaunchedEffect
        }
        keyState = KeyState.Checking
        delay(450)
        val ok = validateDeepSeekKey(apiKey.trim())
        keyState = if (ok) KeyState.Ok else KeyState.Bad
        if (ok) {
            delay(CHECK_HOLD_MS)
            showArrow = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NavyDeep),
    ) {
        FlowingNavyBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // Overline: a small four-point sparkle and the preview label.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SparkleMark(Modifier.size(13.dp), HintBlue)
                Text(
                    text = stringResource(R.string.onboarding_v3_badge),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = HintBlue,
                    letterSpacing = 0.4.sp,
                )
            }

            Spacer(Modifier.weight(0.9f))

            WhaleMark(
                modifier = Modifier.size(88.dp),
                tint = GlassWhite,
            )

            Spacer(Modifier.height(26.dp))

            Text(
                text = stringResource(R.string.onboarding_v3_title_1),
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassWhite,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )
            Text(
                text = stringResource(R.string.onboarding_v3_title_2),
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassWhite,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.onboarding_v3_subtitle),
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Medium,
                color = GlassWhite,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1.1f))

            if (apiOpen) {
                ApiInlinePanel(
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    keyState = keyState,
                    showArrow = showArrow,
                    onBack = {
                        apiOpen = false
                        apiKey = ""
                        keyState = KeyState.Idle
                        showArrow = false
                    },
                    onConfirm = { onApiVerified(apiKey.trim()) },
                )
            } else {
                WelcomeButtons(
                    onSignInWithDeepSeek = onSignInWithDeepSeek,
                    onApiLogin = { apiOpen = true },
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

/**
 * The ground: two slow, out-of-phase radial washes over the navy base. Drawn on
 * a Canvas driven by one infinite float, so it costs one value read per frame
 * and never re-lays-out.
 */
@Composable
private fun FlowingNavyBackdrop() {
    val transition = rememberInfiniteTransition(label = "onboardingBackdrop")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "backdropPhase",
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Base wash, drifting diagonally.
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(NavyDeep, NavyMid, NavyHigh, NavyMid),
                start = Offset(-size.width * 0.3f + size.width * 0.6f * t, 0f),
                end = Offset(size.width * 1.1f + size.width * 0.6f * t, size.height),
            ),
        )
        // A soft highlight that breathes, so the surface reads as moving rather
        // than merely tinted.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(NavyHigh.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * (0.18f + 0.64f * t), size.height * 0.30f),
                radius = size.width * 0.85f,
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width * (0.18f + 0.64f * t), size.height * 0.30f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(WhaleBlue.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * (0.85f - 0.55f * t), size.height * 0.78f),
                radius = size.width * 0.7f,
            ),
            radius = size.width * 0.7f,
            center = Offset(size.width * (0.85f - 0.55f * t), size.height * 0.78f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeButtons(
    onSignInWithDeepSeek: () -> Unit,
    onApiLogin: () -> Unit,
) {
    // Primary: the DeepSeek door, carrying a flowing blue gradient rather than
    // the flat black pill it used to be — on a navy ground a black pill reads
    // as a hole.
    FlowGradientPill(
        onClick = onSignInWithDeepSeek,
        height = 56.dp,
    ) {
        WhaleMark(Modifier.size(20.dp), GlassWhite)
        Spacer(Modifier.width(9.dp))
        Text(
            text = stringResource(R.string.onboarding_v2_deepseek_login),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = GlassWhite,
        )
    }

    Spacer(Modifier.height(12.dp))

    // Secondary: API sign-in. Ghost outline, so the pair reads as
    // "recommended" and "I already have one" without a second filled button.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        onClick = onApiLogin,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.onboarding_v3_api_login),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = GlassWhite.copy(alpha = 0.92f),
            )
        }
    }
}

/**
 * The inline key field and its confirm pill.
 *
 * The pill is the status surface: dim while the key is unusable, a green check
 * for a beat once the vendor accepts it, then an arrow that acts. There is no
 * separate confirmation screen because there is nothing else to confirm — the
 * field and the action are one thought.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiInlinePanel(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    keyState: KeyState,
    showArrow: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val verified = keyState == KeyState.Ok

    // Field.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.07f),
        border = BorderStroke(
            1.dp,
            when (keyState) {
                KeyState.Ok -> OkGreen.copy(alpha = 0.8f)
                KeyState.Bad -> ErrorRed.copy(alpha = 0.8f)
                KeyState.Checking -> WhaleBlue.copy(alpha = 0.6f)
                KeyState.Idle -> Color.White.copy(alpha = 0.18f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (apiKey.isEmpty()) {
                    Text(
                        text = stringResource(R.string.onboarding_v2_api_key_placeholder),
                        fontSize = 15.sp,
                        color = HintBlue.copy(alpha = 0.65f),
                    )
                }
                BasicTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = GlassWhite),
                    cursorBrush = Brush.verticalGradient(listOf(WhaleBlue, WhaleBlue)),
                    // KeyboardType only picks the keyboard; it does not mask
                    // anything. Without this the key was shown in full on
                    // screen, which is not what a password field means.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Trailing status: a turning ring while the vendor is being asked,
            // a check once it answers yes, a warning when it says no.
            when (keyState) {
                KeyState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.8.dp,
                    color = WhaleBlue,
                )
                KeyState.Ok -> Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.onboarding_v2_api_key_valid),
                    tint = OkGreen,
                    modifier = Modifier.size(18.dp),
                )
                KeyState.Bad -> Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = stringResource(R.string.onboarding_v3_api_invalid),
                    tint = ErrorRed,
                    modifier = Modifier.size(16.dp),
                )
                KeyState.Idle -> Unit
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // A one-line verdict, only when there is something to say.
    val verdict = when (keyState) {
        KeyState.Checking -> stringResource(R.string.onboarding_v3_api_checking)
        KeyState.Bad -> stringResource(R.string.onboarding_v3_api_invalid)
        else -> null
    }
    if (verdict != null) {
        Text(
            text = verdict,
            fontSize = 12.sp,
            color = if (keyState == KeyState.Bad) ErrorRed else HintBlue,
        )
        Spacer(Modifier.height(6.dp))
    }

    Spacer(Modifier.height(12.dp))

    // Confirm pill: three faces, one surface.
    val pillColor = when {
        verified -> OkGreen
        else -> Color.White.copy(alpha = 0.12f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(50),
        color = pillColor,
        enabled = verified,
        onClick = { if (showArrow) onConfirm() },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = when {
                    !verified -> "idle"
                    showArrow -> "arrow"
                    else -> "check"
                },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "confirmPill",
            ) { face ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (face) {
                        "check" -> Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.onboarding_v2_api_key_valid),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                        "arrow" -> ArrowGlyph(
                            Modifier.size(30.dp),
                            Color.White,
                        )
                        else -> Text(
                            text = stringResource(R.string.onboarding_v2_continue),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }

    Text(
        text = stringResource(R.string.onboarding_v2_back),
        modifier = Modifier
            .padding(top = 10.dp)
            .clickableNoRipple(onBack),
        fontSize = 14.sp,
        color = HintBlue,
    )
}

/** A pill whose fill drifts, shared by the primary action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowGradientPill(
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "pillFlow")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pillPhase",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1D4ED8),
                            Color(0xFF2563EB),
                            Color(0xFF3B82F6),
                            Color(0xFF1D4ED8),
                        ),
                        start = Offset(-200f + 700f * t, 0f),
                        end = Offset(600f + 700f * t, 220f),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) { content() }
        }
    }
}

/**
 * The DeepSeek mark: a whale in profile.
 *
 * Drawn as paths rather than shipped as a bitmap — it has to sit white on a
 * gradient pill, blue on the ground, and stay crisp at 20dp, and there is no
 * DeepSeek artwork in the tree to ship.
 */
@Composable
private fun WhaleMark(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Body + tail, one silhouette: snout left, back over the head, fluke
        // raised at upper right, then the belly returning.
        val body = Path().apply {
            moveTo(w * 0.06f, h * 0.54f)
            cubicTo(w * 0.08f, h * 0.30f, w * 0.30f, h * 0.16f, w * 0.50f, h * 0.22f)
            cubicTo(w * 0.60f, h * 0.25f, w * 0.65f, h * 0.29f, w * 0.68f, h * 0.33f)
            lineTo(w * 0.74f, h * 0.20f)
            cubicTo(w * 0.79f, h * 0.12f, w * 0.86f, h * 0.09f, w * 0.89f, h * 0.14f)
            cubicTo(w * 0.92f, h * 0.19f, w * 0.88f, h * 0.30f, w * 0.82f, h * 0.40f)
            cubicTo(w * 0.86f, h * 0.46f, w * 0.84f, h * 0.55f, w * 0.77f, h * 0.57f)
            cubicTo(w * 0.72f, h * 0.58f, w * 0.68f, h * 0.55f, w * 0.65f, h * 0.51f)
            cubicTo(w * 0.57f, h * 0.66f, w * 0.40f, h * 0.78f, w * 0.24f, h * 0.74f)
            cubicTo(w * 0.12f, h * 0.71f, w * 0.06f, h * 0.64f, w * 0.06f, h * 0.54f)
            close()
        }
        drawPath(body, tint)

        // Belly: a notch in the ground colour, which is what gives the mark its
        // crescent weight instead of reading as a blob.
        val belly = Path().apply {
            moveTo(w * 0.20f, h * 0.58f)
            cubicTo(w * 0.28f, h * 0.72f, w * 0.48f, h * 0.70f, w * 0.60f, h * 0.52f)
            cubicTo(w * 0.48f, h * 0.46f, w * 0.30f, h * 0.46f, w * 0.20f, h * 0.58f)
            close()
        }
        drawPath(belly, NavyDeep.copy(alpha = if (tint == GlassWhite) 0.9f else 0f))

        // Eye.
        drawCircle(
            color = NavyDeep,
            radius = w * 0.030f,
            center = Offset(w * 0.33f, h * 0.44f),
        )
    }
}

/** Four-point sparkle for the overline badge. */
@Composable
private fun SparkleMark(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.5f, 0f)
            cubicTo(w * 0.58f, h * 0.36f, w * 0.64f, h * 0.42f, w, h * 0.5f)
            cubicTo(w * 0.64f, h * 0.58f, w * 0.58f, h * 0.64f, w * 0.5f, h)
            cubicTo(w * 0.42f, h * 0.64f, w * 0.36f, h * 0.58f, 0f, h * 0.5f)
            cubicTo(w * 0.36f, h * 0.42f, w * 0.42f, h * 0.36f, w * 0.5f, 0f)
            close()
        }
        drawPath(p, tint)
    }
}

/** A chevron-arrow, drawn so it keeps its weight at any pill size. */
@Composable
private fun ArrowGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.16f
        drawLine(
            color = tint,
            start = Offset(w * 0.10f, h * 0.50f),
            end = Offset(w * 0.86f, h * 0.50f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val head = Path().apply {
            moveTo(w * 0.58f, h * 0.20f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.58f, h * 0.80f)
        }
        drawPath(
            head,
            tint,
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}

/** A text link with no ripple — the back action is deliberately quiet. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
