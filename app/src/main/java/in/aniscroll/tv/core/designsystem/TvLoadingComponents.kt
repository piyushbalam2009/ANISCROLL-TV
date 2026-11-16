package `in`.aniscroll.tv.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R

object TvContentLoadingCopy {
    const val INITIALIZING_ANISCROLL = "INITIALIZING ANISCROLL"
    const val LOADING_ANIME = "LOADING ANIME"
    const val FETCHING_DETAILS = "FETCHING DETAILS"
    const val LOADING_EPISODES = "LOADING EPISODES"
    const val SEARCHING = "SEARCHING"
}

/**
 * Official AniScroll Stylized 'A' Swoosh Logo rendered sharply in pure white
 * preserving its original proportions, silhouette, and aspect ratio without distortion.
 */
@Composable
fun AniScrollBrandLogo(
    size: Dp,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    contentDescription: String = "AniScroll Logo"
) {
    Image(
        painter = painterResource(id = R.drawable.aniscroll_logo),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
        modifier = modifier
            .size(size)
            .aspectRatio(1f)
    )
}

/**
 * Lifecycle-aware monochrome shimmer brush designed for Fire TV Stick HD, 4K, and 4K Max.
 * Automatically pauses animation when the lifecycle is not at least STARTED.
 */
@Composable
fun rememberMonochromeShimmerBrush(): Brush {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!isStarted) {
        return remember {
            Brush.linearGradient(
                colors = listOf(TvColors.CardBackground, TvColors.ElevatedSurface, TvColors.CardBackground)
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "monochrome_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    return Brush.linearGradient(
        colors = listOf(
            TvColors.CardBackground,
            TvColors.ShimmerHighlight,
            TvColors.CardBackground
        ),
        start = Offset(translateAnim - 260f, 0f),
        end = Offset(translateAnim + 260f, 180f)
    )
}

/**
 * Reusable AniScroll Content Loading Screen / Area.
 * Features a pure black background, smaller official AniScroll logo, smooth white circular progress
 * indicator, uppercase operation label ("LOADING ANIME", "FETCHING DETAILS", "LOADING EPISODES", "SEARCHING"),
 * and optional subtle subtitle. Never steals D-pad focus.
 */
@Composable
fun TvContentLoadingScreen(
    title: String = TvContentLoadingCopy.LOADING_ANIME,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    logoSize: Dp = 52.dp,
    spinnerSize: Dp = 28.dp,
    fillBackground: Boolean = true
) {
    Box(
        modifier = modifier
            .then(if (fillBackground) Modifier.background(TvColors.MainBackground) else Modifier)
            .focusable(false)
            .testTag("tv_content_loading_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AniScrollBrandLogo(
                size = logoSize,
                alpha = 0.92f,
                modifier = Modifier.testTag("content_loading_logo")
            )

            Spacer(modifier = Modifier.height(16.dp))

            CircularProgressIndicator(
                color = TvColors.PrimaryText,
                trackColor = TvColors.PrimaryText.copy(alpha = 0.14f),
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(spinnerSize)
                    .testTag("content_loading_spinner")
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.4.sp,
                    fontSize = 12.sp
                ),
                color = TvColors.PrimaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("content_loading_title")
            )

            if (!subtitle.isNullOrBlank() && !subtitle.equals(title, ignoreCase = true)) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("content_loading_subtitle")
                )
            }
        }
    }
}

/**
 * Compact inline section loading header used during partial loading or background section refreshes
 * so already-loaded content stays visible without screen flickering.
 */
