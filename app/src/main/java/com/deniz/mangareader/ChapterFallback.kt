package com.deniz.mangareader

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.Locale

data class SourceFailureKey(
    val sourceId: String,
    val primaryMangaId: String,
    val primaryChapterId: String
) {
    fun encoded(): String = listOf(sourceId, primaryMangaId, primaryChapterId).joinToString("|")
}

class SourceHealthMemory private constructor(private val preferences: SharedPreferences?) {
    private val failures = LinkedHashSet<String>().apply {
        addAll(preferences?.getStringSet(KEY, emptySet()).orEmpty())
    }

    @Synchronized
    fun markPermanent(key: SourceFailureKey) {
        failures.remove(key.encoded())
        failures.add(key.encoded())
        while (failures.size > MAX_ENTRIES) failures.remove(failures.first())
        preferences?.edit()?.putStringSet(KEY, failures.toSet())?.apply()
    }

    @Synchronized
    fun isPermanentlyFailed(key: SourceFailureKey): Boolean = key.encoded() in failures

    companion object {
        private const val KEY = "permanent_source_failures"
        private const val MAX_ENTRIES = 200

        fun inMemory() = SourceHealthMemory(null)
        fun get(context: Context) = SourceHealthMemory(
            context.applicationContext.getSharedPreferences("source_health", Context.MODE_PRIVATE)
        )
    }
}

sealed interface FallbackResult {
    data class Found(val sourceId: String, val chapter: Chapter) : FallbackResult
    data class Blocked(val reason: String) : FallbackResult
    data object Unavailable : FallbackResult
}

class ChapterFallbackResolver(
    private val sources: Map<String, MangaSource>,
    private val health: SourceHealthMemory
) {
    suspend fun resolve(primarySourceId: String, manga: Manga, chapter: Chapter): FallbackResult {
        val eligible = sources.filterKeys { sourceId ->
            sourceId != primarySourceId && !health.isPermanentlyFailed(
                SourceFailureKey(sourceId, manga.id, chapter.id)
            )
        }
        var ambiguousReason: String? = null
        for ((sourceId, source) in eligible) {
            try {
                val mangaMatches = source.search(manga.title)
                    .filter { normalizeTitle(it.title) == normalizeTitle(manga.title) }
                if (mangaMatches.size != 1) {
                    if (mangaMatches.size > 1) ambiguousReason = "$sourceId manga eşleşmesi belirsiz."
                    continue
                }
                val alternateManga = source.details(mangaMatches.single())
                val chapterMatches = conservativeChapterMatches(alternateManga.chapters, chapter)
                if (chapterMatches.size != 1) {
                    if (chapterMatches.size > 1) ambiguousReason = "$sourceId bölüm eşleşmesi belirsiz."
                    continue
                }
                val loaded = source.readChapter(alternateManga, chapterMatches.single())
                if (loaded.pages.isNotEmpty()) return FallbackResult.Found(sourceId, loaded)
            } catch (_: IOException) {
                // A source being temporarily unavailable does not poison its health memory.
            }
        }
        return ambiguousReason?.let(FallbackResult::Blocked) ?: FallbackResult.Unavailable
    }

    internal fun conservativeChapterMatches(candidates: List<Chapter>, primary: Chapter): List<Chapter> {
        val exact = candidates.filter { normalizeTitle(it.title) == normalizeTitle(primary.title) }
        if (exact.isNotEmpty()) return exact
        val number = chapterNumber(primary.title) ?: return emptyList()
        return candidates.filter { chapterNumber(it.title) == number }
    }

    private fun normalizeTitle(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun chapterNumber(value: String): String? = Regex("(?<!\\d)(\\d+(?:\\.\\d+)?)(?!\\d)")
        .find(value)?.groupValues?.get(1)?.trimEnd('0')?.trimEnd('.')
}
