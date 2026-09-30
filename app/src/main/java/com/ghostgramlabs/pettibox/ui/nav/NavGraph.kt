package com.ghostgramlabs.pettibox.ui.nav

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.ui.theme.PaperBright
import com.ghostgramlabs.pettibox.ui.theme.isLightTheme
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ghostgramlabs.pettibox.data.preferences.ThemeMode
import com.ghostgramlabs.pettibox.ui.screens.categories.BrowseDestination
import com.ghostgramlabs.pettibox.ui.screens.categories.CategoriesScreen
import com.ghostgramlabs.pettibox.ui.screens.detail.DetailScreen
import com.ghostgramlabs.pettibox.ui.screens.home.HomeScreen
import com.ghostgramlabs.pettibox.ui.screens.search.SearchScreen
import com.ghostgramlabs.pettibox.ui.screens.settings.SettingsScreen

object Routes {
    const val Home = "home"
    const val Search = "search?q={q}&src={src}"
    const val Detail = "detail/{id}"
    const val Categories = "categories?cid={cid}"
    const val Settings = "settings"

    fun search(q: String = "", src: String = "") =
        "search?q=${Uri.encode(q)}&src=${Uri.encode(src)}"
    fun detail(id: Long) = "detail/$id"
    fun categories(cid: String? = null) = "categories?cid=${Uri.encode(cid.orEmpty())}"
}

private data class TopTab(
    val route: String,
    val baseRoute: String,
    val label: String,
    val icon: ImageVector
)

private val topTabs = listOf(
    TopTab(Routes.Home, "home", "Home", Icons.Rounded.Home),
    TopTab(Routes.search(), "search", "Search", Icons.Rounded.Search),
    TopTab(Routes.categories(), "categories", "Browse", Icons.Rounded.GridView),
    TopTab(Routes.Settings, "settings", "Settings", Icons.Rounded.Settings)
)

@Composable
fun PettiBoxNavGraph(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    launch: AppLaunch? = null,
    onLaunchConsumed: () -> Unit = {}
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route.orEmpty()
    val showBottomBar = topTabs.any { it.matches(currentRoute) }

    // Home's add flows requested from outside (shortcut / widget "+").
    var homeAction by remember { mutableStateOf<AppLaunch?>(null) }

    // Outside entry points (notification, widget, app-icon shortcut). The
    // activity stashes the request and re-renders; this effect acts on it
    // once the NavController has actually composed. Without it a reminder
    // tap would open to Home and leave the user hunting for the save.
    androidx.compose.runtime.LaunchedEffect(launch) {
        when (launch ?: return@LaunchedEffect) {
            is AppLaunch.OpenItem -> nav.navigate(Routes.detail((launch as AppLaunch.OpenItem).id))
            AppLaunch.Unread -> nav.navigateTopLevel(
                Routes.categories(BrowseDestination.toCid(BrowseDestination.Unread))
            )
            AppLaunch.Search -> nav.navigateTopLevel(Routes.search())
            AppLaunch.NewNote, AppLaunch.AddLink, AppLaunch.AddChooser -> {
                nav.navigateTopLevel(Routes.Home)
                homeAction = launch
            }
        }
        onLaunchConsumed()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) BottomNav(nav, currentRoute)
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.Home,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.Home) {
                HomeScreen(
                    pendingAction = homeAction,
                    onPendingActionConsumed = { homeAction = null },
                    onOpenItem = { id -> nav.navigate(Routes.detail(id)) },
                    onOpenSource = { src -> nav.navigate(Routes.search(src = src)) },
                    onOpenCategory = { cid -> nav.navigate(Routes.categories(cid)) },
                    onOpenAllCategories = { nav.navigateTopLevel(Routes.categories()) }
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange
                )
            }
            composable(
                Routes.Search,
                arguments = listOf(
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                    navArgument("src") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                val q = entry.arguments?.getString("q").orEmpty()
                val src = entry.arguments?.getString("src").orEmpty()
                SearchScreen(
                    initialQuery = q,
                    initialSource = src.ifBlank { null },
                    onOpenItem = { id -> nav.navigate(Routes.detail(id)) }
                )
            }
            composable(
                Routes.Detail,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) {
                DetailScreen(
                    onBack = { nav.popBackStack() },
                    onDeleted = { nav.popBackStack() },
                    onOpenTag = { name ->
                        nav.navigate(
                            Routes.categories(
                                BrowseDestination.toCid(BrowseDestination.Tag(name))
                            )
                        )
                    }
                )
            }
            composable(
                Routes.Categories,
                arguments = listOf(navArgument("cid") { type = NavType.StringType; defaultValue = "" })
            ) {
                CategoriesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenItem = { id -> nav.navigate(Routes.detail(id)) }
                )
            }
        }
    }
}

/**
 * Slim custom bottom bar. Material 3's [NavigationBar] reserves a fixed
 * 80 dp surface plus its own paddings, which over the long-tap-only
 * label adds up to a noticeable chunk of the viewport. This custom
 * version is 60 dp tall + system-nav inset, with a small "selected dot"
 * underneath the active icon (echoing the hand-drawn dots used in the
 * onboarding page indicator). Selected tab also shows its label in tiny
 * caps; inactive tabs are icon-only so the bar stays light.
 */
@Composable
private fun BottomNav(nav: NavHostController, currentRoute: String) {
    val scheme = MaterialTheme.colorScheme
    // A "dock" the tabs rest on rather than a detached system bar. The shape is
    // SHARED geometry — both themes get the gently-rounded top + top hairline,
    // so light and dark stay consistent. Only the paint differs per mode: a
    // cream tier with a soft lift in light, the flat dark `surface` in dark.
    val light = isLightTheme()
    val dockShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val hairline = scheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (light) Modifier.shadow(8.dp, dockShape, clip = false) else Modifier)
            .clip(dockShape)
            .background(if (light) PaperBright else scheme.surface)
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .navigationBarsPadding()
            .height(60.dp)
    ) {
        topTabs.forEach { tab ->
            val selected = tab.matches(currentRoute)
            BottomNavItem(
                tab = tab,
                selected = selected,
                onClick = { nav.navigateTopLevel(tab.route) }
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    tab: TopTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .semantics {
                role = Role.Tab
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        if (selected) {
            // Selected tab: tiny label + a small dot underneath, instead
            // of M3's pill-shaped indicator. Reads as one continuous
            // hand-drawn motif with the onboarding dots and section
            // header squiggles elsewhere in the app.
            Text(
                tab.label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = scheme.primary
            )
        } else {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.0f))
            )
        }
    }
}

private fun TopTab.matches(route: String): Boolean =
    route.startsWith(baseRoute)

/** Reset to start destination when switching top-level tabs to avoid a deep stack. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}
