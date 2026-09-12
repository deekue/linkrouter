package net.chaosengine.linkrouter.rules

/**
 * Host-scoped match types for host-rewrite rules. Matching semantics are
 * consistent with the existing matching features ([ShortenerMatcher],
 * [RedirectResolver.hostMatches]): hosts are compared in
 * [RuleEngine.normalizeHost] form (lowercased, IDN → ASCII).
 *
 * - [EXACT_HOST]: normalized hosts are equal after stripping ONE leading `www.`
 *   from both sides (www-tolerant, like `RedirectResolver.hostMatches`).
 * - [EXACT_WWW_HOST]: exact equality including any `www.` (like
 *   `RuleEngine.scoreRule`'s `EXACT_HOST` — the `www.` is significant).
 * - [SUBDOMAIN]: the URL host is a proper subdomain of `matchHost`
 *   (host endsWith "." + matchHost, host != matchHost).
 */
enum class RewriteMatchType { EXACT_HOST, EXACT_WWW_HOST, SUBDOMAIN }

/** What a matching rewrite does to the launch URL. */
enum class RewriteKind { HOST_SWAP, PATH_PREFIX_REWRITE }

/**
 * A user-managed host rewrite. When [enabled], the dispatcher rewrites the
 * host (and for [RewriteKind.PATH_PREFIX_REWRITE], the path) of the LAUNCHED
 * final URL, immediately before dispatch. Distinct from [Rule]: this never
 * affects rule matching — only the URL that gets launched.
 *
 * `matchHost`/`targetHost` are stored in [RuleEngine.normalizeHost] form.
 * [preserveHostInPath] is meaningful for [RewriteKind.PATH_PREFIX_REWRITE]
 * only: the literal incoming host is prepended to the original path
 * (`nytimes.com/blah` → `archive.md/nytimes.com/blah`).
 */
data class HostRewrite(
    val id: Long,
    val matchHost: String,
    val matchType: RewriteMatchType,
    val kind: RewriteKind,
    val targetHost: String,
    val preserveHostInPath: Boolean = false,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val isBuiltIn: Boolean = false,
)
