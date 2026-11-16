package com.openminis.app.ui.onboarding

// [T-android-onboarding-v2] First-launch welcome, in the warm-paper style the
// user asked for: cream ground, terracotta starburst wordmark, a hand-drawn
// hero illustration, serif headline, and two pills at the bottom.
//
// Both buttons finish the flow. Neither performs an authentication — see the
// header on [OnboardingGateScreen] for why that is deliberate rather than an
// oversight.

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * [T-android-onboarding-v2] The completed flag.
 *
 * Its own SharedPreferences file, deliberately not the appearance prefs: those
 * are cosmetic settings a user may reset or restore from a backup, and this
 * flag must not travel with them.
 *
 * The key is versioned. `completed_v2` rather than the old `onboarding_done`:
 * the v1 flag was written by a screen that has since been removed, so any
 * device that saw it would otherwise skip the new guide and never see it.
 * Bumping the key re-asks once, for everyone, without needing a migration.
 */
object OnboardingPrefs {
    private const val FILE_NAME = "onboarding_state"
    private const val KEY_COMPLETED = "completed_v5"

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
 * [T-android-onboarding-v2] Whether to show the guide.
 *
 * Reads the flag only. It deliberately does NOT wait for the provider config
 * and does not consult the instance list: the decision has to be available
 * synchronously, before the first frame, or the guide cannot be the start
 * destination and the user sees the sessions list flash first. That also means
 * an existing user who upgrades sees the guide once — which is the intended
 * behaviour here, and why the flag is versioned.
 */
fun shouldShowOnboarding(context: Context): Boolean =
    !OnboardingPrefs.hasCompleted(context)

private val CreamBg = Color(0xFFFAF8F5)
private val InkBlack = Color(0xFF141413)
private val Terracotta = Color(0xFFD97757)
private val WarmBorder = Color(0xFFDEDCD3)
private val WarmDivider = Color(0xFFE5E3DA)
private val MutedText = Color(0xFF7D7B74)

/**
 * [T-android-onboarding-v2] The welcome screen.
 *
 * Two doors out, and neither one authenticates anything by itself:
 *  - [onSignInWithDeepSeek] sends the user to DeepSeek's own console in a
 *    browser, so a key can be obtained where keys actually come from. It is a
 *    redirect, not a login: this build has no OAuth handshake to complete.
 *  - The secondary pill reveals a key field on this same screen; submitting it
 *    calls [onSubmitApiKey], and the provider form opens with that key already
 *    in place.
 *
 * Both finish the guide on the way out — seeing the welcome screen once is the
 * whole point of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingGateScreen(
    onSignInWithDeepSeek: () -> Unit,
    onSubmitApiKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two steps on one screen, so the warm-paper look carries across instead of
    // jumping to a grey settings page for a single field.
    var enteringKey by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CreamBg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // Brand row: starburst + serif wordmark.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClaudeStarburstIcon(modifier = Modifier.size(28.dp))
            Text(
                text = "Minis",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                color = InkBlack,
                letterSpacing = (-0.5).sp,
            )
        }

        Spacer(modifier = Modifier.weight(0.85f))

        ClaudeHeroIllustration(
            modifier = Modifier.size(width = 240.dp, height = 220.dp),
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.onboarding_v2_headline),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Normal,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            color = InkBlack,
            textAlign = TextAlign.Center,
            letterSpacing = 0.3.sp,
        )

        Spacer(modifier = Modifier.weight(1.15f))

        if (enteringKey) {
            ApiKeyStep(
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                onCancel = {
                    enteringKey = false
                    apiKey = ""
                },
                onContinue = { onSubmitApiKey(apiKey) },
            )
        } else {
            WelcomeButtons(
                onSignInWithDeepSeek = onSignInWithDeepSeek,
                onEnterApiKey = { enteringKey = true },
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/**
 * A key counts as entered once it has the prefix and enough length to be a key
 * rather than a typo. There is deliberately no network check: that would spend
 * a request and a spinner on a screen whose only job is to move a string
 * somewhere else. A key that is wrong is reported by the first real request,
 * on the form that then lets it be corrected.
 */
private fun looksLikeKey(key: String): Boolean {
    val k = key.trim()
    return k.startsWith("sk-") && k.length >= 20
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeButtons(
    onSignInWithDeepSeek: () -> Unit,
    onEnterApiKey: () -> Unit,
) {
    // Primary pill: solid ink, DeepSeek mark + label.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(50),
        color = InkBlack,
        contentColor = Color.White,
        onClick = onSignInWithDeepSeek,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            DeepSeekWhaleMark(modifier = Modifier.size(20.dp), tint = Color.White)
            Spacer(modifier = Modifier.size(9.dp))
            Text(
                text = stringResource(R.string.onboarding_v2_deepseek_login),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }

    // "or" rule.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = WarmDivider,
        )
        Text(
            text = stringResource(R.string.onboarding_v2_or),
            modifier = Modifier.padding(horizontal = 14.dp),
            fontSize = 13.sp,
            color = MutedText,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = WarmDivider,
        )
    }

