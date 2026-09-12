package net.chaosengine.linkrouter.rules

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun matchTypeToString(value: MatchType): String = value.name

    @TypeConverter
    fun stringToMatchType(value: String): MatchType = MatchType.valueOf(value)

    @TypeConverter
    fun openModeToString(value: OpenMode): String = value.name

    @TypeConverter
    fun stringToOpenMode(value: String): OpenMode = OpenMode.valueOf(value)

    @TypeConverter
    fun extractTypeToString(value: ExtractType): String = value.name

    @TypeConverter
    fun stringToExtractType(value: String): ExtractType = ExtractType.valueOf(value)

    @TypeConverter
    fun rewriteMatchTypeToString(value: RewriteMatchType): String = value.name

    @TypeConverter
    fun stringToRewriteMatchType(value: String): RewriteMatchType = RewriteMatchType.valueOf(value)

    @TypeConverter
    fun rewriteKindToString(value: RewriteKind): String = value.name

    @TypeConverter
    fun stringToRewriteKind(value: String): RewriteKind = RewriteKind.valueOf(value)
}
