package com.deniz.mangareader

import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.CancellationException

@Composable
fun SourceScreen(store: LibraryStore, sourceId: String, source: MangaSource, initialManga: Manga? = null, onBack: () -> Unit) {

    val sourceName = if (sourceId == "atsu") "Atsu" else "WeebCentral"
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloads = remember { ChapterDownloads.get(context) }
    val downloadStates by downloads.states.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf(initialManga?.id) }
    var selectedTitle by rememberSaveable { mutableStateOf(initialManga?.title.orEmpty()) }
    var selectedCover by rememberSaveable { mutableStateOf((initialManga?.cover as? Page.Remote)?.url.orEmpty()) }
    var chapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<Manga>()) }
    var manga by remember { mutableStateOf<Manga?>(null) }
    var reading by remember { mutableStateOf<Chapter?>(null) }
    var loading by remember { mutableStateOf(false) }
    var localMetadata by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun back() {
        if (chapterId != null) { chapterId = null; reading = null }
        else if (selectedId != null) { if (initialManga != null) onBack() else { selectedId = null; manga = null } }
        else onBack()
    }
    BackHandler { back() }
    // Selection changes cancel the previous UI request; stale results cannot replace this screen.
    LaunchedEffect(submitted, selectedId, chapterId, retry) {
        error = null
        reading = null
        loading = true
        try {
            val id = selectedId
            if (id == null) {
                manga = null
                results = source.search(submitted)
            } else {
                val cached = store.records[id]?.asManga()?.takeIf { it.chapters.isNotEmpty() }
                val book = if (refreshing) {
                    source.details(Manga(id, selectedTitle, Page.Remote(selectedCover), emptyList()))
                } else cached ?: manga?.takeIf { it.id == id } ?: source.details(
                    Manga(id, selectedTitle, Page.Remote(selectedCover), emptyList())
                )
                localMetadata = cached != null && !refreshing
                store.snapshot(sourceId, book)
                store.importLegacy(sourceId, book)
                manga = book
                chapterId?.let { cid ->
                    val entry = book.chapters.firstOrNull { it.id == cid }
                        ?: throw java.io.IOException("Bölüm bulunamadı.")
                    val loaded = if (entry.pages.isNotEmpty()) entry else source.readChapter(book, entry)
                    store.snapshotChapter(sourceId, book, loaded)
                    reading = loaded
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = if (failure is java.io.IOException) failure.message ?: "$sourceName kaynağına bağlanılamadı."
                else "$sourceName verisi yüklenemedi. Lütfen tekrar dene."
        } finally {
            loading = false
            refreshing = false
        }
    }
    val book = manga
    val chapter = reading?.takeIf { it.id == chapterId }
    if (book != null && chapter != null) {
        key(book.id, chapter.id) {
            ReaderScreen(chapter, store.find(sourceId, book)?.chapters?.get(chapter.id)?.page ?: 0,
                onPageSettled = { index -> store.record(sourceId, book, chapter, index) },
                onBack = { back() },
                mangaId = book.id, chapters = book.chapters,
                onChapterSelected = { chapterId = it.id },
                onCompleted = { store.markCompleted(sourceId, book, chapter) })
        }
        return
    }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF111111), surface = Color(0xFF20201F), primary = Color(0xFFE1CBA9)
    )) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    TextButton(onClick = { back() }) { Text(if (selectedId == null) "‹ Kütüphane" else "‹ Geri") }
                    Text(book?.title ?: "$sourceName · Ara", style = MaterialTheme.typography.headlineMedium)
                }
                if (selectedId == null) {
                    item {
                        OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("Manga adı") }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { submitted = query.trim(); retry++ }, enabled = !loading && query.isNotBlank()) {
                            Text("Ara")
                        }
                    }
                }
                if (loading) item { CircularProgressIndicator() }
                if (error != null) item {
                    Text(error!!)
                    TextButton(onClick = { retry++ }) { Text("Tekrar dene") }
                }
                if (!loading && error == null && selectedId == null) {
                    if (submitted.isNotBlank() && results.isEmpty()) item { Text("Sonuç bulunamadı.") }
                    items(results, key = { it.id }) { result ->
                        Card(Modifier.fillMaxWidth().clickable {
                            selectedTitle = result.title
                            selectedCover = (result.cover as Page.Remote).url
                            selectedId = result.id
                        }) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                PageImage(result.cover, result.title, Modifier.width(90.dp).aspectRatio(2f / 3f))
                                Text(result.title, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                if (!loading && book != null && selectedId != null) {
                    item {
                        PageImage(book.cover, book.title, Modifier.fillMaxWidth().height(210.dp))
                        if (localMetadata) Text("Yerel bölüm listesi · Çevrimdışı kullanılabilir")
                        TextButton(onClick = { refreshing = true; retry++ }) { Text("Bölüm listesini yenile") }
                        val saved = store.find(sourceId, book)?.inLibrary == true
                        TextButton(onClick = { store.setInLibrary(sourceId, book, !saved) }, enabled = store.storageError == null) {
                            Text(if (saved) "Kütüphaneden çıkar" else "Kütüphaneye ekle")
                        }
                        store.storageError?.let { Text(it) }
                    }
                    if (book.chapters.isEmpty()) item { Text("Bölüm bulunamadı.") }
                    items(book.chapters, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth().clickable { chapterId = entry.id }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                val downloadKey = "${book.id}.${entry.id}"
                                val download = downloadStates[downloadKey]
                                Row {
                                    TextButton(enabled = downloadStates.values.none { it.active } && download?.complete != true,
                                        onClick = {
                                            downloads.start(downloadKey, source, book, entry) { loaded ->
                                                store.snapshotChapter(sourceId, book, loaded)
                                                store.setInLibrary(sourceId, book, true)
                                            }
                                        }) { Text(download?.label ?: "İndir") }
                                    if (download != null && !download.active) TextButton(onClick = { downloads.remove(downloadKey) }) {
                                        Text("İndirmeyi sil")
                                    }
                                }
                                entry.scanlator?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                                if (localMetadata) Text("Yerel bölüm listesi · Çevrimdışı kullanılabilir")
                        TextButton(onClick = { refreshing = true; retry++ }) { Text("Bölüm listesini yenile") }
                        val saved = store.find(sourceId, book)?.chapters?.get(entry.id)?.page
                                Text(if (saved == null) if (entry.pageCount > 0) "Henüz okunmadı · ${entry.pageCount} sayfa" else "Henüz okunmadı"
                                    else "Sayfa ${saved + 1} / ${entry.pageCount}")
                                if (store.find(sourceId, book)?.chapters?.get(entry.id)?.completed == true) Text("Okundu ✓")
                                if (store.find(sourceId, book)?.lastChapterId == entry.id) Text("Kaldığın bölüm")
                            }
                        }
                    }
                }
            }
        }
    }
}









