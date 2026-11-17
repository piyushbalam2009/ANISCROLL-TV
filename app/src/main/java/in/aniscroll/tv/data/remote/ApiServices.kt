package `in`.aniscroll.tv.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Hoshika API v1 — Official Primary Anime Metadata Provider
 * Documentation: https://hoshika.app/developers
 * Base URL: https://hoshika.app/api/v1/
 */
interface HoshikaApi {

    @GET("home")
    suspend fun getHome(
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Large grid behind each home row.
     * sort: "trending" | "recent" | "top" (for type = "anime")
     */
    @GET("browse")
    suspend fun getBrowse(
        @Query("type") type: String = "anime",
        @Query("sort") sort: String = "trending",
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Current season premieres plus carry-overs.
     */
    @GET("season")
    suspend fun getSeason(
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Filterable, paginated catalogue (fuzzy search + facets).
     */
    @GET("catalog")
    suspend fun getCatalog(
        @Query("type") type: String = "anime",
        @Query("q") query: String? = null,
        @Query("genre") genre: String? = null,
        @Query("format") format: String? = null,
        @Query("status") status: String? = null,
        @Query("year") year: Int? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 1,
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Unified search (anime + manga). Each hit has a shortId.
     */
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
        @Query("format") format: String? = null,
        @Query("year") year: Int? = null,
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Swipe/discovery queue of well-known titles.
     */
    @GET("discover")
    suspend fun getDiscover(
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Anime page: localized titles, synopsis, genres, studios, relations, trailer.
     */
    @GET("anime/{shortId}")
    suspend fun getAnimeDetails(
        @Path("shortId") shortId: Long,
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Episodes paginated by number (after = last number received).
     */
    @GET("anime/{shortId}/episodes")
    suspend fun getAnimeEpisodes(
        @Path("shortId") shortId: Long,
        @Query("after") after: Int? = null,
        @Query("limit") limit: Int = 100,
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Airing schedule for an ISO week (or a from/to window).
     */
    @GET("schedule")
    suspend fun getSchedule(
        @Query("year") year: Int? = null,
        @Query("week") week: Int? = null,
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>

    /**
     * Genre list (names match the catalogue filter).
     */
    @GET("genres")
    suspend fun getGenres(): Response<ResponseBody>

    /**
     * Titles of a genre slug (e.g. "action", "adventure", "fantasy", "romance", "comedy").
     */
    @GET("genre/{slug}")
    suspend fun getGenreTitles(
        @Path("slug") slug: String,
        @Query("type") type: String = "anime",
        @Query("locale") locale: String = "en"
    ): Response<ResponseBody>
}

/**
 * AniScroll Streaming Backend API
 * Base URL: https://anivault-scraper-five.vercel.app/
 * Routing rules:
 * - Movies (format == MOVIE): GET https://anivault-scraper-five.vercel.app/api/watch/zokoanime/{anilist_id}/{episode}/{sub|dub}
 * - TV series, specials, and episodic anime: GET https://anivault-scraper-five.vercel.app/api/watch/megaplay/{anilist_id}/{episode}/{sub|dub}
 */
interface AniScrollApi {

    /**
     * Unified watch route:
     * GET https://anivault-scraper-five.vercel.app/api/watch/{source}/{anilist_id}/{episode}/{sub|dub}
     * where `source` is resolved by StreamingSourceResolver ("megaplay" for series, "zokoanime" for movies).
     */
    @GET("api/watch/{source}/{anilistId}/{episode}/{language}")
    suspend fun getWatchStreams(
        @Path("source") source: String,
        @Path("anilistId") anilistId: Long,
        @Path("episode") episode: Int,
        @Path("language") language: String
    ): Response<ResponseBody> {
        return if (source.equals("zokoanime", ignoreCase = true)) {
            getZokoAnimeStreams(anilistId = anilistId, episode = episode, language = language)
        } else {
            getMegaPlayStreams(anilistId = anilistId, episode = episode, language = language)
        }
    }

    @GET("api/watch/zokoanime/{anilistId}/{episode}/{language}")
    suspend fun getZokoAnimeStreams(
        @Path("anilistId") anilistId: Long,
        @Path("episode") episode: Int,
        @Path("language") language: String
    ): Response<ResponseBody>

    @GET("api/watch/megaplay/{anilistId}/{episode}/{language}")
    suspend fun getMegaPlayStreams(
        @Path("anilistId") anilistId: Long,
        @Path("episode") episode: Int,
        @Path("language") language: String
    ): Response<ResponseBody>

    /**
     * AniScroll backend ID resolution endpoints (resolves AniList & MAL IDs server-side)
     */
    @GET("api/search")
    suspend fun searchAnimeIds(
        @Query("q") query: String,
        @Query("limit") limit: Int = 10
    ): Response<ResponseBody>

    @GET("api/anilist/id")
    suspend fun getAniListIdFromMal(
        @Query("malId") malId: Long
    ): Response<ResponseBody>

    /**
     * Secure backend proxy routes for Hoshika metadata if needed
     */
    @GET("api/hoshika/{path}")
    suspend fun getHoshikaProxy(
        @Path(value = "path", encoded = true) path: String,
        @Query("q") query: String? = null,
        @Query("page") page: Int? = null,
        @Query("genre") genre: String? = null,
        @Query("day") day: String? = null
    ): Response<ResponseBody>

    /**
     * Verified AniScroll backend TMDB artwork endpoint documented on https://anivault-scraper-five.vercel.app
     * Returns `{ "data": { "poster": "...", "cover": "...", "logo": "https://image.tmdb.org/t/p/original/..." } }`
     * when TMDB_API_KEY is configured on the backend server.
     */
    @GET("api/tmdb/anime")
    suspend fun getTmdbAnimeArtwork(
        @Query("malId") malId: Long? = null,
        @Query("title") title: String? = null
    ): Response<ResponseBody>
}

/**
 * Official TMDB v3 API — Exclusively for transparent anime title logos.
 * Base URL: https://api.themoviedb.org/3/
 * Never used for anime metadata, posters, backdrops, or descriptions.
 */
interface TmdbApi {

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") firstAirDateYear: Int? = null,
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<ResponseBody>

    /**
     * Retrieves TV series images (specifically `logos`) across English, language-neutral, and Japanese.
     * Note: We intentionally omit `language` and pass `include_image_language=en,null,ja` so TMDB
     * does not filter out Japanese or language-neutral logos.
     */
    @GET("tv/{tvId}/images")
    suspend fun getTvImages(
        @Path("tvId") tvId: Long,
        @Query("include_image_language") includeImageLanguage: String = "en,null,ja"
    ): Response<ResponseBody>
}

/**
 * TMDB v3 Movie endpoints for anime theatrical films (`format == MOVIE`).
 */
interface TmdbMovieApi {

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("primary_release_year") primaryReleaseYear: Int? = null,
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<ResponseBody>

    @GET("movie/{movieId}/images")
    suspend fun getMovieImages(
        @Path("movieId") movieId: Long,
        @Query("include_image_language") includeImageLanguage: String = "en,null,ja"
    ): Response<ResponseBody>
}

/**
 * Official Fanart.tv v3.2 API — Fallback provider exclusively for transparent anime title logos (`hdtvlogo`).
 * Base URL: https://webservice.fanart.tv/v3.2/
 * Endpoint: GET https://webservice.fanart.tv/v3.2/tv/{tmdb_tv_id}?api_key={FANART_API_KEY}&source=tmdb
 */
interface FanartApi {

    @GET("tv/{tmdbTvId}")
    suspend fun getTvImages(
        @Path("tmdbTvId") tmdbTvId: Long,
        @Query("source") source: String = "tmdb"
    ): Response<ResponseBody>
}

/**
 * Fanart.tv v3.2 Movie endpoint for anime theatrical films (`format == MOVIE`), supporting `hdmovielogo`.
 */
interface FanartMovieApi {

    @GET("movies/{tmdbMovieId}")
    suspend fun getMovieImages(
        @Path("tmdbMovieId") tmdbMovieId: Long
    ): Response<ResponseBody>
}

/**
 * AniSkip v2 API
 * Base URL: https://api.aniskip.com/
 */
interface AniSkipApi {

    @GET("v2/skip-times/{animeId}/{episodeNumber}")
    suspend fun getSkipTimes(
        @Path("animeId") animeId: Long,
        @Path("episodeNumber") episodeNumber: Int,
        @Query("types") types: List<String> = listOf("op", "ed", "mixed-op", "mixed-ed", "recap"),
        @Query("episodeLength") episodeLength: Float = 0f
    ): Response<ResponseBody>
}

/**
 * AniList GraphQL v2 API — Used for retrieving franchise relations and watch order graphs.
 * Base URL: https://graphql.anilist.co/
 */
interface AniListGraphQlApi {

    @retrofit2.http.POST("/")
    suspend fun query(
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<ResponseBody>
}

