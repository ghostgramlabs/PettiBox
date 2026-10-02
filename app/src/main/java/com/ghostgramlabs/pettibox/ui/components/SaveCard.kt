package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.util.TimeFormat
import com.ghostgramlabs.pettibox.domain.model.ContentType
import com.ghostgramlabs.pettibox.domain.model.SourceApp

/**
 * Save card with per-content-type visual treatment so a Pinterest-style
 * grid actually has rhythm:
 *
 * - IMAGE  → polaroid: white frame, photo on top, caption at the bottom.
 * - LINK   → web bookmark: thumbnail with a folded top-right corner and a
 *            rubber-stamp source label.
 * - NOTE   → sticky-note: washed pastel paper (deterministic from item id),
 *            no border, slight tilt — looks scribbled on.
 * - PDF/FILE → manila card with a paperclip stroke up top.
 *
 * Pinned items get a yellow tape strip overlay regardless of type, and
 * the source emoji is reprised as a stamp on every type so the grid has
 * a recurring "postmark" motif.
 */
@Composable
fun SaveCard(
    item: SaveItemEntity,
    accent: Color,
    categoryEmoji: String?,
    categoryName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val type = runCatching { ContentType.valueOf(item.contentType) }.getOrDefault(ContentType.NOTE)
    val source = runCatching { SourceApp.valueOf(item.sourceApp) }.getOrDefault(SourceApp.UNKNOWN)

    when (type) {
        ContentType.IMAGE -> PolaroidCard(item, accent, source, categoryEmoji, categoryName, onClick, onLongClick, modifier)
        ContentType.LINK -> LinkBookmarkCard(item, accent, source, categoryEmoji, categoryName, onClick, onLongClick, modifier)
        ContentType.NOTE, ContentType.TEXT -> StickyNoteCard(item, accent, source, categoryEmoji, categoryName, onClick, onLongClick, modifier)
        ContentType.PDF, ContentType.FILE -> PaperclipCard(item, type, accent, source, categoryEmoji, categoryName, onClick, onLongClick, modifier)
    }
}

/**
 * Card-wide tap modifier — uses `combinedClickable` so long-press is
 * routed to [onLongClick] (the quick-actions sheet) and short tap to
 * [onClick] (open detail). Falls back to plain clickable when no long
 * press callback is provided.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.cardClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
): Modifier = if (onLongClick != null) {
    this.combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = "Open quick actions"
    )
} else {
    this.clickable(onClick = onClick)
}

/**
 * Subtle drop shadow applied only in light mode. Dark mode already gets
 * depth from M3's surface tonal lift; in light mode the surface is too
 * close to the Bone background, so cards looked flat against the paper
 * texture. A 2-3 dp shadow is enough to read as "paper lifted off the
 * desk" without venturing into Material-elevation territory.
 */
@Composable
private fun Modifier.lightCardShadow(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return if (isLight) this.shadow(3.dp, shape, clip = false) else this
}

// ── Variants ──────────────────────────────────────────────────────────────

