package `in`.aniscroll.tv.core.designsystem

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import `in`.aniscroll.tv.artwork.ArtworkType
import `in`.aniscroll.tv.core.device.TvDeviceCapabilities
import `in`.aniscroll.tv.domain.model.Anime

@Composable
fun TvFocusableButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    isAccentGreen: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> TvColors.CardBackground.copy(alpha = 0.5f)
            isFocused && isAccentGreen -> TvColors.SkipGreen.copy(alpha = 0.22f)
            isFocused -> TvColors.FocusBackground
            isAccentGreen -> Color.Transparent
            isPrimary -> TvColors.ElevatedSurface
            else -> TvColors.CardBackground
        },
        label = "button_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> TvColors.MutedText
            isAccentGreen -> TvColors.SkipGreen
            isFocused -> TvColors.FocusText
            else -> TvColors.PrimaryText
        },
        label = "button_fg"
    )

    val borderColor = when {
        isFocused && isAccentGreen -> TvColors.SkipGreen
        isFocused -> TvColors.FocusOutline
        isAccentGreen -> TvColors.SkipGreen.copy(alpha = 0.65f)
        else -> TvColors.Divider
    }

    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Row(
        modifier = modifier
            .then(focusMod)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(4.dp))
            .background(containerColor)
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(4.dp)
            )
            .onKeyEvent { event ->
                if (enabled && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
fun TvSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = when {
        isFocused -> TvColors.FocusBackground
        selected -> TvColors.ElevatedSurface
        else -> TvColors.CardBackground
    }
    val fg = when {
        isFocused -> TvColors.FocusText
        selected -> TvColors.PrimaryText
        else -> TvColors.SecondaryText
    }
    val border = when {
        isFocused -> TvColors.FocusOutline
        selected -> TvColors.PrimaryText
        else -> TvColors.Divider
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(4.dp))
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1
        )
    }
}

@Composable
fun TvAnimePosterCard(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleBadge: String? = null,
    progressFraction: Float? = null,
    cardWidth: Dp = 128.dp,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused?.invoke()
        }
    }

    val cardScale by animateFloatAsState(
        targetValue = if (isFocused) 1.045f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "poster_card_scale"
    )
    val context = LocalContext.current
    val deviceProfile = remember(context) { TvDeviceCapabilities.getDeviceProfile(context) }

    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Column(
        modifier = modifier
            .width(cardWidth)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .then(focusMod)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .background(TvColors.CardBackground)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) TvColors.FocusOutline else TvColors.Divider,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            TvArtworkImage(
                primaryUrl = anime.posterUrl,
                fallbackUrl = anime.bannerUrl,
                artworkType = ArtworkType.POSTER,
                screenName = "PosterCard",
                hoshikaId = anime.hoshikaId,
                contentDescription = anime.preferredTitle,
                targetWidthPx = deviceProfile.recommendedPosterWidthPx,
                targetHeightPx = deviceProfile.recommendedPosterHeightPx,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = anime.preferredTitle,
                            tint = TvColors.MutedText,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = anime.preferredTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvColors.SecondaryText,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Optional monochrome rating pill at top-left
            val normalizedRating = anime.rating?.takeIf { it > 0.0 }?.let { raw ->
                if (raw > 10.0) raw / 10.0 else raw
            }
            if (normalizedRating != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(TvColors.MainBackground.copy(alpha = 0.85f))
                        .border(0.5.dp, TvColors.Divider, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = TvColors.PrimaryText,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", normalizedRating),
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.PrimaryText
                    )
                }
            }

            // Optional format / episode badge at top-right
            val badgeText = subtitleBadge
                ?: anime.format?.takeIf { it.isNotBlank() }?.replace("_", " ")
                ?: anime.releaseYear?.toString()
            if (!badgeText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(TvColors.MainBackground.copy(alpha = 0.85f))
                        .border(0.5.dp, TvColors.Divider, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.PrimaryText,
                        maxLines = 1
                    )
                }
            }

            // Progress bar at bottom if Continue Watching
            if (progressFraction != null && progressFraction > 0f) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = TvColors.PrimaryText,
                    trackColor = TvColors.ElevatedSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = anime.preferredTitle,
            style = MaterialTheme.typography.titleMedium,
            color = if (isFocused) TvColors.PrimaryText else Color(0xFFD9D9D9),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        val secondaryLine = remember(anime.releaseYear, anime.totalEpisodes, anime.currentEpisodes, anime.status) {
            val parts = mutableListOf<String>()
            anime.releaseYear?.let { parts.add(it.toString()) }
            val epCount = anime.totalEpisodes?.takeIf { it > 0 } ?: anime.currentEpisodes?.takeIf { it > 0 }
            if (epCount != null) {
                parts.add("${epCount} Ep")
            }
            val compactStatus = when (anime.status?.uppercase(java.util.Locale.ROOT)) {
                "RELEASING" -> "Airing"
                "NOT_YET_RELEASED" -> "Upcoming"
                "FINISHED" -> "Finished"
                "CANCELLED" -> "Cancelled"
                else -> anime.status?.takeIf { it.isNotBlank() }
            }
            if (compactStatus != null && parts.size < 3) {
                parts.add(compactStatus)
            }
            parts.joinToString(" • ")
        }
        if (secondaryLine.isNotEmpty()) {
            Text(
                text = secondaryLine,
                style = MaterialTheme.typography.labelSmall,
                color = TvColors.MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Displays a transparent anime title logo from TMDB/Fanart preserving its original aspect ratio,
 * or falls back cleanly to crisp white typography if unavailable or failed to load.
 * Delegates to the shared [in.aniscroll.tv.artwork.AnimeLogo] component.
 */
@Composable
fun TvTitleLogoOrText(
    logoUrl: String?,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
    hoshikaId: Long = 0L,
    maxHeight: Dp = 88.dp,
    maxWidth: Dp = 360.dp,
    onLogoLoadFailed: ((String) -> Unit)? = null
) {
    `in`.aniscroll.tv.artwork.AnimeLogo(
        hoshikaId = hoshikaId,
        animeTitle = fallbackTitle,
        logoUrl = logoUrl,
        contentDescription = fallbackTitle,
        modifier = modifier,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        onLogoLoadFailed = onLogoLoadFailed
    )
}

@Composable
fun TvErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.CardBackground)
            .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = message,
            tint = TvColors.SecondaryText,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = TvColors.PrimaryText,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TvFocusableButton(
                text = "Retry",
                icon = Icons.Default.Refresh,
                onClick = onRetry,
                modifier = Modifier.testTag("error_retry_button")
            )
        }
    }
}

