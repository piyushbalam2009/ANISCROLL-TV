package `in`.aniscroll.tv.artwork

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.EventListener
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.network.NetworkModule
import java.io.File

/**
 * Shared Coil ImageLoader configured with [NetworkModule.imageOkHttpClient], [SvgDecoder.Factory],
 * [Bitmap.Config.ARGB_8888] alpha preservation, and error diagnostics so transparent PNG, SVG, WEBP,
 * and JPG anime title logos from TMDB and Fanart.tv decode reliably on all Fire TV / Android TV APKs.
 */
object LogoImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun installShared(imageLoader: ImageLoader) {
        instance = imageLoader
    }

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    fun create(context: Context): ImageLoader {
        val appCtx = context.applicationContext
        return ImageLoader.Builder(appCtx)
            .okHttpClient { NetworkModule.imageOkHttpClient }
            .components {
                add(SvgDecoder.Factory())
            }
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowRgb565(false)
            .crossfade(true)
            .respectCacheHeaders(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(appCtx)
                    .maxSizePercent(0.12)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appCtx.cacheDir, "aniscroll_logo_image_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .eventListener(object : EventListener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    val (host, sanitizedPath) = LogoLogger.sanitizeUrlForDiagnostics(request.data?.toString())
                    val (category, httpStatus) = LogoLogger.classifyThrowable(result.throwable)
                    LogoLogger.w(
                        "Coil logo decode/fetch error host=$host path=$sanitizedPath http=${httpStatus ?: "-"} " +
                            "category=${category.name}: ${result.throwable.message}",
                        result.throwable
                    )
                }
            })
            .build()
    }
}

/**
 * Shared reusable Anime Title Logo component used across Home Hero and Anime Details screens.
 *
 * Responsibilities:
 * 1. Displays the supplied transparent PNG/SVG/WEBP logo URL from TMDB or Fanart.tv using [LogoImageLoader].
 * 2. Preserves original image aspect ratio using ContentScale.Fit (never crops or stretches).
 * 3. Shows the crisp white typography text fallback immediately while loading, on null/invalid URL, or on load failure.
 * 4. Notifies [onLogoLoadFailed] when an image URL fails to render so the ViewModel/Repository can
 *    invalidate any bad Room cache entry and retry TMDB -> Fanart.tv -> Text fallback.
 */
@Composable
fun AnimeLogo(
    hoshikaId: Long,
    animeTitle: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String = animeTitle,
    maxWidth: Dp = 360.dp,
    maxHeight: Dp = 88.dp,
    onLogoLoadFailed: ((String) -> Unit)? = null
) {
    val cleanUrl = remember(logoUrl) {
        LogoLogger.normalizeSecureArtworkUrl(logoUrl)
    }

    var loadSucceededForCurrentUrl by remember(hoshikaId, cleanUrl) { mutableStateOf(false) }
    var loadFailedForCurrentUrl by remember(hoshikaId, cleanUrl) { mutableStateOf(false) }
    var reportedFailureUrl by remember(hoshikaId, cleanUrl) { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val imageLoader = remember(context) { LogoImageLoader.get(context) }
    val imageRequest = remember(context, cleanUrl) {
        cleanUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }

    val showingLogoImage = imageRequest != null &&
        cleanUrl != null &&
        !loadFailedForCurrentUrl &&
        loadSucceededForCurrentUrl

    val containerModifier = modifier
        .widthIn(max = maxWidth)
        .let { base ->
            if (showingLogoImage) {
                base.heightIn(min = 40.dp, max = maxHeight).clipToBounds()
            } else {
                base.heightIn(min = 40.dp)
            }
        }
        .testTag("anime_logo_container_$hoshikaId")

    Box(
        modifier = containerModifier,
        contentAlignment = Alignment.CenterStart
    ) {
        if (imageRequest != null && cleanUrl != null && !loadFailedForCurrentUrl) {
            AsyncImage(
                model = imageRequest,
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> {
                            val drawable = state.result.drawable
                            val validSize = drawable.intrinsicWidth == -1 ||
                                (drawable.intrinsicWidth > 1 && drawable.intrinsicHeight > 1)
                            if (validSize) {
                                loadSucceededForCurrentUrl = true
                                LogoLogger.d(
                                    "HoshikaID=$hoshikaId title='$animeTitle' -> Image load SUCCESS host=${LogoLogger.extractHost(cleanUrl)}"
                                )
                            } else {
                                loadSucceededForCurrentUrl = false
                                loadFailedForCurrentUrl = true
                                if (reportedFailureUrl != cleanUrl) {
                                    reportedFailureUrl = cleanUrl
                                    onLogoLoadFailed?.invoke(cleanUrl)
                                }
                            }
                        }
                        is AsyncImagePainter.State.Error -> {
                            LogoLogger.w(
                                "HoshikaID=$hoshikaId title='$animeTitle' -> Image load FAILED host=${LogoLogger.extractHost(cleanUrl)}: ${state.result.throwable.message}",
                                state.result.throwable
                            )
                            loadSucceededForCurrentUrl = false
                            loadFailedForCurrentUrl = true
                            if (reportedFailureUrl != cleanUrl) {
                                reportedFailureUrl = cleanUrl
                                onLogoLoadFailed?.invoke(cleanUrl)
                            }
                        }
                        else -> Unit
                    }
                },
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    .alpha(if (showingLogoImage) 1f else 0f)
                    .testTag("anime_logo_image_$hoshikaId")
            )
        }

        if (!showingLogoImage) {
            Text(
                text = animeTitle,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = TvColors.PrimaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("anime_logo_text_fallback_$hoshikaId")
            )
        }
    }
}
