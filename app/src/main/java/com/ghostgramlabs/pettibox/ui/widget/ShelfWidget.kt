package com.ghostgramlabs.pettibox.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ghostgramlabs.pettibox.data.local.SaveDao
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.preferences.AppLockPreferences
import com.ghostgramlabs.pettibox.data.util.TimeFormat
import com.ghostgramlabs.pettibox.domain.model.ContentType
import com.ghostgramlabs.pettibox.ui.nav.AppLaunch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShelfWidgetEntryPoint {
    fun saveDao(): SaveDao
    fun appLockPreferences(): AppLockPreferences
}

/**
 * "Unread" home-screen widget: the newest saves you haven't opened yet,
 * one tap to open any of them, and a "+" to add something. With App lock
 * on it shows only the count — titles never sit on the home screen.
 */
class ShelfWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context, ShelfWidgetEntryPoint::class.java)
        val dao = entry.saveDao()
        // First frame from a one-off read; after that the session observes
        // the database, because Glance keeps a session alive and re-draws it
        // on update rather than calling provideGlance again.
        val initialLocked = entry.appLockPreferences().enabled.first()
        val initialTotal = dao.unreadTotal()
        val initialItems = dao.recentUnread(MAX_ITEMS)
        provideContent {
            val locked by entry.appLockPreferences().enabled.collectAsState(initialLocked)
            val total by dao.observeUnreadTotal().collectAsState(initialTotal)
            val items by dao.observeRecentUnread(MAX_ITEMS).collectAsState(initialItems)
            WidgetContent(context, total, if (locked) emptyList() else items, locked)
        }
    }

    companion object {
        private const val MAX_ITEMS = 8

        /**
         * Keeps placed widgets current while the app process is alive:
         * re-renders whenever the unread list or the lock setting changes.
         * Glance's update is a no-op when no widget is placed.
         */
        @OptIn(FlowPreview::class)
        suspend fun keepUpdated(context: Context) {
            val entry = EntryPointAccessors.fromApplication(context, ShelfWidgetEntryPoint::class.java)
            combine(
                entry.saveDao().observeRecentUnread(MAX_ITEMS),
                entry.saveDao().observeUnreadTotal(),
                entry.appLockPreferences().enabled
            ) { items, total, locked -> Triple(items.map { it.id to it.title }, total, locked) }
                .distinctUntilChanged()
                .debounce(500)
                .collect { runCatching { ShelfWidget().updateAll(context) } }
        }
    }
}

class ShelfWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShelfWidget()
}

private val Paper = ColorProvider(day = Color(0xFFFBF6EE), night = Color(0xFF24211D))
private val InkText = ColorProvider(day = Color(0xFF2A231D), night = Color(0xFFE9E2D2))
private val MutedText = ColorProvider(day = Color(0xFF5C5046), night = Color(0xFF9A938A))
private val Accent = ColorProvider(day = Color(0xFFD85A36), night = Color(0xFFD85A36))
private val RowFill = ColorProvider(day = Color(0xFFF1E9DC), night = Color(0xFF2F2C26))
private val OnAccent = ColorProvider(day = Color.White, night = Color.White)

@Composable
private fun WidgetContent(context: Context, total: Int, items: List<SaveItemEntity>, locked: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Paper)
            .cornerRadius(20.dp)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth()
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity(AppLaunch.intent(context, AppLaunch.Unread)))
            ) {
                Text(
                    "PettiBox",
                    style = TextStyle(color = InkText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    when (total) {
                        0 -> "All caught up"
                        1 -> "1 unread"
                        else -> "$total unread"
                    },
                    style = TextStyle(color = MutedText, fontSize = 12.sp)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier
                    .size(36.dp)
                    .background(Accent)
                    .cornerRadius(18.dp)
                    .clickable(actionStartActivity(AppLaunch.intent(context, AppLaunch.AddChooser)))
            ) {
                Text("+", style = TextStyle(color = OnAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        when {
            locked -> Message(
                context,
                "PettiBox is locked",
                "Open it to see your saves."
            )
            items.isEmpty() -> Message(
                context,
                "Nothing waiting ✨",
                "Share a link or photo to PettiBox and it lands here until you open it."
            )
            else -> LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                items(items, itemId = { it.id }) { item ->
                    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .background(RowFill)
                                .cornerRadius(12.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .clickable(actionStartActivity(AppLaunch.intent(context, AppLaunch.OpenItem(item.id))))
                        ) {
                            Text(item.typeEmoji(), style = TextStyle(fontSize = 14.sp))
                            Spacer(GlanceModifier.size(8.dp))
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(
                                    item.title.ifBlank { "Untitled save" },
                                    maxLines = 2,
                                    style = TextStyle(color = InkText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    "saved ${TimeFormat.relative(item.createdAt)}",
                                    style = TextStyle(color = MutedText, fontSize = 11.sp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(context: Context, title: String, body: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity(AppLaunch.intent(context, AppLaunch.Unread))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = TextStyle(color = InkText, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(2.dp))
        Text(body, style = TextStyle(color = MutedText, fontSize = 12.sp))
    }
}

private fun SaveItemEntity.typeEmoji(): String =
    when (runCatching { ContentType.valueOf(contentType) }.getOrNull()) {
        ContentType.LINK -> "🔗"
        ContentType.IMAGE -> "🖼"
        ContentType.PDF -> "📄"
        ContentType.FILE -> "📎"
        else -> "📝"
    }
