package com.openminis.app.ui.chat

// [T-android-balance-menu] "API balance + top up" row for the chat ⋮ menu.
//
// [T-android-menu-radical] This is now the menu's ONLY row, and the row itself
// is one line of small text: `余额 ¥1.50 │ 充值`. No icon, no filled pill, no
// 48dp hit target, no full-width stretch. The balance figure is still produced
// by the project's own quota model — this file only decides how it looks and
// where the top-up link points.

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [T-android-balance-menu] Which provider a top-up link belongs to.
 *
 * Matching is by `customBaseURL` host FIRST, and only falls back to the label.
 * It cannot be done by provider type: a custom OpenAI-compatible endpoint is
 * stored as `ProviderType.openAI` with a `customBaseURL`, so DeepSeek and Zhipu
 * both report type `openAI` — matching on the type would send a DeepSeek user
 * to OpenAI's billing page. Verified against real instances:
 *
 *     DeepSeek → openAI   https://api.deepseek.com
 *     Zhipu    → openAI   https://open.bigmodel.cn/api/paas/v4
 *     Kimi     → kimiCode https://api.moonshot.cn/v1
 *
 * Hosts are checked before labels because the label is user-editable and
 * localized, while the host is what actually routes the request.
 *
 * URLs checked 2026-11-16 (HTTP 200 unless noted). A URL that could not be
 * confirmed is deliberately absent rather than guessed — an unknown provider
 * simply shows no top-up link.
 */
internal object ProviderTopUp {

    private class Entry(
        val hosts: List<String>,
        val labels: List<String>,
        val url: String,
    )

    private val entries = listOf(
        // platform.deepseek.com/top_up — verified 200.
        Entry(listOf("api.deepseek.com"), listOf("deepseek"), "https://platform.deepseek.com/top_up"),
        // platform.moonshot.cn/console/pay — verified 200.
        Entry(listOf("api.moonshot.cn"), listOf("moonshot"), "https://platform.moonshot.cn/console/pay"),
        // platform.moonshot.ai/console/pay — NOT verified; omitted until it is.
        // open.bigmodel.cn/finance-center/finance/overview — verified 200.
        Entry(
            listOf("open.bigmodel.cn"),
            listOf("zhipu", "bigmodel", "智谱"),
            "https://open.bigmodel.cn/finance-center/finance/overview",
        ),
        // The old console.anthropic.com/settings/billing 301-redirects here, so
        // link straight at the destination. Verified 200.
        Entry(
            listOf("api.anthropic.com"),
            listOf("anthropic", "claude"),
            "https://platform.claude.com/settings/billing",
        ),
        // console.cloud.google.com/billing — verified 200. Gemini API billing
        // lives under Cloud Billing, which is the closest stable target.
        Entry(
            listOf("generativelanguage.googleapis.com"),
            listOf("gemini", "google"),
            "https://console.cloud.google.com/billing",
        ),
        // NOT verified: curl gets a Cloudflare 403, so a browser was never
        // confirmed to land on it. Left out rather than shipped unverified.
        // Entry(listOf("api.openai.com"), listOf("openai"),
        //       "https://platform.openai.com/settings/organization/billing/overview"),
    )

    private fun byHost(baseUrl: String?): String? {
        val h = baseUrl?.lowercase().orEmpty()
        if (h.isEmpty()) return null
        return entries.firstOrNull { e -> e.hosts.any { h.contains(it) } }?.url
    }

    private fun byLabel(label: String?): String? {
        val l = label?.lowercase().orEmpty()
        if (l.isEmpty()) return null
        return entries.firstOrNull { e -> e.labels.any { l.contains(it) } }?.url
    }

    /**
     * @param baseUrl the instance's `customBaseURL`. When it is set, the label
     *   is NOT consulted: a relay or proxy whose label happens to contain
     *   "DeepSeek" holds its balance somewhere else, so falling back to the
     *   label would send the user to the wrong billing page. Unknown custom
     *   host means no top-up link, which is the honest answer.
     */
    fun urlFor(baseUrl: String?, label: String?): String? =
        if (baseUrl.isNullOrBlank()) byLabel(label) else byHost(baseUrl)

}

/**
 * [T-android-balance-menu] Open [url] in whatever handles VIEW. Returns false
 * when nothing can handle it, so the caller can tell the user instead of
 * failing silently.
 */
internal fun openExternalUrl(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * [T-android-menu-radical] The balance row, reduced to one line.
 *
 * Layout is `balance │ top up`, 13sp, on the menu's own background. Everything
 * that used to make it heavy is gone: the wallet icon, the tinted capsule
 * behind the figure, the filled button with its 12dp radius, the implied 48dp
 * minimum height inherited from DropdownMenuItem, and the full-width stretch.
 *
 * Tapping the figure forces a refresh (the menu stays open so the spinner is
 * visible); tapping the link closes the menu and opens the provider's billing
 * page. They are separate clickables, so the inner one consumes the tap.
 *
 * @param balanceText already-formatted figure, e.g. "余额 ¥1.50". The caller
 *   resolves it through the quota model; an em dash means "unknown".
 * @param topUpText null when the provider has no known billing page, which is
 *   also how the link and its separator are hidden.
 */
@Composable
fun ChatBalanceMenuItem(
    a11yLabel: String,
    refreshing: Boolean,
    balanceText: String,
    topUpText: String?,
    onRefresh: () -> Unit,
    onTopUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .semantics { contentDescription = a11yLabel }
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onRefresh)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
            )
        }
        Text(
            text = balanceText,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Tabular figures: the amount keeps its width while it refreshes,
            // so the row does not twitch between "¥1.50" and "¥9.80".
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        )
        if (topUpText != null) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(10.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            )
            Text(
                text = topUpText,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button, onClick = onTopUp),
            )
        }
    }
}