    // Secondary pill: outlined on warm white.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, WarmBorder),
        onClick = onEnterApiKey,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_v2_i_have_api),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MutedText,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyStep(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    val valid = looksLikeKey(apiKey)

    Text(
        text = stringResource(R.string.onboarding_v2_api_key_title),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = InkBlack,
    )
    Spacer(modifier = Modifier.height(10.dp))

    // Field: warm card, hairline border that turns terracotta once the key
    // looks complete, and the check that replaces the trailing space.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, if (valid) Terracotta else WarmBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (apiKey.isEmpty()) {
                    Text(
                        text = stringResource(R.string.onboarding_v2_api_key_placeholder),
                        fontSize = 15.sp,
                        color = MutedText,
                    )
                }
                BasicTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = InkBlack),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (valid) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.onboarding_v2_api_key_valid),
                    tint = Terracotta,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(50),
        color = if (valid) InkBlack else InkBlack.copy(alpha = 0.25f),
        contentColor = Color.White,
        enabled = valid,
        onClick = onContinue,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_v2_continue),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }

    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.onboarding_v2_back),
            fontSize = 14.sp,
            color = MutedText,
        )
    }
}

/**
 * The DeepSeek mark, as a whale silhouette.
 *
 * Drawn rather than shipped as a bitmap: it has to be tinted to whatever sits
 * behind it (white on the ink pill) and stay crisp at 20dp, and there is no
 * DeepSeek artwork in the tree to ship.
 */
