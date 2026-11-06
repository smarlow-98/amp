package com.amp.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MediaPermissionState(val granted: Boolean, val request: () -> Unit)

/** Asks for access to music and videos on the phone, and loads them once allowed. */
@Composable
fun rememberMediaPermission(vm: AppViewModel): MediaPermissionState {
    val context = LocalContext.current
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun check() = ContextCompat.checkSelfPermission(context, permissions[0]) ==
        PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(check()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = check()
        if (granted) vm.loadLocal(true)
        else vm.announce("AMP needs access to your music and videos to show the ones on this phone.")
    }

    LaunchedEffect(granted) {
        if (!vm.localLoaded) vm.loadLocal(granted)
    }
    return MediaPermissionState(granted) { launcher.launch(permissions) }
}

@Composable
fun PermissionPrompt(state: MediaPermissionState) {
    if (state.granted) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("AMP needs permission to find the music and videos on this phone.")
        Button(onClick = state.request) { Text("Allow access to music and videos") }
    }
}
