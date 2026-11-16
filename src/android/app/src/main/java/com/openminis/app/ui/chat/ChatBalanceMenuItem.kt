package com.openminis.app.ui.chat

// [T-android-balance-menu] "API balance + top up" row for the chat ⋮ menu.
//
// Balance rendering is NOT implemented here: the project already has
// QuotaSectionHeaderBadge, which resolves the instance's headline pool and
// formats it. This file only adds what was missing — the top-up target and the
// menu row that hosts both.

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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
 * simply shows no top-up button.
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
     *   host means no top-up button, which is the honest answer.
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
 * [T-android-balance-menu] One menu row: icon, balance, and a "top up" pill.
 *
 * @param balanceContent the balance itself — the caller passes
 *   [com.openminis.app.ui.quota.QuotaSectionHeaderBadge] or an em dash. Kept as
 *   a slot so this file needs no knowledge of the quota model.
 * @param refreshing shows a small spinner beside the balance.
 * @param topUpText null when the provider has no known top-up page, which is
 *   also how the button is hidden.
 * @param onRefresh tapping the row; the menu stays open so the spinner is
 *   visible. Implementations should force a refresh.
 * @param onTopUp tapping the pill; the caller closes the menu and opens the URL.
 */
@Composable
fun ChatBalanceMenuItem(
    a11yLabel: String,
    refreshing: Boolean,
    topUpText: String?,
    onRefresh: () -> Unit,
    onTopUp: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    balanceContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trailing: (@Composable () -> Unit)? = if (topUpText != null) {
        @Composable {
            Text(
                text = topUpText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    // Own clickable, so the pill does not also trigger the row's
                    // refresh — the innermost clickable consumes the tap.
                    .clickable(role = Role.Button, onClick = onTopUp)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    } else {
        null
    }

    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
                balanceContent()
            }
        },
        onClick = onRefresh,
        leadingIcon = leadingIcon,
        trailingIcon = trailing,
        modifier = modifier.semantics { contentDescription = a11yLabel },
    )
}
