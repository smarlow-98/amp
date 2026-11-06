package com.amp.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "45 percent, 3 hours 2 minutes left" or "12 chapters, not started". */
fun bookStatus(b: Book, r: ResumePoint?): String {
    if (r == null) {
        val length = if (b.durationMs > 0) spokenLength(b.durationMs) else ""
        val chapters = if (b.parts.size > 1) "${b.parts.size} chapters" else ""
        return listOf(chapters, length, "not started").filter { it.isNotBlank() }.joinToString(", ")
    }
    val listened = b.parts.take(r.index).sumOf { it.durationMs } + r.positionMs
    val total = b.durationMs
    if (total <= 0) return "started"
    val percent = (listened * 100 / total).coerceIn(0, 100)
    return "$percent percent, ${spokenLength((total - listened).coerceAtLeast(0))} left"
}

@Composable
fun AudiobooksScreen(vm: AppViewModel) {
    val perm = rememberMediaPermission(vm)
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addBookFiles(uris)
    }
    val book = openId?.let { id -> vm.books.find { it.id == id } }

    BackHandler(enabled = book != null) { openId = null }

    if (book != null) {
        BookDetail(vm, book) { openId = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Audiobooks")
        PermissionPrompt(perm)
        Button(
            onClick = { picker.launch(arrayOf("audio/*", "video/mp4", "application/octet-stream")) },
            modifier = Modifier.padding(horizontal = 16.dp)
        ) { Text("Add audiobook files") }

        if (vm.books.isEmpty()) {
            Text(
                if (vm.localLoading) "Loading."
                else "No audiobooks found yet. AMP finds m4b files, files marked as audiobooks, " +
                    "and anything in a folder named Audiobooks. You can also add files with the button above.",
                Modifier.padding(16.dp)
            )
        }

        val version = vm.progressVersion
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.books, key = { _, b -> b.id }) { _, b ->
                val r = remember(version, b.id) { vm.bookResume(b) }
                val status = bookStatus(b, r)
                MediaRow(
                    spoken = "${b.title}, by ${b.author}, $status",
                    primary = b.title,
                    secondary = "${b.author}  •  $status",
                    clickLabel = "open",
                    onClick = { openId = b.id },
                    longClickLabel = if (b.added) "remove from list" else null,
                    onLongClick = if (b.added) ({ vm.removeAddedBook(b) }) else null
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BookDetail(vm: AppViewModel, book: Book, onBack: () -> Unit) {
    val r = remember(vm.progressVersion, book.id) { vm.bookResume(book) }
    val marks = remember(vm.bookmarkVersion, book.id) { vm.bookmarks(book.id) }
    val chapters = remember(vm.chapterVersion, book.id) { vm.bookChapters(book) }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "top") {
            Column {
                ScreenHeading(book.title)
                Text("by ${book.author}", Modifier.padding(horizontal = 16.dp))
                Text(bookStatus(book, r), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { vm.playBook(book) }, modifier = Modifier.weight(1f)) {
                        Text(if (r != null) "Resume" else "Play")
                    }
                    OutlinedButton(onClick = { vm.playBook(book, fromStart = true) }, modifier = Modifier.weight(1f)) {
                        Text("Start over")
                    }
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Back") }
                SectionHeading("Bookmarks, ${marks.size}")
                if (marks.isEmpty()) {
                    Text(
                        "No bookmarks yet. While this book is playing, use Add bookmark on the Now playing tab.",
                        Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        items(marks, key = { "bm:${it.id}" }) { m ->
            MediaRow(
                spoken = "Bookmark, ${m.label}",
                primary = m.label,
                secondary = "Bookmark",
                clickLabel = "play from bookmark",
                onClick = { vm.playBookmark(book, m) },
                longClickLabel = "delete bookmark",
                onLongClick = { vm.deleteBookmark(book, m) }
            )
            HorizontalDivider()
        }
        item(key = "chapters") {
            Column {
                SectionHeading(if (chapters.isNullOrEmpty()) "Chapters" else "Chapters, ${chapters.size}")
                when {
                    chapters == null -> Text("Reading chapters.", Modifier.padding(horizontal = 16.dp))
                    chapters.isEmpty() -> Text(
                        "This book has no chapter markers. You can still move around with bookmarks and the skip buttons.",
                        Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        if (!chapters.isNullOrEmpty()) {
            itemsIndexed(chapters, key = { i, _ -> "ch:$i" }) { i, ch ->
                // How long this chapter is: up to the next chapter in the same file, or the end of the file.
                val next = chapters.getOrNull(i + 1)
                val partLength = book.parts.getOrNull(ch.part)?.durationMs ?: 0L
                val end = if (next != null && next.part == ch.part) next.startMs else partLength
                val length = (end - ch.startMs).coerceAtLeast(0)
                MediaRow(
                    spoken = "Chapter ${i + 1}, ${ch.title}" + if (length > 0) ", ${spokenLength(length)}" else "",
                    primary = ch.title,
                    secondary = "Chapter ${i + 1}" + if (length > 0) "  •  ${clockTime(length)}" else "",
                    clickLabel = "play",
                    onClick = { vm.playChapter(book, ch) }
                )
                HorizontalDivider()
            }
        }
    }
}