@Composable
private fun DeepSeekWhaleMark(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Body: tail at the far left, back rising over the shoulder, nose at
        // the right, belly returning.
        val body = Path().apply {
            moveTo(w * 0.04f, h * 0.52f)
            cubicTo(w * 0.12f, h * 0.26f, w * 0.36f, h * 0.16f, w * 0.60f, h * 0.22f)
            cubicTo(w * 0.78f, h * 0.26f, w * 0.90f, h * 0.38f, w * 0.98f, h * 0.48f)
            cubicTo(w * 0.87f, h * 0.57f, w * 0.75f, h * 0.66f, w * 0.60f, h * 0.70f)
            cubicTo(w * 0.36f, h * 0.78f, w * 0.13f, h * 0.72f, w * 0.04f, h * 0.52f)
            close()
        }
        drawPath(body, tint)

        // Tail fin: a wedge under the tail, so it reads as a whale and not a fish.
        val fin = Path().apply {
            moveTo(w * 0.05f, h * 0.50f)
            cubicTo(w * 0.13f, h * 0.58f, w * 0.19f, h * 0.70f, w * 0.21f, h * 0.92f)
            cubicTo(w * 0.12f, h * 0.80f, w * 0.06f, h * 0.68f, w * 0.05f, h * 0.50f)
            close()
        }
        drawPath(fin, tint.copy(alpha = 0.85f))

        // Eye: punched out in the background colour so it reads on any pill.
        drawCircle(
            color = InkBlack,
            radius = w * 0.055f,
            center = Offset(w * 0.74f, h * 0.40f),
        )
    }
}
@Composable
private fun ClaudeStarburstIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.minDimension * 0.46f
        val innerR = size.minDimension * 0.10f
        val rays = 12
        for (i in 0 until rays) {
            val angle = (i * (360f / rays) - 15f) * (Math.PI / 180.0)
            val lenFactor = if (i % 2 == 0) 1.0f else 0.88f
            val sx = cx + (innerR * cos(angle)).toFloat()
            val sy = cy + (innerR * sin(angle)).toFloat()
            val ex = cx + (outerR * lenFactor * cos(angle)).toFloat()
            val ey = cy + (outerR * lenFactor * sin(angle)).toFloat()
            drawLine(
                color = Terracotta,
                start = Offset(sx, sy),
                end = Offset(ex, ey),
                strokeWidth = size.minDimension * 0.095f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The hero: a terracotta node-link constellation behind a single continuous
 * black line drawing. Drawn rather than shipped as a bitmap so it scales and
 * recolours with the theme.
 */
@Composable
private fun ClaudeHeroIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val hub = Offset(w * 0.44f, h * 0.48f)
        val nTopLeft = Offset(w * 0.25f, h * 0.19f)
        val nTopMid = Offset(w * 0.36f, h * 0.10f)
        val nMidRight = Offset(w * 0.56f, h * 0.31f)
        val nFarRight = Offset(w * 0.72f, h * 0.32f)
        val nBotLeft = Offset(w * 0.34f, h * 0.50f)

        val linkStroke = w * 0.024f
        drawLine(Terracotta, nTopLeft, hub, strokeWidth = linkStroke, cap = StrokeCap.Round)
        drawLine(Terracotta, nTopMid, hub, strokeWidth = linkStroke, cap = StrokeCap.Round)
        drawLine(Terracotta, nMidRight, hub, strokeWidth = linkStroke, cap = StrokeCap.Round)
        drawLine(Terracotta, nFarRight, hub, strokeWidth = linkStroke, cap = StrokeCap.Round)
        drawLine(Terracotta, nBotLeft, nFarRight, strokeWidth = linkStroke, cap = StrokeCap.Round)
        drawLine(Terracotta, nBotLeft, nMidRight, strokeWidth = linkStroke * 0.9f, cap = StrokeCap.Round)

        drawCircle(Terracotta, radius = w * 0.048f, center = nTopLeft)
        drawCircle(Terracotta, radius = w * 0.044f, center = nTopMid)
        drawCircle(Terracotta, radius = w * 0.044f, center = nMidRight)
        drawCircle(Terracotta, radius = w * 0.048f, center = nFarRight)
        drawCircle(Terracotta, radius = w * 0.052f, center = nBotLeft)
        drawCircle(Terracotta, radius = w * 0.068f, center = hub)

        val path = Path().apply {
            // Four finger loops.
            moveTo(w * 0.25f, h * 0.67f)
            cubicTo(w * 0.26f, h * 0.52f, w * 0.29f, h * 0.47f, w * 0.30f, h * 0.50f)
            cubicTo(w * 0.31f, h * 0.53f, w * 0.28f, h * 0.64f, w * 0.29f, h * 0.65f)
            cubicTo(w * 0.30f, h * 0.66f, w * 0.32f, h * 0.46f, w * 0.34f, h * 0.47f)
            cubicTo(w * 0.35f, h * 0.48f, w * 0.32f, h * 0.62f, w * 0.33f, h * 0.63f)
            cubicTo(w * 0.34f, h * 0.64f, w * 0.36f, h * 0.45f, w * 0.38f, h * 0.46f)
            cubicTo(w * 0.39f, h * 0.47f, w * 0.37f, h * 0.60f, w * 0.38f, h * 0.61f)
            cubicTo(w * 0.39f, h * 0.62f, w * 0.40f, h * 0.46f, w * 0.42f, h * 0.47f)
            // Dip, then the deep right loop.
            cubicTo(w * 0.43f, h * 0.48f, w * 0.40f, h * 0.68f, w * 0.44f, h * 0.68f)
            cubicTo(w * 0.47f, h * 0.68f, w * 0.46f, h * 0.48f, w * 0.50f, h * 0.50f)
            cubicTo(w * 0.53f, h * 0.52f, w * 0.46f, h * 0.87f, w * 0.57f, h * 0.88f)
            cubicTo(w * 0.67f, h * 0.89f, w * 0.72f, h * 0.74f, w * 0.69f, h * 0.64f)
            // Upper profile rising to the top right.
            cubicTo(w * 0.66f, h * 0.54f, w * 0.58f, h * 0.55f, w * 0.58f, h * 0.46f)
            cubicTo(w * 0.58f, h * 0.39f, w * 0.61f, h * 0.36f, w * 0.59f, h * 0.27f)
            cubicTo(w * 0.57f, h * 0.19f, w * 0.61f, h * 0.09f, w * 0.69f, h * 0.06f)
        }

        drawPath(
            path = path,
            color = InkBlack,
            style = Stroke(
                width = w * 0.030f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
