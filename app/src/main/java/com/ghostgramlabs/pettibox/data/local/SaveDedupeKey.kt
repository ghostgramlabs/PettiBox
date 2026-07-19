package com.ghostgramlabs.pettibox.data.local

import androidx.room.ColumnInfo

/**
 * Just enough of a save to decide "is this already on the shelf?" during
 * a backup restore. Links match on URL; URL-less items (notes,
 * screenshots, files) match on the content-type + creation-time + title
 * triple, which survives the export/import round trip unchanged.
 */
data class SaveDedupeKey(
    val url: String?,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val title: String
)
