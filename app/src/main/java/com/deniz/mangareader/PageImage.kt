package com.deniz.mangareader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.SingletonImageLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlin.random.Random

@Composable
fun PageImage(
    page: Page,
    contentDescription: String,
    modifier: Modifier = Modifier,
    paper: Boolean = false,
    onLoaded: () -> Unit = {},
    debugContext: String = contentDescription,
    onPermanentFailure: (Page.Remote, ImageFailure) -> Unit = { _, _ -> }
) {
    when (page) {
        is Page.Local -> {
            androidx.compose.runtime.LaunchedEffect(page) { onLoaded() }
            PrintedImage(painterResource(page.imageRes), contentDescription, modifier, paper)
        }
        is Page.Remote -> {
            val context = LocalContext.current
            val request = remember(context, page.url) {
                ImagePipeline.register(page)
                // Unsupported schemes become a normal loading error, never a file/content fetch.
                val uri = android.net.Uri.parse(page.url)
                val valid = uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
                ImageRequest.Builder(context)
                    .data(if (valid) DownloadedImages.requestData(page) { DownloadedImages.existing(context, it) } else null)
                    .diskCacheKey(page.url)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = ContentScale.Fit,
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Color(0xFFE2DDD5))
                    }
                },
                error = {
                    val failedPainter = painter
                    val failure = remember(it.result.throwable) { ImageDiagnostics.classify(it.result.throwable) }
                    LaunchedEffect(it.result) {
                        ImageDiagnostics.report(it.result.throwable, debugContext)
                        if (failure.permanent) onPermanentFailure(page, failure)
                    }
                    val scope = rememberCoroutineScope()
                    var retrying by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(failure.message, color = Color(0xFFE2DDD5))
                        TextButton(enabled = !retrying, onClick = {
                            retrying = true
                            scope.launch {
                                try {
                                    if (failure.mayBeCorrupt) withContext(Dispatchers.IO) {
                                        SingletonImageLoader.get(context).diskCache?.remove(page.url)
                                    }
                                    failedPainter.restart()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) { ImageDiagnostics.report(failure, debugContext) } finally { retrying = false }
                            }
                        }) {
                            Text("Tekrar dene", color = Color(0xFFE2DDD5))
                        }
                    }
                },
                success = {
                    androidx.compose.runtime.LaunchedEffect(page) { onLoaded() }
                    PrintedImage(painter, contentDescription, Modifier.fillMaxSize(), paper)
                }
            )
        }
    }
}

@Composable
private fun PrintedImage(painter: Painter, description: String, modifier: Modifier, paper: Boolean) {
    val warmth = remember {
        ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 0.965f, 0f, 0f, 0f,
            0f, 0f, 0.89f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
    }
    Image(painter, description, contentScale = ContentScale.Fit,
        colorFilter = if (paper) warmth else null,
        modifier = if (!paper) modifier else modifier.drawWithCache {
            // Follow the decoded image's true bounds, including non-2:3 remote pages.
            val intrinsic = painter.intrinsicSize
            val iw = if (intrinsic.width.isFinite() && intrinsic.width > 0) intrinsic.width else 720f
            val ih = if (intrinsic.height.isFinite() && intrinsic.height > 0) intrinsic.height else 1080f
            val fit = minOf(size.width / iw, size.height / ih)
            val width = iw * fit
            val height = ih * fit
            val left = (size.width - width) / 2f
            val top = (size.height - height) / 2f
            val random = Random(42)
            val grain = List(6500) {
                Offset(left + random.nextFloat() * width, top + random.nextFloat() * height)
            }
            onDrawWithContent {
                drawContent()
                grain.forEach { drawCircle(Color(0x092C2118), 0.65f * width / 720f, it) }
            }
        })
}





