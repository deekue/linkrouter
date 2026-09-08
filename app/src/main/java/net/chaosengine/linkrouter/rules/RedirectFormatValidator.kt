package net.chaosengine.linkrouter.rules

import java.net.URLEncoder
import java.util.regex.PatternSyntaxException

/**
 * Redirect-format validation + a live wrapper -> destination preview
 * (generalizes [RuleValidator]). Pure JVM — unit-testable.
 */
object RedirectFormatValidator {

    sealed class Result {
        data class Valid(val normalized: RedirectFormat) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private val PARAM_NAME = Regex("^[A-Za-z0-9_.-]+$")

    /** Validates a format; rejects empty fields, bad param names, and bad regexes. */
    fun validate(fmt: RedirectFormat): Result {
        if (fmt.name.isBlank()) return Result.Invalid("Name is empty")
        val p = fmt.pattern.trim()
        if (p.isEmpty()) return Result.Invalid("Pattern is empty")

        // The pattern must carry a host for its match type to be meaningful.
        val host = (if (p.startsWith("*.")) p.substring(2) else p).substringBefore('/')
        if (host.isEmpty()) return Result.Invalid("Pattern must include a host")

        val target = fmt.extractTarget.trim()
        if (target.isEmpty()) return Result.Invalid("Extraction target is empty")
        when (fmt.extractType) {
            ExtractType.QUERY_PARAM, ExtractType.BASE64_PARAM -> {
                if (!PARAM_NAME.matches(target)) {
                    return Result.Invalid("Invalid parameter name: $target")
                }
            }
            ExtractType.PATH_REGEX, ExtractType.FULL_URL_REGEX -> {
                try {
                    java.util.regex.Pattern.compile(target)
                } catch (e: PatternSyntaxException) {
                    return Result.Invalid("Invalid regex: ${e.description}")
                }
            }
        }

        return Result.Valid(fmt.copy(name = fmt.name.trim(), pattern = p, extractTarget = target))
    }

    /**
     * A representative example wrapper URL for THIS format, so the editor's
     * live preview is meaningful. Mirrors [RuleValidator.sampleUrls] in spirit.
     */
    fun sampleWrapper(fmt: RedirectFormat): String {
        val p = fmt.pattern.trim()
        if (p.isEmpty()) return ""
        val host = (if (p.startsWith("*.")) p.substring(2) else p).substringBefore('/')
        val baseHost = RuleEngine.normalizeHost(host.ifEmpty { "example.com" })
        val path = if ('/' in p) p.substring(p.indexOf('/')) else ""
        val target = fmt.extractTarget.trim().ifEmpty { "q" }
        val dest = "https://example.com/"
        return when (fmt.extractType) {
            ExtractType.QUERY_PARAM ->
                "https://$baseHost$path?$target=${urlEncode(dest)}"
            ExtractType.BASE64_PARAM ->
                "https://$baseHost$path?$target=${base64(dest)}"
            ExtractType.PATH_REGEX ->
                "https://$baseHost$path"
            ExtractType.FULL_URL_REGEX ->
                "https://$baseHost$path"
        }
    }

    /**
     * Live preview: resolve a single format against its own sample wrapper and
     * return the destination, or null if it did not resolve. Mirrors
     * [RuleValidator.previewMatch].
     */
    fun preview(fmt: RedirectFormat): String? =
        RedirectResolver.resolve(sampleWrapper(fmt), listOf(fmt))

    private fun urlEncode(s: String): String =
        try {
            URLEncoder.encode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

    private fun base64(s: String): String =
        try {
            java.util.Base64.getEncoder().encodeToString(s.toByteArray())
        } catch (e: Exception) {
            s
        }
}
