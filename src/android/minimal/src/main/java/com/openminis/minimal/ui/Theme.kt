package com.openminis.minimal.ui

// [T-minimal] Two grounds, one palette.
//
// The welcome screen is navy (DeepSeek Harness's own language); the conversation
// is warm off-white. That is not an inconsistency — the welcome screen is a
// product's front door and the conversation is a working surface, and the
// transition between them is the only moment the app changes colour.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Welcome (navy) ──────────────────────────────────────────────────────────
val NavyDeep = Color(0xFF0B1526)
val NavyMid = Color(0xFF152A4A)
val NavyHigh = Color(0xFF1E4270)
val HintBlue = Color(0xFF9FB6D4)
val WhaleBlue = Color(0xFF4A9EFF)

// ── Conversation (warm paper) ───────────────────────────────────────────────
val Paper = Color(0xFFFAF8F5)
val Ink = Color(0xFF141413)
val InkMuted = Color(0xFF7D7B74)
val Hairline = Color(0xFFE5E3DA)
val Terracotta = Color(0xFFD97757)
val OkGreen = Color(0xFF2FBF71)
val ErrorRed = Color(0xFFE0674F)

private val scheme = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF2EFE9),
    onSurfaceVariant = InkMuted,
    error = ErrorRed,
)

private val type = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
)

@Composable
fun MinimalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = type,
        content = content,
    )
}
