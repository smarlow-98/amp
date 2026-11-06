package com.amp.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

@Composable
fun NowPlayingScreen(vm: AppViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeading("Now playing")

        if (!vm.hasMedia) {
            Text(
                "Nothing is playing. Choose something from Music, Videos or Audiobooks.",
                Modifier.padding(horizontal = 16.dp)
            )
            return@Column
        }

        if (vm.currentKind == Kind.VIDEO) VideoSurface(vm.controller)

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                vm.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            if (vm.artist.isNotBlank()) Text(vm.artist)

            PositionSlider(vm)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::previous, modifier = Modifier.weight(1f)) { Text("Previous") }
                Button(onClick = vm::playPause, modifier = Modifier.weight(1f)) {
                    Text(if (vm.isPlaying) "Pause" else "Play")
                }
                OutlinedButton(onClick = vm::next, modifier = Modifier.weight(1f)) { Text("Next") }
            }

            val isBook = vm.currentKind == Kind.AUDIOBOOK
            val skipSeconds = if (isBook) 30 else 10
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.seekBy(-skipSeconds * 1000L) }, modifier = Modifier.weight(1f)) {
                    Text("Back $skipSeconds seconds")
                }
                OutlinedButton(onClick = { vm.seekBy(skipSeconds * 1000L) }, modifier = Modifier.weight(1f)) {
                    Text("Forward $skipSeconds seconds")
                }
            }

            if (isBook) {
                Button(onClick = vm::addBookmark, modifier = Modifier.fillMaxWidth()) {
                    Text("Add bookmark")
                }
            }

            OutlinedButton(onClick = vm::cycleSpeed, modifier = Modifier.fillMaxWidth()) {
                Text("Speed: " + vm.speedLabel(vm.speed))
            }

            OutlinedButton(onClick = vm::announceTime, modifier = Modifier.fillMaxWidth()) {
                Text("Speak time")
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = vm.shuffleOn,
                        role = Role.Switch,
                        onValueChange = { vm.toggleShuffle() }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Shuffle", Modifier.weight(1f))
                Switch(checked = vm.shuffleOn, onCheckedChange = null)
            }

            OutlinedButton(onClick = vm::cycleRepeat, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Repeat: " + when (vm.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "all"
                        Player.REPEAT_MODE_ONE -> "one"
                        else -> "off"
                    }
                )
            }

            vm.currentLocalMp3?.let { uri ->
                OutlinedButton(
                    onClick = { vm.openTagEditor(uri, vm.title) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Edit tags") }
            }
        }
    }
}

@Composable
private fun PositionSlider(vm: AppViewModel) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val duration = vm.durationMs.coerceAtLeast(1L).toFloat()
    val value = (dragValue ?: vm.positionMs.toFloat()).coerceIn(0f, duration)

    Column {
        Slider(
            value = value,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { vm.seekTo(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..duration,
            enabled = vm.durationMs > 0,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Playback position"
                    stateDescription =
                        "${spokenTime(value.toLong())} of ${spokenTime(vm.durationMs)}"
                }
        )
        // Shown for sighted helpers; TalkBack already gets this from the slider.
        Text(
            "${clockTime(value.toLong())} / ${clockTime(vm.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Composable
private fun VideoSurface(player: Player?) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                contentDescription = "Video"
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    )
}
