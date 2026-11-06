package com.amp.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
fun TagEditorScreen(vm: AppViewModel) {
    val session = vm.tagEdit ?: return

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onWritePermissionResult(result.resultCode == Activity.RESULT_OK) }

    // Android asks you to allow AMP to change this file the first time.
    LaunchedEffect(vm.pendingWrite) {
        vm.pendingWrite?.let { writeLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    BackHandler { vm.closeTagEditor() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("Edit tags")
        Text(session.label, Modifier.padding(horizontal = 16.dp))

        val fields = session.fields
        if (fields == null) {
            Text("Reading tags.", Modifier.padding(16.dp))
            OutlinedButton(onClick = vm::closeTagEditor, modifier = Modifier.padding(16.dp)) {
                Text("Cancel")
            }
        } else {
            TagForm(
                initial = fields,
                saving = session.saving,
                onSave = vm::saveTags,
                onCancel = vm::closeTagEditor
            )
        }
    }
}

@Composable
private fun TagForm(
    initial: Id3Fields,
    saving: Boolean,
    onSave: (Id3Fields) -> Unit,
    onCancel: () -> Unit
) {
    var f by remember { mutableStateOf(initial) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TagField("Title", f.title) { f = f.copy(title = it) }
        TagField("Artist", f.artist) { f = f.copy(artist = it) }
        TagField("Album", f.album) { f = f.copy(album = it) }
        TagField("Album artist", f.albumArtist) { f = f.copy(albumArtist = it) }
        TagField("Year", f.year, KeyboardType.Number) { f = f.copy(year = it) }
        TagField("Track number", f.track) { f = f.copy(track = it) }
        TagField("Genre", f.genre, hint = "Use a standard name, like Rock, Jazz or Podcast.") {
            f = f.copy(genre = it)
        }
        TagField("Composer", f.composer) { f = f.copy(composer = it) }
        TagField("Comment", f.comment) { f = f.copy(comment = it) }

        Text("Fields left blank keep their current value.")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(f) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                Text(if (saving) "Saving" else "Save")
            }
            OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun TagField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}
