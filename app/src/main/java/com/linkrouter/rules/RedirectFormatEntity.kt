package com.linkrouter.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity mirroring the [RedirectFormat] domain model. */
@Entity(
    tableName = "redirect_formats",
    indices = [Index(value = ["enabled", "priority"])],
)
data class RedirectFormatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo val name: String,
    @ColumnInfo val pattern: String,
    @ColumnInfo val matchType: MatchType,
    @ColumnInfo val extractType: ExtractType,
    @ColumnInfo val extractTarget: String,
    @ColumnInfo val enabled: Boolean,
    @ColumnInfo val priority: Int,
    @ColumnInfo val isBuiltIn: Boolean,
) {
    fun toRedirectFormat(): RedirectFormat = RedirectFormat(
        id = id,
        name = name,
        pattern = pattern,
        matchType = matchType,
        extractType = extractType,
        extractTarget = extractTarget,
        enabled = enabled,
        priority = priority,
        isBuiltIn = isBuiltIn,
    )

    companion object {
        fun fromRedirectFormat(format: RedirectFormat): RedirectFormatEntity = RedirectFormatEntity(
            id = format.id,
            name = format.name,
            pattern = format.pattern,
            matchType = format.matchType,
            extractType = format.extractType,
            extractTarget = format.extractTarget,
            enabled = format.enabled,
            priority = format.priority,
            isBuiltIn = format.isBuiltIn,
        )
    }
}
