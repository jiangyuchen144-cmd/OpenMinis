package com.openminis.minimal.ui

// [T-minimal] The welcome screen, and the only place a key is entered.
//
// The screen has two states rather than two screens: "API 登录" reveals the field
// in place, and the pill underneath it becomes the confirm control. A second
// full-page surface for one text field was the previous design and it was worse
// in every way that matters — it lost the background, it added a back gesture,
// and it made the user feel they had navigated away from the thing they were
// doing.
//
// The key is VERIFIED, not shape-checked. "Starts with sk- and is long enough"
// answers a different question from "does DeepSeek accept this": a well-formed
// string the vendor rejects would walk the user into an app that cannot answer.
// One GET to /models, debounced, runs while they are still looking at the field.

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.minimal.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val CHECK_HOLD_MS = 700L

private enum class KeyState { Idle, Checking, Ok, Bad }

/**
 * Asks the vendor whether the key works.
 *
 * Returns true only for a successful, non-empty model list. Every other outcome
 * — timeout, HTTP error, malformed body, no network — is a false, with no attempt
 * to guess which: a wrong key is what the user needs to hear about, and "we could
 * not tell" is not something this screen can act on.
 */
private suspend fun verifyKey(key: String): Boolean {
    if (key.length < 8) return false
    return withTimeoutOrNull(15_000) {
        runCatching { com.openminis.minimal.data.KeyCheck.verify(key) }.getOrDefault(false)
    } ?: false
}

@Composable
fun WelcomeScreen(
    onVerified: (String) -> Unit,
) {
    var apiOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var keyState by remember { mutableStateOf(KeyState.Idle) }
    var showArrow by remember { mutableStateOf(false) }

    // Debounced: one request per pause in typing, and a new keystroke restarts
    // this effect, which cancels the in-flight one.
    LaunchedEffect(apiKey, apiOpen) {
        if (!apiOpen) return@LaunchedEffect
        showArrow = false
        if (apiKey.isBlank()) {
            keyState = KeyState.Idle
            return@LaunchedEffect
        }
        keyState = KeyState.Checking
        delay(450)
        val ok = verifyKey(apiKey.trim())
        keyState = if (ok) KeyState.Ok else KeyState.Bad
        if (ok) {
            delay(CHECK_HOLD_MS)
            showArrow = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(NavyDeep)) {
        FlowingNavyBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SparkleMark(Modifier.size(13.dp), HintBlue)
                Text(
                    text = stringResource(R.string.ob_badge),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = HintBlue,
                    letterSpacing = 0.4.sp,
                )
            }

            Spacer(Modifier.weight(0.9f))

            WhaleMark(Modifier.size(88.dp), Color.White)

            Spacer(Modifier.height(26.dp))

            Text(
                text = stringResource(R.string.ob_title_1),
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )
            Text(
                text = stringResource(R.string.ob_title_2),
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.ob_subtitle),
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
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
                    onConfirm = { onVerified(apiKey.trim()) },
                )
            } else {
                // One door. The previous layout had a "DeepSeek 登录" pill that
                // opened a browser — a redirect pretending to be a login. With
                // no OAuth to complete, the honest screen offers the key.
                FlowGradientPill(onClick = { apiOpen = true }, height = 56.dp) {
                    WhaleMark(Modifier.size(20.dp), Color.White)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = stringResource(R.string.ob_api_login),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

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

    Text(
        text = stringResource(R.string.ob_key_title),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White,
    )
    Spacer(Modifier.height(10.dp))

    Surface(
        modifier = Modifier.fillMaxWidth().height(54.dp),
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
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (apiKey.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ob_key_placeholder),
                        fontSize = 15.sp,
                        color = HintBlue.copy(alpha = 0.65f),
                    )
                }
                BasicTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Color.White),
                    cursorBrush = Brush.verticalGradient(listOf(WhaleBlue, WhaleBlue)),
                    // KeyboardType only picks the keyboard; it masks nothing.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when (keyState) {
                KeyState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.8.dp,
                    color = WhaleBlue,
                )
                KeyState.Ok -> Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = OkGreen,
                    modifier = Modifier.size(18.dp),
                )
                KeyState.Bad -> Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(16.dp),
                )
                KeyState.Idle -> Unit
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    val verdict = when (keyState) {
        KeyState.Checking -> stringResource(R.string.ob_key_checking)
        KeyState.Bad -> stringResource(R.string.ob_key_invalid)
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

    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(50),
        color = if (verified) OkGreen else Color.White.copy(alpha = 0.12f),
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
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                        "arrow" -> ArrowGlyph(Modifier.size(30.dp), Color.White)
                        else -> Text(
                            text = stringResource(R.string.ob_continue),
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
        text = stringResource(R.string.ob_back),
        modifier = Modifier
            .padding(top = 10.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        fontSize = 14.sp,
        color = HintBlue,
    )
}

/** The ground: washes that drift, so the surface reads as moving rather than tinted. */
@Composable
private fun FlowingNavyBackdrop() {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(NavyDeep, NavyMid, NavyHigh, NavyMid),
                start = Offset(-size.width * 0.3f + size.width * 0.6f * t, 0f),
                end = Offset(size.width * 1.1f + size.width * 0.6f * t, size.height),
            ),
        )
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

@Composable
private fun FlowGradientPill(
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "pill")
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
        modifier = Modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(
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

/** The DeepSeek mark, as a whale in profile, drawn so it tints and scales. */
@Composable
private fun WhaleMark(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
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
        val belly = Path().apply {
            moveTo(w * 0.20f, h * 0.58f)
            cubicTo(w * 0.28f, h * 0.72f, w * 0.48f, h * 0.70f, w * 0.60f, h * 0.52f)
            cubicTo(w * 0.48f, h * 0.46f, w * 0.30f, h * 0.46f, w * 0.20f, h * 0.58f)
            close()
        }
        drawPath(belly, NavyDeep)
        drawCircle(NavyDeep, radius = w * 0.030f, center = Offset(w * 0.33f, h * 0.44f))
    }
}

@Composable
private fun SparkleMark(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
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

@Composable
private fun ArrowGlyph(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.16f
        drawLine(
            color = tint,
            start = Offset(w * 0.10f, h * 0.50f),
            end = Offset(w * 0.86f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val head = Path().apply {
            moveTo(w * 0.58f, h * 0.20f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.58f, h * 0.80f)
        }
        drawPath(
            head,
            tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
