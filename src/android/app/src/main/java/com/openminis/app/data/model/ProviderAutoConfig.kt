package com.openminis.app.data.model

/**
 * [T-android-provider-auto-config] What the provider detail screen no longer
 * asks for.
 *
 * The screen was cut down to four things a person actually has to decide —
 * label, API key, balance, models. Everything else it used to offer (custom
 * base URL, append-/v1, user agent, API format, idle timeout, Azure mode,
 * image endpoint, enabled switch, thinking rules) is now decided here or left
 * at the value the instance was created with.
 *
 * The one case that genuinely needs a decision at creation time is the
 * endpoint: a user adding "DeepSeek" through the OpenAI-compatible flow gets
 * `https://api.openai.com` unless they type something, which is wrong for the
 * key they are about to paste. Matching on the label fixes that without
 * showing a URL field.
 *
 * Deliberately narrow: it fills a base URL that is BLANK, and never rewrites
 * one that is set. An instance already pointing at a relay keeps pointing at
 * it — the whole point of "auto" is to save typing, not to overrule a choice
 * that was made explicitly.
 */
object ProviderAutoConfig {

    /**
     * Label substring → console/API root. Ordered: the first match wins, so
     * more specific names come before the vendor they sit inside.
     */
    private val LABEL_HOSTS: List<Pair<List<String>, String>> = listOf(
        listOf("deepseek", "深度求索") to "https://api.deepseek.com",
        listOf("moonshot", "kimi") to "https://api.moonshot.cn/v1",
        listOf("zhipu", "bigmodel", "智谱") to "https://open.bigmodel.cn/api/paas/v4",
        listOf("siliconflow", "硅基") to "https://api.siliconflow.cn/v1",
        listOf("openrouter") to "https://openrouter.ai/api/v1",
        listOf("groq") to "https://api.groq.com/openai/v1",
        listOf("together") to "https://api.together.xyz/v1",
        listOf("mistral") to "https://api.mistral.ai/v1",
        listOf("xai", "grok") to "https://api.x.ai/v1",
        listOf("volces", "volcengine", "火山", "豆包", "doubao") to "https://ark.cn-beijing.volces.com/api/v3",
        listOf("dashscope", "qwen", "通义", "千问") to "https://dashscope.aliyuncs.com/compatible-mode/v1",
    )

    /** The base URL a label implies, or null when the label names no known vendor. */
    fun baseUrlForLabel(label: String?): String? {
        val l = label?.lowercase().orEmpty()
        if (l.isBlank()) return null
        return LABEL_HOSTS.firstOrNull { (names, _) -> names.any { l.contains(it) } }?.second
    }

    /**
     * Returns the instance to persist. Only `customBaseURL` can change.
     */
    fun apply(instance: ProviderInstance): ProviderInstance {
        if (instance.providerType != ProviderType.openAI) return instance
        if (!instance.customBaseURL.isNullOrBlank()) return instance
        val derived = baseUrlForLabel(instance.label) ?: return instance
        return instance.copy(customBaseURL = derived)
    }
}