@Composable
private fun PolaroidCard(
    item: SaveItemEntity,
    accent: Color,
    source: SourceApp,
    categoryEmoji: String?,
    categoryName: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightCardShadow(RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFFDFBF6))
                .border(1.dp, Color(0xFFE6DFD2), RoundedCornerShape(6.dp))
                .cardClickable(onClick, onLongClick)
                .padding(8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(accent.copy(alpha = 0.12f))
            ) {
                AsyncImage(
                    model = item.thumbnailUri ?: item.localUri,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                SourceStamp(
                    source = source,
                    accent = accent,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
            Spacer(Modifier.height(6.dp))
            FooterMeta(item, accent, categoryEmoji, categoryName, source, omitSource = true)
            Spacer(Modifier.height(6.dp))
        }
        if (item.isPinned) PinnedTape(Modifier.align(Alignment.TopStart))
        if (onLongClick != null) CardActionButton(
            onClick = onLongClick,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun LinkBookmarkCard(
    item: SaveItemEntity,
    accent: Color,
    source: SourceApp,
    categoryEmoji: String?,
    categoryName: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightCardShadow(RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.outline, RoundedCornerShape(14.dp))
                .cardClickable(onClick, onLongClick)
                .drawWithContent {
                    drawContent()
                    drawFoldedCorner(accent.copy(alpha = 0.22f))
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f)
                    .background(accent.copy(alpha = 0.14f))
            ) {
                if (!item.thumbnailUri.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnailUri,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.Link,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                    )
                }
                SourceStamp(
                    source = source,
                    accent = accent,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                )
            }
            Column(Modifier.padding(14.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    maxLines = 2
                )
                if (!item.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Spacer(Modifier.height(10.dp))
                FooterMeta(item, accent, categoryEmoji, categoryName, source, omitSource = true)
            }
        }
        if (item.isPinned) PinnedTape(Modifier.align(Alignment.TopStart))
        if (onLongClick != null) CardActionButton(
            onClick = onLongClick,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun StickyNoteCard(
    item: SaveItemEntity,
    accent: Color,
    source: SourceApp,
    categoryEmoji: String?,
    categoryName: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier
) {
    // Theme-aware sticky tint + ink: bright pastels in light mode (real
    // sticky-note look on cream paper) become muted dark-warm tones in
    // dark mode (tinted dark "paper"), with light ink on top. The bright
    // pastels were punching through the warm-charcoal dark theme like
    // stickers stuck on a wall — this restores the right "paper that
    // belongs in this room" feel.
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tint = stickyTintFor(item.id, isDark)
    val ink = if (isDark) Color(0xFFE9E2D2) else Color(0xFF2A231C)
    // No card-level tilt: the pastel tint + no-border treatment + footer
    // stamp already say "sticky note." Tilted serif body in a small
    // staggered cell was hurting readability without adding meaning.
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(tint)
                .cardClickable(onClick, onLongClick)
                .padding(14.dp)
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Serif
                ),
                color = ink,
                maxLines = 4,
                // Text sits straight on the note (no header image), so keep
                // the first lines clear of the quick-actions button.
                modifier = if (onLongClick != null) Modifier.padding(end = 26.dp) else Modifier
            )
            val body = noteBodyBelowTitle(item.title, item.notes)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                // Capped at 3 lines (was 6) — long previews on a tinted
                // small card become a wall of text with bad scan affordance.
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink.copy(alpha = 0.78f),
                    maxLines = 3
                )
            }
            // Reminder pill above the meta row so the user can read WHEN
            // they'll be nudged without opening the save. The bare clock
            // icon that used to live in the meta row was a feature
            // half-shown — the most useful piece of metadata (the time)
            // was hidden one tap away.
            if (item.remindAt != null && item.remindAt > System.currentTimeMillis()) {
                Spacer(Modifier.height(8.dp))
                ReminderPill(remindAt = item.remindAt, accent = accent)
            }
            if (item.isArchived) {
                Spacer(Modifier.height(8.dp))
                ArchivedPill(accent = accent)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceStamp(source = source, accent = ink)
                Spacer(Modifier.weight(1f))
                Text(
                    TimeFormat.relative(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.6f)
                )
                if (item.isPinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Bookmark, null, tint = accent, modifier = Modifier.size(14.dp))
                }
                if (item.isFavorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                }
            }
            if (categoryEmoji != null && categoryName != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$categoryEmoji $categoryName",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
        }
        if (item.isPinned) PinnedTape(Modifier.align(Alignment.TopStart))
        if (onLongClick != null) CardActionButton(
            onClick = onLongClick,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun PaperclipCard(
    item: SaveItemEntity,
    type: ContentType,
    accent: Color,
    source: SourceApp,
    categoryEmoji: String?,
    categoryName: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightCardShadow(RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.outline, RoundedCornerShape(14.dp))
                .cardClickable(onClick, onLongClick)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(headerRatio(type))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconFor(type),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(42.dp)
                )
                SourceStamp(
                    source = source,
                    accent = accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
            Column(Modifier.padding(14.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    maxLines = 2
                )
                if (!item.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Spacer(Modifier.height(10.dp))
                FooterMeta(item, accent, categoryEmoji, categoryName, source, omitSource = true)
            }
        }
        Paperclip(Modifier.align(Alignment.TopCenter))
        if (item.isPinned) PinnedTape(Modifier.align(Alignment.TopStart))
        if (onLongClick != null) CardActionButton(
            onClick = onLongClick,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

/**
 * Quick notes are titled after their first line, so on the card the note
 * text would repeat it. Show only what comes after; null when nothing does.
 */
private fun noteBodyBelowTitle(title: String, notes: String?): String? {
    val text = notes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val firstLine = text.lineSequence().first().trim()
    val titleStem = title.removeSuffix("…")
    if (firstLine != title && !(title.endsWith("…") && firstLine.startsWith(titleStem))) return text
    return text.lineSequence().drop(1).joinToString("\n").trim().ifEmpty { null }
}

@Composable
private fun CardActionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // 28 dp chip (was 40) so it stops dominating small cards. A hairline
    // outlineVariant ring keeps the circle legible against pale paper
    // surfaces (polaroid frame in light mode) where a flat surface fill
    // would otherwise disappear into the card. The circle is drawn inside
    // the 48 dp touch target — painted on the button itself, the minimum
    // touch size stretched it to 48 dp and it covered card text.
    IconButton(
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize()
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(scheme.surface.copy(alpha = 0.94f))
                .border(1.dp, scheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.MoreHoriz,
                contentDescription = "Open quick actions",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── Shared bits ───────────────────────────────────────────────────────────

@Composable
private fun FooterMeta(
    item: SaveItemEntity,
    accent: Color,
    categoryEmoji: String?,
    categoryName: String?,
    source: SourceApp,
    omitSource: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        // Reminder pill is its own row above the meta line. Putting the
        // time inline with pin/favorite icons either overflowed the
        // 2-column card or pushed the relative-saved-time off-screen.
        if (item.remindAt != null && item.remindAt > System.currentTimeMillis()) {
            ReminderPill(remindAt = item.remindAt, accent = accent)
            Spacer(Modifier.height(6.dp))
        }
        if (item.isArchived) {
            ArchivedPill(accent = accent)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (categoryEmoji != null && categoryName != null) {
                Box(
                    Modifier
                        .background(accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "$categoryEmoji $categoryName",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = if (omitSource) TimeFormat.relative(item.createdAt)
                       else "${source.emoji} ${TimeFormat.relative(item.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            if (item.isPinned) {
                Icon(Icons.Rounded.Bookmark, null, tint = accent, modifier = Modifier.size(16.dp))
            }
            if (item.isFavorite) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Rounded.Favorite, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Compact reminder label used in card footers. Renders as a tinted
 * pill — clock glyph + a short time string from [formatReminderCompact].
 * Sized for 150 dp-wide cards: "Tomorrow 9am" comfortably fits, longer
 * forms (e.g. "Dec 3 9am") still fit on one line.
 */
@Composable
private fun ArchivedPill(accent: Color) {
    Text(
        "Archived",
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(accent.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun ReminderPill(remindAt: Long, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(accent.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(
            Icons.Rounded.AccessTime,
            contentDescription = "Reminder",
            tint = accent,
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            com.ghostgramlabs.pettibox.ui.components.formatReminderCompact(remindAt),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Rubber-stamp treatment of the source app: tilted, monospaced, dark-ink
 * label inside a category-tinted border. The label uses [onSurface] so the
 * text reads against any hero (photo, accent block, sticky-note tint) —
 * accent-tinted text was failing contrast on the paler categories.
 */
@Composable
private fun SourceStamp(
    source: SourceApp,
    accent: Color,
    modifier: Modifier = Modifier
) {
    if (source == SourceApp.UNKNOWN) return
    val label = MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .rotate(-7f)
            .border(1.2.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(source.emoji, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(3.dp))
        Text(
            source.displayName.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            ),
            color = label.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun PinnedTape(modifier: Modifier = Modifier) {
    // offset() accepts negatives; padding() does not — this used to crash
    // when the grid composed a pinned item.
    Box(
        modifier
            .offset(x = 14.dp, y = (-6).dp)
            .rotate(-22f)
            .background(Color(0xCCF2E5A8))
            .padding(horizontal = 22.dp, vertical = 4.dp)
    )
}

@Composable
private fun Paperclip(modifier: Modifier = Modifier) {
    // Tiny U-shaped stroke at the top edge of the card, suggesting a clip
    // hooked over the page. Subtle but distinctive.
    Box(
        modifier
            .offset(y = (-4).dp)
            .size(width = 16.dp, height = 22.dp)
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.2f, 0f)
                    lineTo(w * 0.2f, h * 0.7f)
                    quadraticTo(w * 0.5f, h, w * 0.8f, h * 0.7f)
                    lineTo(w * 0.8f, h * 0.2f)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF8C8579),
                    style = Stroke(width = 2.4f, cap = StrokeCap.Round)
                )
            }
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFoldedCorner(color: Color) {
    val cornerSize = 18f
    val w = size.width
    val path = Path().apply {
        moveTo(w - cornerSize, 0f)
        lineTo(w, 0f)
        lineTo(w, cornerSize)
        close()
    }
    drawPath(path, color)
    // Hairline diagonal where the fold meets the page
    drawLine(
        color = color.copy(alpha = 0.55f),
        start = Offset(w - cornerSize, 0f),
        end = Offset(w, cornerSize),
        strokeWidth = 1.2f
    )
}

private fun iconFor(type: ContentType) = when (type) {
    ContentType.LINK -> Icons.Rounded.Link
    ContentType.IMAGE -> Icons.Rounded.Image
    ContentType.PDF -> Icons.Rounded.PictureAsPdf
    ContentType.NOTE, ContentType.TEXT -> Icons.AutoMirrored.Rounded.Notes
    ContentType.FILE -> Icons.AutoMirrored.Rounded.Notes
}

private fun headerRatio(type: ContentType): Float = when (type) {
    ContentType.LINK -> 1.4f
    ContentType.IMAGE -> 1.0f
    ContentType.PDF -> 1.6f
    ContentType.TEXT -> 2.2f
    ContentType.NOTE -> 2.4f
    ContentType.FILE -> 1.8f
}

// ── Sticky note tints ─────────────────────────────────────────────────────
// Two parallel palettes so the sticky-note metaphor survives the theme
// switch: bright pastels on cream in light mode (paper on a desk),
// muted dark-warm tones on charcoal in dark mode (tinted dark paper).

// Desaturated paper-like tints. The previous values (#F2E5A8 butter etc.)
// were highlighter-bright on Bone — read as classroom Post-its rather than
// the Muji "real paper" feel the rest of the palette aims at. Each hue
// keeps its identity (yellow / green / blue / pink) but the saturation is
// dialled down ~12% so they read as tinted cream paper, sitting in the
// same family as the muted dark sticky tints.
private val StickyNoteTintsLight = listOf(
    Color(0xFFECE0B4), // butter — muted dijon
    Color(0xFFD3DBC6), // mint — sage-grey
    Color(0xFFCCD3DA), // sky — paper-blue
    Color(0xFFDFD0CC)  // peach — washed pink
)

private val StickyNoteTintsDark = listOf(
    Color(0xFF433D2A), // muted warm yellow
    Color(0xFF3D3F2E), // muted warm green
    Color(0xFF353A40), // muted blue-gray
    Color(0xFF433539)  // muted warm pink
)

private fun stickyTintFor(id: Long, isDark: Boolean): Color {
    val palette = if (isDark) StickyNoteTintsDark else StickyNoteTintsLight
    val idx = ((id % palette.size) + palette.size) % palette.size
    return palette[idx.toInt()]
}
