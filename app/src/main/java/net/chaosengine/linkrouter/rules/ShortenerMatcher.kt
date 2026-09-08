package net.chaosengine.linkrouter.rules

/**
 * Pure-JVM matcher for [ShortenerHost] rows against an incoming URL's host/path
 * (as produced by `RuleEngine.normalize`: host lowercased, path starting with
 * "/"). A row with `pathPrefix == null` matches any path on the domain
 * (host-only, back-compat); a non-null prefix requires the path to start with
 * it, case-insensitively (RuleEngine lowercases only the host, so the path
 * keeps its case and must be compared with `ignoreCase`).
 */
object ShortenerMatcher {

    /** True if [incoming] equals or is a subdomain of [stored] (both with one
     *  leading "www." stripped, lowercased). */
    fun domainMatches(incoming: String, stored: String): Boolean {
        val base = stored.lowercase().trim().removePrefix("www.")
        if (base.isEmpty()) return false
        val inHost = incoming.lowercase().trim().removePrefix("www.")
        return inHost == base || inHost.endsWith(".$base")
    }

    /** True if [host]/[path] (from RuleEngine.normalize of the incoming URL)
     *  matches the row: domain per [domainMatches]; if the row has a prefix,
     *  path must start with it (case-insensitive, leading "/" normalized). */
    fun matches(host: String, path: String, s: ShortenerHost): Boolean {
        if (!domainMatches(host, s.host)) return false
        val p = s.pathPrefix?.trim()?.lowercase() ?: return true
        val prefix = if (p.startsWith("/")) p else "/$p"
        return path.startsWith(prefix, ignoreCase = true)
    }
}
