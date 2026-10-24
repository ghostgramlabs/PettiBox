package com.ghostgramlabs.pettibox.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("parent_id"),
        Index("sort_order")
    ]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    @ColumnInfo(name = "color_hex") val colorHex: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "user_created") val userCreated: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /**
     * A locked collection's saves show only inside it, after a fingerprint /
     * face / phone-PIN unlock. Everywhere else (Home, search, Unread, the
     * widget, nudges) leaves them out. See [NOT_IN_LOCKED_COLLECTION].
     */
    @ColumnInfo(name = "is_locked", defaultValue = "0") val isLocked: Boolean = false
)
