package com.amp.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Where you left off in a video or audiobook. [index] is the chapter for audiobooks. */
data class ResumePoint(
    val id: String,
    val title: String,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

data class Bookmark(val id: Long, val index: Int, val positionMs: Long, val label: String)

/** Saves resume points, bookmarks and settings on the phone. */
class AppStore(context: Context) {

    private val prefs = context.getSharedPreferences("amp_state", Context.MODE_PRIVATE)

    fun resume(id: String): ResumePoint? {
        val s = prefs.getString("resume:$id", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            ResumePoint(id, o.optString("title"), o.optInt("index"), o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
        }.getOrNull()
    }

    fun saveResume(p: ResumePoint) {
        val o = JSONObject()
            .put("title", p.title)
            .put("index", p.index)
            .put("pos", p.positionMs)
            .put("dur", p.durationMs)
            .put("at", p.updatedAt)
        prefs.edit().putString("resume:${p.id}", o.toString()).apply()
    }

    fun clearResume(id: String) {
        prefs.edit().remove("resume:$id").apply()
    }

    fun bookmarks(bookId: String): List<Bookmark> {
        val arr = runCatching { JSONArray(prefs.getString("bookmarks:$bookId", null) ?: "[]") }
            .getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Bookmark(o.optLong("id"), o.optInt("index"), o.optLong("pos"), o.optString("label"))
        }.sortedWith(compareBy<Bookmark>({ it.index }, { it.positionMs }))
    }

    fun addBookmark(bookId: String, b: Bookmark) = saveBookmarks(bookId, bookmarks(bookId) + b)

    fun deleteBookmark(bookId: String, id: Long) =
        saveBookmarks(bookId, bookmarks(bookId).filter { it.id != id })

    private fun saveBookmarks(bookId: String, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("index", it.index)
                    .put("pos", it.positionMs)
                    .put("label", it.label)
            )
        }
        prefs.edit().putString("bookmarks:$bookId", arr.toString()).apply()
    }

    var addedBooks: Set<String>
        get() = prefs.getStringSet("addedBooks", null)?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet("addedBooks", value).apply()
        }

    /** Plex music libraries you chose to use for audiobooks. Null means "pick by name". */
    var plexBookSections: Set<String>?
        get() = if (prefs.contains("plexBookSections")) prefs.getStringSet("plexBookSections", null)?.toSet() else null
        set(value) {
            if (value == null) prefs.edit().remove("plexBookSections").apply()
            else prefs.edit().putStringSet("plexBookSections", value).apply()
        }

    var audiobookSpeed: Float
        get() = prefs.getFloat("bookSpeed", 1f)
        set(value) {
            prefs.edit().putFloat("bookSpeed", value).apply()
        }
}