@Composable
fun TvSectionLoadingHeader(
    label: String = TvContentLoadingCopy.LOADING_ANIME,
    detailText: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .focusable(false)
            .clip(RoundedCornerShape(6.dp))
            .background(TvColors.SecondaryBackground)
            .border(1.dp, TvColors.Divider, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("tv_section_loading_header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AniScrollBrandLogo(
            size = 22.dp,
            alpha = 0.9f
        )
        CircularProgressIndicator(
            color = TvColors.PrimaryText,
            trackColor = TvColors.PrimaryText.copy(alpha = 0.15f),
            strokeWidth = 1.8.dp,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.8.sp,
                fontSize = 11.sp
            ),
            color = TvColors.PrimaryText
        )
        if (!detailText.isNullOrBlank() && !detailText.equals(label, ignoreCase = true)) {
            Text(
                text = "•  $detailText",
                style = MaterialTheme.typography.bodyMedium,
                color = TvColors.SecondaryText,
                maxLines = 1
            )
        }
    }
}

/**
 * Single monochrome shimmer poster card skeleton (2:3 aspect ratio + title lines).
 */
@Composable
fun TvPosterCardSkeleton(
    cardWidth: Dp = 128.dp,
    shimmerBrush: Brush = rememberMonochromeShimmerBrush(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(cardWidth)
            .focusable(false)
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush)
                .border(1.dp, TvColors.Divider, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .height(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(shimmerBrush)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .height(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(shimmerBrush)
        )
    }
}

/**
 * Horizontal row of monochrome shimmer poster card skeletons for Home & Details related anime sections.
 */
@Composable
fun TvAnimeRowSkeleton(
    sectionLabel: String? = null,
    cardsCount: Int = 6,
    cardWidth: Dp = 128.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 32.dp),
    modifier: Modifier = Modifier
) {
    val shimmerBrush = rememberMonochromeShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusable(false)
            .testTag("tv_anime_row_skeleton")
    ) {
        if (!sectionLabel.isNullOrBlank()) {
            Row(
                modifier = Modifier.padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvSectionLoadingHeader(label = sectionLabel)
            }
        } else {
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .width(180.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(cardsCount) {
                TvPosterCardSkeleton(
                    cardWidth = cardWidth,
                    shimmerBrush = shimmerBrush
                )
            }
        }
    }
}

/**
 * Full Home Screen initial loading experience combining the official AniScroll content loading indicator
 * ("LOADING ANIME") with subtle hero & poster row monochrome shimmer skeletons.
 */
@Composable
fun TvHomeLoadingSkeleton(
    heroHeight: Dp = 280.dp,
    statusMessage: String = "Loading Hoshika anime catalogue...",
    modifier: Modifier = Modifier
) {
    val shimmerBrush = rememberMonochromeShimmerBrush()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .focusable(false)
            .testTag("home_loading_skeleton")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Hero banner skeleton frame
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .background(TvColors.SecondaryBackground)
                    .border(0.5.dp, TvColors.Divider)
                    .padding(horizontal = 32.dp, vertical = 22.dp)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.BottomStart),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(240.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .width(320.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .width(420.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .width(116.dp)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(shimmerBrush)
                            )
                        }
                    }
                }
            }

            // Two horizontal row skeletons
            TvAnimeRowSkeleton(cardsCount = 6)
            TvAnimeRowSkeleton(cardsCount = 6)
        }

        // Centered prominent AniScroll content loading badge over dimmed backdrop
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(12.dp))
                .background(TvColors.MainBackground.copy(alpha = 0.88f))
                .border(1.dp, TvColors.Divider, RoundedCornerShape(12.dp))
                .padding(horizontal = 36.dp, vertical = 24.dp)
        ) {
            TvContentLoadingScreen(
                title = TvContentLoadingCopy.LOADING_ANIME,
                subtitle = statusMessage,
                fillBackground = false
            )
        }
    }
}

/**
 * Section-level poster grid loading view for Search ("SEARCHING") and Discover ("LOADING ANIME").
 * Keeps surrounding search bars and filter chips intact while rendering the AniScroll indicator
 * and monochrome shimmer poster placeholders inside the content region.
 */
