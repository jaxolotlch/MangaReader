package com.deniz.mangareader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(
    chapter: Chapter, initialPage: Int, onPageSettled: (Int) -> Unit, onBack: () -> Unit,
    mangaId: String,
    chapters: List<Chapter>,
    onChapterSelected: (Chapter) -> Unit,
    onCompleted: () -> Unit,
    onPermanentPageFailure: (Page.Remote, ImageFailure) -> Unit = { _, _ -> }
) {
    val pages = chapter.pages
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(pages.indices)) { pages.size }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    val loadedPages = remember(chapter.id) { mutableStateMapOf<Int, Boolean>() }
    val chapterIndex = chapters.indexOfFirst { it.id == chapter.id }
    val previous = chapters.getOrNull(chapterIndex - 1)
    val next = if (chapterIndex >= 0) chapters.getOrNull(chapterIndex + 1) else null
    val savePage by rememberUpdatedState(onPageSettled)
    val complete by rememberUpdatedState(onCompleted)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { savePage(it) }
    }
    val atEnd = pager.settledPage == pages.lastIndex && loadedPages[pages.lastIndex] == true
    LaunchedEffect(atEnd) {
        if (atEnd) {
            savePage(pager.settledPage)
            complete()
            controlsVisible = true
        }
    }

    val context = LocalContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    LaunchedEffect(pager, chapter.id, loader) {
        snapshotFlow {
            // Cancel immediately on a new gesture; visible page must finish before speculation resumes.
            if (pager.isScrollInProgress || loadedPages[pager.settledPage] != true) null else pager.settledPage
        }.distinctUntilChanged().collectLatest { current ->
            if (current == null) return@collectLatest
            delay(150)
            withContext(Dispatchers.IO) {
                for (index in (current + 1)..minOf(current + 3, pages.lastIndex)) {
                    val page = pages[index] as? Page.Remote ?: continue
                    ImagePipeline.register(page)
                    if (DownloadedImages.existing(context, page.url) != null) continue
                    try {
                        val uri = android.net.Uri.parse(page.url)
                        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) continue
                        // Same loader/key/disk cache as PageImage. A tiny decode avoids full-size bitmaps.
                        // Sequential execution keeps only one speculative request active at a time.
                        val result = loader.execute(ImageRequest.Builder(context)
                            .data(page.url).diskCacheKey(page.url)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .size(1, 1).build())
                        if (result is coil3.request.ErrorResult) ImageDiagnostics.report(result.throwable, "manga=$mangaId chapter=${chapter.id} page=$index", true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Visible loading owns errors and retries; prefetch stays silent.
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF111111)).safeDrawingPadding()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                },
                beyondViewportPageCount = 0
            ) { index ->
                PageImage(pages[index], "Manga sayfası ${index + 1} / ${pages.size}",
                    Modifier.fillMaxSize(), paper = true,
                    onLoaded = { loadedPages[index] = true },
                    debugContext = "manga=$mangaId chapter=${chapter.id} page=$index",
                    onPermanentFailure = onPermanentPageFailure)
            }
        }
        if (controlsVisible) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0xDC171717)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Bölümler", color = Color(0xFFE2DDD5)) }
                TextButton(onClick = { showChapters = true }, modifier = Modifier.weight(1f)) {
                    Text("${chapter.title} ▾", color = Color(0xFFE2DDD5), fontSize = 16.sp)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xDC171717)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.settledPage + 1} / ${pages.size}", color = Color(0xFFE2DDD5))
                    Text(if (atEnd) "Bölüm tamamlandı ✓" else "← Sağdan sola", color = Color(0xFFAAA69F), fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (previous != null) TextButton(onClick = { onChapterSelected(previous) }) {
                        Text("Önceki bölüm", color = Color(0xFFE2DDD5))
                    }
                    Spacer(Modifier.weight(1f))
                    if (next != null) TextButton(onClick = { onChapterSelected(next) }) {
                        Text("Sonraki bölüm", color = Color(0xFFE2DDD5))
                    }
                }
            }
        }
    }
    if (showChapters) {
        AlertDialog(onDismissRequest = { showChapters = false },
            containerColor = Color(0xFF20201F),
            title = { Text("Bölümler", color = Color(0xFFE2DDD5)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = chapterIndex.coerceAtLeast(0))) {
                    items(chapters, key = { it.id }) { entry ->
                        Column(Modifier.fillMaxWidth().clickable {
                            showChapters = false
                            if (entry.id != chapter.id) onChapterSelected(entry)
                        }.padding(vertical = 12.dp)) {
                            Text((if (entry.id == chapter.id) "• " else "") + entry.title, color = Color(0xFFE2DDD5))
                            entry.scanlator?.let { Text(it, color = Color(0xFFAAA69F), fontSize = 12.sp) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Kapat") } })
    }
}


