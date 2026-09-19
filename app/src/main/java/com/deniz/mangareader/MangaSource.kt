package com.deniz.mangareader

import androidx.annotation.DrawableRes

sealed interface Page {
    data class Local(@param:DrawableRes val imageRes: Int) : Page
    data class Remote(
        val url: String,
        val sourceId: String? = null,
        val referrer: String? = null
    ) : Page
}
data class Chapter(
    val id: String, val title: String, val pages: List<Page>,
    val pageCount: Int = pages.size, val remoteId: String = id, val scanlator: String? = null
)
data class Manga(val id: String, val title: String, val cover: Page, val chapters: List<Chapter>)

interface MangaSource {
    fun getManga(): List<Manga>
    suspend fun search(query: String): List<Manga> = getManga().filter { it.title.contains(query, true) }
    suspend fun details(manga: Manga): Manga = manga
    suspend fun readChapter(manga: Manga, chapter: Chapter): Chapter = chapter
}

object SourceCatalog {
    fun configured(): Map<String, MangaSource> = linkedMapOf(
        "atsu" to AtsuSource(),
        "weebcentral" to WeebCentralSource()
    )
}

object LocalMangaSource : MangaSource {
    override fun getManga(): List<Manga> {
        val pages = listOf(R.drawable.manga_page_1, R.drawable.manga_page_2, R.drawable.manga_page_3).map { Page.Local(it) }
        return listOf(Manga(
            id = "local_city", title = "Şehirde Bir Gün", cover = Page.Local(R.drawable.manga_page_1),
            chapters = listOf(
                Chapter("morning", "Bölüm 1 · Sabah", pages),
                Chapter("afternoon", "Bölüm 2 · Öğleden sonra", listOf(pages[1], pages[2], pages[0])),
                Chapter("evening", "Bölüm 3 · Akşam", pages.reversed())
            )
        ))
    }
}

// Public photo fixtures, not scraped manga. Stable URLs also serve as cache keys.
object TestHttpMangaSource : MangaSource {
    override fun getManga(): List<Manga> {
        val pages = listOf(10, 20, 30).map {
            Page.Remote("https://picsum.photos/id/$it/720/1080?grayscale")
        }
        return listOf(Manga("http_photos", "Fotoğraf denemesi", pages.first(),
            listOf(Chapter("http_sample", "Bölüm 1 · Test görselleri", pages))))
    }
}