@Composable
fun TvPosterGridSkeleton(
    title: String = TvContentLoadingCopy.LOADING_ANIME,
    subtitle: String? = null,
    columnsCount: Int = 6,
    rowsCount: Int = 2,
    modifier: Modifier = Modifier
) {
    val shimmerBrush = rememberMonochromeShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxSize()
            .focusable(false)
            .testTag("tv_poster_grid_skeleton"),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TvSectionLoadingHeader(
            label = title,
            detailText = subtitle
        )

        repeat(rowsCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                repeat(columnsCount) {
                    TvPosterCardSkeleton(
                        cardWidth = 148.dp,
                        shimmerBrush = shimmerBrush
                    )
                }
            }
        }
    }
}

/**
 * Section-level episode list loading skeleton ("LOADING EPISODES") for DetailsScreen and EpisodesScreen.
 */
@Composable
fun TvEpisodeListSkeleton(
    title: String = TvContentLoadingCopy.LOADING_EPISODES,
    subtitle: String? = null,
    itemCount: Int = 4,
    modifier: Modifier = Modifier
) {
    val shimmerBrush = rememberMonochromeShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusable(false)
            .testTag("tv_episode_list_skeleton"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TvSectionLoadingHeader(
            label = title,
            detailText = subtitle
        )

        Spacer(modifier = Modifier.height(4.dp))

        repeat(itemCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.CardBackground)
                    .border(1.dp, TvColors.Divider, RoundedCornerShape(6.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(148.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.80f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(shimmerBrush)
                    )
                }
            }
        }
    }
}

/**
 * Section-level schedule list loading skeleton ("LOADING ANIME") for ScheduleScreen.
 */
@Composable
fun TvScheduleListSkeleton(
    title: String = TvContentLoadingCopy.LOADING_ANIME,
    subtitle: String? = null,
    itemCount: Int = 5,
    modifier: Modifier = Modifier
) {
    val shimmerBrush = rememberMonochromeShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxSize()
            .focusable(false)
            .testTag("tv_schedule_list_skeleton"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TvSectionLoadingHeader(
            label = title,
            detailText = subtitle
        )

        repeat(itemCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.CardBackground)
                    .border(1.dp, TvColors.Divider, RoundedCornerShape(6.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(108.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.48f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.32f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(shimmerBrush)
                    )
                }
            }
        }
    }
}

/**
 * Watch Order timeline loading skeleton ("FETCHING DETAILS") with vertical timeline spine placeholders.
 */
@Composable
fun TvWatchOrderTimelineSkeleton(
    title: String = TvContentLoadingCopy.FETCHING_DETAILS,
    subtitle: String? = null,
    itemCount: Int = 4,
    modifier: Modifier = Modifier
) {
    val shimmerBrush = rememberMonochromeShimmerBrush()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .focusable(false)
            .padding(horizontal = 36.dp, vertical = 24.dp)
            .testTag("tv_watch_order_skeleton")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            TvSectionLoadingHeader(
                label = title,
                detailText = subtitle
            )

            // Header skeleton
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(108.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmerBrush)
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .width(340.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .width(136.dp)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(shimmerBrush)
                            )
                        }
                    }
                }
            }

            // Timeline items skeleton
            repeat(itemCount) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TvColors.CardBackground)
                            .border(1.dp, TvColors.Divider, RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(76.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(shimmerBrush)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(shimmerBrush)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.55f)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(shimmerBrush)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(shimmerBrush)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean monochrome empty state component so empty responses never leave an infinite spinner.
 */
@Composable
fun TvEmptyStateBanner(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.CardBackground)
            .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
            .padding(28.dp)
            .testTag("tv_empty_state_banner"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AniScrollBrandLogo(
            size = 36.dp,
            alpha = 0.65f
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = TvColors.PrimaryText,
            textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TvColors.SecondaryText,
                textAlign = TextAlign.Center
            )
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TvFocusableButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.testTag("empty_state_action_button")
            )
        }
    }
}
