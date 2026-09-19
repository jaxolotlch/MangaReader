package com.deniz.mangareader

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WeebCentralParserTest {
    private val source = WeebCentralSource()
    private fun html(value: String) = Jsoup.parse(value, "https://weebcentral.com/")
    @Test fun extractsCoversAndDecodesTitlesWithoutDuplicateCards() {
        val result = source.parseSearch(html("""<a href="/series/ABC/Name"><picture><img src="https://cdn.example/cover.jpg" alt="One &amp; Two cover"></picture></a><a href="/series/ABC/Name">Title</a>"""))
        assertEquals(1, result.size)
        assertEquals("weebcentral.ABC", result.single().id)
        assertEquals("One & Two", result.single().title)
    }
    @Test fun oldestChapterFirstAndBadgesAreNotPartOfTitle() {
        val result = source.parseChapters(html("""<a href="/chapters/NEW"><span class="grow"><span>Chapter 2</span><span>Last Read</span></span></a><a href="/chapters/OLD"><span class="grow"><span>Chapter 1</span></span></a>"""))
        assertEquals(listOf("OLD", "NEW"), result.map { it.id })
        assertEquals("Chapter 2", result.last().title)
    }
    @Test fun pageOrderAndOriginalUrlsRemainUnchanged() {
        val pages = source.parsePages(html("""<img src="/logo.svg" alt="Logo"><img src="https://cdn.example/01.jpg" alt="Page 1"><img src="https://cdn.example/02.jpg" alt="Page 2">"""))
        assertEquals(listOf(
            Page.Remote("https://cdn.example/01.jpg", "weebcentral"),
            Page.Remote("https://cdn.example/02.jpg", "weebcentral")
        ), pages)
    }
}
