package com.linkrouter.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity mirroring the [QueryParamFilter] domain model. */
@Entity(
    tableName = "query_param_filters",
    indices = [Index(value = ["enabled", "priority"])],
)
data class QueryParamFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo val name: String,
    @ColumnInfo val host: String?,
    @ColumnInfo val param: String,
    @ColumnInfo val enabled: Boolean,
    @ColumnInfo val priority: Int,
    @ColumnInfo val isBuiltIn: Boolean,
) {
    fun toQueryParamFilter(): QueryParamFilter = QueryParamFilter(
        id = id,
        name = name,
        host = host,
        param = param,
        enabled = enabled,
        priority = priority,
        isBuiltIn = isBuiltIn,
    )

    companion object {
        fun fromQueryParamFilter(f: QueryParamFilter): QueryParamFilterEntity = QueryParamFilterEntity(
            id = f.id,
            name = f.name,
            host = f.host,
            param = f.param,
            enabled = f.enabled,
            priority = f.priority,
            isBuiltIn = f.isBuiltIn,
        )
    }
}
