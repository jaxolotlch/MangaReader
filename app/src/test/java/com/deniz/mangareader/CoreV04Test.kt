package com.deniz.mangareader

import coil3.network.HttpException
import coil3.network.NetworkResponse
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreV04Test {
    private val primary = Chapter("group-a.four", "Chapter 4", emptyList(), remoteId = "four", scanlator = "A")
    private val book = Manga("atsu.jojo", "JoJolion", Page.Remote("https://atsu.test/cover"), listOf(primary))
    private val alternate = primary.copy(id = "wc-four", remoteId = "wc-four", scanlator = "B",
        pages = listOf(Page.Remote("https://cdn.test/1.jpg"), Page.Remote("https://cdn.test/2.jpg")))
    private val otherBook = book.copy(id = "weebcentral.jojo", chapters = listOf(alternate))
    private val key = SourceFailureKey("atsu", book.id, primary.id)
    private fun http(code: Int) = ImageDiagnostics.classify(HttpException(NetworkResponse(code = code)))

    private class Source(val manga: Manga, var failure: Exception? = null) : MangaSource {
        var searches = 0
        var reads = 0
        override fun getManga() = emptyList<Manga>()
        override suspend fun search(query: String): List<Manga> {
            searches++
            failure?.let { throw it }
            return listOf(manga)
        }
        override suspend fun details(manga: Manga) = this.manga
        override suspend fun readChapter(manga: Manga, chapter: Chapter): Chapter { reads++; return chapter }
    }
    private fun resolver(health: SourceHealthMemory = SourceHealthMemory.inMemory(), source: MangaSource = Source(otherBook)) =
        ChapterFallbackResolver(mapOf("weebcentral" to source), health)

    @Test fun chapterOneIsNotTen() {
        assertTrue(resolver().conservativeChapterMatches(listOf(primary.copy(title = "Chapter 10")), primary.copy(title = "Chapter 1")).isEmpty())
    }
    @Test fun chapterTwoIsNotTwenty() {
        assertTrue(resolver().conservativeChapterMatches(listOf(primary.copy(title = "Chapter 20")), primary.copy(title = "Chapter 2")).isEmpty())
    }
    @Test fun tenEqualsTenPointZero() {
        val candidate = primary.copy(title = "Chapter 10.0")
        assertEquals(listOf(candidate), resolver().conservativeChapterMatches(listOf(candidate), primary.copy(title = "Chapter 10")))
    }
    @Test fun tenPointFiveEqualsTenPointFifty() {
        val candidate = primary.copy(title = "Chapter 10.50")
        assertEquals(listOf(candidate), resolver().conservativeChapterMatches(listOf(candidate), primary.copy(title = "Chapter 10.5")))
    }
    @Test fun fractionalLeadingZeroIsSignificant() {
        assertTrue(resolver().conservativeChapterMatches(listOf(primary.copy(title = "Chapter 10.05")), primary.copy(title = "Chapter 10.5")).isEmpty())
    }
    @Test fun normalizationPreservesIntegerZerosAndRejectsVolumeNumbers() {
        val resolver = resolver()
        listOf("1", "10", "20", "100", "10.05").forEach { assertEquals(it, resolver.chapterNumber("Chapter $it")) }
        assertEquals("10", resolver.chapterNumber("Chapter 10.00"))
        assertEquals("10.5", resolver.chapterNumber("Chapter 10.50"))
        assertNull(resolver.chapterNumber("Volume 1 Chapter 10"))
    }
    @Test fun duplicateScanlatorBlockedEvenWhenOnlyOneTitleMatchesExactly() = runBlocking {
        val source = Source(otherBook.copy(chapters = listOf(alternate, alternate.copy(id = "group-c", title = "Chapter 4.0: Dawn", scanlator = "C"))))
        assertTrue(resolver(source = source).resolve("atsu", book, primary) is FallbackResult.Blocked)
        assertEquals(0, source.reads)
    }
    @Test fun primary410FallsBackWithWholePageSetAndPrimaryIdentity() = runBlocking {
        val health = SourceHealthMemory.inMemory()
        health.recordFailure(key, http(410))
        val found = resolver(health).resolve("atsu", book, primary) as FallbackResult.Found
        val replacement = primary.withFallbackPages(found)
        assertTrue(health.isPermanentlyFailed(key))
        assertEquals(primary.id, replacement.id)
        assertEquals(primary.remoteId, replacement.remoteId)
        assertEquals(primary.scanlator, replacement.scanlator)
        assertEquals(primary.title, replacement.title)
        assertEquals(2, replacement.pageCount)
        assertTrue(replacement.pages.all { it is Page.Remote && it.sourceId == "weebcentral" })
        assertEquals(SourceFailureKey("atsu", book.id, primary.id), SourceFailureKey("atsu", book.id, replacement.id))
    }
    @Test fun failedFallbackCandidateIsNotSearchedAgain() = runBlocking {
        val health = SourceHealthMemory.inMemory()
        val source = Source(otherBook)
        val resolver = resolver(health, source)
        val first = resolver.resolve("atsu", book, primary) as FallbackResult.Found
        health.recordFailure(SourceFailureKey(first.sourceId, book.id, primary.id), http(404))
        repeat(2) { assertEquals(FallbackResult.Unavailable, resolver.resolve("atsu", book, primary)) }
        assertEquals(1, source.searches)
        assertEquals(1, source.reads)
        assertFalse(health.isPermanentlyFailed(SourceFailureKey(first.sourceId, otherBook.id, alternate.id)))
    }
    @Test fun timeoutDoesNotPoisonHealthAndCandidateCanRecover() = runBlocking {
        val health = SourceHealthMemory.inMemory()
        val source = Source(otherBook, SocketTimeoutException())
        val resolver = resolver(health, source)
        health.recordFailure(key, ImageDiagnostics.classify(SocketTimeoutException()))
        assertEquals(FallbackResult.Unavailable, resolver.resolve("atsu", book, primary))
        source.failure = null
        assertTrue(resolver.resolve("atsu", book, primary) is FallbackResult.Found)
        assertEquals(2, source.searches)
        assertFalse(health.isPermanentlyFailed(key))
    }
    @Test fun rateLimitServerAndConnectionFailuresStayTransient() {
        val health = SourceHealthMemory.inMemory()
        val failures = listOf(http(429), http(500), http(503), ImageDiagnostics.classify(UnknownHostException()))
        failures.forEach { failure ->
            assertFalse(failure.permanent)
            assertFalse(failure.fallbackEligible)
            health.recordFailure(key, failure)
        }
        assertEquals("HTTP_429", failures.first().kind)
        assertFalse(health.isPermanentlyFailed(key))
    }
    @Test fun forbiddenMayFallBackButIsOnlyExcludedForCurrentSession() = runBlocking {
        val failure = http(403)
        val health = SourceHealthMemory.inMemory()
        health.recordFailure(key, failure)
        assertTrue(failure.fallbackEligible)
        assertFalse(health.isPermanentlyFailed(key))
        val source = Source(otherBook)
        val resolver = resolver(health, source)
        assertEquals(FallbackResult.Unavailable, resolver.resolve("atsu", book, primary, setOf("weebcentral")))
        assertEquals(0, source.searches)
        assertTrue(resolver.resolve("atsu", book, primary) is FallbackResult.Found)
    }
    @Test fun healthIsBoundedAndGroupAndMangaScopesRemainSeparate() {
        val health = SourceHealthMemory.inMemory()
        repeat(201) { health.markPermanent(key.copy(primaryChapterId = "chapter-$it")) }
        assertFalse(health.isPermanentlyFailed(key.copy(primaryChapterId = "chapter-0")))
        assertTrue(health.isPermanentlyFailed(key.copy(primaryChapterId = "chapter-200")))
        assertFalse(health.isPermanentlyFailed(key.copy(primaryMangaId = "atsu.other", primaryChapterId = "chapter-200")))
        assertFalse(health.isPermanentlyFailed(key.copy(sourceId = "weebcentral", primaryChapterId = "chapter-200")))
    }
    @Test fun titleMatchingHandlesUnicodeButNeverUsesSimilarTitles() = runBlocking {
        val composed = book.copy(title = "Étoile — 東京")
        val decomposed = otherBook.copy(title = "E\u0301TOILE: 東京")
        assertTrue(resolver(source = Source(decomposed)).resolve("atsu", composed, primary) is FallbackResult.Found)
        assertEquals(FallbackResult.Unavailable, resolver(source = Source(otherBook.copy(title = "JoJolion Extra"))).resolve("atsu", book, primary))
    }
    @Test fun cancellationIsNotConvertedToUnavailable() = runBlocking {
        try {
            resolver(source = Source(otherBook, CancellationException())).resolve("atsu", book, primary)
            fail("Cancellation must leave the reader")
        } catch (_: CancellationException) { }
    }
    @Test fun unscopedOrAtsuRequestDoesNotInheritWeebHeadersForReusedUrl() {
        val url = "https://cdn.test/reused.jpg"
        val request = okhttp3.Request.Builder().url(url).header("Accept", "original").build()
        ImagePipeline.register(Page.Remote(url, "weebcentral"))
        assertEquals("https://weebcentral.com/", ImagePipeline.applySourceHeaders(request).header("Referer"))
        ImagePipeline.register(Page.Remote(url, "atsu"))
        assertSame(request, ImagePipeline.applySourceHeaders(request))
        ImagePipeline.register(Page.Remote(url, "weebcentral"))
        ImagePipeline.register(Page.Remote(url))
        assertSame(request, ImagePipeline.applySourceHeaders(request))
    }
}
