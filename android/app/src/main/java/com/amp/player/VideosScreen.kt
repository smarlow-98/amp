package com.amp.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val videoTabs = listOf("Continue watching", "Movies", "TV shows", "On this phone")

@Composable
fun VideosScreen(vm: AppViewModel) {
    val perm = rememberMediaPermission(vm)
    var section by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = section == 2 && vm.tvStack.isNotEmpty()) { vm.tvBack() }

    LaunchedEffect(section, vm.plexConnected, vm.localLoaded, vm.progressVersion) {
        if (section == 0) vm.refreshContinueWatching()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Videos")
        ScrollableTabRow(selectedTabIndex = section, edgePadding = 16.dp) {
            videoTabs.forEachIndexed { i, label ->
                Tab(selected = section == i, onClick = { section = i }, text = { Text(label) })
            }
        }
        // Keeps your place in each list while you switch tabs or open a show.
        val holder = rememberSaveableStateHolder()
        val tvKey = "${vm.tvStack.size}:${vm.tvStack.lastOrNull()?.title ?: ""}"
        holder.SaveableStateProvider(if (section == 2) "tv:$tvKey" else "section:$section") {
            when (section) {
                0 -> ContinueList(vm)
                1 -> MovieList(vm)
                2 -> TvBrowser(vm)
                else -> {
                    PermissionPrompt(perm)
                    PhoneVideoList(vm)
                }
            }
        }
    }
}

@Composable
private fun PlexNeeded(what: String) {
    Text("Sign in to Plex on the Settings tab to see your $what.", Modifier.padding(16.dp))
}

@Composable
private fun ColumnScope.ContinueList(vm: AppViewModel) {
    val list = vm.continueWatching
    if (list.isEmpty()) {
        Text(
            if (vm.continueLoading) "Loading." else "Nothing to continue yet. Movies and shows you start will show up here.",
            Modifier.padding(16.dp)
        )
    }
    LazyColumn(Modifier.weight(1f)) {
        itemsIndexed(list, key = { _, e -> e.key }) { _, e ->
            val started = e.positionMs > 0 && e.durationMs > 0
            val status = if (started) "${spokenLength(e.durationMs - e.positionMs)} left" else "up next"
            MediaRow(
                spoken = "${e.title}, ${e.subtitle}, $status",
                primary = e.title,
                secondary = listOf(
                    e.subtitle,
                    if (started) "${clockTime(e.positionMs)} / ${clockTime(e.durationMs)}" else "Up next"
                ).filter { it.isNotBlank() }.joinToString("  •  "),
                clickLabel = if (started) "resume" else "play",
                onClick = { vm.playContinue(e, false) },
                longClickLabel = "play from the start",
                onLongClick = { vm.playContinue(e, true) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ColumnScope.MovieList(vm: AppViewModel) {
    if (!vm.plexConnected) {
        PlexNeeded("movies")
        return
    }
    if (vm.plexLoading && vm.plexMovies.isEmpty()) Text("Loading your movies.", Modifier.padding(16.dp))
    else if (vm.plexMovies.isEmpty()) Text("No movies were found on your Plex servers.", Modifier.padding(16.dp))
    SortMenu(MovieSort.entries, vm.movieSort, { it.label }, vm::changeMovieSort)
    val movies = remember(vm.plexMovies, vm.movieSort) { sortMovies(vm.plexMovies, vm.movieSort) }
    LazyColumn(Modifier.weight(1f)) {
        itemsIndexed(movies, key = { _, m -> m.uid }) { _, m ->
            PlexVideoRow(m, kind = "movie", extra = m.year, onPlay = { vm.playPlexVideo(m, it) })
        }
    }
}

@Composable
private fun ColumnScope.TvBrowser(vm: AppViewModel) {
    if (!vm.plexConnected) {
        PlexNeeded("TV shows")
        return
    }
    val page = vm.tvStack.lastOrNull()
    if (page == null) {
        if (vm.plexLoading && vm.plexShows.isEmpty()) Text("Loading your TV shows.", Modifier.padding(16.dp))
        else if (vm.plexShows.isEmpty()) Text("No TV shows were found on your Plex server.", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.plexShows, key = { i, s -> "$i:${s.ratingKey}" }) { _, show ->
                MediaRow(
                    spoken = listOf(show.title, "TV show", show.year).filter { it.isNotBlank() }.joinToString(", "),
                    primary = show.title,
                    secondary = show.year,
                    clickLabel = "open",
                    onClick = { vm.openShow(show) }
                )
                HorizontalDivider()
            }
        }
        return
    }

    Text(
        page.title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }
    )
    OutlinedButton(onClick = vm::tvBack, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Back") }
    if (vm.plexBusy) Text("Loading.", Modifier.padding(16.dp))
    LazyColumn(Modifier.weight(1f)) {
        itemsIndexed(page.items, key = { i, item -> "$i:${item.ratingKey}" }) { index, item ->
            if (item.isPlayable) {
                val number = if (item.type == "episode" && item.index > 0) "episode ${item.index}" else ""
                PlexVideoRow(item, kind = "", extra = number, onPlay = { vm.openTvItem(index, it) })
            } else {
                MediaRow(
                    spoken = listOf(item.title, if (item.type == "season") "season" else "").filter { it.isNotBlank() }.joinToString(", "),
                    primary = item.title,
                    secondary = "",
                    clickLabel = "open",
                    onClick = { vm.openTvItem(index, false) }
                )
                HorizontalDivider()
            }
        }
    }
}

/** A movie or episode row. Double tap resumes; double tap and hold starts over. */
@Composable
private fun PlexVideoRow(item: PlexItem, kind: String, extra: String, onPlay: (fromStart: Boolean) -> Unit) {
    val status = plexWatchStatus(item)
    val length = if (item.durationMs > 0) spokenLength(item.durationMs) else ""
    MediaRow(
        spoken = listOf(item.title, kind, extra, length, status).filter { it.isNotBlank() }.joinToString(", "),
        primary = item.title,
        secondary = listOf(extra, if (item.durationMs > 0) clockTime(item.durationMs) else "", status)
            .filter { it.isNotBlank() }.joinToString("  •  "),
        clickLabel = if (item.viewOffsetMs > 0) "resume" else "play",
        onClick = { onPlay(false) },
        longClickLabel = "play from the start",
        onLongClick = { onPlay(true) }
    )
    HorizontalDivider()
}

@Composable
private fun ColumnScope.PhoneVideoList(vm: AppViewModel) {
    val list = vm.localVideos
    if (list.isEmpty()) {
        Text(if (vm.localLoading) "Loading." else "No videos were found on this phone.", Modifier.padding(16.dp))
    }
    val version = vm.progressVersion
    LazyColumn(Modifier.weight(1f)) {
        itemsIndexed(list, key = { _, v -> v.uri.toString() }) { _, v ->
            val r = if (version >= 0) vm.localVideoResume(v) else null
            val status = if (r != null && r.positionMs > 0) "${spokenLength((r.durationMs.takeIf { it > 0 } ?: v.durationMs) - r.positionMs)} left" else ""
            MediaRow(
                spoken = listOf(v.title, "video", spokenLength(v.durationMs), status).filter { it.isNotBlank() }.joinToString(", "),
                primary = v.title,
                secondary = listOf(clockTime(v.durationMs), status).filter { it.isNotBlank() }.joinToString("  •  "),
                clickLabel = if (r != null) "resume" else "play",
                onClick = { vm.playLocalVideo(v, false) },
                longClickLabel = "play from the start",
                onLongClick = { vm.playLocalVideo(v, true) }
            )
            HorizontalDivider()
        }
    }
}
