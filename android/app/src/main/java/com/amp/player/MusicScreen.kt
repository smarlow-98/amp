package com.amp.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val sourceLabels = listOf("All", "On this phone", "Plex")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(vm: AppViewModel) {
    val perm = rememberMediaPermission(vm)
    var source by rememberSaveable { mutableIntStateOf(0) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var openArtist by rememberSaveable { mutableStateOf<String?>(null) }

    val songs = remember(vm.localSongs, vm.plexSongs, source) {
        when (source) {
            1 -> vm.localSongs
            2 -> vm.plexSongs
            else -> vm.localSongs + vm.plexSongs
        }.sortedBy { it.title.lowercase() }
    }
    val albums = remember(songs) { groupAlbums(songs) }
    val artists = remember(songs) { groupArtists(songs) }

    val album = openAlbum?.let { k -> albums.find { it.key == k } }
    val artist = openArtist?.let { k -> artists.find { it.key == k } }

    BackHandler(enabled = album != null || artist != null) {
        openAlbum = null
        openArtist = null
    }

    if (album != null) {
        SongListPage(vm, album.title, "by ${album.artist}, ${album.songs.size} songs", album.songs) { openAlbum = null }
        return
    }
    if (artist != null) {
        SongListPage(vm, artist.name, "${artist.songs.size} songs", artist.songs) { openArtist = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Music")
        PermissionPrompt(perm)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sourceLabels.forEachIndexed { i, label ->
                FilterChip(
                    selected = source == i,
                    onClick = { source = i },
                    label = { Text(label) }
                )
            }
        }

        TabRow(selectedTabIndex = section) {
            Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("Songs") })
            Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("Albums") })
            Tab(selected = section == 2, onClick = { section = 2 }, text = { Text("Artists") })
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (source == 2 && !vm.plexConnected) {
            Text("Sign in to Plex on the Settings tab to see your Plex music.", Modifier.padding(16.dp))
        }
        if (vm.plexLoading && source != 1) {
            Text("Loading your Plex library. This can take a minute for big libraries.", Modifier.padding(horizontal = 16.dp))
        } else if (vm.localLoading && source != 2) {
            Text("Loading music on this phone.", Modifier.padding(horizontal = 16.dp))
        }

        val q = query.trim()
        when (section) {
            0 -> {
                val list = remember(songs, q) {
                    if (q.isEmpty()) songs else songs.filter {
                        it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
                    }
                }
                if (list.isEmpty() && !vm.localLoading && !vm.plexLoading) {
                    Text(if (q.isEmpty()) "No songs found." else "Nothing matches your search.", Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (list.isNotEmpty()) {
                        item(key = "actions") { PlayShuffleRow(vm, list, "${list.size} songs") }
                    }
                    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                        SongRow(vm, s) { vm.playSongs(list, i) }
                    }
                }
            }
            1 -> {
                val list = remember(albums, q) {
                    if (q.isEmpty()) albums else albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
                }
                if (list.isEmpty() && !vm.localLoading && !vm.plexLoading) {
                    Text(if (q.isEmpty()) "No albums found." else "Nothing matches your search.", Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(list, key = { _, a -> a.key }) { _, a ->
                        val count = if (a.songs.size == 1) "1 song" else "${a.songs.size} songs"
                        MediaRow(
                            spoken = "${a.title}, album by ${a.artist}, $count",
                            primary = a.title,
                            secondary = "${a.artist}  •  $count",
                            clickLabel = "open",
                            onClick = { openAlbum = a.key },
                            longClickLabel = "play album",
                            onLongClick = { vm.playSongs(a.songs, 0) }
                        )
                        HorizontalDivider()
                    }
                }
            }
            else -> {
                val list = remember(artists, q) {
                    if (q.isEmpty()) artists else artists.filter { it.name.contains(q, true) }
                }
                if (list.isEmpty() && !vm.localLoading && !vm.plexLoading) {
                    Text(if (q.isEmpty()) "No artists found." else "Nothing matches your search.", Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(list, key = { _, a -> a.key }) { _, a ->
                        val count = if (a.songs.size == 1) "1 song" else "${a.songs.size} songs"
                        MediaRow(
                            spoken = "${a.name}, artist, $count",
                            primary = a.name,
                            secondary = count,
                            clickLabel = "open",
                            onClick = { openArtist = a.key },
                            longClickLabel = "play all songs",
                            onLongClick = { vm.playSongs(a.songs, 0) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayShuffleRow(vm: AppViewModel, songs: List<Song>, label: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.playSongs(songs, 0) }, modifier = Modifier.weight(1f)) { Text("Play all") }
            OutlinedButton(onClick = { vm.shuffleSongs(songs) }, modifier = Modifier.weight(1f)) { Text("Shuffle") }
        }
    }
}

@Composable
private fun SongListPage(vm: AppViewModel, title: String, subtitle: String, songs: List<Song>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeading(title)
        Text(subtitle, Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Back")
        }
        LazyColumn(Modifier.weight(1f)) {
            item(key = "actions") { PlayShuffleRow(vm, songs, "") }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                SongRow(vm, s) { vm.playSongs(songs, i) }
            }
        }
    }
}

@Composable
private fun SongRow(vm: AppViewModel, s: Song, onPlay: () -> Unit) {
    val fromPlex = s.source == Source.PLEX
    val spoken = buildString {
        append(s.title).append(", by ").append(s.artist)
        if (s.album.isNotBlank()) append(", album ").append(s.album)
        if (s.durationMs > 0) append(", ").append(spokenTime(s.durationMs))
        if (fromPlex) append(", from Plex")
    }
    val secondary = listOf(
        s.artist,
        s.album,
        if (s.durationMs > 0) clockTime(s.durationMs) else "",
        if (fromPlex) "Plex" else ""
    ).filter { it.isNotBlank() }.joinToString("  •  ")
    val uri = s.uri
    val canEdit = !fromPlex && s.isMp3 && uri != null
    MediaRow(
        spoken = spoken,
        primary = s.title,
        secondary = secondary,
        clickLabel = "play",
        onClick = onPlay,
        longClickLabel = if (canEdit) "edit tags" else null,
        onLongClick = if (canEdit && uri != null) ({ vm.openTagEditor(uri, s.title) }) else null
    )
    HorizontalDivider()
}
