package com.deniz.mangareader

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import coil3.SingletonImageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal fun storageHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

object DownloadedImages {
    fun file(context: Context, url: String): File = File(context.filesDir, "downloads/images/${storageHash(url)}.img")
    fun existing(context: Context, url: String): File? = file(context, url).takeIf { it.isFile && it.length() > 0 }
    internal fun requestData(page: Page.Remote, localFile: (String) -> File?): Any =
        localFile(page.url) ?: page.url
}

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data class Downloading(val completed: Int, val total: Int) : DownloadState
    data class Downloaded(val total: Int) : DownloadState
    data class Failed(val completed: Int, val total: Int, val reason: String) : DownloadState
    data object Deleting : DownloadState
}

internal fun restoredDownloadState(completed: Int, total: Int): DownloadState =
    if (total > 0 && completed == total) DownloadState.Downloaded(total)
    else DownloadState.Failed(completed, total, "Yarım kaldı")

// One foreground-process worker. Completed files and manifests survive process death; retry skips finished files.
class ChapterDownloads private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private var job: Job? = null
    private var activeKey: String? = null
    private val manifests = File(context.filesDir, "downloads/chapters")

    init {
        scope.launch { mutex.withLock {
            manifests.listFiles { file -> file.extension == "json" }?.forEach { file ->
                try {
                    val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                    val urls = urls(json)
                    val count = urls.count { DownloadedImages.existing(context, it) != null }
                    update(json.getString("key"), restoredDownloadState(count, urls.size))
                } catch (_: Exception) { /* An unreadable manifest is never treated as a complete download. */ }
            }
        } }
    }
    private fun update(key: String, state: DownloadState) { mutableStates.value = mutableStates.value + (key to state) }
    private fun manifest(key: String) = AtomicFile(File(manifests, "${storageHash(key)}.json"))
    private fun urls(json: JSONObject): List<String> = json.getJSONArray("urls").let { a -> List(a.length()) { a.getString(it) } }

    @Synchronized
    fun start(key: String, source: MangaSource, manga: Manga, chapter: Chapter, saveMetadata: (Chapter) -> Unit) {
        if (job?.isActive == true) return
        activeKey = key
        update(key, DownloadState.Downloading(0, chapter.pageCount))
        job = scope.launch { mutex.withLock {
            try {
                val loaded = if (chapter.pages.isEmpty()) source.readChapter(manga, chapter) else chapter
                withContext(Dispatchers.Main) { saveMetadata(loaded) }
                val remotePages = loaded.pages.map { it as? Page.Remote ?: throw IOException("Geçersiz sayfa adresi.") }
                remotePages.forEach(ImagePipeline::register)
                val pages = remotePages.map { it.url }
                if (pages.isEmpty()) throw IOException("Bölüm boş.")
                val existing = pages.count { DownloadedImages.existing(context, it) != null }
                update(key, DownloadState.Downloading(existing, pages.size))
                manifests.mkdirs()
                val atomic = manifest(key)
                val out = atomic.startWrite()
                try {
                    out.write(JSONObject().put("key", key).put("urls", JSONArray(pages)).toString().toByteArray())
                    atomic.finishWrite(out)
                } catch (ex: Exception) { atomic.failWrite(out); throw ex }
                for ((index, url) in pages.withIndex()) {
                    ensureActive()
                    if (DownloadedImages.existing(context, url) == null) download(url)
                    update(key, DownloadState.Downloading(index + 1, pages.size))
                }
                update(key, DownloadState.Downloaded(pages.size))
            } catch (cancelled: CancellationException) {
                val previous = mutableStates.value[key] as? DownloadState.Downloading
                update(key, DownloadState.Failed(previous?.completed ?: 0, previous?.total ?: 0, "Yarım kaldı"))
                throw cancelled
            } catch (ex: Exception) {
                ImageDiagnostics.report(ex, "download=$key")
                val previous = mutableStates.value[key] as? DownloadState.Downloading
                update(key, DownloadState.Failed(
                    previous?.completed ?: 0,
                    previous?.total ?: 0,
                    ex.message ?: "İndirme başarısız"
                ))
            }
        } }
    }

    private suspend fun download(url: String) {
        val target = DownloadedImages.file(context, url)
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        try {
            val cache = SingletonImageLoader.get(context).diskCache
            try {
            val snapshot = cache?.openSnapshot(url)
            if (snapshot != null) {
                snapshot.use { cached ->
                    cache.fileSystem.source(cached.data).use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = okio.Buffer()
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer, 64 * 1024L)
                                if (count == -1L) break
                                output.write(buffer.readByteArray())
                            }
                            output.fd.sync()
                        }
                    }
                }
                if (validImage(part)) { if (!part.renameTo(target)) throw IOException("Dosya kaydedilemedi."); return }
                part.delete()
                cache.remove(url)
            }
            } catch (cachedFailure: IOException) {
                ImageDiagnostics.report(cachedFailure, "download cache-read")
                part.delete()
                runCatching { cache?.remove(url) }
            }
            val call = ImagePipeline.client.newCall(Request.Builder().url(url).build())
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("İndirme HTTP ${response.code}")
                val body = response.body ?: throw IOException("Boş görsel yanıtı.")
                body.byteStream().use { input -> FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
            }
            if (!validImage(part)) throw IOException("Image decode validation failed")
            if (!part.renameTo(target)) throw IOException("Dosya kaydedilemedi.")
        } finally { part.delete() }
    }
    private fun validImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return file.length() > 0 && bounds.outWidth > 0 && bounds.outHeight > 0
    }

    fun remove(key: String) {
        update(key, DownloadState.Deleting)
        scope.launch {
            if (activeKey == key) job?.cancelAndJoin()
            mutex.withLock {
                try {
                    val atomic = manifest(key)
                    val removed = urls(JSONObject(atomic.openRead().bufferedReader().use { it.readText() }))
                    // Keep shared originals referenced by any other chapter manifest.
                    val retained = manifests.listFiles { f -> f.extension == "json" && f != atomic.baseFile }.orEmpty()
                        .flatMap { urls(JSONObject(AtomicFile(it).openRead().bufferedReader().use { r -> r.readText() })) }.toSet()
                    removed.filterNot { it in retained }.forEach {
                        val image = DownloadedImages.file(context, it)
                        if (image.exists() && !image.delete()) throw IOException("Dosya silinemedi.")
                    }
                    atomic.delete()
                    mutableStates.value = mutableStates.value - key
                } catch (ex: Exception) {
                    update(key, DownloadState.Failed(0, 0, "İndirme silinemedi"))
                }
            }
        }
    }
    companion object {
        @Volatile private var instance: ChapterDownloads? = null
        fun get(context: Context): ChapterDownloads = instance ?: synchronized(this) {
            instance ?: ChapterDownloads(context.applicationContext).also { instance = it }
        }
    }
}

