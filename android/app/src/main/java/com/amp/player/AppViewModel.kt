package com.amp.player

import android.app.Application
import android.content.ComponentName
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TagEditSession(
    val uri: Uri,
    val label: String,
    val fields: Id3Fields? = null,
    val saving: Boolean = false
)

data class PlexPage(val title: String, val items: List<PlexItem>)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application get() = getApplication()

    // ---------- Spoken announcements (read by TalkBack) ----------

    var announcement by mutableStateOf("")
        private set
    var announcementId by mutableIntStateOf(0)
        private set

    fun announce(message: String) {
        announcement = message
        announcementId++
    }

    // ---------- Player ----------

    var controller by mutableStateOf<MediaController?>(null)
        private set
    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    var hasMedia by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var title by mutableStateOf("Nothing playing")
        private set
    var artist by mutableStateOf("")
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var shuffleOn by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var currentIsVideo by mutableStateOf(false)
        private set
    var currentLocalMp3 by mutableStateOf<Uri?>(null)
        private set

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            val reason = when (error.errorCode) {
                in 2000..2999 -> "The file or stream could not be loaded."
                in 3000..3999 -> "The file could not be read. It may be damaged."
                in 4000..4999 -> "This format could not be decoded on this phone."
                else -> "Something went wrong."
            }
            announce("Playback error. $reason")
        }
    }

    // ---------- Local library ----------

    var music by mutableStateOf<List<LocalTrack>>(emptyList())
        private set
    var videos by mutableStateOf<List<LocalTrack>>(emptyList())
        private set
    var localLoading by mutableStateOf(false)
        private set
    var localLoaded by mutableStateOf(false)
        private set

    // ---------- Tag editor ----------

    private val tagEditor = TagEditor(app)
    var tagEdit by mutableStateOf<TagEditSession?>(null)
        private set
    var pendingWrite by mutableStateOf<IntentSender?>(null)
        private set
    private var pendingFile: File? = null
    private var pendingWarning: String? = null

    // ---------- Plex ----------

    private val plex = PlexClient(app)
    var plexSignedIn by mutableStateOf(plex.accountToken != null)
        private set
    var plexWaitingForBrowser by mutableStateOf(false)
        private set
    var plexServers by mutableStateOf<List<PlexServer>>(emptyList())
        private set
    var plexConnected by mutableStateOf(false)
        private set
    var plexStack by mutableStateOf<List<PlexPage>>(emptyList())
        private set
    var plexBusy by mutableStateOf(false)
        private set
    private var signInJob: Job? = null

    init {
        controllerFuture.addListener({
            try {
                val c = controllerFuture.get()
                c.addListener(playerListener)
                controller = c
                refresh()
            } catch (e: Exception) {
                announce("The player could not start.")
            }
        }, ContextCompat.getMainExecutor(app))

        // Update the position once a second. Kept slow on purpose so the
        // screen stays smooth and TalkBack isn't flooded.
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val c = controller
                if (c != null && c.isPlaying) positionMs = c.currentPosition.coerceAtLeast(0)
            }
        }

        if (plexSignedIn) restorePlex()
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }

    private fun refresh() {
        val c = controller ?: return
        hasMedia = c.mediaItemCount > 0
        isPlaying = c.isPlaying
        val item = c.currentMediaItem
        title = c.mediaMetadata.title?.toString()
            ?: item?.mediaMetadata?.title?.toString()
            ?: "Nothing playing"
        artist = c.mediaMetadata.artist?.toString() ?: ""
        durationMs = c.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        positionMs = c.currentPosition.coerceAtLeast(0)
        shuffleOn = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        val extras = item?.mediaMetadata?.extras
        currentIsVideo = extras?.getBoolean(EXTRA_IS_VIDEO) == true
        currentLocalMp3 = extras?.getString(EXTRA_LOCAL_MP3)?.let(Uri::parse)
    }

    fun playQueue(items: List<MediaItem>, startIndex: Int) {
        val c = controller
        if (c == null) {
            announce("The player is still starting. Try again in a moment.")
            return
        }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = (c.currentPosition + deltaMs).coerceIn(0L, max)
        c.seekTo(target)
        positionMs = target
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        positionMs = ms
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        announce(
            when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> "Repeat all"
                Player.REPEAT_MODE_ONE -> "Repeat one"
                else -> "Repeat off"
            }
        )
    }

    fun announceTime() {
        val c = controller ?: return
        val pos = c.currentPosition.coerceAtLeast(0)
        val dur = durationMs
        announce(
            if (dur > 0) "${spokenTime(pos)} of ${spokenTime(dur)}. ${spokenTime(dur - pos)} left."
            else spokenTime(pos)
        )
    }

    // ---------- Local library ----------

    fun loadLocal() {
        viewModelScope.launch {
            localLoading = true
            val (m, v) = withContext(Dispatchers.IO) {
                LocalLibrary.loadAudio(appContext) to LocalLibrary.loadVideo(appContext)
            }
            music = m
            videos = v
            localLoading = false
            localLoaded = true
        }
    }

    fun playLocal(list: List<LocalTrack>, index: Int) {
        playQueue(list.map { it.toMediaItem() }, index)
    }

    // ---------- Tag editor ----------

    fun openTagEditor(uri: Uri, label: String) {
        tagEdit = TagEditSession(uri, label)
        viewModelScope.launch {
            try {
                val fields = tagEditor.read(uri)
                tagEdit = tagEdit?.copy(fields = fields)
                announce("Tags loaded.")
            } catch (e: Exception) {
                tagEdit = null
                announce("Could not read the tags. ${e.message ?: ""}")
            }
        }
    }

    fun closeTagEditor() {
        tagEdit = null
        pendingWrite = null
        pendingFile = null
        tagEditor.cleanup()
    }

    fun saveTags(fields: Id3Fields) {
        val session = tagEdit ?: return
        tagEdit = session.copy(saving = true)
        viewModelScope.launch {
            try {
                // Don't write to a file while it's being played.
                if (currentLocalMp3 == session.uri) controller?.pause()
                val edited = tagEditor.buildEditedFile(session.uri, fields)
                if (tagEditor.tryWriteBack(session.uri, edited.file)) {
                    finishSaved(edited.warning)
                } else {
                    pendingFile = edited.file
                    pendingWarning = edited.warning
                    pendingWrite = MediaStore.createWriteRequest(
                        appContext.contentResolver, listOf(session.uri)
                    ).intentSender
                }
            } catch (e: Exception) {
                tagEdit = tagEdit?.copy(saving = false)
                announce("Could not save the tags. ${e.message ?: ""}")
            }
        }
    }

    fun onWritePermissionResult(granted: Boolean) {
        pendingWrite = null
        val file = pendingFile
        val session = tagEdit
        if (!granted || file == null || session == null) {
            tagEdit = session?.copy(saving = false)
            announce("Tags were not saved, because permission was not given.")
            return
        }
        viewModelScope.launch {
            try {
                tagEditor.writeBack(session.uri, file)
                finishSaved(pendingWarning)
            } catch (e: Exception) {
                tagEdit = tagEdit?.copy(saving = false)
                announce("Could not save the tags. ${e.message ?: ""}")
            }
        }
    }

    private fun finishSaved(warning: String?) {
        announce(listOfNotNull("Tags saved.", warning).joinToString(" "))
        closeTagEditor()
        loadLocal()
    }

    // ---------- Plex ----------

    fun startPlexSignIn(openUrl: (String) -> Unit) {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            try {
                val pin = plex.createPin()
                plexWaitingForBrowser = true
                openUrl(plex.authUrl(pin))
                val deadline = System.currentTimeMillis() + 10 * 60_000
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(2000)
                    val token = try { plex.checkPin(pin) } catch (e: Exception) { null }
                    if (token != null) {
                        plex.accountToken = token
                        plexSignedIn = true
                        plexWaitingForBrowser = false
                        announce("Signed in to Plex.")
                        loadPlexServers()
                        return@launch
                    }
                }
                plexWaitingForBrowser = false
                announce("Plex sign in timed out. Please try again.")
            } catch (e: Exception) {
                plexWaitingForBrowser = false
                announce("Could not start Plex sign in. ${e.message ?: ""}")
            }
        }
    }

    fun cancelPlexSignIn() {
        signInJob?.cancel()
        plexWaitingForBrowser = false
        announce("Sign in cancelled.")
    }

    fun loadPlexServers() {
        viewModelScope.launch {
            plexBusy = true
            try {
                val list = plex.servers()
                plexServers = list
                when (list.size) {
                    0 -> announce("No Plex servers were found on your account.")
                    1 -> connectServer(list[0])
                    else -> announce("${list.size} Plex servers found.")
                }
            } catch (e: Exception) {
                announce("Could not load your Plex servers. ${e.message ?: ""}")
            } finally {
                plexBusy = false
            }
        }
    }

    fun connectServer(server: PlexServer) {
        viewModelScope.launch {
            plexBusy = true
            announce("Connecting to ${server.name}.")
            try {
                plex.connect(server)
                val items = plex.sections()
                plexStack = listOf(PlexPage(server.name, items))
                plexConnected = true
                announce("Connected to ${server.name}. ${items.size} libraries.")
            } catch (e: Exception) {
                announce(e.message ?: "Could not connect to ${server.name}.")
            } finally {
                plexBusy = false
            }
        }
    }

    private fun restorePlex() {
        if (!plex.hasSavedServer) {
            loadPlexServers()
            return
        }
        viewModelScope.launch {
            plexBusy = true
            try {
                val items = plex.sections()
                plexStack = listOf(PlexPage(plex.savedServerName.ifBlank { "Plex" }, items))
                plexConnected = true
            } catch (e: Exception) {
                plex.forgetServer()
                loadPlexServers()
            } finally {
                plexBusy = false
            }
        }
    }

    fun openPlexItem(index: Int) {
        val page = plexStack.lastOrNull() ?: return
        val item = page.items.getOrNull(index) ?: return
        if (item.isPlayable) {
            val playable = page.items.filter { it.isPlayable }
            playQueue(playable.map { plex.toMediaItem(it) }, playable.indexOf(item).coerceAtLeast(0))
            return
        }
        val path = item.browsePath ?: return
        viewModelScope.launch {
            plexBusy = true
            try {
                val items = plex.browse(path)
                plexStack = plexStack + PlexPage(item.title, items)
                announce("${item.title}. ${items.size} items.")
            } catch (e: Exception) {
                announce("Could not open ${item.title}. ${e.message ?: ""}")
            } finally {
                plexBusy = false
            }
        }
    }

    fun plexBack() {
        if (plexStack.size > 1) {
            plexStack = plexStack.dropLast(1)
            announce(plexStack.last().title)
        }
    }

    fun plexChangeServer() {
        plex.forgetServer()
        plexConnected = false
        plexStack = emptyList()
        loadPlexServers()
    }

    fun plexSignOut() {
        signInJob?.cancel()
        plex.signOut()
        plexSignedIn = false
        plexConnected = false
        plexServers = emptyList()
        plexStack = emptyList()
        announce("Signed out of Plex.")
    }
}
