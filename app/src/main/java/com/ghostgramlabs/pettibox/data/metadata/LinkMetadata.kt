package com.ghostgramlabs.pettibox.data.metadata

data class LinkMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    /** Channel or writer, when the page says. */
    val author: String? = null
) {
    /**
     * Who / where / what, as one searchable block — so a video or post
     * whose title is vague can still be found by channel or description.
     */
    fun searchableDetails(): String? =
        listOfNotNull(
            author?.let { "By $it" },
            siteName,
            description
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").ifBlank { null }
}
