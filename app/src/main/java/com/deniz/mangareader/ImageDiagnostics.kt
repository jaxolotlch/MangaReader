package com.deniz.mangareader

import android.util.Log
import coil3.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class NonImageResponse(val mime: String) : IOException("Non-image response: $mime")

// Shared transport for Coil and explicit original-file downloads; no second transient cache.
object ImagePipeline {
    private val pageMetadata = ConcurrentHashMap<String, Page.Remote>()

    fun register(page: Page.Remote) {
        if (page.sourceId != null) pageMetadata[page.url] = page
    }

    internal fun applySourceHeaders(original: Request): Request {
        val page = pageMetadata[original.url.toString()]
        if (page?.sourceId != "weebcentral") return original
        return original.newBuilder()
            .header("Referer", page.referrer ?: "https://weebcentral.com/")
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("User-Agent", "MangaReader/0.3 (Android)")
            .build()
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Normal cross-origin <img> delivery headers; no cookies, tokens, or challenge data.
            chain.proceed(applySourceHeaders(chain.request()))
        }
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val mime = response.body?.contentType()?.toString().orEmpty()
            if (response.isSuccessful && (mime.startsWith("text/html") || mime.contains("json"))) {
                response.close()
                throw NonImageResponse(mime)
            }
            response
        }.build()
}

data class ImageFailure(
    val kind: String,
    val message: String,
    val mayBeCorrupt: Boolean = false,
    val permanent: Boolean = false
)

object ImageDiagnostics {
    fun classify(error: Throwable): ImageFailure {
        val causes = generateSequence(error) { it.cause }.take(10).toList()
        val http = causes.filterIsInstance<HttpException>().firstOrNull()
        if (http != null) return ImageFailure("HTTP_${http.response.code}", when (http.response.code) {
            403 -> "Kaynak bu görsele erişime izin vermiyor (403)."
            410 -> "Görsel kaynak tarafından kaldırılmış veya artık sunulmuyor (410)."
            404 -> "Görsel kaynakta bulunamadı (404)."
            429 -> "Kaynak çok fazla istek aldı. Biraz sonra tekrar dene (429)."
            504 -> "Görsele ulaşılamadı; çevrimdışıysa yerel kopyası bulunmuyor."
            in 500..599 -> "Kaynak sunucusunda hata var (${http.response.code})."
            else -> "Görsel isteği başarısız (${http.response.code})."
        }, permanent = http.response.code == 404 || http.response.code == 410)
        if (causes.any { it is NonImageResponse }) return ImageFailure("NON_IMAGE", "Kaynak görsel yerine farklı bir yanıt gönderdi.", true)
        if (causes.any { it is SocketTimeoutException }) return ImageFailure("TIMEOUT", "Görsel yükleme zaman aşımına uğradı.")
        if (causes.any { it is UnknownHostException || it is ConnectException }) return ImageFailure("NETWORK", "Kaynağa bağlanılamadı. Çevrimdışı olabilirsin.")
        val text = causes.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }.lowercase()
        if (text.contains("nullrequest") || text.contains("invalid url") || text.contains("invalid uri"))
            return ImageFailure("INVALID_URL", "Kaynak geçerli bir görsel adresi sağlamadı.")
        if (text.contains("decode") || text.contains("bitmap")) return ImageFailure("DECODE_OR_CACHE", "Görsel çözümlenemedi; dosya bozuk veya biçimi desteklenmiyor.", true)
        if (text.contains("disk") || text.contains("enospc") || text.contains("file")) return ImageFailure("DISK", "Yerel görsel dosyası okunamadı veya depolama alanı yetersiz.")
        if (causes.any { it is IOException }) return ImageFailure("NETWORK_IO", "Görsel aktarımı tamamlanamadı. Tekrar deneyebilirsin.")
        return ImageFailure("UNKNOWN", "Görsel açılamadı. Tekrar deneyebilirsin.")
    }
    fun report(error: Throwable, context: String, prefetch: Boolean = false): ImageFailure {
        val failure = classify(error)
        Log.w("MangaImage", "${if (prefetch) "prefetch" else "visible"} $context kind=${failure.kind}", error)
        return failure
    }
}

