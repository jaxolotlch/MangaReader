package com.deniz.mangareader

import android.util.Log
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
import kotlinx.coroutines.launch

@Composable
fun SourceScreen(
    store: LibraryStore,
    sourceId: String,
    source: MangaSource,
    initialManga: Manga? = null,
    onBack: () -> Unit
) {
    val sourceName = if (sourceId == "atsu") "Atsu" else "WeebCentral"
    val context = androidx.compose.ui.platform.LocalContext.current
    val sources = remember { SourceCatalog.configured() }
    val health = remember { SourceHealthMemory.get(context) }
    val fallback = remember { ChapterFallbackResolver(sources, health) }
    val downloads = remember { ChapterDownloads.get(context) }
    val downloadStates by downloads.states.collectAsState()
    val scope = rememberCoroutineScope()

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
    var fallbackRunning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun back() {
        if (chapterId != null) {
            chapterId = null
            reading = null
        } else if (selectedId != null) {
            if (initialManga != null) onBack() else {
                selectedId = null
                manga = null
            }
        } else onBack()
    }
    BackHandler { back() }

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
                chapterId?.let { idToRead ->
                    val entry = book.chapters.firstOrNull { it.id == idToRead }
                        ?: throw java.io.IOException("Bölüm bulunamadı.")
                    var loaded = if (entry.pages.isNotEmpty()) entry else source.readChapter(book, entry)
                    val pageSource = (loaded.pages.firstOrNull() as? Page.Remote)?.sourceId ?: sourceId
                    if (health.isPermanentlyFailed(SourceFailureKey(pageSource, book.id, entry.id))) {
                        loaded = when (val result = fallback.resolve(sourceId, book, entry)) {
                            is FallbackResult.Found -> entry.copy(
                                pages = result.chapter.pages,
                                pageCount = result.chapter.pages.size
                            )
                            is FallbackResult.Blocked -> throw java.io.IOException(
                                "Kalıcı kaynak hatası var; güvenli fallback belirsiz: ${result.reason}"
                            )
                            FallbackResult.Unavailable -> throw java.io.IOException(
                                "Kalıcı kaynak hatası var ve güvenli fallback bulunamadı."
                            )
                        }
                    }
                    store.snapshotChapter(sourceId, book, loaded)
                    reading = loaded
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = if (failure is java.io.IOException) {
                failure.message ?: "$sourceName kaynağına bağlanılamadı."
            } else "$sourceName verisi yüklenemedi. Lütfen tekrar dene."
        } finally {
            loading = false
            refreshing = false
        }
    }

    val book = manga
    val chapter = reading?.takeIf { it.id == chapterId }
    if (book != null && chapter != null) {
        key(book.id, chapter.id, chapter.pages.firstOrNull()) {
            ReaderScreen(
                chapter = chapter,
                initialPage = store.find(sourceId, book)?.chapters?.get(chapter.id)?.page ?: 0,
                onPageSettled = { index -> store.record(sourceId, book, chapter, index) },
                onBack = ::back,
                mangaId = book.id,
                chapters = book.chapters,
                onChapterSelected = { chapterId = it.id },
                onCompleted = { store.markCompleted(sourceId, book, chapter) },
                onPermanentPageFailure = { failedPage, _ ->
                    if (!fallbackRunning) {
                        val failedSource = failedPage.sourceId ?: sourceId
                        health.markPermanent(SourceFailureKey(failedSource, book.id, chapter.id))
                        fallbackRunning = true
                        scope.launch {
                            when (val result = fallback.resolve(sourceId, book, chapter)) {
                                is FallbackResult.Found -> {
                                    val replacement = chapter.copy(
                                        pages = result.chapter.pages,
                                        pageCount = result.chapter.pages.size
                                    )
                                    store.snapshotChapter(sourceId, book, replacement)
                                    reading = replacement
                                }
                                is FallbackResult.Blocked -> Log.w("MangaFallback", "BLOCKED ${result.reason}")
                                FallbackResult.Unavailable -> Log.w("MangaFallback", "No safe fallback for ${book.id}/${chapter.id}")
                            }
                            fallbackRunning = false
                        }
                    }
                }
            )
        }
        return
    }

    SourceBrowser(
        sourceName = sourceName,
        selected = book,
        query = query,
        onQueryChanged = { query = it },
        onSearch = { submitted = query.trim(); retry++ },
        results = results,
        loading = loading,
        error = error,
        localMetadata = localMetadata,
        store = store,
        sourceId = sourceId,
        downloadStates = downloadStates,
        downloads = downloads,
        source = source,
        onBack = ::back,
        onRetry = { retry++ },
        onRefresh = { refreshing = true; retry++ },
        onMangaSelected = { result ->
            selectedTitle = result.title
            selectedCover = (result.cover as Page.Remote).url
            selectedId = result.id
        },
        onChapterSelected = { chapterId = it.id }
    )
}

