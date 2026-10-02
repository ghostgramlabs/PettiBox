package com.ghostgramlabs.pettibox.ui.screens.detail

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.ghostgramlabs.pettibox.data.article.ArticleExtractor
import com.ghostgramlabs.pettibox.data.local.ArticleCopyEntity
import com.ghostgramlabs.pettibox.data.local.ArticleCopyStatus
import com.ghostgramlabs.pettibox.data.local.ArticleCopySummary
import com.ghostgramlabs.pettibox.data.preferences.ArticlePreferences
import org.jsoup.nodes.Entities
import java.net.URI
import java.text.DateFormat
import java.util.Date

/**
 * The detail screen's offline-reading row for a link save. It only ever
 * shows one clear next step: read the copy, wait for it, retry it, or make
 * one. Sites that can never have a copy (video, social) show nothing.
 */
@Composable
fun OfflineCopyCard(
    url: String,
    copy: ArticleCopySummary?,
    accent: Color,
    onRead: () -> Unit,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = copy?.status?.let { runCatching { ArticleCopyStatus.valueOf(it) }.getOrNull() }
    if (status == ArticleCopyStatus.UNSUPPORTED) return
    if (status == null && !ArticleExtractor.isSupported(url)) return

    val scheme = MaterialTheme.colorScheme
    val ready = status == ArticleCopyStatus.READY
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (ready) accent.copy(alpha = 0.14f) else scheme.surfaceVariant.copy(alpha = 0.6f))
            .let { if (ready) it.clickable(onClick = onRead) else it }
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (status) {
                ArticleCopyStatus.PENDING -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = accent,
                    modifier = Modifier.size(18.dp)
                )
                ArticleCopyStatus.FAILED -> Icon(
                    Icons.Rounded.CloudOff, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                )
                else -> Icon(
                    Icons.AutoMirrored.Rounded.MenuBook, null,
                    tint = if (ready) accent else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val (title, subtitle) = when (status) {
                ArticleCopyStatus.READY -> "Read offline" to
                    "${readingTime(copy!!.wordCount)} · copy saved ${shortDate(copy.fetchedAt)}"
                ArticleCopyStatus.PENDING -> "Saving a copy to read offline…" to
                    "Happens in the background whenever you're online"
                ArticleCopyStatus.FAILED -> "No offline copy" to
                    (copy?.failureReason ?: "Couldn't save this page")
                else -> "Read it later, even offline" to
                    "Keep a copy of this article on your phone"
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (ready) accent else scheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        when (status) {
            ArticleCopyStatus.READY -> Icon(
                Icons.Rounded.ChevronRight, null, tint = accent, modifier = Modifier.padding(end = 6.dp)
            )
            ArticleCopyStatus.FAILED -> TextButton(onClick = onRequest) { Text("Try again") }
            ArticleCopyStatus.PENDING -> Unit
            else -> TextButton(onClick = onRequest) { Text("Keep offline") }
        }
    }
}

/**
 * Full-screen reader for an offline copy. The copy is rendered from local
 * storage with JavaScript off and network loads blocked, so it works with
 * no connection and can't run anything from the original page. Tapping a
 * link inside the article opens it in the browser.
 */
