package `in`.aniscroll.tv.features.discover

import `in`.aniscroll.tv.data.repository.DiscoverQueryFilter
import `in`.aniscroll.tv.data.repository.DiscoverSectionData
import `in`.aniscroll.tv.data.repository.DiscoverSortKey
import `in`.aniscroll.tv.domain.model.Anime

data class DiscoverFilterOption(
    val label: String,
    val apiValue: String?
)

data class DiscoverRatingOption(
    val label: String,
    val minScoreOutOf10: Double?
)

data class DiscoverYearOption(
    val label: String,
    val year: Int?
)

object DiscoverFilterCatalog {
    val DEFAULT_GENRES = listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Fantasy",
        "Horror",
        "Mystery",
        "Romance",
        "Sci-Fi",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
        "Isekai"
    )

    val FORMAT_OPTIONS = listOf(
        DiscoverFilterOption("All", null),
        DiscoverFilterOption("TV", "TV"),
        DiscoverFilterOption("Movie", "MOVIE"),
        DiscoverFilterOption("OVA", "OVA"),
        DiscoverFilterOption("ONA", "ONA"),
        DiscoverFilterOption("Special", "SPECIAL")
    )

    val STATUS_OPTIONS = listOf(
        DiscoverFilterOption("All", null),
        DiscoverFilterOption("Airing", "RELEASING"),
        DiscoverFilterOption("Finished", "FINISHED"),
        DiscoverFilterOption("Upcoming", "NOT_YET_RELEASED")
    )

    val YEAR_OPTIONS = listOf(
        DiscoverYearOption("Any", null),
        DiscoverYearOption("2026", 2026),
        DiscoverYearOption("2025", 2025),
        DiscoverYearOption("2024", 2024),
        DiscoverYearOption("2023", 2023),
        DiscoverYearOption("2022", 2022),
        DiscoverYearOption("2021", 2021),
        DiscoverYearOption("2020", 2020),
        DiscoverYearOption("2019", 2019),
        DiscoverYearOption("2018", 2018),
        DiscoverYearOption("2015", 2015)
    )

    val MIN_RATING_OPTIONS = listOf(
        DiscoverRatingOption("Any", null),
        DiscoverRatingOption("7.0+", 7.0),
        DiscoverRatingOption("7.5+", 7.5),
        DiscoverRatingOption("8.0+", 8.0),
        DiscoverRatingOption("8.5+", 8.5)
    )

    val SORT_OPTIONS: List<DiscoverSortKey> = DiscoverSortKey.entries
}

data class DiscoverUiState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isFromOfflineCache: Boolean = false,
    val landingErrorMessage: String? = null,
    val heroItems: List<Anime> = emptyList(),
    val selectedHeroIndex: Int = 0,
    val isHeroFocused: Boolean = false,
    val sections: List<DiscoverSectionData> = emptyList(),
    val genres: List<String> = DiscoverFilterCatalog.DEFAULT_GENRES,
    val seasonLabel: String? = null,
    val discoverQueueItems: List<Anime> = emptyList(),
    val discoverNextOffset: Int? = null,
    val hasMoreDiscoverQueue: Boolean = false,
    val isLoadingMoreDiscoverQueue: Boolean = false,
    val discoverQueueError: String? = null,
    val appliedFilter: DiscoverQueryFilter = DiscoverQueryFilter(),
    val draftFilter: DiscoverQueryFilter = DiscoverQueryFilter(),
    val isFilterPanelExpanded: Boolean = false,
    val isFilteredLoading: Boolean = false,
    val isFilteredLoadingMore: Boolean = false,
    val filteredItems: List<Anime> = emptyList(),
    val filteredPage: Int = 1,
    val hasMoreFiltered: Boolean = false,
    val filteredTotalCount: Int? = null,
    val filteredErrorMessage: String? = null,
    val lastFocusedSectionId: String? = null,
    val lastFocusedAnimeId: Long = -1L,
    val shouldRestoreCardFocus: Boolean = false
) {
    val isFilteredMode: Boolean
        get() = appliedFilter.hasActiveFilters

    val activeHeroAnime: Anime?
        get() = heroItems.getOrNull(selectedHeroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0)))
}
