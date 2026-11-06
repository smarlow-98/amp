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

data class PlexServer(
    val name: String,
    val accessToken: String,
    val owned: Boolean,
    val connections: List<PlexConnection>
)

data class PlexItem(
    val title: String,
    val type: String,
    val subtitle: String,
    val artist: String,
    val browsePath: String?,
    val partKey: String?,
    val durationMs: Long,
    val ratingKey: String
) {
    val isPlayable: Boolean get() = partKey != null
    val isVideo: Boolean get() = type == "movie" || type == "episode" || type == "clip"
}

data class PlexPin(val id: Long, val code: String)

private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name, "")

/**
 * Talks to plex.tv and Plex Media Server. Any Plex user can sign in with their
 * own account; the app never sees their password, only a token.
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

    private var serverBase: String? = prefs.getString(KEY_BASE, null)
    private var serverToken: String? = prefs.getString(KEY_SERVER_TOKEN, null)

    val savedServerName: String get() = prefs.getString(KEY_SERVER_NAME, "") ?: ""
    val hasSavedServer: Boolean get() = serverBase != null && serverToken != null

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
        forgetServer()
    }

    fun forgetServer() {
        prefs.edit().remove(KEY_BASE).remove(KEY_SERVER_TOKEN).remove(KEY_SERVER_NAME).apply()
        serverBase = null
        serverToken = null
    }

    // ---------- Servers ----------

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

    /** Tries local addresses first, then remote, then Plex relay. */
    suspend fun connect(server: PlexServer) = withContext(Dispatchers.IO) {
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
                serverBase = c.uri
                serverToken = server.accessToken
                prefs.edit()
                    .putString(KEY_BASE, c.uri)
                    .putString(KEY_SERVER_TOKEN, server.accessToken)
                    .putString(KEY_SERVER_NAME, server.name)
                    .apply()
                return@withContext
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Could not reach ${server.name}. Make sure the server is turned on.", lastError)
    }

    // ---------- Browsing ----------

    suspend fun sections(): List<PlexItem> = withContext(Dispatchers.IO) {
        val container = serverJson("/library/sections")
        val dirs = container.optJSONArray("Directory") ?: JSONArray()
        (0 until dirs.length()).mapNotNull { i ->
            val d = dirs.getJSONObject(i)
            val type = d.str("type")
            if (type == "photo") return@mapNotNull null
            PlexItem(
                title = d.str("title").ifBlank { "Library" },
                type = "section",
                subtitle = when (type) {
                    "artist" -> "Music library"
                    "movie" -> "Movie library"
                    "show" -> "TV library"
                    else -> "Library"
                },
                artist = "",
                browsePath = "/library/sections/${d.str("key")}/all",
                partKey = null,
                durationMs = 0,
                ratingKey = "section-${d.str("key")}"
            )
        }
    }

    suspend fun browse(path: String): List<PlexItem> = withContext(Dispatchers.IO) {
        val c = serverJson(path)
        val arr = c.optJSONArray("Metadata") ?: c.optJSONArray("Directory") ?: JSONArray()
        (0 until arr.length()).map { i -> parseItem(arr.getJSONObject(i), path) }
    }

    fun toMediaItem(item: PlexItem): MediaItem {
        val partKey = item.partKey ?: ""
        val sep = if (partKey.contains('?')) "&" else "?"
        val uri = Uri.parse(
            (serverBase ?: "") + partKey + sep + "X-Plex-Token=" + Uri.encode(serverToken ?: "")
        )
        return buildMediaItem(
            id = "plex:${item.ratingKey}:$partKey",
            uri = uri,
            title = item.title,
            artist = item.artist.ifBlank { null },
            album = null,
            isVideo = item.isVideo,
            localMp3 = null
        )
    }

    private fun parseItem(m: JSONObject, parentPath: String): PlexItem {
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
            title = m.str("title").ifBlank { "Untitled" },
            type = type,
            subtitle = subtitle,
            artist = artist,
            browsePath = browsePath,
            partKey = partKey,
            durationMs = m.optLong("duration", 0L),
            ratingKey = m.str("ratingKey")
        )
    }

    private fun serverJson(path: String): JSONObject {
        val base = serverBase ?: throw IOException("No Plex server is selected.")
        val token = serverToken ?: throw IOException("No Plex server is selected.")
        return JSONObject(http(base + path, token = token)).optJSONObject("MediaContainer") ?: JSONObject()
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
            conn.setRequestProperty("X-Plex-Version", "0.1.0")
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
        const val KEY_CLIENT = "clientId"
        const val KEY_TOKEN = "accountToken"
        const val KEY_BASE = "serverBase"
        const val KEY_SERVER_TOKEN = "serverToken"
        const val KEY_SERVER_NAME = "serverName"
    }
}
