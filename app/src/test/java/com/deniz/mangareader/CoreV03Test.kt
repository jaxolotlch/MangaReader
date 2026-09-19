package com.deniz.mangareader

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreV03Test {
    private class FakeSource(
        private val result: Manga,
        private val loaded: Chapter = result.chapters.first()
    ) : MangaSource {
        override fun getManga() = emptyList<Manga>()
        override suspend fun search(query: String) = listOf(result)
        override suspend fun details(manga: Manga) = result
        override suspend fun readChapter(manga: Manga, chapter: Chapter) = loaded
    }

    private val primaryChapter = Chapter("primary-4", "Chapter 4", emptyList(), pageCount = 20)
    private val primaryManga = Manga("atsu.jojo", "JoJolion", Page.Remote("https://atsu/cover"), listOf(primaryChapter))

    @Test fun fallbackRequiresUniqueMangaAndChapterThenKeepsFallbackSourceOnPages() = runBlocking {
        val alternateChapter = Chapter(
            "wc-4", "Chapter 4",
            listOf(Page.Remote("https://cdn/4-1.jpg", "weebcentral"))
        )
        val alternate = Manga("weebcentral.jojo", "JoJolion", Page.Remote("https://wc/cover"), listOf(alternateChapter))
        val result = ChapterFallbackResolver(
            mapOf("atsu" to FakeSource(primaryManga), "weebcentral" to FakeSource(alternate, alternateChapter)),
            SourceHealthMemory.inMemory()
        ).resolve("atsu", primaryManga, primaryChapter)
        assertTrue(result is FallbackResult.Found)
        assertEquals("weebcentral", (result as FallbackResult.Found).sourceId)
        assertEquals("weebcentral", (result.chapter.pages.single() as Page.Remote).sourceId)
    }

    @Test fun fallbackBlocksDuplicateScanlatorCandidatesInsteadOfGuessing() = runBlocking {
        val alternatives = listOf(
            Chapter("a", "Chapter 4", emptyList(), scanlator = "A"),
            Chapter("b", "Chapter 4", emptyList(), scanlator = "B")
        )
        val alternate = Manga("weebcentral.jojo", "JoJolion", Page.Remote("https://wc/cover"), alternatives)
        val result = ChapterFallbackResolver(
            mapOf("atsu" to FakeSource(primaryManga), "weebcentral" to FakeSource(alternate)),
            SourceHealthMemory.inMemory()
        ).resolve("atsu", primaryManga, primaryChapter)
        assertTrue(result is FallbackResult.Blocked)
    }

    @Test fun permanentSourceMemoryPreventsRepeatedCandidateAttempt() = runBlocking {
        val alternateChapter = Chapter("wc-4", "Chapter 4", listOf(Page.Remote("https://cdn/4.jpg")))
        val alternate = Manga("weebcentral.jojo", "JoJolion", Page.Remote("https://wc/cover"), listOf(alternateChapter))
        val health = SourceHealthMemory.inMemory().also {
            it.markPermanent(SourceFailureKey("weebcentral", primaryManga.id, primaryChapter.id))
        }
        val result = ChapterFallbackResolver(
            mapOf("atsu" to FakeSource(primaryManga), "weebcentral" to FakeSource(alternate)), health
        ).resolve("atsu", primaryManga, primaryChapter)
        assertEquals(FallbackResult.Unavailable, result)
    }

    @Test fun typedDownloadStateRestoresCompletedAndPartialProgress() {
        assertEquals(DownloadState.Downloaded(12), restoredDownloadState(12, 12))
        assertEquals(DownloadState.Failed(5, 12, "Yarım kaldı"), restoredDownloadState(5, 12))
    }

    @Test fun offlineReaderPrefersExplicitDownloadedFileOverNetworkUrl() {
        val page = Page.Remote("https://cdn/page.jpg", "weebcentral")
        val local = File("downloaded-page.img")
        assertSame(local, DownloadedImages.requestData(page) { local })
        assertEquals(page.url, DownloadedImages.requestData(page) { null })
    }

    @Test fun sourceSpecificHeadersAreNotAppliedToAtsu() {
        val atsu = okhttp3.Request.Builder().url("https://atsu.moe/static/page.webp").build()
        assertSame(atsu, ImagePipeline.applySourceHeaders(atsu))
        val page = Page.Remote("https://cdn.example/page.jpg", "weebcentral", "https://weebcentral.com/chapters/ABC")
        ImagePipeline.register(page)
        val weeb = ImagePipeline.applySourceHeaders(okhttp3.Request.Builder().url(page.url).build())
        assertEquals(page.referrer, weeb.header("Referer"))
        assertTrue(weeb.header("Accept")!!.contains("image/"))
        assertEquals("MangaReader/0.3 (Android)", weeb.header("User-Agent"))
        assertNull(weeb.header("Cookie"))
    }
}
