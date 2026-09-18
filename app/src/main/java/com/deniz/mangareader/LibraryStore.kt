package com.deniz.mangareader

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class ChapterProgress(val id: String, val title: String, val page: Int, val total: Int, val readAt: Long, val completed: Boolean = false)
data class SavedManga(
    val sourceId: String, val mangaId: String, val title: String, val coverUrl: String,
    val inLibrary: Boolean, val lastChapterId: String?, val lastReadAt: Long,
    val chapters: Map<String, ChapterProgress>, val knownChapters: List<Chapter> = emptyList()
) {
    val key: String get() = "$sourceId.$mangaId"
    fun asManga() = Manga(key, title, Page.Remote(coverUrl), knownChapters)
}

// One versioned document; SharedPreferences atomically persists it and flushes apply on lifecycle transitions.
class LibraryStore(private val preferences: SharedPreferences, private val legacy: SharedPreferences) {
    var records by mutableStateOf<Map<String, SavedManga>>(emptyMap())
        private set
    var storageError by mutableStateOf<String?>(null)
        private set

    init {
        try {
            preferences.getString("library", null)?.let { text ->
                val root = JSONObject(text)
                require(root.getInt("version") == 1)
                val books = root.getJSONArray("manga")
                records = (0 until books.length()).map { index ->
                    val b = books.getJSONObject(index)
                    val saved = b.getJSONArray("chapters")
                    val chapters = (0 until saved.length()).map { i ->
                        val c = saved.getJSONObject(i)
                        ChapterProgress(c.getString("id"), c.getString("title"), c.getInt("page"),
                            c.getInt("total"), c.getLong("readAt"), c.optBoolean("completed", false))
                    }.associateBy { it.id }
                    SavedManga(b.getString("sourceId"), b.getString("mangaId"), b.getString("title"),
                        b.getString("coverUrl"), b.getBoolean("inLibrary"),
                        b.optString("lastChapterId").takeIf { it.isNotEmpty() }, b.getLong("lastReadAt"), chapters, ChapterSnapshots.decode(b.optJSONArray("knownChapters")))
                }.associateBy { it.key }
            }
        } catch (_: Exception) {
            // Do not overwrite a file that could not be read.
            storageError = "Kütüphane kaydı okunamadı. Mevcut kayıt değiştirilmedi."
        }
    }

    fun find(sourceId: String, manga: Manga) = records["$sourceId.${manga.id.removePrefix("$sourceId.")}"]
    private fun entry(sourceId: String, manga: Manga): SavedManga {
        val old = find(sourceId, manga)
        return SavedManga(sourceId, manga.id.removePrefix("$sourceId."), manga.title,
            (manga.cover as? Page.Remote)?.url.orEmpty(), old?.inLibrary ?: false,
            old?.lastChapterId, old?.lastReadAt ?: 0L, old?.chapters.orEmpty(), old?.knownChapters.orEmpty())
    }

    fun snapshot(sourceId: String, manga: Manga) {
        val old = entry(sourceId, manga)
        val prior = old.knownChapters.associateBy { it.id }
        val merged = manga.chapters.map { fresh ->
            val cached = prior[fresh.id]
            if (fresh.pages.isEmpty() && cached != null) fresh.copy(pages = cached.pages) else fresh
        }
        // Keep previously known chapters if a source temporarily omits them; progress is never discarded.
        save(old.copy(knownChapters = merged + old.knownChapters.filter { previous -> merged.none { it.id == previous.id } }))
    }

    fun snapshotChapter(sourceId: String, manga: Manga, chapter: Chapter) {
        val base = find(sourceId, manga)?.asManga() ?: manga
        snapshot(sourceId, base.copy(chapters = base.chapters.filterNot { it.id == chapter.id }.let { others ->
            if (base.chapters.any { it.id == chapter.id }) base.chapters.map { if (it.id == chapter.id) chapter else it }
            else others + chapter
        }))
    }

    fun setInLibrary(sourceId: String, manga: Manga, value: Boolean) {
        save(entry(sourceId, manga).copy(inLibrary = value))
    }

    fun record(sourceId: String, manga: Manga, chapter: Chapter, index: Int) {
        if (chapter.pages.isEmpty()) return
        val old = entry(sourceId, manga)
        val now = System.currentTimeMillis()
        val progress = ChapterProgress(chapter.id, chapter.title, index.coerceIn(chapter.pages.indices), chapter.pages.size, now, old.chapters[chapter.id]?.completed == true)
        save(old.copy(inLibrary = true, lastChapterId = chapter.id, lastReadAt = now,
            chapters = old.chapters + (chapter.id to progress)))
    }

    fun markCompleted(sourceId: String, manga: Manga, chapter: Chapter) {
        val old = entry(sourceId, manga)
        val progress = old.chapters[chapter.id] ?: return
        if (!progress.completed) save(old.copy(chapters = old.chapters +
            (chapter.id to progress.copy(completed = true))))
    }

    // Old preferences contain no titles/covers. Import when the manga's metadata first becomes available.
    fun importLegacy(sourceId: String, manga: Manga) {
        val old = entry(sourceId, manga)
        val imported = manga.chapters.mapNotNull { chapter ->
            val key = "page.${manga.id}.${chapter.id}"
            if (!legacy.contains(key) || old.chapters.containsKey(chapter.id)) null else
                chapter.id to ChapterProgress(chapter.id, chapter.title,
                    legacy.getInt(key, 0).coerceIn(0, (chapter.pageCount - 1).coerceAtLeast(0)), chapter.pageCount, 0L)
        }.toMap()
        if (imported.isNotEmpty()) save(old.copy(inLibrary = true,
            lastChapterId = old.lastChapterId ?: legacy.getString("chapter.${manga.id}", null),
            chapters = imported + old.chapters))
    }

    companion object {
        @Volatile private var instance: LibraryStore? = null
        fun get(context: android.content.Context): LibraryStore = instance ?: synchronized(this) {
            instance ?: LibraryStore(context.applicationContext.getSharedPreferences("library_store", 0),
                context.applicationContext.getSharedPreferences("reader", 0)).also { instance = it }
        }
    }

    private fun save(book: SavedManga) {
        if (storageError != null) return
        val updated = records + (book.key to book)
        val books = JSONArray()
        updated.values.forEach { b ->
            val chapters = JSONArray()
            b.chapters.values.forEach { c ->
                chapters.put(JSONObject().put("id", c.id).put("title", c.title).put("page", c.page)
                    .put("total", c.total).put("readAt", c.readAt).put("completed", c.completed))
            }
            books.put(JSONObject().put("sourceId", b.sourceId).put("mangaId", b.mangaId)
                .put("title", b.title).put("coverUrl", b.coverUrl).put("inLibrary", b.inLibrary)
                .put("lastChapterId", b.lastChapterId.orEmpty()).put("lastReadAt", b.lastReadAt).put("chapters", chapters).put("knownChapters", ChapterSnapshots.encode(b.knownChapters)))
        }
        preferences.edit().putString("library", JSONObject().put("version", 1).put("manga", books).toString()).apply()
        records = updated
    }
}



