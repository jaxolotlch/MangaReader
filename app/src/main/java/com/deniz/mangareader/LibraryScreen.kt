package com.deniz.mangareader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LibraryScreen(store: LibraryStore) {
    val sources = remember { mapOf<String, MangaSource>("atsu" to AtsuSource(), "weebcentral" to WeebCentralSource()) }
    var openedSource by rememberSaveable { mutableStateOf<String?>(null) }
    var openedBook by rememberSaveable { mutableStateOf<String?>(null) }
    val source = sources[openedSource]
    if (source != null) {
        SourceScreen(store, openedSource!!, source, store.records[openedBook]?.asManga(), onBack = {
            openedSource = null
            openedBook = null
        })
        return
    }
    val books = store.records.values.filter { it.inLibrary }.sortedByDescending { it.lastReadAt }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF111111), surface = Color(0xFF20201F), primary = Color(0xFFE1CBA9)
    )) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Kütüphane", style = MaterialTheme.typography.headlineMedium)
                    Row {
                        TextButton(onClick = { openedBook = null; openedSource = "atsu" }) { Text("Atsu · Ara") }
                        TextButton(onClick = { openedBook = null; openedSource = "weebcentral" }) { Text("WeebCentral · Ara") }
                    }
                    store.storageError?.let { Text(it) }
                }
                if (books.isEmpty()) item { Text("Kütüphanen henüz boş. Manga ara veya okumaya başla.") }
                items(books, key = { it.key }) { book ->
                    Card(Modifier.fillMaxWidth().clickable(enabled = sources.containsKey(book.sourceId)) {
                        openedBook = book.key
                        openedSource = book.sourceId
                    }) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            PageImage(Page.Remote(book.coverUrl), "${book.title} kapağı",
                                Modifier.width(100.dp).aspectRatio(2f / 3f))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(book.title, style = MaterialTheme.typography.titleLarge)
                                val last = book.chapters[book.lastChapterId]
                                if (last != null) {
                                    Text(last.title)
                                    Text(if (last.total > 0) "Sayfa ${last.page + 1} / ${last.total}"
                                        else "Sayfa ${last.page + 1}")
                                } else Text("Henüz başlanmadı")
                                if (!sources.containsKey(book.sourceId)) Text("Kaynak şu anda kullanılamıyor")
                            }
                        }
                    }
                }
            }
        }
    }
}

