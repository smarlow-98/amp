package com.amp.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun PlexScreen(vm: AppViewModel) {
    BackHandler(enabled = vm.plexConnected && vm.plexStack.size > 1) { vm.plexBack() }

    Column(Modifier.fillMaxSize()) {
        when {
            !vm.plexSignedIn -> PlexSignIn(vm)
            !vm.plexConnected -> PlexServerList(vm)
            else -> PlexBrowser(vm)
        }
    }
}

@Composable
private fun PlexSignIn(vm: AppViewModel) {
    val context = LocalContext.current
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading("Plex")
        if (vm.plexWaitingForBrowser) {
            Text(
                "Finish signing in to Plex in your browser, then come back to AMP. " +
                    "This screen updates by itself.",
                Modifier.padding(horizontal = 16.dp)
            )
            OutlinedButton(onClick = vm::cancelPlexSignIn, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Cancel sign in")
            }
        } else {
            Text(
                "Sign in with your own Plex account to play music and videos from your Plex servers.",
                Modifier.padding(horizontal = 16.dp)
            )
            Button(
                onClick = {
                    vm.startPlexSignIn { url ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: ActivityNotFoundException) {
                            vm.announce("No web browser was found to sign in with.")
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("Sign in to Plex") }
        }
    }
}

@Composable
private fun PlexServerList(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Choose a Plex server")
        if (vm.plexBusy) Text("Loading.", Modifier.padding(horizontal = 16.dp))

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.plexServers) { _, server ->
                val owner = if (server.owned) "your server" else "shared with you"
                MediaRow(
                    spoken = "${server.name}, $owner",
                    primary = server.name,
                    secondary = owner,
                    clickLabel = "connect",
                    onClick = { vm.connectServer(server) }
                )
                HorizontalDivider()
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = vm::loadPlexServers, modifier = Modifier.weight(1f)) { Text("Refresh") }
            OutlinedButton(onClick = vm::plexSignOut, modifier = Modifier.weight(1f)) { Text("Sign out") }
        }
    }
}

@Composable
private fun PlexBrowser(vm: AppViewModel) {
    val page = vm.plexStack.lastOrNull()
    Column(Modifier.fillMaxSize()) {
        ScreenHeading(page?.title ?: "Plex")

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (vm.plexStack.size > 1) {
                OutlinedButton(onClick = vm::plexBack, modifier = Modifier.weight(1f)) { Text("Back") }
            }
            OutlinedButton(onClick = vm::plexChangeServer, modifier = Modifier.weight(1f)) {
                Text("Change server")
            }
        }

        if (vm.plexBusy) Text("Loading.", Modifier.padding(16.dp))
        if (page != null && page.items.isEmpty() && !vm.plexBusy) {
            Text("There's nothing here.", Modifier.padding(16.dp))
        }

        LazyColumn(Modifier.weight(1f)) {
            val items = page?.items ?: emptyList()
            itemsIndexed(items, key = { i, item -> "$i:${item.ratingKey}" }) { index, item ->
                val kind = when (item.type) {
                    "artist" -> "artist"
                    "album" -> "album"
                    "show" -> "TV show"
                    "season" -> "season"
                    "movie" -> "movie"
                    "episode" -> "episode"
                    "section" -> "library"
                    else -> ""
                }
                val spoken = listOf(
                    item.title,
                    kind,
                    item.subtitle,
                    if (item.durationMs > 0) spokenTime(item.durationMs) else ""
                ).filter { it.isNotBlank() }.joinToString(", ")
                val secondary = listOf(
                    item.subtitle,
                    if (item.durationMs > 0) clockTime(item.durationMs) else ""
                ).filter { it.isNotBlank() }.joinToString("  •  ")
                MediaRow(
                    spoken = spoken,
                    primary = item.title,
                    secondary = secondary,
                    clickLabel = if (item.isPlayable) "play" else "open",
                    onClick = { vm.openPlexItem(index) }
                )
                HorizontalDivider()
            }
        }
    }
}