@Composable
fun ArticleReaderDialog(
    title: String,
    url: String,
    copy: ArticleCopyEntity,
    textZoom: Int,
    onTextZoomChange: (Int) -> Unit,
    onOpenOriginal: () -> Unit,
    onUpdateCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val page = remember(copy.html, copy.fetchedAt, title, scheme) {
        readerHtml(
            title = title,
            site = hostOf(url),
            byline = copy.byline,
            wordCount = copy.wordCount,
            fetchedAt = copy.fetchedAt,
            body = copy.html.orEmpty(),
            colors = ReaderColors(
                background = scheme.background.css(),
                text = scheme.onBackground.css(),
                muted = scheme.onSurfaceVariant.css(),
                accent = scheme.primary.css(),
                subtle = scheme.surfaceVariant.css()
            )
        )
    }
    val background = scheme.background.toArgb()
    // Creating the WebView blocks the first frame for a second or two on a
    // cold start, which used to show as a blank screen. Draw the toolbar and
    // a spinner first, build the WebView a frame later, and drop the spinner
    // once the page has rendered.
    var showWebView by remember { mutableStateOf(false) }
    var pageLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        showWebView = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Draw behind the status bar like every other screen, instead of
        // leaving the dim scrim showing above the toolbar as a dark band.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val dialogView = LocalView.current
        val lightBars = scheme.surface.luminance() > 0.5f
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                // A dialog window stops at the status bar even with
                // decorFitsSystemWindows off; let it cover the whole screen
                // and drop the dim, which otherwise shows above the toolbar.
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.setDimAmount(0f)
                window.attributes = window.attributes.apply {
                    // Centred inside the bar-free area it sat a few px down,
                    // leaving a sliver of the screen behind showing on top.
                    gravity = Gravity.TOP
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setFitInsetsTypes(0)
                    }
                }
                WindowCompat.getInsetsController(window, dialogView).isAppearanceLightStatusBars = lightBars
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(scheme.background)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                }
                Text(
                    "Offline copy",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = textZoom > ArticlePreferences.MIN_TEXT_ZOOM,
                    onClick = { onTextZoomChange(textZoom - ArticlePreferences.TEXT_ZOOM_STEP) }
                ) { Icon(Icons.Rounded.TextDecrease, contentDescription = "Smaller text") }
                IconButton(
                    enabled = textZoom < ArticlePreferences.MAX_TEXT_ZOOM,
                    onClick = { onTextZoomChange(textZoom + ArticlePreferences.TEXT_ZOOM_STEP) }
                ) { Icon(Icons.Rounded.TextIncrease, contentDescription = "Bigger text") }
                IconButton(onClick = onUpdateCopy) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Update the saved copy")
                }
                IconButton(onClick = onOpenOriginal) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open original", tint = scheme.primary)
                }
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (showWebView) {
                    ReaderWebView(
                        html = page,
                        plainText = copy.textContent.orEmpty(),
                        textColor = scheme.onBackground.toArgb(),
                        baseUrl = url,
                        textZoom = textZoom,
                        backgroundColor = background,
                        onLoaded = { pageLoaded = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (!pageLoaded) {
                    CircularProgressIndicator(
                        color = scheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebView(
    html: String,
    plainText: String,
    textColor: Int,
    baseUrl: String,
    textZoom: Int,
    backgroundColor: Int,
    onLoaded: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Creating a WebView throws while the system WebView package is
            // being updated or is disabled. The copy is still readable as
            // plain text, so fall back instead of crashing the reader.
            runCatching { WebView(context) }.getOrNull()?.apply {
                setBackgroundColor(backgroundColor)
                settings.apply {
                    javaScriptEnabled = false
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                // The page carries its own theme colors; don't let the
                // WebView invert them a second time in dark mode.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    settings.isAlgorithmicDarkeningAllowed = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        onLoaded()
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        // In-page jumps (footnotes, table of contents) stay in the reader.
                        if (target.fragment != null && target.buildUpon().fragment(null).toString() == baseUrl) {
                            return false
                        }
                        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                        return true
                    }
                }
            } ?: plainTextFallback(context, plainText, textColor, backgroundColor).also { onLoaded() }
        },
        update = { view ->
            when (view) {
                is WebView -> {
                    view.settings.textZoom = textZoom
                    if (view.tag != html) {
                        view.tag = html
                        view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                    }
                }
                is ScrollView -> (view.getChildAt(0) as? TextView)?.textSize = 18f * textZoom / 100f
            }
        }
    )
}

private fun plainTextFallback(
    context: android.content.Context,
    text: String,
    textColor: Int,
    backgroundColor: Int
): android.view.View = ScrollView(context).apply {
    setBackgroundColor(backgroundColor)
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    addView(TextView(context).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 18f
        setLineSpacing(0f, 1.4f)
        setTextIsSelectable(true)
        setPadding(pad, pad, pad, pad * 2)
    })
}

private data class ReaderColors(
    val background: String,
    val text: String,
    val muted: String,
    val accent: String,
    val subtle: String
)

private fun readerHtml(
    title: String,
    site: String?,
    byline: String?,
    wordCount: Int,
    fetchedAt: Long,
    body: String,
    colors: ReaderColors
): String {
    val meta = listOfNotNull(site, byline, readingTime(wordCount)).joinToString(" · ") { Entities.escape(it) }
    return """
        <!doctype html>
        <html><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { background: ${colors.background}; color: ${colors.text}; }
          body { margin: 0; padding: 20px 20px 56px; font: 18px/1.65 Georgia, "Noto Serif", serif;
                 overflow-wrap: break-word; }
          h1.title { font: 700 1.55em/1.25 Georgia, "Noto Serif", serif; margin: 4px 0 10px; }
          .meta { color: ${colors.muted}; font: 0.8em/1.4 sans-serif; margin-bottom: 4px; }
          .snapshot { color: ${colors.muted}; font: 0.75em/1.4 sans-serif; margin-bottom: 24px;
                      padding-bottom: 16px; border-bottom: 1px solid ${colors.subtle}; }
          h1, h2, h3, h4 { line-height: 1.3; }
          a { color: ${colors.accent}; }
          blockquote { margin: 1em 0; padding-left: 1em; border-left: 3px solid ${colors.accent}; color: ${colors.muted}; }
          pre, code { font-family: monospace; font-size: 0.85em; background: ${colors.subtle}; border-radius: 6px; }
          code { padding: 1px 4px; }
          pre { padding: 12px; overflow-x: auto; white-space: pre-wrap; }
          pre code { padding: 0; background: none; }
          table { border-collapse: collapse; display: block; overflow-x: auto; font-size: 0.9em; }
          th, td { border: 1px solid ${colors.subtle}; padding: 6px 8px; text-align: left; }
          hr { border: 0; border-top: 1px solid ${colors.subtle}; }
          figcaption { color: ${colors.muted}; font-size: 0.8em; }
        </style>
        </head><body>
        <h1 class="title">${Entities.escape(title)}</h1>
        <div class="meta">$meta</div>
        <div class="snapshot">Copy saved ${Entities.escape(shortDate(fetchedAt))} · text only, pictures aren&apos;t saved. For the full page, tap the ↗ button at the top.</div>
        $body
        </body></html>
    """.trimIndent()
}

private fun Color.css(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

private fun hostOf(url: String): String? =
    runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

private fun readingTime(words: Int): String = "${(words / 220).coerceAtLeast(1)} min read"

private fun shortDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** Opens a URL in the browser; false when nothing can handle it. */
internal fun openInBrowser(context: android.content.Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
