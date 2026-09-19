package com.deniz.mangareader

import android.content.Context
import android.content.SharedPreferences
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

data class SourceFailureKey(
    val sourceId: String,
    val primaryMangaId: String,
    val primaryChapterId: String
) {
    fun encoded(): String = listOf(sourceId, primaryMangaId, primaryChapterId).joinToString("|")
}

class SourceHealthMemory private constructor(private val preferences: SharedPreferences?) {
    private val failures = LinkedHashSet<String>().apply {
        addAll(preferences?.getStringSet(KEY, emptySet()).orEmpty().toList().takeLast(MAX_ENTRIES))
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

    fun recordFailure(key: SourceFailureKey, failure: ImageFailure) {
        if (failure.permanent) markPermanent(key)
    }

    companion object {
        private const val KEY = "permanent_source_failures"
        private const val MAX_ENTRIES = 200

        fun inMemory() = SourceHealthMemory(null)
        private var instance: SourceHealthMemory? = null
        @Synchronized
        fun get(context: Context): SourceHealthMemory = instance ?: SourceHealthMemory(
            context.applicationContext.getSharedPreferences("source_health", Context.MODE_PRIVATE)
        ).also { instance = it }
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
    suspend fun resolve(
        primarySourceId: String, manga: Manga, chapter: Chapter,
        excludedSources: Set<String> = emptySet()
    ): FallbackResult {
        if (normalizeTitle(manga.title).isBlank()) return FallbackResult.Blocked("Manga başlığı doğrulanamıyor.")
        val eligible = sources.filterKeys { sourceId ->
            sourceId != primarySourceId && sourceId !in excludedSources && !health.isPermanentlyFailed(
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
                if (normalizeTitle(alternateManga.title) != normalizeTitle(manga.title)) continue
                val chapterMatches = conservativeChapterMatches(alternateManga.chapters, chapter)
                if (chapterMatches.size != 1) {
                    if (chapterMatches.size > 1) ambiguousReason = "$sourceId bölüm eşleşmesi belirsiz."
                    continue
                }
                val loaded = source.readChapter(alternateManga, chapterMatches.single())
                if (loaded.pages.isNotEmpty()) return FallbackResult.Found(sourceId, loaded.copy(
                    pages = loaded.pages.map { page ->
                        if (page is Page.Remote) page.copy(sourceId = sourceId) else page
                    }
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Metadata/parsing/connection failures are not proof of permanently missing images.
            }
        }
        return ambiguousReason?.let(FallbackResult::Blocked) ?: FallbackResult.Unavailable
    }

    internal fun conservativeChapterMatches(candidates: List<Chapter>, primary: Chapter): List<Chapter> {
        val number = chapterNumber(primary.title)
        if (number != null) {
            // Check ALL groups before accepting even an exact title match.
            return candidates.filter { chapterNumber(it.title) == number }
        }
        return candidates.filter { normalizeTitle(it.title) == normalizeTitle(primary.title) }
    }

    private fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    internal fun chapterNumber(value: String): String? {
        // Never mistake a volume number or a number embedded in a title for a chapter.
        val match = Regex("^(?:(?:chapter|ch\\.?|bölüm)\\s*|#)?(\\d+(?:\\.\\d+)?)(?=\\s|:|$)", RegexOption.IGNORE_CASE)
            .find(value.trim()) ?: return null
        return BigDecimal(match.groupValues[1]).stripTrailingZeros().toPlainString()
    }
}

// Only replace delivery data. IDs, scanlator and title continue to belong to the primary source.
internal fun Chapter.withFallbackPages(found: FallbackResult.Found): Chapter =
    copy(pages = found.chapter.pages, pageCount = found.chapter.pages.size)
