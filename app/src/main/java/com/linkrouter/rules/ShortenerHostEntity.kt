package com.linkrouter.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity mirroring the [ShortenerHost] domain model. */
@Entity(
    tableName = "shortener_hosts",
    indices = [Index(value = ["enabled", "priority"])],
)
data class ShortenerHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo val name: String,
    @ColumnInfo val host: String,
    @ColumnInfo val enabled: Boolean,
    @ColumnInfo val priority: Int,
    @ColumnInfo val isBuiltIn: Boolean,
) {
    fun toShortenerHost(): ShortenerHost = ShortenerHost(
        id = id,
        name = name,
        host = host,
        enabled = enabled,
        priority = priority,
        isBuiltIn = isBuiltIn,
    )

    companion object {
        fun fromShortenerHost(host: ShortenerHost): ShortenerHostEntity = ShortenerHostEntity(
            id = host.id,
            name = host.name,
            host = host.host,
            enabled = host.enabled,
            priority = host.priority,
            isBuiltIn = host.isBuiltIn,
        )
    }
}
