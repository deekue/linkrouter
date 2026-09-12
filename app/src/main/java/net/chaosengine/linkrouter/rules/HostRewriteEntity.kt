package net.chaosengine.linkrouter.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity mirroring the [HostRewrite] domain model. */
@Entity(
    tableName = "host_rewrites",
    indices = [Index(value = ["priority"], unique = true)],
)
data class HostRewriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo val matchHost: String,
    @ColumnInfo val matchType: RewriteMatchType,
    @ColumnInfo val kind: RewriteKind,
    @ColumnInfo val targetHost: String,
    @ColumnInfo val preserveHostInPath: Boolean,
    @ColumnInfo val enabled: Boolean,
    @ColumnInfo val priority: Int,
    @ColumnInfo val isBuiltIn: Boolean,
) {
    fun toHostRewrite(): HostRewrite = HostRewrite(
        id = id,
        matchHost = matchHost,
        matchType = matchType,
        kind = kind,
        targetHost = targetHost,
        preserveHostInPath = preserveHostInPath,
        enabled = enabled,
        priority = priority,
        isBuiltIn = isBuiltIn,
    )

    companion object {
        fun fromHostRewrite(rw: HostRewrite): HostRewriteEntity = HostRewriteEntity(
            id = rw.id,
            matchHost = rw.matchHost,
            matchType = rw.matchType,
            kind = rw.kind,
            targetHost = rw.targetHost,
            preserveHostInPath = rw.preserveHostInPath,
            enabled = rw.enabled,
            priority = rw.priority,
            isBuiltIn = rw.isBuiltIn,
        )
    }
}
