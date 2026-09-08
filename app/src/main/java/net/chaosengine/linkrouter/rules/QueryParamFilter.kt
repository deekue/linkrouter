package net.chaosengine.linkrouter.rules

/**
 * A user-managed query-param filter. When [enabled], the dispatcher strips the
 * [param] query key from the LAUNCHED final URL (global when [host] is null,
 * otherwise scoped to [host] and its subdomains). Distinct from [Rule]: this
 * never affects rule matching — only the URL that gets launched.
 */
data class QueryParamFilter(
    val id: Long,
    val name: String,
    val host: String?,      // null = global; else lowercase host (e.g. "tiktok.com")
    val param: String,      // query key, e.g. "utm_source" (lowercase)
    val enabled: Boolean = false,
    val priority: Int = 0,
    val isBuiltIn: Boolean = false,
)
