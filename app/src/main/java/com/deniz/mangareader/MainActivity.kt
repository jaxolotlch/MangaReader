package com.deniz.mangareader

import android.os.Bundle
import okio.Path.Companion.toOkioPath
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deniz.mangareader.ui.theme.MangaReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coil3.SingletonImageLoader.setSafe { context ->
            coil3.ImageLoader.Builder(context)
                .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(ImagePipeline.client)) }
                .diskCache {
                    coil3.disk.DiskCache.Builder()
                        .directory(context.cacheDir.resolve("page_images").toOkioPath())
                        .maxSizeBytes(2L * 1024 * 1024 * 1024)
                        .build()
                }
                .build()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val libraryStore = LibraryStore.get(applicationContext)
        setContent {
            MangaReaderTheme {
                LibraryScreen(libraryStore)
            }
        }
    }
}






