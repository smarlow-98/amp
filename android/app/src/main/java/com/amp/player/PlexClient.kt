package com.amp.player

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)

/** A Plex server on your account. */
data class PlexServer(
    val id: String,
    val name: String,
    val accessToken: String,
    val owned: Boolean,
    val connections: List<PlexConnection>
)

/** A server AMP is using, with the address that worked. */
data class ConnectedServer(val id: String, val name: String, val base: String, val token: String)

data class PlexSection(val server: String, val key: String, val title: String, val type: String)

data class PlexItem(
    val server: String,
    val title: String,
    val type: String,
    val subtitle: String,
    val artist: String,
    val browsePath: String?,
    val partKey: String?,
    val durationMs: Long,
    val ratingKey: String,
    val album: String = "",
    val albumArtist: String = "",
    val index: Int = 0,
    val parentIndex: Int = 0,
    val year: String = "",
    val viewOffsetMs: Long = 0,
    val viewCount: Int = 0,
    val lastViewedAt: Long = 0
) {
    val isPlayable: Boolean get() = partKey != null
    val isVideo: Boolean get() = type == "movie" || type == "episode" || type == "clip"
    /** Unique across all your servers. */
    val uid: String get() = "$server:$ratingKey"
}

data class PlexPin(val id: Long, val code: String)

private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name, "")

/** "1 hour 2 minutes left", "watched", or "" for a Plex video. */
fun plexWatchStatus(item: PlexItem): String = when {
    item.viewOffsetMs > 0 && item.durationMs > 0 -> "${spokenLength(item.durationMs - item.viewOffsetMs)} left"
    item.viewCount > 0 -> "watched"
    else -> ""
}

/**
 * Talks to plex.tv and your Plex Media Servers. Any Plex user can sign in with
 * their own account; the app never sees their password, only a token.
 * Several servers can be connected at once, for example one for music and one for video.
 */
