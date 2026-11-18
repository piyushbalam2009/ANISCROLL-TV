package `in`.aniscroll.tv.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import `in`.aniscroll.tv.artwork.ArtworkErrorCategory
import `in`.aniscroll.tv.artwork.ArtworkType
import `in`.aniscroll.tv.artwork.LogoLogger

/**
 * Shared Coil-backed artwork renderer for all posters, backdrop banners, and episode thumbnails.
 *
 * Features:
 * - Always uses the shared singleton Coil [coil.ImageLoader] installed in AppContainer.
 * - Supports primary + fallback URL hierarchy (e.g., banner -> poster, episode thumbnail -> anime poster).
 * - Uses per-item scoped memoryCacheKey (`type:animeId:ep:WxH:url`) to avoid collisions or low-res reuse,
 *   while sharing `diskCacheKey(url)` so valid CDN artwork is cached once on disk.
 * - Handles Loading, Success, Error, and Missing/Invalid URL states gracefully with a clean
 *   monochrome placeholder (never leaves unexplained black rectangles).
 * - Records structured, token-sanitized diagnostics for every failure and success.
 */
@Composable
fun TvArtworkImage(
    primaryUrl: String?,
    artworkType: ArtworkType,
    screenName: String,
    hoshikaId: Long,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    episodeNumber: Int? = null,
    targetWidthPx: Int = 440,
    targetHeightPx: Int = 660,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    loadingColor: Color = TvColors.SecondaryBackground,
    placeholder: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val cleanPrimary = remember(primaryUrl) {
        LogoLogger.normalizeSecureArtworkUrl(primaryUrl)
    }
    val cleanFallback = remember(fallbackUrl, cleanPrimary) {
        LogoLogger.normalizeSecureArtworkUrl(fallbackUrl)?.takeIf { it != cleanPrimary }
    }

    var primaryFailed by remember(hoshikaId, episodeNumber, cleanPrimary, cleanFallback) {
        mutableStateOf(false)
    }
    var fallbackFailed by remember(hoshikaId, episodeNumber, cleanPrimary, cleanFallback) {
        mutableStateOf(false)
    }
    var isLoaded by remember(hoshikaId, episodeNumber, cleanPrimary, cleanFallback) {
        mutableStateOf(false)
    }

    // Log missing or malformed raw URLs once per key
    LaunchedEffect(screenName, artworkType, hoshikaId, episodeNumber, primaryUrl, fallbackUrl, cleanPrimary, cleanFallback) {
        if (!primaryUrl.isNullOrBlank() && cleanPrimary == null) {
            LogoLogger.recordArtworkFailure(
                screenName = screenName,
                artworkType = artworkType,
                hoshikaId = hoshikaId,
                episodeNumber = episodeNumber,
                url = primaryUrl,
                category = ArtworkErrorCategory.INVALID_URL,
                customReason = "Malformed or rejected primary artwork URL"
            )
        } else if (cleanPrimary == null && cleanFallback == null) {
            LogoLogger.recordArtworkFailure(
                screenName = screenName,
                artworkType = artworkType,
                hoshikaId = hoshikaId,
                episodeNumber = episodeNumber,
                url = null,
                category = ArtworkErrorCategory.MISSING_URL,
                customReason = "No valid artwork URL available"
            )
        }
    }

    val activeUrl: String? = when {
        cleanPrimary != null && !primaryFailed -> cleanPrimary
        cleanFallback != null && !fallbackFailed -> cleanFallback
        else -> null
    }

    val request = remember(context, activeUrl, artworkType, hoshikaId, episodeNumber, targetWidthPx, targetHeightPx) {
        activeUrl?.let { url ->
            val memKey = "${artworkType.key}:$hoshikaId:${episodeNumber ?: 0}:${targetWidthPx}x${targetHeightPx}:$url"
            ImageRequest.Builder(context)
                .data(url)
                .diskCacheKey(url)
                .memoryCacheKey(memKey)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .size(targetWidthPx.coerceAtLeast(1), targetHeightPx.coerceAtLeast(1))
                .crossfade(true)
                .build()
        }
    }

    val loadingBrush = rememberMonochromeShimmerBrush()

    Box(modifier = modifier) {
        if (request != null && activeUrl != null) {
            if (!isLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(loadingBrush)
                )
            }
            key(activeUrl) {
                val painter = rememberAsyncImagePainter(
                    model = request,
                    imageLoader = context.imageLoader,
                    contentScale = contentScale
                )
                val painterState = painter.state

                LaunchedEffect(activeUrl, painterState) {
                    when (painterState) {
                        is AsyncImagePainter.State.Success -> {
                            val d = painterState.result.drawable
                            val tinyOrInvalid = d.intrinsicWidth == 1 && d.intrinsicHeight == 1
                            if (tinyOrInvalid) {
                                isLoaded = false
                                LogoLogger.recordArtworkFailure(
                                    screenName = screenName,
                                    artworkType = artworkType,
                                    hoshikaId = hoshikaId,
                                    episodeNumber = episodeNumber,
                                    url = activeUrl,
                                    category = ArtworkErrorCategory.DECODE_FAILURE,
                                    customReason = "Invalid 1x1 tracking/empty bitmap returned"
                                )
                                if (activeUrl == cleanPrimary) {
                                    primaryFailed = true
                                } else {
                                    fallbackFailed = true
                                }
                            } else {
                                isLoaded = true
                                LogoLogger.recordArtworkSuccess(
                                    screenName = screenName,
                                    artworkType = artworkType,
                                    hoshikaId = hoshikaId,
                                    episodeNumber = episodeNumber,
                                    url = activeUrl,
                                    dataSource = painterState.result.dataSource
                                )
                            }
                        }
                        is AsyncImagePainter.State.Error -> {
                            isLoaded = false
                            val (category, httpStatus) = LogoLogger.classifyThrowable(painterState.result.throwable)
                            LogoLogger.recordArtworkFailure(
                                screenName = screenName,
                                artworkType = artworkType,
                                hoshikaId = hoshikaId,
                                episodeNumber = episodeNumber,
                                url = activeUrl,
                                category = category,
                                httpStatus = httpStatus,
                                throwable = painterState.result.throwable
                            )
                            if (activeUrl == cleanPrimary) {
                                primaryFailed = true
                            } else {
                                fallbackFailed = true
                            }
                        }
                        is AsyncImagePainter.State.Loading,
                        is AsyncImagePainter.State.Empty -> Unit
                    }
                }

                Image(
                    painter = painter,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    alignment = alignment,
                    modifier = Modifier
                        .testTag(
                            "tv_artwork_${artworkType.key}_${hoshikaId}_${if (activeUrl == cleanPrimary) "primary" else "fallback"}"
                        )
                        .then(imageModifier)
                )
            }
        } else {
            placeholder()
        }
    }
}
