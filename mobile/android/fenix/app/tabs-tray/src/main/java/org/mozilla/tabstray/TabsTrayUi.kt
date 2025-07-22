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
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import mozilla.components.compose.base.theme.AcornTheme
import kotlin.collections.listOf

sealed class TabsTrayMode {
    object Normal : TabsTrayMode()
    object Private : TabsTrayMode()
    object Synced : TabsTrayMode()
}

data class TabsTrayState(
    val tabs: List<Tab> = emptyList(),
    val mode: TabsTrayMode = TabsTrayMode.Normal,
)

data class Tab(
    val id: String,
    val url: String,
    val title: String,
    val bitmap: Bitmap,
    val mode: TabsTrayMode = TabsTrayMode.Normal
)

@Composable
fun TabsTrayUi(tabs: List<Tab> = emptyList()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = { /*TODO*/ }) {
                Text("Normal")
            }
            Button(onClick = { /*TODO*/ }) {
                Text("Private")
            }
            Button(onClick = { /*TODO*/ }) {
                Text("Synced")
            }
            var showMenu by remember { mutableStateOf(false) }
            Box(modifier = Modifier) {
                Button(onClick = { showMenu = !showMenu }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { /* Handle settings click */ showMenu = false })
                }
            }
        }

        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.padding(8.dp)) {
            items(tabs) { tab ->
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

@Preview
@Composable
fun TabsTrayPreview() {
    // Helper function to create a bitmap with a specific color
    fun createColoredBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        return bitmap
    }

    val tabs = listOf(
        Tab(
            "1",
            "https://www.mozilla.org",
            "Mozilla",
            createColoredBitmap(200, 150, Color.RED)
        ),
        Tab(
            "2",
            "https://www.google.com",
            "Google",
            createColoredBitmap(200, 150, Color.BLUE)
        ),
        Tab(
            "3",
            "https://www.github.com",
            "GitHub",
            createColoredBitmap(200, 150, Color.GREEN)
        ),
        Tab(
            "4",
            "https://www.bugzilla.org",
            "Bugzilla",
            createColoredBitmap(200, 150, Color.RED)
        ),
        Tab(
            "5",
            "https://www.phabricator.com",
            "Phab",
            createColoredBitmap(200, 150, Color.BLUE)
        ),
        Tab(
            "6",
            "https://www.slack.com",
            "Slack",
            createColoredBitmap(200, 150, Color.GREEN)
        )
    )

    AcornTheme {
        Box(Modifier.background(ComposeColor(Color.LTGRAY))) {
            TabsTrayUi(tabs)
        }
    }
}
