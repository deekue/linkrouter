package com.linkrouter.importexport

import com.linkrouter.rules.ExtractType
import com.linkrouter.rules.MatchType
import com.linkrouter.rules.OpenMode
import com.linkrouter.rules.RedirectFormat
import com.linkrouter.rules.Rule
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

/**
 * Local JSON backup/restore of rules + redirect formats (DESIGN.md section 10, M4).
 * No network involved — the string is written/read from a local file only.
 *
 * The `redirectFormats` field is additive (null-safe) so files produced before
 * the feature existed still parse (Moshi ignores unknown members, and a missing
 * field deserializes to null).
 */
object RuleSerializer {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(RuleList::class.java)

    @JsonClass(generateAdapter = true)
    data class RuleList(
        val version: Int = 1,
        val rules: List<RuleDto> = emptyList(),
        val redirectFormats: List<RedirectFormatDto>? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RuleDto(
        val pattern: String,
        val matchType: String,
        val targetPackage: String,
        val targetActivity: String? = null,
        val openMode: String,
        val enabled: Boolean = true,
    )

    @JsonClass(generateAdapter = true)
    data class RedirectFormatDto(
        val name: String,
        val pattern: String,
        val matchType: String,
        val extractType: String,
        val extractTarget: String,
        val enabled: Boolean = true,
        val priority: Int = 0,
        val isBuiltIn: Boolean = false,
        val openRealDestination: Boolean = false,
    )

    fun toJson(rules: List<Rule>): String = toJson(rules, emptyList())

    fun toJson(rules: List<Rule>, formats: List<RedirectFormat>): String {
        val list = RuleList(
            version = 1,
            rules = rules.map {
                RuleDto(
                    pattern = it.pattern,
                    matchType = it.matchType.name,
                    targetPackage = it.targetPackage,
                    targetActivity = it.targetActivity,
                    openMode = it.openMode.name,
                    enabled = it.enabled,
                )
            },
            redirectFormats = formats.map {
                RedirectFormatDto(
                    name = it.name,
                    pattern = it.pattern,
                    matchType = it.matchType.name,
                    extractType = it.extractType.name,
                    extractTarget = it.extractTarget,
                    enabled = it.enabled,
                    priority = it.priority,
                    isBuiltIn = it.isBuiltIn,
                    openRealDestination = it.openRealDestination,
                )
            },
        )
        return adapter.toJson(list)
    }

    fun fromJson(json: String): List<Rule> {
        val list = adapter.fromJson(json) ?: throw IllegalArgumentException("Empty or invalid JSON")
        return list.rules.map { dto ->
            Rule(
                id = 0,
                pattern = dto.pattern,
                matchType = MatchType.valueOf(dto.matchType),
                targetPackage = dto.targetPackage,
                targetActivity = dto.targetActivity,
                openMode = OpenMode.valueOf(dto.openMode),
                enabled = dto.enabled,
                priority = 0,
            )
        }
    }

    fun fromFormatJson(json: String): List<RedirectFormat> {
        val list = adapter.fromJson(json) ?: throw IllegalArgumentException("Empty or invalid JSON")
        val dtos = list.redirectFormats ?: emptyList()
        return dtos.map { dto ->
            RedirectFormat(
                id = 0,
                name = dto.name,
                pattern = dto.pattern,
                matchType = MatchType.valueOf(dto.matchType),
                extractType = ExtractType.valueOf(dto.extractType),
                extractTarget = dto.extractTarget,
                enabled = dto.enabled,
                priority = dto.priority,
                isBuiltIn = dto.isBuiltIn,
                openRealDestination = dto.openRealDestination,
            )
        }
    }
}
