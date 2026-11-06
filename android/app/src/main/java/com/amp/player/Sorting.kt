package com.amp.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Sort options, like iTunes.

enum class SongSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENT("Recently added"),
    YEAR("Year, newest first"),
    LENGTH("Length, shortest first")
}

enum class AlbumSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    YEAR("Year, newest first"),
    RECENT("Recently added")
}

enum class ArtistSort(val label: String) {
    NAME("Name"),
    SONGS("Most songs")
}

enum class MovieSort(val label: String) {
    TITLE("Title"),
    YEAR("Year, newest first"),
    RECENT("Recently added")
}

inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

private val albumOrder = compareBy<Song>({ it.album.lowercase() }, { it.disc }, { it.track }, { it.title.lowercase() })

fun sortSongs(list: List<Song>, sort: SongSort): List<Song> = when (sort) {
    SongSort.TITLE -> list.sortedBy { it.title.lowercase() }
    SongSort.ARTIST -> list.sortedWith(compareBy<Song> { it.artist.lowercase() }.then(albumOrder))
    SongSort.ALBUM -> list.sortedWith(albumOrder)
    SongSort.RECENT -> list.sortedWith(compareByDescending<Song> { it.addedAt }.then(albumOrder))
    SongSort.YEAR -> list.sortedWith(compareByDescending<Song> { it.year }.then(albumOrder))
    SongSort.LENGTH -> list.sortedWith(compareBy<Song> { it.durationMs }.thenBy { it.title.lowercase() })
}

fun sortAlbums(list: List<Album>, sort: AlbumSort): List<Album> = when (sort) {
    AlbumSort.TITLE -> list.sortedBy { it.title.lowercase() }
    AlbumSort.ARTIST -> list.sortedWith(compareBy<Album>({ it.artist.lowercase() }, { it.title.lowercase() }))
    AlbumSort.YEAR -> list.sortedWith(compareByDescending<Album> { it.year }.thenBy { it.title.lowercase() })
    AlbumSort.RECENT -> list.sortedWith(compareByDescending<Album> { it.addedAt }.thenBy { it.title.lowercase() })
}

fun sortArtists(list: List<ArtistGroup>, sort: ArtistSort): List<ArtistGroup> = when (sort) {
    ArtistSort.NAME -> list.sortedBy { it.name.lowercase() }
    ArtistSort.SONGS -> list.sortedWith(compareByDescending<ArtistGroup> { it.songs.size }.thenBy { it.name.lowercase() })
}

fun sortMovies(list: List<PlexItem>, sort: MovieSort): List<PlexItem> = when (sort) {
    MovieSort.TITLE -> list.sortedBy { it.title.lowercase() }
    MovieSort.YEAR -> list.sortedWith(compareByDescending<PlexItem> { it.year.toIntOrNull() ?: 0 }.thenBy { it.title.lowercase() })
    MovieSort.RECENT -> list.sortedWith(compareByDescending<PlexItem> { it.addedAt }.thenBy { it.title.lowercase() })
}

/**
 * A "Sort by" button that opens a menu. TalkBack reads it as
 * "Sort by: Title, button"; the chosen option in the menu is marked selected.
 */
@Composable
fun <T> SortMenu(options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = { open = true }) { Text("Sort by: ${label(current)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                val isCurrent = option == current
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                    leadingIcon = if (isCurrent) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    modifier = Modifier.semantics { selected = isCurrent }
                )
            }
        }
    }
}