@Composable
fun TvLoadingIndicator(
    message: String = TvContentLoadingCopy.LOADING_ANIME,
    modifier: Modifier = Modifier,
    title: String = TvContentLoadingCopy.LOADING_ANIME
) {
    val resolvedTitle = remember(title, message) {
        when {
            title != TvContentLoadingCopy.LOADING_ANIME -> title
            message.contains("search", ignoreCase = true) -> TvContentLoadingCopy.SEARCHING
            message.contains("episode", ignoreCase = true) -> TvContentLoadingCopy.LOADING_EPISODES
            message.contains("detail", ignoreCase = true) ||
                message.contains("watch order", ignoreCase = true) ||
                message.contains("franchise", ignoreCase = true) -> TvContentLoadingCopy.FETCHING_DETAILS
            else -> TvContentLoadingCopy.LOADING_ANIME
        }
    }
    val resolvedSubtitle = remember(resolvedTitle, message) {
        if (message.equals(resolvedTitle, ignoreCase = true) || message.equals("Loading...", ignoreCase = true)) {
            null
        } else {
            message
        }
    }

    TvContentLoadingScreen(
        title = resolvedTitle,
        subtitle = resolvedSubtitle,
        modifier = modifier.fillMaxWidth().padding(24.dp),
        fillBackground = false
    )
}

@Composable
fun TvConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = TvColors.SecondaryBackground,
            border = BorderStroke(1.dp, TvColors.Divider),
            modifier = Modifier.widthIn(min = 280.dp, max = 380.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TvColors.PrimaryText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvColors.SecondaryText
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TvFocusableButton(
                        text = cancelText,
                        onClick = onDismiss,
                        modifier = Modifier.testTag("dialog_cancel_button")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TvFocusableButton(
                        text = confirmText,
                        isPrimary = true,
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("dialog_confirm_button")
                    )
                }
            }
        }
    }
}
