package `in`.aniscroll.tv.artwork

import android.content.Context
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import `in`.aniscroll.tv.core.designsystem.TvColors

/**
 * Shared Coil ImageLoader configured with [SvgDecoder.Factory] so transparent PNG, SVG, WEBP,
 * and JPG anime title logos from TMDB and Fanart.tv decode reliably on Android TV.
 */
object LogoImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    fun create(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}

/**
 * Shared reusable Anime Title Logo component used across Home Hero and Anime Details screens.
 *
 * Responsibilities:
 * 1. Displays the supplied transparent PNG/SVG/WEBP logo URL from TMDB or Fanart.tv using [LogoImageLoader].
 * 2. Preserves original image aspect ratio using ContentScale.Fit (never crops or stretches).
 * 3. Reserves a clean loading state height so hero/details layouts never collapse when the logo loads.
 * 4. Displays a crisp white typography text fallback on null/invalid URL or load failure.
 * 5. Notifies [onLogoLoadFailed] when an image URL fails to render so the ViewModel/Repository can
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
        logoUrl?.trim()?.takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                (it.startsWith("https://") || it.startsWith("http://"))
        }
    }

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

    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .heightIn(min = 40.dp, max = maxHeight)
            .clipToBounds()
            .testTag("anime_logo_container_$hoshikaId"),
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
                            LogoLogger.d(
                                "HoshikaID=$hoshikaId title='$animeTitle' -> Image load SUCCESS host=${LogoLogger.extractHost(cleanUrl)}"
                            )
                        }
                        is AsyncImagePainter.State.Error -> {
                            LogoLogger.w(
                                "HoshikaID=$hoshikaId title='$animeTitle' -> Image load FAILED host=${LogoLogger.extractHost(cleanUrl)}: ${state.result.throwable.message}",
                                state.result.throwable
                            )
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
                    .testTag("anime_logo_image_$hoshikaId")
            )
        } else {
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
