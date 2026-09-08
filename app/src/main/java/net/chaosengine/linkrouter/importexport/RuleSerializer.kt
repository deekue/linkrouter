package net.chaosengine.linkrouter.importexport

import net.chaosengine.linkrouter.rules.ExtractType
import net.chaosengine.linkrouter.rules.MatchType
import net.chaosengine.linkrouter.rules.OpenMode
import net.chaosengine.linkrouter.rules.RedirectFormat
import net.chaosengine.linkrouter.rules.QueryParamFilter
import net.chaosengine.linkrouter.rules.Rule
import net.chaosengine.linkrouter.rules.ShortenerHost
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
        val queryParamFilters: List<QueryParamFilterDto>? = null,
        val shortenerHosts: List<ShortenerHostDto>? = null,
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

    @JsonClass(generateAdapter = true)
    data class QueryParamFilterDto(
        val param: String,
        val host: String? = null,
        val enabled: Boolean = true,
        val builtIn: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class ShortenerHostDto(
        val name: String,
        val host: String,
        val pathPrefix: String? = null,
        val enabled: Boolean = true,
        val builtIn: Boolean = false,
    )

    fun toJson(rules: List<Rule>): String = toJson(rules, emptyList())

    fun toJson(rules: List<Rule>, formats: List<RedirectFormat>): String =
        toJson(rules, formats, emptyList())

    fun toJson(rules: List<Rule>, formats: List<RedirectFormat>, filters: List<QueryParamFilter>): String =
        toJson(rules, formats, filters, emptyList())

    fun toJson(
        rules: List<Rule>,
        formats: List<RedirectFormat>,
        filters: List<QueryParamFilter>,
        hosts: List<ShortenerHost>,
    ): String {
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
            queryParamFilters = filters.map {
                QueryParamFilterDto(
                    param = it.param,
                    host = it.host,
                    enabled = it.enabled,
                    builtIn = it.isBuiltIn,
                )
            },
            shortenerHosts = hosts.map {
                ShortenerHostDto(
                    name = it.name,
                    host = it.host,
                    pathPrefix = it.pathPrefix,
                    enabled = it.enabled,
                    builtIn = it.isBuiltIn,
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

    fun fromFilterJson(json: String): List<QueryParamFilter> {
        val list = adapter.fromJson(json) ?: throw IllegalArgumentException("Empty or invalid JSON")
        val dtos = list.queryParamFilters ?: emptyList()
        return dtos.map { dto ->
            QueryParamFilter(
                id = 0,
                name = dto.param,
                param = dto.param,
                host = dto.host,
                enabled = dto.enabled,
                priority = 0,
                isBuiltIn = dto.builtIn,
            )
        }
    }

    fun fromShortenerHostJson(json: String): List<ShortenerHost> {
        val list = adapter.fromJson(json) ?: throw IllegalArgumentException("Empty or invalid JSON")
        val dtos = list.shortenerHosts ?: emptyList()
        return dtos.map { dto ->
            ShortenerHost(
                id = 0,
                name = dto.name,
                host = dto.host,
                pathPrefix = dto.pathPrefix,
                enabled = dto.enabled,
                priority = 0,
                isBuiltIn = dto.builtIn,
            )
        }
    }
}
