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
        @Query("limit") limit: Int = 24,
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
 * Base URL: https://api.aniscroll.in/
 * Endpoint: GET https://api.aniscroll.in/api/watch/zokoanime/{anilist_id}/{episode}/{sub|dub}
 */
interface AniScrollApi {

    @GET("api/watch/zokoanime/{anilistId}/{episode}/{language}")
    suspend fun getEpisodeStreams(
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
     * Secure backend proxy routes for TMDB & Fanart.tv transparent title logos
     * so TMDB and Fanart API credentials are never embedded in the APK.
     */
    @GET("api/tmdb/search/tv")
    suspend fun searchTmdbTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int? = null
    ): Response<ResponseBody>

    @GET("api/tmdb/tv/{tvId}/images")
    suspend fun getTmdbTvImages(
        @Path("tvId") tvId: Long,
        @Query("include_image_language") includeImageLanguage: String = "en,ja,null"
    ): Response<ResponseBody>

    @GET("api/fanart/tv/{tvId}")
    suspend fun getFanartTvImages(
        @Path("tvId") tvId: Long
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
        @Query("types") types: List<String> = listOf("op", "ed", "mixed-op", "mixed-ed"),
        @Query("episodeLength") episodeLength: Float = 0f
    ): Response<ResponseBody>
}
