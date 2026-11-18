package `in`.aniscroll.tv.features.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.aniscroll.tv.artwork.ArtworkType
import `in`.aniscroll.tv.core.designsystem.SafeFocusRequester
import `in`.aniscroll.tv.core.designsystem.TvAnimePosterCard
import `in`.aniscroll.tv.core.designsystem.TvArtworkImage
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvPosterCardSkeleton
import `in`.aniscroll.tv.core.designsystem.TvSelectableChip
import `in`.aniscroll.tv.core.designsystem.safeFocusRequester
import `in`.aniscroll.tv.core.device.TvDeviceCapabilities
import `in`.aniscroll.tv.data.repository.DiscoverQueryFilter
import `in`.aniscroll.tv.data.repository.DiscoverSectionData
import `in`.aniscroll.tv.data.repository.DiscoverSortKey
import `in`.aniscroll.tv.domain.model.Anime
import java.util.Locale

@Composable
fun DiscoverHeaderBar(
    subtitle: String,
    activeFilterCount: Int,
    isFilterPanelExpanded: Boolean,
    searchFocusRequester: SafeFocusRequester,
    onOpenSearch: () -> Unit,
    onToggleFilters: () -> Unit,
    onResetFilters: () -> Unit,
    hasActiveFilters: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Discover Anime",
                style = MaterialTheme.typography.headlineMedium,
                color = TvColors.PrimaryText,
                modifier = Modifier.testTag("discover_header_title")
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TvColors.SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("discover_header_subtitle")
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasActiveFilters) {
                TvFocusableButton(
                    text = "Reset",
                    icon = Icons.Default.Refresh,
                    onClick = onResetFilters,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    modifier = Modifier.testTag("discover_quick_reset_button")
                )
            }
            TvFocusableButton(
                text = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                icon = Icons.Default.Tune,
                isPrimary = isFilterPanelExpanded || activeFilterCount > 0,
                onClick = onToggleFilters,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                modifier = Modifier.testTag("discover_filters_button")
            )
            TvFocusableButton(
                text = "Search",
                icon = Icons.Default.Search,
                onClick = onOpenSearch,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                modifier = Modifier
                    .safeFocusRequester(searchFocusRequester)
                    .testTag("discover_search_button")
            )
        }
    }
}

@Composable
fun DiscoverGenreExplorerRow(
    genres: List<String>,
    selectedGenre: String?,
    allChipFocusRequester: SafeFocusRequester,
    upFocusRequester: SafeFocusRequester? = null,
    downFocusRequester: SafeFocusRequester? = null,
    onSelectGenre: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("discover_genre_row"),
        contentPadding = PaddingValues(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "genre_all") {
            val focusNavMod = Modifier
                .safeFocusRequester(allChipFocusRequester)
                .focusProperties {
                    if (upFocusRequester != null) up = upFocusRequester.orDefault()
                    if (downFocusRequester != null) down = downFocusRequester.orDefault()
                }
            TvSelectableChip(
                label = "All Genres",
                selected = selectedGenre == null,
                onClick = { onSelectGenre(null) },
                modifier = focusNavMod.testTag("discover_genre_all")
            )
        }

        items(genres, key = { "genre_$it" }) { genre ->
            val focusNavMod = Modifier.focusProperties {
                if (upFocusRequester != null) up = upFocusRequester.orDefault()
                if (downFocusRequester != null) down = downFocusRequester.orDefault()
            }
            TvSelectableChip(
                label = genre,
                selected = selectedGenre.equals(genre, ignoreCase = true),
                onClick = { onSelectGenre(genre) },
                modifier = focusNavMod.testTag("discover_genre_$genre")
            )
        }
    }
}

