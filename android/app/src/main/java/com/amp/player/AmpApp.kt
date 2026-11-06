package com.amp.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AmpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

private data class NavTab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    NavTab("Music", Icons.Filled.Home),
    NavTab("Videos", Icons.Filled.Star),
    NavTab("Audiobooks", Icons.Filled.Favorite),
    NavTab("Now playing", Icons.Filled.PlayArrow),
    NavTab("Settings", Icons.Filled.Settings)
)

@Composable
fun AmpApp(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val view = LocalView.current

    // Speak status messages through TalkBack.
    LaunchedEffect(vm.announcementId) {
        if (vm.announcementId > 0 && vm.announcement.isNotBlank()) {
            view.announceForAccessibility(vm.announcement)
        }
    }

    val editing = vm.tagEdit != null

    Scaffold(
        bottomBar = {
            if (!editing) {
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                editing -> TagEditorScreen(vm)
                tab == 0 -> MusicScreen(vm)
                tab == 1 -> VideosScreen(vm)
                tab == 2 -> AudiobooksScreen(vm)
                tab == 3 -> NowPlayingScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

/** A screen title that TalkBack treats as a heading. */
@Composable
fun ScreenHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { heading() }
    )
}

/** A smaller heading inside a screen. */
@Composable
fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

/**
 * One list item. TalkBack reads [spoken] as a single line; double tap runs
 * [onClick], and double tap and hold runs [onLongClick] when there is one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    spoken: String,
    primary: String,
    secondary: String,
    clickLabel: String,
    onClick: () -> Unit,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = clickLabel,
                onClick = onClick,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            Text(primary, style = MaterialTheme.typography.bodyLarge)
            if (secondary.isNotBlank()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
