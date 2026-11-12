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
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
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
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused && enabled) 1.05f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "button_scale"
    )

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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp)
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
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
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

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chip_scale"
    )

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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(if (isFocused || selected) 2.dp else 1.dp, border, RoundedCornerShape(6.dp))
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
    cardWidth: Dp = 152.dp,
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

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "card_scale"
    )

    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Column(
        modifier = modifier
            .width(cardWidth)
            .then(focusMod)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(TvColors.CardBackground)
                .border(
                    width = if (isFocused) 2.5.dp else 1.dp,
                    color = if (isFocused) TvColors.FocusOutline else TvColors.Divider,
                    shape = RoundedCornerShape(6.dp)
                )
        ) {
            if (!anime.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(anime.posterUrl)
                        .crossfade(true)
                        .size(360, 540)
                        .build(),
                    contentDescription = anime.preferredTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = anime.preferredTitle,
                            tint = TvColors.MutedText,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
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

            // Optional status / episode badge at top-right
            val badgeText = subtitleBadge
                ?: anime.format?.takeIf { it.isNotBlank() }
                ?: anime.releaseYear?.toString()
            if (!badgeText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TvColors.MainBackground.copy(alpha = 0.85f))
                        .border(0.5.dp, TvColors.Divider, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.PrimaryText
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
                        .height(3.dp),
                    color = TvColors.PrimaryText,
                    trackColor = TvColors.ElevatedSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = anime.preferredTitle,
            style = MaterialTheme.typography.titleMedium,
            color = if (isFocused) TvColors.PrimaryText else TvColors.SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val secondaryLine = buildString {
            anime.releaseYear?.let { append(it) }
            if (!anime.status.isNullOrBlank()) {
                if (isNotEmpty()) append(" • ")
                append(anime.status)
            }
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
 */
@Composable
fun TvTitleLogoOrText(
    logoUrl: String?,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 84.dp,
    maxWidth: Dp = 340.dp
) {
    var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }

    if (!logoUrl.isNullOrBlank() && !logoLoadFailed) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(logoUrl)
                .crossfade(true)
                .build(),
            contentDescription = fallbackTitle,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    logoLoadFailed = true
                }
            },
            modifier = modifier
                .heightIn(max = maxHeight)
                .widthIn(max = maxWidth)
        )
    } else {
        Text(
            text = fallbackTitle,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            color = TvColors.PrimaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
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
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = TvColors.PrimaryText,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TvColors.SecondaryText
        )
    }
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
            shape = RoundedCornerShape(8.dp),
            color = TvColors.SecondaryBackground,
            border = BorderStroke(1.dp, TvColors.Divider),
            modifier = Modifier.widthIn(min = 320.dp, max = 460.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TvColors.PrimaryText
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvColors.SecondaryText
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TvFocusableButton(
                        text = cancelText,
                        onClick = onDismiss,
                        modifier = Modifier.testTag("dialog_cancel_button")
                    )
                    Spacer(modifier = Modifier.width(12.dp))
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
