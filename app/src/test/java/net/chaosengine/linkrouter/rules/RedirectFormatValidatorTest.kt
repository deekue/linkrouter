package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectFormatValidatorTest {

    private fun fmt(
        name: String = "F",
        pattern: String = "r.link",
        extractType: ExtractType = ExtractType.QUERY_PARAM,
        extractTarget: String = "q",
    ) = RedirectFormat(
        id = 0,
        name = name,
        pattern = pattern,
        matchType = MatchType.EXACT_HOST,
        extractType = extractType,
        extractTarget = extractTarget,
        enabled = true,
    )

    @Test
    fun validate_valid_google() {
        assertTrue(
            RedirectFormatValidator.validate(RedirectFormat.BUILT_IN_GOOGLE)
                is RedirectFormatValidator.Result.Valid,
        )
    }

    @Test
    fun validate_empty_name_invalid() {
        assertTrue(
            RedirectFormatValidator.validate(fmt(name = "")) is RedirectFormatValidator.Result.Invalid,
        )
    }

    @Test
    fun validate_empty_pattern_invalid() {
        assertTrue(
            RedirectFormatValidator.validate(fmt(pattern = "")) is RedirectFormatValidator.Result.Invalid,
        )
    }

    @Test
    fun validate_pattern_without_host_invalid() {
        assertTrue(
            RedirectFormatValidator.validate(fmt(pattern = "/only/path"))
                is RedirectFormatValidator.Result.Invalid,
        )
    }

    @Test
    fun validate_query_param_bad_name_invalid() {
        assertTrue(
            RedirectFormatValidator.validate(
                fmt(extractType = ExtractType.QUERY_PARAM, extractTarget = "bad name"),
            ) is RedirectFormatValidator.Result.Invalid,
        )
    }

    @Test
    fun validate_regex_bad_target_invalid() {
        assertTrue(
            RedirectFormatValidator.validate(
                fmt(extractType = ExtractType.PATH_REGEX, extractTarget = "(unclosed"),
            ) is RedirectFormatValidator.Result.Invalid,
        )
    }

    @Test
    fun validate_regex_good_target_valid() {
        assertTrue(
            RedirectFormatValidator.validate(
                fmt(extractType = ExtractType.PATH_REGEX, extractTarget = "/go/(.*)"),
            ) is RedirectFormatValidator.Result.Valid,
        )
    }

    @Test
    fun sampleWrapper_query_param_contains_encoded_dest() {
        val sw = RedirectFormatValidator.sampleWrapper(
            fmt(extractType = ExtractType.QUERY_PARAM, extractTarget = "q"),
        )
        assertTrue("expected ?q= in: $sw", sw.contains("?q="))
        assertTrue("expected url-encoded dest in: $sw", sw.contains("https%3A%2F%2Fexample.com%2F"))
    }

    @Test
    fun sampleWrapper_base64_contains_base64() {
        val dest = "https://example.com/"
        val b64 = java.util.Base64.getEncoder().encodeToString(dest.toByteArray())
        val sw = RedirectFormatValidator.sampleWrapper(
            fmt(extractType = ExtractType.BASE64_PARAM, extractTarget = "d"),
        )
        assertTrue("expected base64 of the sample dest in: $sw", sw.contains(b64))
    }

    @Test
    fun preview_google_returns_destination() {
        assertEquals(
            "https://example.com/",
            RedirectFormatValidator.preview(RedirectFormat.BUILT_IN_GOOGLE),
        )
    }
}
