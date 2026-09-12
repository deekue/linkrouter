package net.chaosengine.linkrouter.rules

/**
 * Validation for host-rewrite rules, before they are stored. Pure JVM —
 * unit-testable, and mirrors the shape of [RuleValidator.Result] /
 * [RedirectFormatValidator].
 *
 * The rewriter is defensive (D6) and will never crash on a malformed rule, so
 * validation is here to give the UI editor a clear reason *up front* rather
 * than storing a rule that can never do anything. Blocking issues (Invalid)
 * are structural emptiness; anything recoverable is a warning carried on
 * [Result.Valid].
 */
object HostRewriteValidator {

    sealed class Result {
        abstract val warnings: List<String>
        data class Valid(
            val normalizedMatchHost: String,
            val normalizedTargetHost: String,
            override val warnings: List<String> = emptyList(),
        ) : Result()
        data class Invalid(
            val reason: String,
            override val warnings: List<String> = emptyList(),
        ) : Result()
    }

    /**
     * Validate a rule's fields. Returns [Result.Invalid] for a structurally
     * unusable rule (empty/blank host, or a host with no usable form) and
     * [Result.Valid] (with optional [Result.Valid.warnings]) otherwise.
     *
     * Warnings (not blocks), per the plan, for the loop-generating case:
     * `PATH_PREFIX_REWRITE` whose normalized target base equals the match base
     * would produce `host/host/...` on re-tap — and `SUBDOMAIN` match scopes,
     * which always capture every proper subdomain (and so possibly unrelated
     * sites). Warnings never block: the rule still stores and rewrites.
     */
    fun validate(
        matchHost: String,
        matchType: RewriteMatchType,
        kind: RewriteKind,
        targetHost: String,
        preserveHostInPath: Boolean,
    ): Result {
        val m = RuleEngine.normalizeHost(matchHost)
        val t = RuleEngine.normalizeHost(targetHost)
        if (m.isEmpty()) return Result.Invalid("Match host is empty")
        if (t.isEmpty()) return Result.Invalid("Target host is empty")

        val warnings = mutableListOf<String>()

        if (kind == RewriteKind.PATH_PREFIX_REWRITE) {
            // Loop-generating: target base == match base would re-prefix on
            // re-tap. Single-pass means it is not re-applied, but warn.
            if (stripWww(t) == stripWww(m)) {
                warnings += "Target equals the match host — this rule may produce $t/$m/... paths."
            }
            if (!preserveHostInPath) {
                warnings += "PATH_PREFIX_REWRITE without preserveHostInPath will not prefix the original host."
            }
        }

        if (matchType == RewriteMatchType.SUBDOMAIN) {
            // SUBDOMAIN scopes are broad by nature (they match every proper
            // subdomain of the base), so always flag them — the user may not
            // have intended to capture unrelated sibling sites.
            warnings += "SUBDOMAIN matching captures every subdomain of $m and may reach unrelated sites."
        }

        return Result.Valid(m, t, warnings)
    }

    // Exposed for the validator's base-host comparison; kept simple/delegated.
    private fun stripWww(host: String): String {
        val h = host.lowercase()
        return if (h.startsWith("www.")) h.substring(4) else h
    }
}
