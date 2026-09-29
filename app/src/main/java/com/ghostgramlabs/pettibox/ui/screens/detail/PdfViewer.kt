package com.ghostgramlabs.pettibox.ui.screens.detail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * An open PDF, rendered page-by-page on demand. PdfRenderer allows only one
 * open page at a time and isn't thread-safe, so every touch of it is
 * serialized on [lock] — including close, which may race a page render when
 * the viewer is dismissed mid-scroll.
 */
private class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {
    private val lock = Any()
    private var closed = false

    /** Page sizes in PDF points, read up front so the list can reserve space. */
    val pageSizes: List<Pair<Int, Int>> = List(renderer.pageCount) { i ->
        renderer.openPage(i).use { it.width.coerceAtLeast(1) to it.height.coerceAtLeast(1) }
    }

    suspend fun render(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@withContext null
            runCatching {
                renderer.openPage(index).use { page ->
                    val w = targetWidth.coerceAtLeast(1)
                    val h = (w.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    /** Closes off the main thread, since it may wait out a page render. */
    fun closeAsync() = Dispatchers.IO.asExecutor().execute { close() }

    companion object {
        /** Null if the file is gone, unreadable, or password-protected. */
        fun open(context: Context, uri: String): PdfDocument? {
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
            }.getOrNull() ?: return null
            return runCatching { PdfDocument(pfd, PdfRenderer(pfd)) }
                .onFailure { runCatching { pfd.close() } }
                .getOrNull()
        }
    }
}

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data object Failed : PdfLoad
    data class Ready(val doc: PdfDocument) : PdfLoad
}

@Composable
private fun rememberPdfDocument(uri: String): PdfLoad {
    val context = LocalContext.current
    val load by produceState<PdfLoad>(PdfLoad.Loading, uri) {
        val doc = withContext(Dispatchers.IO) { PdfDocument.open(context, uri) }
        value = doc?.let { PdfLoad.Ready(it) } ?: PdfLoad.Failed
        awaitDispose { doc?.closeAsync() }
    }
    return load
}

/**
 * In-app PDF reader: pages stacked in a scrolling column, pinch/double-tap to
 * zoom. Pages render lazily as they scroll in, at 1.5x screen width so text
 * stays readable when zoomed without holding a full-resolution bitmap per
 * page. [onPageChanged] reports (current page, page count) for the header.
 */
@Composable
fun PdfPages(
    uri: String,
    title: String,
    onOpenExternally: () -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    when (val load = rememberPdfDocument(uri)) {
        PdfLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        PdfLoad.Failed -> PdfUnavailable(onOpenExternally, modifier)
        is PdfLoad.Ready -> {
            val doc = load.doc
            val listState = rememberLazyListState()
            val zoom = rememberZoomState()
            val scope = rememberCoroutineScope()
            val flingBehavior = ScrollableDefaults.flingBehavior()
            val pageCount = doc.pageSizes.size
            val current by remember(listState) { derivedStateOf { listState.currentPage() } }
            LaunchedEffect(current, pageCount) {
                onPageChanged(current, pageCount)
            }

            BoxWithConstraints(
                modifier
                    .fillMaxSize()
                    // Initial pass: the zoom gesture must see drags before the
                    // LazyColumn claims them as scrolls.
                    .zoomable(
                        state = zoom,
                        pass = PointerEventPass.Initial,
                        onOverflow = { overflow ->
                            // Vertical pan past the zoomed viewport's edge
                            // scrolls the page list. The list lives inside the
                            // scale, so screen pixels shrink by the zoom factor.
                            listState.dispatchRawDelta(-overflow.y / zoom.scale)
                            true
                        },
                        onFling = { velocity ->
                            scope.launch {
                                listState.scroll {
                                    with(flingBehavior) { performFling(-velocity.y / zoom.scale) }
                                }
                            }
                        }
                    )
            ) {
                val renderWidth = with(LocalDensity.current) {
                    (maxWidth.toPx() * 1.5f).toInt().coerceIn(1, MAX_RENDER_WIDTH)
                }
                LazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomTransform(zoom)
                ) {
                    items(pageCount, key = { it }) { index ->
                        val (w, h) = doc.pageSizes[index]
                        val bitmap by produceState<ImageBitmap?>(null, doc, index, renderWidth) {
                            value = doc.render(index, renderWidth)?.asImageBitmap()
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(w.toFloat() / h)
                                .background(Color.White)
                        ) {
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = "$title, page ${index + 1} of $pageCount",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * First page of a PDF as a still image, for the detail screen's hero card —
 * otherwise a PDF save shows an empty tile and gives no hint it can be opened.
 */
@Composable
fun PdfFirstPage(uri: String, contentDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = with(LocalDensity.current) { maxWidth.toPx().toInt().coerceIn(1, MAX_RENDER_WIDTH) }
        val bitmap by produceState<ImageBitmap?>(null, uri, width) {
            value = withContext(Dispatchers.IO) {
                PdfDocument.open(context, uri)?.use { it.render(0, width) }
            }?.asImageBitmap()
        }
        val page = bitmap
        if (page != null) {
            Image(
                bitmap = page,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Rounded.PictureAsPdf,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

/** Shown for files we can't render in-app: missing, locked PDFs, or non-PDF documents. */
@Composable
fun PdfUnavailable(
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = "This PDF can't be shown here. It may be password-protected or damaged."
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.PictureAsPdf,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenExternally) { Text("Open in another app") }
    }
}

/** The page occupying most of the viewport, 1-based. */
private fun LazyListState.currentPage(): Int {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 1
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val atCenter = visible.firstOrNull { it.offset <= viewportCenter && it.offset + it.size > viewportCenter }
    return (atCenter ?: visible.first()).index + 1
}

private const val MAX_RENDER_WIDTH = 1800
