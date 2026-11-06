package com.amp.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
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
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Library") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        label = { Text("Plex") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("Now playing") }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                editing -> TagEditorScreen(vm)
                tab == 0 -> LibraryScreen(vm)
                tab == 1 -> PlexScreen(vm)
                else -> NowPlayingScreen(vm)
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