@Composable
fun DiscoverFilterPanel(
    visible: Boolean,
    draftFilter: DiscoverQueryFilter,
    genres: List<String>,
    applyFocusRequester: SafeFocusRequester,
    onUpdateGenre: (String?) -> Unit,
    onUpdateFormat: (String?) -> Unit,
    onUpdateStatus: (String?) -> Unit,
    onUpdateYear: (Int?) -> Unit,
    onUpdateMinRating: (Double?) -> Unit,
    onUpdateSort: (DiscoverSortKey) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TvColors.SecondaryBackground)
                .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
                .padding(16.dp)
                .testTag("discover_filter_panel"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filter & Sort Catalog",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.PrimaryText
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvFocusableButton(
                        text = "Reset",
                        icon = Icons.Default.Refresh,
                        onClick = onReset,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("discover_reset_filters_button")
                    )
                    TvFocusableButton(
                        text = "Apply",
                        isPrimary = true,
                        onClick = onApply,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier
                            .safeFocusRequester(applyFocusRequester)
                            .testTag("discover_apply_filters_button")
                    )
                    TvFocusableButton(
                        text = "Close",
                        icon = Icons.Default.Close,
                        onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("discover_close_filters_button")
                    )
                }
            }

            FilterChipGroupRow(label = "Genre") {
                item(key = "fg_all") {
                    TvSelectableChip(
                        label = "All",
                        selected = draftFilter.genre == null,
                        onClick = { onUpdateGenre(null) },
                        modifier = Modifier.testTag("filter_genre_all")
                    )
                }
                items(genres, key = { "fg_$it" }) { genre ->
                    TvSelectableChip(
                        label = genre,
                        selected = draftFilter.genre.equals(genre, ignoreCase = true),
                        onClick = { onUpdateGenre(genre) },
                        modifier = Modifier.testTag("filter_genre_$genre")
                    )
                }
            }

            FilterChipGroupRow(label = "Format") {
                items(DiscoverFilterCatalog.FORMAT_OPTIONS, key = { "ff_${it.label}" }) { opt ->
                    TvSelectableChip(
                        label = opt.label,
                        selected = draftFilter.format == opt.apiValue,
                        onClick = { onUpdateFormat(opt.apiValue) },
                        modifier = Modifier.testTag("filter_format_${opt.label}")
                    )
                }
            }

            FilterChipGroupRow(label = "Status") {
                items(DiscoverFilterCatalog.STATUS_OPTIONS, key = { "fs_${it.label}" }) { opt ->
                    TvSelectableChip(
                        label = opt.label,
                        selected = draftFilter.status == opt.apiValue,
                        onClick = { onUpdateStatus(opt.apiValue) },
                        modifier = Modifier.testTag("filter_status_${opt.label}")
                    )
                }
            }

            FilterChipGroupRow(label = "Year") {
                items(DiscoverFilterCatalog.YEAR_OPTIONS, key = { "fy_${it.label}" }) { opt ->
                    TvSelectableChip(
                        label = opt.label,
                        selected = draftFilter.year == opt.year,
                        onClick = { onUpdateYear(opt.year) },
                        modifier = Modifier.testTag("filter_year_${opt.label}")
                    )
                }
            }

            FilterChipGroupRow(label = "Rating") {
                items(DiscoverFilterCatalog.MIN_RATING_OPTIONS, key = { "fr_${it.label}" }) { opt ->
                    TvSelectableChip(
                        label = opt.label,
                        selected = draftFilter.minRatingOutOf10 == opt.minScoreOutOf10,
                        onClick = { onUpdateMinRating(opt.minScoreOutOf10) },
                        modifier = Modifier.testTag("filter_rating_${opt.label}")
                    )
                }
            }

            FilterChipGroupRow(label = "Sort By") {
                items(DiscoverFilterCatalog.SORT_OPTIONS, key = { "sort_${it.name}" }) { opt ->
                    TvSelectableChip(
                        label = opt.label,
                        selected = draftFilter.sortBy == opt,
                        onClick = { onUpdateSort(opt) },
                        modifier = Modifier.testTag("filter_sort_${opt.name}")
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipGroupRow(
    label: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = TvColors.SecondaryText,
            modifier = Modifier.width(68.dp)
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun DiscoverFeaturedHeroCarousel(
    heroItems: List<Anime>,
    selectedIndex: Int,
    heroHeight: Dp,
    contentMaxWidth: Dp,
    watchFocusRequester: SafeFocusRequester,
    upFocusRequester: SafeFocusRequester? = null,
    downFocusRequester: SafeFocusRequester? = null,
    onHeroFocusChanged: (Boolean) -> Unit,
    onSelectIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onWatchNow: (Anime) -> Unit,
    onOpenDetails: (Anime) -> Unit,
    modifier: Modifier = Modifier
) {
    if (heroItems.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, heroItems.lastIndex)
    val anime = heroItems[safeIndex]
    val context = LocalContext.current
    val deviceProfile = remember(context) { TvDeviceCapabilities.getDeviceProfile(context) }

    val focusHookModifier = Modifier
        .focusProperties {
            if (upFocusRequester != null) up = upFocusRequester.orDefault()
            if (downFocusRequester != null) down = downFocusRequester.orDefault()
        }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clip(RectangleShape)
            .background(TvColors.SecondaryBackground)
            .onFocusChanged { focusState ->
                onHeroFocusChanged(focusState.hasFocus)
            }
            .testTag("discover_hero_section")
    ) {
        Crossfade(
            targetState = anime,
            animationSpec = tween(durationMillis = 420),
            label = "discover_hero_crossfade",
            modifier = Modifier.fillMaxSize()
        ) { currentHero ->
            TvArtworkImage(
                primaryUrl = currentHero.bannerUrl,
                fallbackUrl = currentHero.posterUrl,
                artworkType = ArtworkType.BANNER,
                screenName = "DiscoverHero",
                hoshikaId = currentHero.hoshikaId,
                contentDescription = currentHero.preferredTitle,
                targetWidthPx = deviceProfile.recommendedBannerWidthPx,
                targetHeightPx = deviceProfile.recommendedBannerHeightPx,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
                imageModifier = Modifier
                    .fillMaxSize()
                    .testTag("discover_hero_backdrop_${currentHero.hoshikaId}")
            )
        }

        // Monochrome scrim gradients for strong contrast without vibrant colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            TvColors.MainBackground,
                            TvColors.MainBackground.copy(alpha = 0.90f),
                            TvColors.MainBackground.copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TvColors.MainBackground.copy(alpha = 0.30f),
                            Color.Transparent,
                            TvColors.MainBackground.copy(alpha = 0.78f),
                            TvColors.MainBackground
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxHeight()
                .widthIn(max = contentMaxWidth)
                .padding(horizontal = 32.dp, vertical = 18.dp)
                .testTag("discover_hero_content"),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = if (heroItems.size > 1) {
                    "FEATURED ANIME  •  ${safeIndex + 1} / ${heroItems.size}"
                } else {
                    "FEATURED ANIME"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TvColors.SecondaryText
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = anime.preferredTitle,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = TvColors.PrimaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("discover_hero_title")
            )

            val secondaryTitle = remember(anime.title, anime.englishTitle) {
                val eng = anime.englishTitle?.trim()
                val rom = anime.title.trim()
                when {
                    !eng.isNullOrBlank() && rom.isNotBlank() && !eng.equals(rom, ignoreCase = true) -> rom
                    else -> null
                }
            }
            if (secondaryTitle != null) {
                Text(
                    text = secondaryTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Metadata line: Rating • Format • Year • Episodes • Status
            val normalizedRating = anime.rating?.takeIf { it > 0.0 }?.let { raw ->
                if (raw > 10.0) raw / 10.0 else raw
            }
            val episodeInfo = anime.totalEpisodes?.takeIf { it > 0 }?.let { "$it Episodes" }
                ?: anime.currentEpisodes?.takeIf { it > 0 }?.let { "$it Eps Available" }
            val readableStatus = when (anime.status?.uppercase(Locale.ROOT)) {
                "RELEASING" -> "Airing"
                "NOT_YET_RELEASED" -> "Upcoming"
                "FINISHED" -> "Finished"
                "CANCELLED" -> "Cancelled"
                else -> anime.status?.takeIf { it.isNotBlank() }
            }

            val metaParts = buildList {
                anime.format?.takeIf { it.isNotBlank() }?.let { add(it.replace("_", " ")) }
                anime.releaseYear?.let { add(it.toString()) }
                episodeInfo?.let { add(it) }
                readableStatus?.let { add(it) }
                if (anime.genres.isNotEmpty()) {
                    add(anime.genres.take(3).joinToString(" / "))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("discover_hero_metadata")
            ) {
                if (normalizedRating != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(TvColors.ElevatedSurface)
                            .border(0.5.dp, TvColors.Divider, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Rating",
                            tint = TvColors.PrimaryText,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f", normalizedRating),
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.PrimaryText
                        )
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString("  •  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = TvColors.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!anime.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = anime.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("discover_hero_synopsis")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvFocusableButton(
                    text = "Watch Now",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    onClick = { onWatchNow(anime) },
                    modifier = focusHookModifier
                        .safeFocusRequester(watchFocusRequester)
                        .testTag("discover_hero_watch_button")
                )
                TvFocusableButton(
                    text = "Details",
                    icon = Icons.Default.Info,
                    onClick = { onOpenDetails(anime) },
                    modifier = focusHookModifier.testTag("discover_hero_details_button")
                )

                if (heroItems.size > 1) {
                    Spacer(modifier = Modifier.width(6.dp))
                    HeroChevronButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous featured anime",
                        onClick = onPrevious,
                        modifier = focusHookModifier.testTag("discover_hero_prev")
                    )
                    HeroChevronButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next featured anime",
                        onClick = onNext,
                        modifier = focusHookModifier.testTag("discover_hero_next")
                    )
                }
            }
        }

        // Monochrome dot indicators at bottom right
        if (heroItems.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 32.dp, vertical = 22.dp)
                    .testTag("discover_hero_dots"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                heroItems.forEachIndexed { idx, _ ->
                    val active = idx == safeIndex
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (active) 20.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) TvColors.PrimaryText else TvColors.MutedText.copy(alpha = 0.5f))
                            .clickable { onSelectIndex(idx) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroChevronButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(38.dp)
            .clip(CircleShape)
            .background(if (isFocused) TvColors.FocusBackground else TvColors.CardBackground.copy(alpha = 0.85f))
            .border(1.5.dp, if (isFocused) TvColors.FocusOutline else TvColors.Divider, CircleShape)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) TvColors.FocusText else TvColors.PrimaryText,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun DiscoverCuratedRow(
    section: DiscoverSectionData,
    firstCardFocusRequester: SafeFocusRequester? = null,
    upFocusRequester: SafeFocusRequester? = null,
    restoredFocusSectionId: String? = null,
    restoredFocusAnimeId: Long = -1L,
    isLoadingMoreAtEnd: Boolean = false,
    hasMoreAtEnd: Boolean = false,
    loadMoreError: String? = null,
    onLoadMoreAtEnd: (() -> Unit)? = null,
    onConsumeRestoreFocus: () -> Unit = {},
    onAnimeClick: (Anime) -> Unit,
    onAnimeFocused: (Anime) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowState = rememberLazyListState()

    if (onLoadMoreAtEnd != null && hasMoreAtEnd) {
        val shouldLoadMore by remember(section.items.size) {
            derivedStateOf {
                val lastVisible = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                section.items.isNotEmpty() && lastVisible >= (section.items.size - 4).coerceAtLeast(0)
            }
        }
        LaunchedEffect(shouldLoadMore, isLoadingMoreAtEnd, loadMoreError) {
            if (shouldLoadMore && !isLoadingMoreAtEnd && loadMoreError == null) {
                onLoadMoreAtEnd()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleLarge,
                color = TvColors.PrimaryText
            )
            if (!section.subtitle.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TvColors.CardBackground)
                        .border(1.dp, TvColors.Divider, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = section.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.SecondaryText
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.testTag("discover_row_${section.id}")
        ) {
            itemsIndexed(
                items = section.items,
                key = { _, anime -> "${section.id}_${anime.hoshikaId}" }
            ) { index, anime ->
                val cardFocusRequester = remember(section.id, anime.hoshikaId, index, firstCardFocusRequester) {
                    if (index == 0 && firstCardFocusRequester != null) {
                        firstCardFocusRequester
                    } else {
                        SafeFocusRequester()
                    }
                }

                LaunchedEffect(restoredFocusSectionId, restoredFocusAnimeId, cardFocusRequester.isAttached) {
                    if (restoredFocusSectionId == section.id &&
                        restoredFocusAnimeId == anime.hoshikaId &&
                        cardFocusRequester.isAttached
                    ) {
                        onConsumeRestoreFocus()
                        cardFocusRequester.requestFocusSafely()
                    }
                }

                val upMod = if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester.orDefault() }
                } else {
                    Modifier
                }

                TvAnimePosterCard(
                    anime = anime,
                    onClick = { onAnimeClick(anime) },
                    onFocused = { onAnimeFocused(anime) },
                    modifier = upMod
                        .safeFocusRequester(cardFocusRequester)
                        .testTag("discover_card_${section.id}_${anime.hoshikaId}")
                )
            }

            if (isLoadingMoreAtEnd) {
                items(2, key = { "discover_more_skel_$it" }) {
                    TvPosterCardSkeleton()
                }
            } else if (loadMoreError != null && onLoadMoreAtEnd != null) {
                item(key = "discover_more_retry") {
                    Box(
                        modifier = Modifier
                            .width(128.dp)
                            .height(192.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TvFocusableButton(
                            text = "Retry",
                            icon = Icons.Default.Refresh,
                            onClick = onLoadMoreAtEnd,
                            modifier = Modifier.testTag("discover_queue_retry_button")
                        )
                    }
                }
            }
        }
    }
}
