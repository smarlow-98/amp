package com.amp.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)
    ) {
        ScreenHeading("Settings")
        SectionHeading("Plex")
        if (!vm.plexSignedIn) PlexSignIn(vm) else PlexServers(vm)
    }
}

@Composable
private fun PlexSignIn(vm: AppViewModel) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.plexWaitingForBrowser) {
            Text(
                "Finish signing in to Plex in your browser, then come back to AMP. " +
                    "This screen updates by itself."
            )
            OutlinedButton(onClick = vm::cancelPlexSignIn) { Text("Cancel sign in") }
        } else {
            Text("Sign in with your own Plex account to play music, movies and TV from your Plex servers.")
            Button(
                onClick = {
                    vm.startPlexSignIn { url ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: ActivityNotFoundException) {
                            vm.announce("No web browser was found to sign in with.")
                        }
                    }
                }
            ) { Text("Sign in to Plex") }
        }
    }
}

@Composable
private fun PlexServers(vm: AppViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val on = vm.connectedServers
        Text(
            when {
                on.isEmpty() -> "No servers are on. Turn on a server below."
                else -> "Using ${on.joinToString(" and ") { it.name }}."
            }
        )
        Text(
            if (vm.plexLoading) "Loading your Plex library."
            else "${vm.plexSongs.size} songs, ${vm.plexMovies.size} movies, ${vm.plexShows.size} TV shows."
        )

        SectionHeading("Servers")
        Text(
            "Turn on every server you want to use. Music and Videos combine the libraries " +
                "from all servers that are on."
        )
        if (vm.plexBusy && vm.plexServers.isEmpty()) Text("Loading your servers.")
        vm.plexServers.forEach { server ->
            val isOn = vm.isServerOn(server)
            val owner = if (server.owned) "your server" else "shared with you"
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isOn,
                        role = Role.Switch,
                        enabled = !vm.plexBusy,
                        onValueChange = { vm.toggleServer(server) }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${server.name}, $owner", Modifier.weight(1f))
                Switch(checked = isOn, onCheckedChange = null, enabled = !vm.plexBusy)
            }
        }

        Button(
            onClick = vm::loadPlexLibrary,
            enabled = !vm.plexLoading && on.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reload Plex library") }
        OutlinedButton(onClick = { vm.loadPlexServers() }, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh server list")
        }
        OutlinedButton(onClick = vm::plexSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Plex") }
    }
}