@Composable
private fun SourceBrowser(
    sourceName: String,
    selected: Manga?,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<Manga>,
    loading: Boolean,
    error: String?,
    localMetadata: Boolean,
    store: LibraryStore,
    sourceId: String,
    downloadStates: Map<String, DownloadState>,
    downloads: ChapterDownloads,
    source: MangaSource,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onMangaSelected: (Manga) -> Unit,
    onChapterSelected: (Chapter) -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF111111), surface = Color(0xFF20201F), primary = Color(0xFFE1CBA9)
    )) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().safeDrawingPadding(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    TextButton(onClick = onBack) { Text(if (selected == null) "‹ Kütüphane" else "‹ Geri") }
                    Text(selected?.title ?: "$sourceName · Ara", style = MaterialTheme.typography.headlineMedium)
                }
                if (selected == null) item {
                    SourceSearch(query, onQueryChanged, loading, onSearch)
                }
                if (loading) item { CircularProgressIndicator() }
                if (error != null) item {
                    Text(error)
                    TextButton(onClick = onRetry) { Text("Tekrar dene") }
                }
                if (!loading && error == null && selected == null) {
                    if (query.isNotBlank() && results.isEmpty()) item { Text("Sonuç bulunamadı.") }
                    items(results, key = { it.id }) { result -> MangaResultCard(result, onMangaSelected) }
                }
                if (!loading && selected != null) {
                    item { MangaSummary(selected, sourceId, store, localMetadata, onRefresh) }
                    if (selected.chapters.isEmpty()) item { Text("Bölüm bulunamadı.") }
                    items(selected.chapters, key = { it.id }) { chapter ->
                        ChapterCard(
                            manga = selected,
                            chapter = chapter,
                            sourceId = sourceId,
                            store = store,
                            source = source,
                            downloads = downloads,
                            allDownloadStates = downloadStates,
                            onOpen = { onChapterSelected(chapter) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSearch(query: String, onChanged: (String) -> Unit, loading: Boolean, onSearch: () -> Unit) {
    OutlinedTextField(query, onValueChange = onChanged, singleLine = true,
        label = { Text("Manga adı") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = onSearch, enabled = !loading && query.isNotBlank()) { Text("Ara") }
}

@Composable
private fun MangaResultCard(manga: Manga, onSelected: (Manga) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onSelected(manga) }) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PageImage(manga.cover, manga.title, Modifier.width(90.dp).aspectRatio(2f / 3f))
            Text(manga.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MangaSummary(
    manga: Manga,
    sourceId: String,
    store: LibraryStore,
    localMetadata: Boolean,
    onRefresh: () -> Unit
) {
    PageImage(manga.cover, manga.title, Modifier.fillMaxWidth().height(210.dp))
    if (localMetadata) Text("Yerel bölüm listesi · Çevrimdışı kullanılabilir")
    TextButton(onClick = onRefresh) { Text("Bölüm listesini yenile") }
    val saved = store.find(sourceId, manga)?.inLibrary == true
    TextButton(onClick = { store.setInLibrary(sourceId, manga, !saved) }, enabled = store.storageError == null) {
        Text(if (saved) "Kütüphaneden çıkar" else "Kütüphaneye ekle")
    }
    store.storageError?.let { Text(it) }
}

@Composable
private fun ChapterCard(
    manga: Manga,
    chapter: Chapter,
    sourceId: String,
    store: LibraryStore,
    source: MangaSource,
    downloads: ChapterDownloads,
    allDownloadStates: Map<String, DownloadState>,
    onOpen: () -> Unit
) {
    val key = "${manga.id}.${chapter.id}"
    val state = allDownloadStates[key] ?: DownloadState.NotDownloaded
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(chapter.title, style = MaterialTheme.typography.titleMedium)
            ChapterDownloadActions(
                state = state,
                anotherDownloadActive = allDownloadStates.values.any { it is DownloadState.Downloading || it is DownloadState.Deleting },
                onDownload = {
                    downloads.start(key, source, manga, chapter) { loaded ->
                        store.snapshotChapter(sourceId, manga, loaded)
                        store.setInLibrary(sourceId, manga, true)
                    }
                },
                onDelete = { downloads.remove(key) }
            )
            chapter.scanlator?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            val progress = store.find(sourceId, manga)?.chapters?.get(chapter.id)
            Text(when {
                progress != null && chapter.pageCount > 0 -> "Sayfa ${progress.page + 1} / ${chapter.pageCount}"
                progress != null -> "Sayfa ${progress.page + 1}"
                chapter.pageCount > 0 -> "Henüz okunmadı · ${chapter.pageCount} sayfa"
                else -> "Henüz okunmadı"
            })
            if (progress?.completed == true) Text("Okundu ✓")
            if (store.find(sourceId, manga)?.lastChapterId == chapter.id) Text("Kaldığın bölüm")
        }
    }
}

@Composable
private fun ChapterDownloadActions(
    state: DownloadState,
    anotherDownloadActive: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        when (state) {
            DownloadState.NotDownloaded -> TextButton(enabled = !anotherDownloadActive, onClick = onDownload) { Text("İndir") }
            is DownloadState.Downloading -> {
                if (state.total > 0) LinearProgressIndicator(
                    progress = { state.completed.toFloat() / state.total },
                    modifier = Modifier.width(110.dp)
                )
                Text(" ${state.completed}/${state.total.coerceAtLeast(0)}")
            }
            is DownloadState.Downloaded -> Text("İndirildi · ${state.total} sayfa")
            is DownloadState.Failed -> TextButton(enabled = !anotherDownloadActive, onClick = onDownload) {
                Text("Tekrar dene · ${state.completed}/${state.total}")
            }
            DownloadState.Deleting -> Text("Siliniyor…")
        }
        if (state is DownloadState.Downloaded || state is DownloadState.Failed) {
            TextButton(enabled = state !is DownloadState.Deleting, onClick = onDelete) { Text("Sil") }
        }
    }
}
