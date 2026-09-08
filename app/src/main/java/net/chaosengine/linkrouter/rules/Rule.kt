package net.chaosengine.linkrouter.rules

enum class MatchType { EXACT_HOST, SUBDOMAIN, PATH_PREFIX, REGEX }

enum class OpenMode { NORMAL, PRIVATE }

data class Rule(
    val id: Long,
    val pattern: String,
    val matchType: MatchType,
    val targetPackage: String,
    val targetActivity: String? = null,
    val openMode: OpenMode = OpenMode.NORMAL,
    val enabled: Boolean = true,
    val priority: Int = 0,
)
