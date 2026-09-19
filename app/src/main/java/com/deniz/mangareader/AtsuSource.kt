package com.deniz.mangareader

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AtsuSource : MangaSource {
    override fun getManga(): List<Manga> = emptyList()

    override suspend fun search(query: String): List<Manga> {
        if (query.isBlank()) return emptyList()
        val json = request("/collections/manga/documents/search", mapOf(
            "q" to query.trim(), "query_by" to "title", "per_page" to "30",
            "include_fields" to "id,title,poster"
        ))
        return json.getJSONArray("hits").objects().map { hit ->
            val item = hit.getJSONObject("document")
            Manga("atsu.${item.getString("id")}", item.getString("title"),
                Page.Remote(imageUrl(item.getString("poster")), "atsu"), emptyList())
        }
    }

    override suspend fun details(manga: Manga): Manga {
        val id = manga.id.removePrefix("atsu.")
        val info = request("/api/manga/info", mapOf("mangaId" to id))
        val page = request("/api/manga/page", mapOf("id" to id)).getJSONObject("mangaPage")
        val groups = page.getJSONArray("scanlators").objects().associate {
            it.getString("id") to it.getString("name")
        }
        val chapters = request("/api/manga/allChapters", mapOf("mangaId" to id))
            .getJSONArray("chapters").objects().sortedBy { it.optDouble("number", 0.0) }.map {
                val remoteId = it.getString("id")
                val groupId = it.getString("scanlationMangaId")
                // Never deduplicate by chapter number: each group keeps its own progress key.
                Chapter("$groupId.$remoteId", it.getString("title"), emptyList(),
                    pageCount = it.optInt("pageCount", 0), remoteId = remoteId,
                    scanlator = groups[groupId] ?: groupId)
            }
        return manga.copy(title = info.getString("title"), chapters = chapters)
    }

    override suspend fun readChapter(manga: Manga, chapter: Chapter): Chapter {
        val json = request("/api/read/chapter", mapOf(
            "mangaId" to manga.id.removePrefix("atsu."), "chapterId" to chapter.remoteId
        )).getJSONObject("readChapter")
        val pages = json.getJSONArray("pages").objects().sortedBy { it.getInt("number") }
            .map { Page.Remote(imageUrl(it.getString("image")), "atsu") }
        if (pages.isEmpty()) throw IOException("Bu bölümde okunabilir sayfa bulunamadı.")
        return chapter.copy(pages = pages, pageCount = pages.size)
    }

    private fun imageUrl(path: String): String {
        val url = URI("https://atsu.moe/").resolve(path)
        if (url.scheme !in listOf("https", "http") || url.host.isNullOrBlank()) {
            throw IOException("Atsu geçerli bir görsel adresi döndürmedi.")
        }
        return url.toString()
    }

    private suspend fun request(path: String, parameters: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = URL("https://atsu.moe$path?$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Atsu isteği başarısız (HTTP $code).")
            if (!connection.contentType.orEmpty().contains("application/json", true)) {
                throw IOException("Atsu API yanıtı alınamadı; erişim kısıtlanmış olabilir.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            ensureActive()
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
}
