package net.chaosengine.linkrouter.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity mirroring the [Rule] domain model (DESIGN.md section 4). */
@Entity(tableName = "rules", indices = [Index(value = ["priority"], unique = true)])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo val pattern: String,
    @ColumnInfo val matchType: MatchType,
    @ColumnInfo val targetPackage: String,
    @ColumnInfo val targetActivity: String?,
    @ColumnInfo val openMode: OpenMode,
    @ColumnInfo val enabled: Boolean,
    @ColumnInfo val priority: Int,
) {
    fun toRule(): Rule = Rule(
        id = id,
        pattern = pattern,
        matchType = matchType,
        targetPackage = targetPackage,
        targetActivity = targetActivity,
        openMode = openMode,
        enabled = enabled,
        priority = priority,
    )

    companion object {
        fun fromRule(rule: Rule): RuleEntity = RuleEntity(
            id = rule.id,
            pattern = rule.pattern,
            matchType = rule.matchType,
            targetPackage = rule.targetPackage,
            targetActivity = rule.targetActivity,
            openMode = rule.openMode,
            enabled = rule.enabled,
            priority = rule.priority,
        )
    }
}
