package com.amp.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.CancellationException
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

/** A chapter of an audiobook: a separate file ([part]), or a marker inside one file. */
data class BookChapter(val title: String, val part: Int, val startMs: Long)

/** One row in Continue watching, from this phone or from Plex. */
data class ContinueEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastViewed: Long,
    val local: LocalVideo? = null,
    val plex: PlexItem? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application get() = getApplication()
    private val store = AppStore(app)

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
    var speed by mutableFloatStateOf(1f)
        private set
    var currentKind by mutableStateOf(Kind.MUSIC)
        private set
    var currentLocalMp3 by mutableStateOf<Uri?>(null)
        private set
    var currentBookId by mutableStateOf<String?>(null)
        private set
    var currentPart by mutableIntStateOf(0)
        private set
    private var currentPlexKey: String? = null
    private var currentPlexServer: String? = null
    private var currentResumeId: String? = null
    private var wasPlaying = false
    private var lastState = Player.STATE_IDLE

    /** Goes up whenever resume points change, so lists can refresh. */
    var progressVersion by mutableIntStateOf(0)
        private set
    var bookmarkVersion by mutableIntStateOf(0)
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

    // ---------- Library on this phone ----------

    var localSongs by mutableStateOf<List<Song>>(emptyList())
        private set
    var localVideos by mutableStateOf<List<LocalVideo>>(emptyList())
        private set
    var books by mutableStateOf<List<Book>>(emptyList())
        private set
    var localLoading by mutableStateOf(false)
        private set
    var localLoaded by mutableStateOf(false)
        private set
    private var hasMediaPermission = false

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
    /** The servers AMP is using right now. You can use several at once. */
    var connectedServers by mutableStateOf(plex.connected)
        private set
    var plexConnected by mutableStateOf(plex.connected.isNotEmpty())
        private set
    var plexBusy by mutableStateOf(false)
        private set
    var plexLoading by mutableStateOf(false)
        private set
    var plexSongs by mutableStateOf<List<Song>>(emptyList())
        private set
    var plexMovies by mutableStateOf<List<PlexItem>>(emptyList())
        private set
    var plexShows by mutableStateOf<List<PlexItem>>(emptyList())
        private set
    var tvStack by mutableStateOf<List<PlexPage>>(emptyList())
        private set
    var continueWatching by mutableStateOf<List<ContinueEntry>>(emptyList())
        private set
    var continueLoading by mutableStateOf(false)
        private set
    private var signInJob: Job? = null
    private var libraryJob: Job? = null

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
        // screen stays smooth and TalkBack isn't flooded. Also saves your place.
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                val c = controller
                if (c != null && c.isPlaying) {
                    positionMs = c.currentPosition.coerceAtLeast(0)
                    tick++
                    if (tick % 5 == 0) saveProgress(bump = false)
                    if (tick % 10 == 0) reportPlex("playing")
                }
            }
        }

        if (plexSignedIn) restorePlex()
    }

    override fun onCleared() {
        saveProgress(bump = false)
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }

    private fun refresh() {
        val c = controller ?: return
        val playingNow = c.isPlaying
        hasMedia = c.mediaItemCount > 0
        isPlaying = playingNow
        val item = c.currentMediaItem
        title = c.mediaMetadata.title?.toString()
            ?: item?.mediaMetadata?.title?.toString()
            ?: "Nothing playing"
        artist = c.mediaMetadata.artist?.toString() ?: ""
        durationMs = c.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        positionMs = c.currentPosition.coerceAtLeast(0)
        shuffleOn = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        speed = c.playbackParameters.speed
        val extras = item?.mediaMetadata?.extras
        currentKind = extras?.getString(EXTRA_KIND) ?: Kind.MUSIC
        currentLocalMp3 = extras?.getString(EXTRA_LOCAL_MP3)?.let(Uri::parse)
        currentBookId = extras?.getString(EXTRA_BOOK_ID)
        currentPart = extras?.getInt(EXTRA_PART) ?: 0
        currentPlexKey = extras?.getString(EXTRA_PLEX_KEY)
        currentPlexServer = extras?.getString(EXTRA_PLEX_SERVER)
        currentResumeId = extras?.getString(EXTRA_RESUME_ID)

        if (wasPlaying && !playingNow) {
            saveProgress(bump = true)
            reportPlex("paused")
        }
        wasPlaying = playingNow

        val state = c.playbackState
        if (state == Player.STATE_ENDED && lastState != Player.STATE_ENDED) onEnded()
        lastState = state
    }

    private fun onEnded() {
        val bookId = currentBookId
        if (currentKind == Kind.AUDIOBOOK && bookId != null) {
            store.clearResume("book:$bookId")
            progressVersion++
            announce("End of book.")
        }
        currentResumeId?.let { store.clearResume(it); progressVersion++ }
        reportPlex("stopped")
    }

    /** Remembers where you are in a video or audiobook. */
    private fun saveProgress(bump: Boolean) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val pos = c.currentPosition.coerceAtLeast(0)
        val dur = c.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        val now = System.currentTimeMillis()
        when (currentKind) {
            Kind.VIDEO -> {
                val id = currentResumeId ?: return
                if (dur > 0 && pos > dur * 95 / 100) store.clearResume(id)
                else if (pos > 15_000) store.saveResume(ResumePoint(id, title, 0, pos, dur, now))
            }
            Kind.AUDIOBOOK -> {
                val bookId = currentBookId ?: return
                val bookTitle = books.find { it.id == bookId }?.title ?: title
                store.saveResume(ResumePoint("book:$bookId", bookTitle, currentPart, pos, dur, now))
            }
            else -> return
        }
        if (bump) progressVersion++
    }

    private fun reportPlex(state: String) {
        val c = controller ?: return
        val key = currentPlexKey ?: return
        val server = currentPlexServer ?: return
        if (currentKind != Kind.VIDEO) return
        val dur = c.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = c.currentPosition.coerceAtLeast(0)
        viewModelScope.launch { plex.reportTimeline(server, key, state, pos, dur) }
    }

    fun playQueue(items: List<MediaItem>, startIndex: Int, startPositionMs: Long = 0L, playbackSpeed: Float = 1f) {
        val c = controller
        if (c == null) {
            announce("The player is still starting. Try again in a moment.")
            return
        }
        if (items.isEmpty()) return
        saveProgress(bump = true)
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs.coerceAtLeast(0))
        c.setPlaybackSpeed(playbackSpeed)
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

    private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    fun cycleSpeed() {
        val c = controller ?: return
        val cur = c.playbackParameters.speed
        val next = speeds.firstOrNull { it > cur + 0.01f } ?: speeds.first()
        c.setPlaybackSpeed(next)
        if (currentKind == Kind.AUDIOBOOK) store.audiobookSpeed = next
        announce("Speed ${speedLabel(next)}")
    }

    fun speedLabel(f: Float): String {
        val text = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
        return "$text times"
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

    // ---------- Library on this phone ----------

    fun loadLocal(permissionGranted: Boolean = hasMediaPermission) {
        hasMediaPermission = permissionGranted
        viewModelScope.launch {
            localLoading = true
            val ctx = appContext
            val songs = withContext(Dispatchers.IO) {
                if (permissionGranted) runCatching { LocalLibrary.loadSongs(ctx) }.getOrDefault(emptyList()) else emptyList()
            }
            val videos = withContext(Dispatchers.IO) {
                if (permissionGranted) runCatching { LocalLibrary.loadVideos(ctx) }.getOrDefault(emptyList()) else emptyList()
            }
            val bookList = withContext(Dispatchers.IO) {
                LocalLibrary.loadBooks(ctx, store.addedBooks, permissionGranted)
            }
            localSongs = songs
            localVideos = videos
            books = bookList
            localLoading = false
            localLoaded = true
        }
    }

    private fun reloadBooks() {
        viewModelScope.launch {
            val ctx = appContext
            books = withContext(Dispatchers.IO) {
                LocalLibrary.loadBooks(ctx, store.addedBooks, hasMediaPermission)
            }
        }
    }

    // ---------- Music ----------

    private fun songItem(s: Song): MediaItem = when (s.source) {
        Source.PHONE -> buildMediaItem(
            id = s.id,
            uri = s.uri!!,
            title = s.title,
            artist = s.artist,
            album = s.album.ifBlank { null },
            kind = Kind.MUSIC,
            localMp3 = if (s.isMp3) s.uri else null
        )
        Source.PLEX -> plex.toMediaItem(s.plex!!, Kind.MUSIC)
    }

    /** Plays a whole list of songs, starting at the one you picked. */
    fun playSongs(list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        controller?.shuffleModeEnabled = false
        playQueue(list.map(::songItem), index.coerceIn(0, list.lastIndex))
    }

    /** Shuffles every song in the list, with no limit. */
    fun shuffleSongs(list: List<Song>) {
        if (list.isEmpty()) return
        controller?.shuffleModeEnabled = false
        playQueue(list.shuffled().map(::songItem), 0)
        announce("Shuffling ${list.size} songs.")
    }

    // ---------- Sorting ----------

    var songSort by mutableStateOf(enumOrDefault(store.sort("songs", ""), SongSort.TITLE))
        private set
    var albumSort by mutableStateOf(enumOrDefault(store.sort("albums", ""), AlbumSort.TITLE))
        private set
    var artistSort by mutableStateOf(enumOrDefault(store.sort("artists", ""), ArtistSort.NAME))
        private set
    var movieSort by mutableStateOf(enumOrDefault(store.sort("movies", ""), MovieSort.TITLE))
        private set

    fun changeSongSort(s: SongSort) { songSort = s; store.setSort("songs", s.name); announce("Sorted by ${s.label}.") }
    fun changeAlbumSort(s: AlbumSort) { albumSort = s; store.setSort("albums", s.name); announce("Sorted by ${s.label}.") }
    fun changeArtistSort(s: ArtistSort) { artistSort = s; store.setSort("artists", s.name); announce("Sorted by ${s.label}.") }
    fun changeMovieSort(s: MovieSort) { movieSort = s; store.setSort("movies", s.name); announce("Sorted by ${s.label}.") }

    // ---------- Videos ----------

    private fun videoResumeId(v: LocalVideo) = "video:${v.uri}"

    fun localVideoResume(v: LocalVideo): ResumePoint? = store.resume(videoResumeId(v))

    fun playLocalVideo(v: LocalVideo, fromStart: Boolean) {
        val id = videoResumeId(v)
        val start = if (fromStart) 0L else store.resume(id)?.positionMs ?: 0L
        val item = buildMediaItem(id, v.uri, v.title, null, null, Kind.VIDEO, resumeId = id)
        playQueue(listOf(item), 0, start)
    }

    fun playPlexVideo(item: PlexItem, fromStart: Boolean) {
        val start = if (fromStart) 0L else item.viewOffsetMs
        playQueue(listOf(plex.toMediaItem(item, Kind.VIDEO)), 0, start)
    }

    fun refreshContinueWatching() {
        viewModelScope.launch {
            continueLoading = true
            val locals = localVideos.mapNotNull { v ->
                store.resume(videoResumeId(v))?.let { r ->
                    ContinueEntry(
                        key = videoResumeId(v), title = v.title, subtitle = "On this phone",
                        positionMs = r.positionMs, durationMs = r.durationMs.takeIf { it > 0 } ?: v.durationMs,
                        lastViewed = r.updatedAt, local = v
                    )
                }
            }
            val remote = if (plexConnected) {
                try {
                    plex.connected.flatMap { s ->
                        try {
                            plex.onDeck(s.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }.filter { it.isPlayable }.map {
                        ContinueEntry(
                            key = "plex:${it.uid}", title = it.title,
                            subtitle = it.subtitle.ifBlank { if (it.type == "movie") "Movie" else "Plex" },
                            positionMs = it.viewOffsetMs, durationMs = it.durationMs,
                            lastViewed = it.lastViewedAt, plex = it
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    announce("Could not load Continue watching from Plex. ${e.message ?: ""}")
                    emptyList()
                }
            } else emptyList()
            continueWatching = (locals + remote).distinctBy { it.key }.sortedByDescending { it.lastViewed }
            continueLoading = false
        }
    }

    fun playContinue(e: ContinueEntry, fromStart: Boolean) {
        e.local?.let { playLocalVideo(it, fromStart); return }
        e.plex?.let { playPlexVideo(it, fromStart) }
    }

    fun openShow(show: PlexItem) {
        val path = show.browsePath ?: return
        viewModelScope.launch {
            plexBusy = true
            try {
                val items = plex.browse(show.server, path)
                tvStack = listOf(PlexPage(show.title, items))
                announce("${show.title}. ${items.size} items.")
            } catch (e: Exception) {
                announce("Could not open ${show.title}. ${e.message ?: ""}")
            } finally {
                plexBusy = false
            }
        }
    }

    fun openTvItem(index: Int, fromStart: Boolean) {
        val page = tvStack.lastOrNull() ?: return
        val item = page.items.getOrNull(index) ?: return
        if (item.isPlayable) {
            val episodes = page.items.filter { it.isPlayable }
            val i = episodes.indexOf(item).coerceAtLeast(0)
            val start = if (fromStart) 0L else item.viewOffsetMs
            playQueue(episodes.map { plex.toMediaItem(it, Kind.VIDEO) }, i, start)
            return
        }
        val path = item.browsePath ?: return
        viewModelScope.launch {
            plexBusy = true
            try {
                val items = plex.browse(item.server, path)
                tvStack = tvStack + PlexPage(item.title, items)
                announce("${item.title}. ${items.size} items.")
            } catch (e: Exception) {
                announce("Could not open ${item.title}. ${e.message ?: ""}")
            } finally {
                plexBusy = false
            }
        }
    }

    fun tvBack() {
        if (tvStack.isNotEmpty()) {
            tvStack = tvStack.dropLast(1)
            announce(tvStack.lastOrNull()?.title ?: "TV shows")
        }
    }

    // ---------- Audiobooks ----------

    fun bookResume(b: Book): ResumePoint? = store.resume("book:${b.id}")

    fun bookmarks(bookId: String): List<Bookmark> = store.bookmarks(bookId)

    fun playBook(b: Book, fromStart: Boolean = false) {
        val r = if (fromStart) null else bookResume(b)
        playBookAt(b, r?.index ?: 0, r?.positionMs ?: 0L)
    }

    fun playBookAt(b: Book, part: Int, positionMs: Long) {
        val items = b.parts.mapIndexed { i, p ->
            buildMediaItem(
                id = "book:${b.id}#$i",
                uri = p.uri,
                title = if (b.parts.size > 1) p.title else b.title,
                artist = b.author,
                album = b.title,
                kind = Kind.AUDIOBOOK,
                bookId = b.id,
                part = i
            )
        }
        controller?.shuffleModeEnabled = false
        playQueue(items, part, positionMs, store.audiobookSpeed)
    }

    fun addBookmark() {
        val c = controller ?: return
        val bookId = currentBookId ?: return
        val pos = c.currentPosition.coerceAtLeast(0)
        val book = books.find { it.id == bookId }
        val chapters = book?.let { bookChapters(it) } ?: emptyList()
        val chapter = chapters.getOrNull(chapterIndex(chapters, currentPart, pos))
        val label = if (chapter != null) {
            "${chapter.title}, ${spokenTime(pos - chapter.startMs)} in"
        } else {
            spokenTime(pos)
        }
        store.addBookmark(bookId, Bookmark(System.currentTimeMillis(), currentPart, pos, label))
        bookmarkVersion++
        announce("Bookmark added at $label.")
    }

    fun playBookmark(b: Book, m: Bookmark) = playBookAt(b, m.index, m.positionMs)

    // ---------- Chapters ----------

    private val chapterCache = mutableMapOf<String, List<BookChapter>>()
    private val chaptersLoading = mutableSetOf<String>()

    /** Goes up when chapter lists finish loading, so screens can refresh. */
    var chapterVersion by mutableIntStateOf(0)
        private set

    val currentBook: Book? get() = books.find { it.id == currentBookId }

    /**
     * The chapters of a book: its files if it has several, or the chapter
     * markers inside a single .m4b file. Returns null while still reading the file.
     */
    fun bookChapters(b: Book): List<BookChapter>? {
        chapterCache[b.id]?.let { return it }
        if (b.parts.size > 1) {
            val list = b.parts.mapIndexed { i, p -> BookChapter(p.title, i, 0L) }
            chapterCache[b.id] = list
            return list
        }
        if (chaptersLoading.add(b.id)) {
            val uri = b.parts.first().uri
            viewModelScope.launch {
                val found = withContext(Dispatchers.IO) { Mp4Chapters.read(appContext, uri) }
                chapterCache[b.id] = found.map { BookChapter(it.title, 0, it.startMs) }
                chaptersLoading.remove(b.id)
                chapterVersion++
            }
        }
        return null
    }

    fun chapterIndex(chapters: List<BookChapter>, part: Int, positionMs: Long): Int =
        chapters.indexOfLast { it.part < part || (it.part == part && it.startMs <= positionMs) }

    /** Next or previous chapter of the book that's playing. */
    fun chapterJump(delta: Int) {
        val c = controller ?: return
        val b = currentBook ?: return
        val chapters = bookChapters(b)
        if (chapters == null) {
            announce("Still reading the chapters. Try again in a moment.")
            return
        }
        if (chapters.isEmpty()) {
            announce("This book has no chapters.")
            return
        }
        val part = c.currentMediaItemIndex
        val pos = c.currentPosition.coerceAtLeast(0)
        val idx = chapterIndex(chapters, part, pos)
        var target = idx + delta
        // Like a CD player: Previous goes to the start of this chapter first.
        if (delta < 0 && idx >= 0) {
            val cur = chapters[idx]
            if (cur.part == part && pos - cur.startMs > 3_000) target = idx
        }
        if (target !in chapters.indices) {
            announce(if (delta > 0) "This is the last chapter." else "This is the first chapter.")
            if (delta < 0) c.seekTo(chapters.first().part, chapters.first().startMs)
            return
        }
        val ch = chapters[target]
        c.seekTo(ch.part, ch.startMs)
        positionMs = ch.startMs
        announce(ch.title)
    }

    /** Plays a book from a chapter, or jumps there if the book is already playing. */
    fun playChapter(b: Book, ch: BookChapter) {
        val c = controller
        if (c != null && currentBookId == b.id && c.mediaItemCount == b.parts.size) {
            c.seekTo(ch.part, ch.startMs)
            if (!c.isPlaying) c.play()
            announce(ch.title)
        } else {
            playBookAt(b, ch.part, ch.startMs)
        }
    }

    fun deleteBookmark(b: Book, m: Bookmark) {
        store.deleteBookmark(b.id, m.id)
        bookmarkVersion++
        announce("Bookmark deleted.")
    }

    fun addBookFiles(uris: List<Uri>) {
        val resolver = appContext.contentResolver
        val added = uris.mapNotNull { uri ->
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Some file pickers don't allow lasting access; the file may only open until restart.
            }
            uri.toString()
        }
        store.addedBooks = store.addedBooks + added
        reloadBooks()
        announce(if (added.size == 1) "Audiobook added." else "${added.size} audiobooks added.")
    }

    fun removeAddedBook(b: Book) {
        if (!b.added) return
        val uri = b.id.removePrefix("doc:")
        store.addedBooks = store.addedBooks - uri
        reloadBooks()
        announce("${b.title} removed from the list. The file was not deleted.")
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

    // ---------- Plex account ----------

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
                        loadPlexServers(autoConnect = true)
                        return@launch
                    }
                }
                plexWaitingForBrowser = false
                announce("Plex sign in timed out. Please try again.")
            } catch (e: CancellationException) {
                throw e
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

    /** Loads the list of servers on your account. With [autoConnect], turns on your own servers if none are on yet. */
    fun loadPlexServers(autoConnect: Boolean = false) {
        viewModelScope.launch {
            plexBusy = true
            try {
                val list = plex.servers()
                plexServers = list
                if (list.isEmpty()) {
                    announce("No Plex servers were found on your account.")
                } else if (autoConnect && plex.connected.isEmpty()) {
                    val pick = list.filter { it.owned }.ifEmpty { list }
                    val names = mutableListOf<String>()
                    for (server in pick) {
                        try {
                            plex.connect(server)
                            names += server.name
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            announce(e.message ?: "Could not reach ${server.name}.")
                        }
                    }
                    syncConnected()
                    if (names.isNotEmpty()) {
                        announce("Connected to ${names.joinToString(" and ")}. Loading your library.")
                        loadPlexLibrary()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                announce("Could not load your Plex servers. ${e.message ?: ""}")
            } finally {
                plexBusy = false
            }
        }
    }

    private fun syncConnected() {
        connectedServers = plex.connected
        plexConnected = plex.connected.isNotEmpty()
    }

    fun isServerOn(server: PlexServer): Boolean = connectedServers.any { it.id == server.id }

    /** Turns one server on or off. Music and Videos show everything from every server that's on. */
    fun toggleServer(server: PlexServer) {
        if (isServerOn(server)) {
            plex.disconnect(server.id)
            syncConnected()
            announce("${server.name} turned off.")
            loadPlexLibrary()
            return
        }
        viewModelScope.launch {
            plexBusy = true
            announce("Connecting to ${server.name}.")
            try {
                plex.connect(server)
                syncConnected()
                announce("Connected to ${server.name}. Loading its library.")
                loadPlexLibrary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                announce(e.message ?: "Could not connect to ${server.name}.")
            } finally {
                plexBusy = false
            }
        }
    }

    private fun restorePlex() {
        if (plex.connected.isEmpty()) {
            loadPlexServers(autoConnect = true)
        } else {
            loadPlexLibrary()
            loadPlexServers()
        }
    }

    private class ServerLibrary(val songs: List<Song>, val movies: List<PlexItem>, val shows: List<PlexItem>)

    private suspend fun loadServer(id: String): ServerLibrary {
        val sections = plex.librarySections(id)
        return ServerLibrary(
            songs = plex.allTracks(sections).filter { it.isPlayable }.map { it.toSong() },
            movies = plex.movies(sections),
            shows = plex.shows(sections)
        )
    }

    /** Loads every song, movie and TV show from every server that's on, and combines them. */
    fun loadPlexLibrary() {
        libraryJob?.cancel()
        if (plex.connected.isEmpty()) {
            clearPlexLibrary()
            return
        }
        libraryJob = viewModelScope.launch {
            plexLoading = true
            try {
                val songs = mutableListOf<Song>()
                val movies = mutableListOf<PlexItem>()
                val shows = mutableListOf<PlexItem>()
                val failed = mutableListOf<String>()
                for (s in plex.connected) {
                    val lib = try {
                        loadServer(s.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The server's address may have changed, for example when you're away
                        // from home. Look it up again and try once more.
                        try {
                            val fresh = plex.servers().find { it.id == s.id }
                            if (fresh != null) {
                                plex.connect(fresh)
                                loadServer(s.id)
                            } else null
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (e2: Exception) {
                            null
                        }
                    }
                    if (lib == null) {
                        failed += s.name
                    } else {
                        songs += lib.songs
                        movies += lib.movies
                        shows += lib.shows
                    }
                }
                syncConnected()
                plexSongs = songs
                plexMovies = movies.sortedBy { it.title.lowercase() }
                plexShows = shows.sortedBy { it.title.lowercase() }
                val summary = "${songs.size} songs, ${movies.size} movies, ${shows.size} TV shows"
                announce(
                    if (failed.isEmpty()) "Plex library loaded. $summary."
                    else "Plex library loaded. $summary. Could not reach ${failed.joinToString(" and ")}."
                )
            } finally {
                plexLoading = false
            }
        }
    }

    fun plexSignOut() {
        signInJob?.cancel()
        libraryJob?.cancel()
        plex.signOut()
        plexSignedIn = false
        plexServers = emptyList()
        syncConnected()
        clearPlexLibrary()
        announce("Signed out of Plex.")
    }

    private fun clearPlexLibrary() {
        plexSongs = emptyList()
        plexMovies = emptyList()
        plexShows = emptyList()
        tvStack = emptyList()
        continueWatching = continueWatching.filter { it.local != null }
    }
}
