package com.amp.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Settings")
        SectionHeading("Plex")
        when {
            !vm.plexSignedIn -> PlexSignIn(vm)
            !vm.plexConnected -> PlexServerList(vm)
            else -> PlexConnected(vm)
        }
    }
}

@Composable
private fun PlexSignIn(vm: AppViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                "Sign in with your own Plex account to play music, movies and TV from your Plex servers.",
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
private fun ColumnScope.PlexServerList(vm: AppViewModel) {
    Text("Choose a Plex server.", Modifier.padding(horizontal = 16.dp))
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

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = vm::loadPlexServers, modifier = Modifier.fillMaxWidth()) { Text("Refresh server list") }
        OutlinedButton(onClick = vm::plexSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Plex") }
    }
}

@Composable
private fun PlexConnected(vm: AppViewModel) {
    Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Connected to ${vm.plexServerName}.")
        Text(
            if (vm.plexLoading) "Loading your Plex library."
            else "${vm.plexSongs.size} songs, ${vm.plexMovies.size} movies, ${vm.plexShows.size} TV shows."
        )
        Button(
            onClick = vm::loadPlexLibrary,
            enabled = !vm.plexLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reload Plex library") }
        OutlinedButton(onClick = vm::plexChangeServer, modifier = Modifier.fillMaxWidth()) { Text("Change server") }
        OutlinedButton(onClick = vm::plexSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Plex") }
    }
}
