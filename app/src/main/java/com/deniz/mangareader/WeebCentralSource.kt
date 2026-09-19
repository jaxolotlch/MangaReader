package com.deniz.mangareader

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class WeebCentralSource : MangaSource {
    override fun getManga(): List<Manga> = emptyList()
    override suspend fun search(query: String): List<Manga> {
        if (query.isBlank()) return emptyList()
        return parseSearch(request("/search/data?text=${URLEncoder.encode(query.trim(), "UTF-8")}&display_mode=Full%20Display"))
    }
    override suspend fun details(manga: Manga): Manga {
        val id = manga.id.removePrefix("weebcentral.")
        require(id.matches(Regex("[A-Za-z0-9]+")))
        val chapters = parseChapters(request("/series/$id/full-chapter-list"))
        if (chapters.isEmpty()) throw IOException("WeebCentral bölüm listesi alınamadı.")
        return manga.copy(chapters = chapters)
    }
    override suspend fun readChapter(manga: Manga, chapter: Chapter): Chapter {
        require(chapter.remoteId.matches(Regex("[A-Za-z0-9]+")))
        val referrer = "https://weebcentral.com/chapters/${chapter.remoteId}"
        val pages = parsePages(request("/chapters/${chapter.remoteId}/images?is_prev=False&reading_style=long_strip"))
            .map { page -> (page as Page.Remote).copy(referrer = referrer) }
        if (pages.isEmpty()) throw IOException("WeebCentral sayfa listesi alınamadı.")
        return chapter.copy(pages = pages, pageCount = pages.size)
    }
    private suspend fun request(path: String): Document = withContext(Dispatchers.IO) {
        val connection = URL("https://weebcentral.com$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("WeebCentral isteği başarısız (HTTP $status).")
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            ensureActive()
            val document = Jsoup.parse(html, "https://weebcentral.com/")
            if (document.title().contains("Just a moment", true) || document.selectFirst("#challenge-form") != null)
                throw IOException("WeebCentral erişim kontrolü nedeniyle yanıt vermedi.")
            document
        } finally { connection.disconnect() }
    }
    internal fun parseSearch(document: Document): List<Manga> = document.select("a[href*=/series/]")
        .mapNotNull { link ->
            val image = link.selectFirst("img[alt][src]") ?: return@mapNotNull null
            val id = link.absUrl("href").substringAfter("/series/").substringBefore('/')
            val title = image.attr("alt").removeSuffix(" cover").trim()
            if (id.isBlank() || title.isBlank()) null else
                Manga("weebcentral.$id", title, Page.Remote(image.absUrl("src"), "weebcentral", "https://weebcentral.com/"), emptyList())
        }.distinctBy { it.id }
    internal fun parseChapters(document: Document): List<Chapter> = document.select("a[href*=/chapters/]")
        .map { link ->
            val id = link.absUrl("href").substringAfter("/chapters/").substringBefore('?').substringBefore('/')
            val title = link.selectFirst("span.grow > span")?.text()?.trim().orEmpty()
            Chapter(id, title.ifBlank { link.ownText().ifBlank { "Bölüm $id" } }, emptyList())
        }.distinctBy { it.id }.asReversed()
    internal fun parsePages(document: Document): List<Page> = document.select("img[alt^=Page][src]")
        .map { Page.Remote(it.absUrl("src"), "weebcentral") }
}
