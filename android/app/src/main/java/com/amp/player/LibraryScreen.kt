package com.amp.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun granted() = ContextCompat.checkSelfPermission(context, permissions[0]) ==
        PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = granted()
        if (!hasPermission) vm.announce("AMP needs access to your music and videos to show them.")
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && !vm.localLoaded) vm.loadLocal()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Library")

        if (!hasPermission) {
            Text(
                "AMP needs permission to find the music and videos on this phone.",
                modifier = Modifier.padding(16.dp)
            )
            Button(
                onClick = { launcher.launch(permissions) },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("Allow access to music and videos") }
            return@Column
        }

        var section by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }

        TabRow(selectedTabIndex = section) {
            Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("Music (${vm.music.size})") })
            Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("Videos (${vm.videos.size})") })
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )

        val source = if (section == 0) vm.music else vm.videos
        val filtered = remember(source, query) {
            if (query.isBlank()) source
            else source.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    it.album.contains(query, ignoreCase = true)
            }
        }

        when {
            vm.localLoading && source.isEmpty() ->
                Text("Loading your library.", Modifier.padding(16.dp))
            filtered.isEmpty() ->
                Text(
                    if (query.isBlank()) {
                        if (section == 0) "No music was found on this phone." else "No videos were found on this phone."
                    } else "Nothing matches your search.",
                    Modifier.padding(16.dp)
                )
        }

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(filtered, key = { _, t -> t.uri.toString() }) { index, t ->
                val spoken = if (t.isVideo) {
                    "${t.title}, video, ${spokenTime(t.durationMs)}"
                } else {
                    buildString {
                        append(t.title).append(", by ").append(t.artist)
                        if (t.album.isNotBlank()) append(", album ").append(t.album)
                        append(", ").append(spokenTime(t.durationMs))
                    }
                }
                val secondary = listOf(t.artist, t.album, clockTime(t.durationMs))
                    .filter { it.isNotBlank() }.joinToString("  •  ")
                MediaRow(
                    spoken = spoken,
                    primary = t.title,
                    secondary = secondary,
                    clickLabel = "play",
                    onClick = { vm.playLocal(filtered, index) },
                    longClickLabel = if (t.isMp3) "edit tags" else null,
                    onLongClick = if (t.isMp3) ({ vm.openTagEditor(t.uri, t.title) }) else null
                )
                HorizontalDivider()
            }
        }
    }
}
