package com.deniz.mangareader

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LibraryPersistenceTest {
    private fun preferences(values: MutableMap<String, Any> = mutableMapOf()): SharedPreferences {
        val pending = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString", "putInt", "putLong", "putBoolean" -> { pending[args!![0] as String] = args[1]; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "apply", "commit" -> { pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; pending.clear(); true }
                else -> proxy
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "contains" -> values.containsKey(args!![0])
                "getAll" -> values.toMap()
                "getString", "getInt", "getLong", "getBoolean" -> values[args!![0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
    }
    private val first = Chapter("group-a.chapter", "Chapter 1", listOf(Page.Remote("https://example.test/1.jpg"), Page.Remote("https://example.test/2.jpg")), scanlator = "A")
    private val second = Chapter("group-b.chapter", "Chapter 1", listOf(Page.Remote("https://example.test/b.jpg")), scanlator = "B")
    private val book = Manga("atsu.test", "Test", Page.Remote("https://example.test/cover.jpg"), listOf(first, second))

    @Test fun restartPreservesBothGroupsMetadataAndCompletion() {
        val prefs = preferences()
        val store = LibraryStore(prefs, preferences())
        store.snapshot("atsu", book)
        store.record("atsu", book, first, 1)
        store.markCompleted("atsu", book, first)
        store.record("atsu", book, second, 0)
        store.record("atsu", book, first, 0)
        val restored = LibraryStore(prefs, preferences()).find("atsu", book)!!
        assertTrue(restored.inLibrary)
        assertEquals(2, restored.chapters.size)
        assertTrue(restored.chapters[first.id]!!.completed)
        assertEquals(0, restored.chapters[first.id]!!.page)
        assertEquals(book.chapters, restored.asManga().chapters)
    }
    @Test fun removingLibraryRetainsMetadataAndReadingHistory() {
        val store = LibraryStore(preferences(), preferences())
        store.snapshot("atsu", book)
        store.record("atsu", book, first, 1)
        store.setInLibrary("atsu", book, false)
        assertFalse(store.find("atsu", book)!!.inLibrary)
        assertEquals(1, store.find("atsu", book)!!.chapters[first.id]!!.page)
        assertEquals(2, store.find("atsu", book)!!.knownChapters.size)
    }
    @Test fun metadataRefreshKeepsKnownPageUrlsAndMissingChapter() {
        val prefs = preferences()
        val store = LibraryStore(prefs, preferences())
        store.snapshot("atsu", book)
        store.snapshot("atsu", book.copy(chapters = listOf(first.copy(pages = emptyList()))))
        val restored = LibraryStore(prefs, preferences()).find("atsu", book)!!
        assertEquals(first.pages, restored.knownChapters.first().pages)
        assertTrue(restored.knownChapters.any { it.id == second.id })
    }
    @Test fun oldVersionOneRecordImportsWithoutOptionalMetadata() {
        val data = """{"version":1,"manga":[{"sourceId":"atsu","mangaId":"test","title":"Test","coverUrl":"https://example.test/c","inLibrary":true,"lastChapterId":"old","lastReadAt":123,"chapters":[{"id":"old","title":"Old","page":4,"total":10,"readAt":123}]}]}"""
        val restored = LibraryStore(preferences(mutableMapOf("library" to data)), preferences()).find("atsu", book)!!
        assertEquals(4, restored.chapters["old"]!!.page)
        assertFalse(restored.chapters["old"]!!.completed)
        assertTrue(restored.knownChapters.isEmpty())
    }
    @Test fun sourceIdentityKeepsSameMangaIdsSeparate() {
        val prefs = preferences()
        val store = LibraryStore(prefs, preferences())
        val other = book.copy(id = "weebcentral.test")
        store.snapshot("atsu", book)
        store.snapshot("weebcentral", other)
        store.record("atsu", book, first, 1)
        store.record("weebcentral", other, first, 0)
        val restored = LibraryStore(prefs, preferences())
        assertEquals(1, restored.find("atsu", book)!!.chapters[first.id]!!.page)
        assertEquals(0, restored.find("weebcentral", other)!!.chapters[first.id]!!.page)
    }
    @Test fun damagedDocumentIsNotOverwritten() {
        val data = mutableMapOf<String, Any>("library" to "broken")
        val store = LibraryStore(preferences(data), preferences())
        store.snapshot("atsu", book)
        assertNotNull(store.storageError)
        assertEquals("broken", data["library"])
    }
    @Test fun fallbackSnapshotPreservesPrimaryHistoryAndOtherScanlatorAfterRestart() {
        val prefs = preferences()
        val store = LibraryStore(prefs, preferences())
        store.snapshot("atsu", book)
        store.record("atsu", book, first, 1)
        store.markCompleted("atsu", book, first)
        store.record("atsu", book, second, 0)
        val before = store.find("atsu", book)!!
        val alternate = Chapter("different-id", "Different source title",
            listOf(Page.Remote("https://cdn.test/a.jpg", "weebcentral")), scanlator = "Other group")
        store.snapshotChapter("atsu", book, first.withFallbackPages(FallbackResult.Found("weebcentral", alternate)))
        val restored = LibraryStore(prefs, preferences()).find("atsu", book)!!
        assertEquals(before.chapters, restored.chapters)
        assertEquals(before.lastReadAt, restored.lastReadAt)
        assertEquals(before.lastChapterId, restored.lastChapterId)
        assertEquals("atsu", restored.sourceId)
        assertEquals(first.id, restored.knownChapters.first().id)
        assertEquals(first.scanlator, restored.knownChapters.first().scanlator)
        assertEquals(alternate.pages, restored.knownChapters.first().pages)
        assertEquals(second, restored.knownChapters.last())
    }
}

