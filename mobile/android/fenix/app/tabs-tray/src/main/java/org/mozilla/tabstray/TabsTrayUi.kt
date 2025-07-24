package org.mozilla.tabstray

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import mozilla.components.compose.base.theme.AcornColors
import mozilla.components.compose.base.theme.AcornTheme
import mozilla.components.lib.state.Action
import mozilla.components.lib.state.State
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.observeAsComposableState
import kotlin.collections.listOf

data class Tab(
    val id: String,
    val url: String,
    val title: String,
    val bitmap: Bitmap,
    val mode: TabsTrayMode
)

sealed class TabsTrayMode {
    object Normal : TabsTrayMode()
    object Private : TabsTrayMode()
    object Synced : TabsTrayMode()
}

data class TabsTrayState(
    val tabs: List<Tab> = emptyList(),
    val mode: TabsTrayMode = TabsTrayMode.Normal,
    val threeDotMenuOpen: Boolean = false
) : State {
    val displayedTabs: List<Tab>
        get() = tabs.filter { it.mode == mode }
}

sealed class TabsTrayAction : Action {
    data object NormalModeClicked : TabsTrayAction()
    data object PrivateModeClicked : TabsTrayAction()
    data object SyncedModeClicked : TabsTrayAction()
    sealed class ThreeDotMenuAction : TabsTrayAction() {
        data object MenuClicked : TabsTrayAction()
        data object MenuDismissed : TabsTrayAction()
        // TODO can these last actions handle their navigation through the Store?
        data object SettingsItemClicked : ThreeDotMenuAction()
        data object AiSummariesItemClicked : ThreeDotMenuAction()
    }
    // TODO how can we open a tab?
    data class TabClicked(val tab: Tab) : TabsTrayAction()
}

fun tabsTrayReducer(state: TabsTrayState, action: TabsTrayAction): TabsTrayState =
    when (action) {
        TabsTrayAction.NormalModeClicked -> state.copy(mode = TabsTrayMode.Normal)
        TabsTrayAction.PrivateModeClicked -> state.copy(mode = TabsTrayMode.Private)
        TabsTrayAction.SyncedModeClicked -> state.copy(mode = TabsTrayMode.Synced)
        TabsTrayAction.ThreeDotMenuAction.AiSummariesItemClicked -> state.copy(threeDotMenuOpen = false)
        TabsTrayAction.ThreeDotMenuAction.SettingsItemClicked -> state.copy(threeDotMenuOpen = false)
        TabsTrayAction.ThreeDotMenuAction.MenuClicked -> state.copy(threeDotMenuOpen = true)
        TabsTrayAction.ThreeDotMenuAction.MenuDismissed -> state.copy(threeDotMenuOpen = false)
        is TabsTrayAction.TabClicked -> state // No state change for now, will be handled by navigation.
    }

private enum class Destinations(val route: String) {
    Tabs("tabs"),
    AiSummaries("aiSummaries")
}

@Composable
fun TabsTrayHost(store: Store<TabsTrayState, TabsTrayAction>) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "tabs") {
        composable(Destinations.Tabs.route) {
            TabsTrayUi(store = store, navController)
        }
        composable(Destinations.AiSummaries.route) {

        }
    }
}

@Composable
fun TabsTrayUi(
    store: Store<TabsTrayState, TabsTrayAction>,
    navController: NavController,
) {
    val state by store.observeAsComposableState { it }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = { store.dispatch(TabsTrayAction.NormalModeClicked) }) {
                Text("Normal")
            }
            Button(onClick = { store.dispatch(TabsTrayAction.PrivateModeClicked) }) {
                Text("Private")
            }
            Button(onClick = { store.dispatch(TabsTrayAction.SyncedModeClicked) }) {
                Text("Synced")
            }
            Box(modifier = Modifier) {
                Button(onClick = { store.dispatch(TabsTrayAction.ThreeDotMenuAction.MenuClicked) }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                DropdownMenu(
                    expanded = state.threeDotMenuOpen,
                    onDismissRequest = { store.dispatch(TabsTrayAction.ThreeDotMenuAction.MenuDismissed) }
                ) {
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            store.dispatch(TabsTrayAction.ThreeDotMenuAction.SettingsItemClicked)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("AI mode tab summaries") },
                        onClick = {
                            // TODO is there a way we can handle this without calling the navController from the view layer?
                            navController.navigate(Destinations.AiSummaries.route)
                            store.dispatch(TabsTrayAction.ThreeDotMenuAction.AiSummariesItemClicked)
                        }
                    )
                }
            }
        }

        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.padding(8.dp)) {
            items(state.displayedTabs) { tab ->
                TabItem(tab = tab, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
fun TabItem(tab: Tab, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(ComposeColor(Color.LTGRAY)),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = tab.title, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Image(
            bitmap = tab.bitmap.asImageBitmap(),
            contentDescription = "Thumbnail for ${tab.title}",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AiSummariesUi() {
    // TODO: replace this with anything interesting. Meant to showcase local nav.
    Text("Hello AI Summaries!", color = AcornTheme.colors.textPrimary, fontSize = 24.sp)
}

// Helper function to create a bitmap with a specific color
fun createColoredBitmap(width: Int, height: Int, color: Int): Bitmap {
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    canvas.drawColor(color)
    return bitmap
}

val normalTabs = listOf(
    Tab(
        "1",
        "https://www.mozilla.org",
        "Mozilla",
        createColoredBitmap(200, 150, Color.RED),
        mode = TabsTrayMode.Normal
    ),
    Tab(
        "2",
        "https://www.google.com",
        "Google",
        createColoredBitmap(200, 150, Color.BLUE),
        mode = TabsTrayMode.Normal
    ),
    Tab(
        "3",
        "https://www.github.com",
        "GitHub",
        createColoredBitmap(200, 150, Color.GREEN),
        mode = TabsTrayMode.Normal
    ),
    Tab(
        "4",
        "https://www.bugzilla.org",
        "Bugzilla",
        createColoredBitmap(200, 150, Color.RED),
        mode = TabsTrayMode.Normal
    ),
    Tab(
        "5",
        "https://www.phabricator.com",
        "Phab",
        createColoredBitmap(200, 150, Color.BLUE),
        mode = TabsTrayMode.Normal
    ),
    Tab(
        "6",
        "https://www.slack.com",
        "Slack",
        createColoredBitmap(200, 150, Color.GREEN),
        mode = TabsTrayMode.Normal
    )
)

val privateTabs = normalTabs.map {
    it.copy(title = "Private: ${it.title}", mode = TabsTrayMode.Private)
}

val syncedTabs = normalTabs.map {
    it.copy(title = "Synced: ${it.title}", mode = TabsTrayMode.Synced)
}

val tabs = normalTabs + privateTabs + syncedTabs

@Preview
@Composable
fun TabsTrayPreview() {
    AcornTheme {
        Box(Modifier.background(AcornTheme.colors.layer1)) {
            TabsTrayHost(Store(TabsTrayState(tabs = tabs), ::tabsTrayReducer))
        }
    }
}

@Preview
@Composable
fun AiSummariesPreview() {
    AcornTheme {
        Box(Modifier.background(AcornTheme.colors.layer1)) {
            AiSummariesUi()
        }
    }
}
