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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    private const val KEY_COMPLETED = "completed_v3"

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
 * Both pills call [onFinish]. Neither authenticates anything: this build has no
 * Google sign-in and no email sign-up, and the guide's only job is to hand the
 * user to the sessions list, which already carries the real setup steps
 * (add a provider → pick a model → start talking) and the restore-from-backup
 * entry. The labels are kept because the user asked for this layout
 * specifically — but if they ever read as a promise the app does not keep,
 * changing the two strings is the whole fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingGateScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        // Primary pill: solid ink.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(50),
            color = InkBlack,
            contentColor = Color.White,
            onClick = onFinish,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                GoogleGIcon(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.onboarding_v2_continue_google),
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
            onClick = onFinish,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_v2_enter_email),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = MutedText,
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
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

@Composable
private fun GoogleGIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.20f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = Color(0xFFEA4335),
            startAngle = -145f,
            sweepAngle = 105f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 145f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -15f,
            sweepAngle = 65f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.52f, size.height * 0.50f),
            end = Offset(size.width * 0.94f, size.height * 0.50f),
            strokeWidth = stroke * 0.95f,
            cap = StrokeCap.Butt,
        )
    }
}
