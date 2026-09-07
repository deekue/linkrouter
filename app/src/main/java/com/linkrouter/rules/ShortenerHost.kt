package com.linkrouter.rules

/**
 * A user-managed URL-shortener host. When [enabled], the dispatcher follows
 * the host's redirects (pure-JVM fast path) to the final URL and re-runs the
 * rule engine on it. Distinct from [RedirectFormat]: this is a network host
 * flag, not a string-extraction wrapper.
 */
data class ShortenerHost(
    val id: Long,
    val name: String,
    val host: String,          // lowercase host, no scheme (e.g. "t.co")
    val enabled: Boolean = false,
    val priority: Int = 0,
    val isBuiltIn: Boolean = false,
)