class PlexClient(context: Context) {

    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)

    val clientId: String = prefs.getString(KEY_CLIENT, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_CLIENT, it).apply() }

    var accountToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var connected: List<ConnectedServer> = loadConnected()
        private set

    private fun loadConnected(): List<ConnectedServer> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_SERVERS, null) ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ConnectedServer(o.str("id"), o.str("name"), o.str("base"), o.str("token"))
        }
    }.getOrDefault(emptyList())

    private fun saveConnected(list: List<ConnectedServer>) {
        connected = list
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("base", it.base).put("token", it.token))
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun server(id: String): ConnectedServer =
        connected.find { it.id == id } ?: throw IOException("That Plex server is not connected.")

    // ---------- Sign in ----------

    suspend fun createPin(): PlexPin = withContext(Dispatchers.IO) {
        val o = JSONObject(http("https://plex.tv/api/v2/pins?strong=true", method = "POST"))
        PlexPin(o.getLong("id"), o.getString("code"))
    }

    fun authUrl(pin: PlexPin): String =
        "https://app.plex.tv/auth#?clientID=" + Uri.encode(clientId) +
            "&code=" + Uri.encode(pin.code) +
            "&context%5Bdevice%5D%5Bproduct%5D=AMP"

    /** Returns the account token once you've finished signing in, otherwise null. */
    suspend fun checkPin(pin: PlexPin): String? = withContext(Dispatchers.IO) {
        val o = JSONObject(http("https://plex.tv/api/v2/pins/${pin.id}"))
        o.str("authToken").ifBlank { null }
    }

    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).apply()
        saveConnected(emptyList())
    }

    // ---------- Servers ----------

    /** Every server on your account, including ones shared with you. */
    suspend fun servers(): List<PlexServer> = withContext(Dispatchers.IO) {
        val token = accountToken ?: throw IOException("You are not signed in to Plex.")
        val arr = JSONArray(
            http("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token = token)
        )
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (!o.str("provides").contains("server")) return@mapNotNull null
            val conns = o.optJSONArray("connections") ?: JSONArray()
            PlexServer(
                id = o.str("clientIdentifier").ifBlank { o.str("name") },
                name = o.str("name").ifBlank { "Plex server" },
                accessToken = o.str("accessToken").ifBlank { token },
                owned = o.optBoolean("owned", false),
                connections = (0 until conns.length()).map { j ->
                    val c = conns.getJSONObject(j)
                    PlexConnection(c.str("uri"), c.optBoolean("local"), c.optBoolean("relay"))
                }.filter { it.uri.isNotBlank() }
            )
        }
    }

    /** Finds an address that works (local first, then remote, then relay) and adds the server. */
    suspend fun connect(server: PlexServer) {
        withContext(Dispatchers.IO) {
            val ordered = server.connections.sortedBy { c ->
                when {
                    c.local && !c.relay -> 0
                    !c.relay -> 1
                    else -> 2
                }
            }
            var lastError: Exception? = null
            for (c in ordered) {
                try {
                    http("${c.uri}/identity", token = server.accessToken, timeoutMs = 5_000)
                    val entry = ConnectedServer(server.id, server.name, c.uri, server.accessToken)
                    saveConnected(connected.filter { it.id != server.id } + entry)
                    return@withContext
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw IOException("Could not reach ${server.name}. Make sure that computer is on.", lastError)
        }
    }

    fun disconnect(id: String) {
        saveConnected(connected.filter { it.id != id })
    }

    // ---------- Libraries ----------

    suspend fun librarySections(serverId: String): List<PlexSection> = withContext(Dispatchers.IO) {
        val dirs = serverJson(serverId, "/library/sections").optJSONArray("Directory") ?: JSONArray()
        (0 until dirs.length()).map { i ->
            val d = dirs.getJSONObject(i)
            PlexSection(serverId, d.str("key"), d.str("title"), d.str("type"))
        }
    }

    /** Every song in the given music libraries, loaded in pages. */
    suspend fun allTracks(sections: List<PlexSection>): List<PlexItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlexItem>()
        for (s in sections.filter { it.type == "artist" }) {
            var start = 0
            while (true) {
                val path = "/library/sections/${s.key}/all?type=10" +
                    "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$PAGE"
                val arr = serverJson(s.server, path).optJSONArray("Metadata") ?: JSONArray()
                for (i in 0 until arr.length()) out += parseItem(arr.getJSONObject(i), path, s.server)
                if (arr.length() < PAGE) break
                start += PAGE
            }
        }
        out
    }

    suspend fun movies(sections: List<PlexSection>): List<PlexItem> =
        sectionItems(sections.filter { it.type == "movie" })

    suspend fun shows(sections: List<PlexSection>): List<PlexItem> =
        sectionItems(sections.filter { it.type == "show" })

    private suspend fun sectionItems(sections: List<PlexSection>): List<PlexItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlexItem>()
        for (s in sections) {
            val path = "/library/sections/${s.key}/all"
            val arr = serverJson(s.server, path).optJSONArray("Metadata") ?: JSONArray()
            for (i in 0 until arr.length()) out += parseItem(arr.getJSONObject(i), path, s.server)
        }
        out
    }

    /** Movies and episodes you're partway through, plus the next episode of shows you're watching. */
    suspend fun onDeck(serverId: String): List<PlexItem> = withContext(Dispatchers.IO) {
        val path = "/library/onDeck"
        val arr = serverJson(serverId, path).optJSONArray("Metadata") ?: JSONArray()
        (0 until arr.length()).map { i -> parseItem(arr.getJSONObject(i), path, serverId) }
    }

    suspend fun browse(serverId: String, path: String): List<PlexItem> = withContext(Dispatchers.IO) {
        val c = serverJson(serverId, path)
        val arr = c.optJSONArray("Metadata") ?: c.optJSONArray("Directory") ?: JSONArray()
        (0 until arr.length()).map { i -> parseItem(arr.getJSONObject(i), path, serverId) }
    }

    /** Tells Plex how far you've watched, so other Plex apps can resume too. */
    suspend fun reportTimeline(serverId: String, ratingKey: String, state: String, timeMs: Long, durationMs: Long) {
        withContext(Dispatchers.IO) {
            val s = connected.find { it.id == serverId } ?: return@withContext
            val ok = runCatching {
                http(
                    "${s.base}/:/timeline?ratingKey=$ratingKey&key=%2Flibrary%2Fmetadata%2F$ratingKey" +
                        "&state=$state&time=$timeMs&duration=$durationMs",
                    token = s.token, timeoutMs = 5_000
                )
            }.isSuccess
            if (!ok) {
                runCatching {
                    http(
                        "${s.base}/:/progress?key=$ratingKey&identifier=com.plexapp.plugins.library" +
                            "&time=$timeMs&state=$state",
                        token = s.token, timeoutMs = 5_000
                    )
                }
            }
        }
    }

    fun toMediaItem(item: PlexItem, kind: String): MediaItem {
        val s = connected.find { it.id == item.server }
        val partKey = item.partKey ?: ""
        val sep = if (partKey.contains('?')) "&" else "?"
        val uri = Uri.parse(
            (s?.base ?: "") + partKey + sep + "X-Plex-Token=" + Uri.encode(s?.token ?: "")
        )
        val artist = if (kind == Kind.VIDEO) item.subtitle else item.artist
        return buildMediaItem(
            id = "plex:${item.uid}",
            uri = uri,
            title = item.title,
            artist = artist.ifBlank { null },
            album = item.album.ifBlank { null },
            kind = kind,
            plexKey = item.ratingKey,
            plexServer = item.server
        )
    }

    private fun parseItem(m: JSONObject, parentPath: String, serverId: String): PlexItem {
        val type = m.str("type")
        val part = m.optJSONArray("Media")?.optJSONObject(0)
            ?.optJSONArray("Part")?.optJSONObject(0)
        val partKey = part?.str("key")?.ifBlank { null }
        val key = m.str("key")
        val browsePath = when {
            partKey != null -> null
            key.isBlank() -> null
            key.startsWith("/") -> key
            else -> parentPath.substringBefore('?').trimEnd('/') + "/" + key
        }
        val year = m.str("year")
        val subtitle = when (type) {
            "track" -> listOf(m.str("originalTitle").ifBlank { m.str("grandparentTitle") }, m.str("parentTitle"))
                .filter { it.isNotBlank() }.joinToString(", ")
            "album" -> listOf(m.str("parentTitle"), year).filter { it.isNotBlank() }.joinToString(", ")
            "episode" -> buildString {
                append(m.str("grandparentTitle"))
                val s = m.str("parentIndex")
                val e = m.str("index")
                if (s.isNotBlank() && e.isNotBlank()) append(", season $s episode $e")
            }
            "season" -> m.str("parentTitle")
            "movie", "show" -> year
            else -> ""
        }
        val artist = when (type) {
            "track" -> m.str("originalTitle").ifBlank { m.str("grandparentTitle") }
            "episode" -> m.str("grandparentTitle")
            else -> ""
        }
        return PlexItem(
            server = serverId,
            title = m.str("title").ifBlank { "Untitled" },
            type = type,
            subtitle = subtitle,
            artist = artist,
            browsePath = browsePath,
            partKey = partKey,
            durationMs = m.optLong("duration", 0L),
            ratingKey = m.str("ratingKey"),
            album = if (type == "track") m.str("parentTitle") else "",
            albumArtist = if (type == "track") m.str("grandparentTitle") else "",
            index = m.optInt("index", 0),
            parentIndex = m.optInt("parentIndex", 0),
            year = year,
            viewOffsetMs = m.optLong("viewOffset", 0L),
            viewCount = m.optInt("viewCount", 0),
            lastViewedAt = m.optLong("lastViewedAt", 0L) * 1000
        )
    }

    private fun serverJson(serverId: String, path: String): JSONObject {
        val s = server(serverId)
        val body = http(s.base + path, token = s.token, timeoutMs = 30_000)
        return JSONObject(body).optJSONObject("MediaContainer") ?: JSONObject()
    }

    private fun http(
        url: String,
        method: String = "GET",
        token: String? = null,
        timeoutMs: Int = 15_000
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Plex-Product", "AMP")
            conn.setRequestProperty("X-Plex-Version", "0.3.0")
            conn.setRequestProperty("X-Plex-Client-Identifier", clientId)
            conn.setRequestProperty("X-Plex-Platform", "Android")
            conn.setRequestProperty("X-Plex-Device-Name", "AMP on ${Build.MODEL}")
            if (token != null) conn.setRequestProperty("X-Plex-Token", token)
            if (method == "POST") {
                conn.doOutput = true
                conn.outputStream.close()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw IOException("Plex says you are not authorized. Try signing out and back in.")
            if (code !in 200..299) throw IOException("Plex returned error $code.")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val PAGE = 1000
        const val KEY_CLIENT = "clientId"
        const val KEY_TOKEN = "accountToken"
        const val KEY_SERVERS = "connectedServers"
    }
}
