package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.aniscroll.tv.artwork.AnimeLogo
import `in`.aniscroll.tv.artwork.FanartLogoProvider
import `in`.aniscroll.tv.artwork.LogoImageLoader
import `in`.aniscroll.tv.artwork.LogoRepository
import `in`.aniscroll.tv.artwork.TmdbLogoProvider
import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.AniScrollDatabase
import `in`.aniscroll.tv.core.database.LogoCacheEntity
import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.data.remote.FanartApi
import `in`.aniscroll.tv.data.remote.TmdbApi
import `in`.aniscroll.tv.domain.model.Anime
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AniScrollDatabase
    private lateinit var dao: AniScrollDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AniScrollDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AniScroll TV", appName)
    }

    // TEST 1: TMDB returns valid English PNG logo -> Expected: TMDB English PNG logo selected
    @Test
    fun test1_tmdbHasEnglishPngLogo_selectsTmdbEnglishLogo() = runTest {
        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            imagesBody = """
                {
                  "id": 1429,
                  "logos": [
                    { "file_path": "/ja_logo.png", "iso_639_1": "ja", "width": 1200, "height": 400, "aspect_ratio": 3.0, "vote_average": 5.0 },
                    { "file_path": "/en_logo.svg", "iso_639_1": "en", "width": 1000, "height": 320, "aspect_ratio": 3.125, "vote_average": 4.9 },
                    { "file_path": "/en_logo.png", "iso_639_1": "en", "width": 900, "height": 300, "aspect_ratio": 3.0, "vote_average": 4.5 }
                  ]
                }
            """.trimIndent()
        )
        val fakeFanart = FakeFanartApi(fanartBody = null)
        val repo = createLogoRepository(fakeTmdb, fakeFanart)

        val anime = sampleAotAnime()
        val info = repo.getLogoInfoForAnime(anime)

        assertNotNull(info)
        assertEquals("TMDB", info!!.provider)
        assertEquals("en", info.language)
        assertEquals("https://image.tmdb.org/t/p/original/en_logo.png", info.logoUrl)
        assertEquals(0, fakeFanart.requestCount)
    }

    // TEST 2: TMDB has only Japanese or language-neutral logo -> Expected: Japanese / neutral logo selected
    @Test
    fun test2_tmdbHasJapaneseAndNeutralLogos_selectsJapaneseThenNeutral() = runTest {
        val fakeTmdbJa = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            imagesBody = """
                {
                  "id": 1429,
                  "logos": [
                    { "file_path": "/neutral_logo.png", "iso_639_1": null, "width": 1000, "height": 320, "aspect_ratio": 3.125, "vote_average": 4.5 },
                    { "file_path": "/ja_only.png", "iso_639_1": "ja", "width": 850, "height": 257, "aspect_ratio": 3.3, "vote_average": 3.5 }
                  ]
                }
            """.trimIndent()
        )
        val repoJa = createLogoRepository(fakeTmdbJa, FakeFanartApi(null))
        val infoJa = repoJa.getLogoInfoForAnime(sampleAotAnime())
        assertNotNull(infoJa)
        assertEquals("TMDB", infoJa!!.provider)
        assertEquals("ja", infoJa.language)
        assertEquals("https://image.tmdb.org/t/p/original/ja_only.png", infoJa.logoUrl)

        dao.deleteLogoCache(sampleAotAnime().hoshikaId)

        val fakeTmdbNeutral = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            imagesBody = """
                {
                  "id": 1429,
                  "logos": [
                    { "file_path": "/neutral_logo.png", "iso_639_1": null, "width": 1000, "height": 320, "aspect_ratio": 3.125, "vote_average": 4.0 }
                  ]
                }
            """.trimIndent()
        )
        val repoNeutral = createLogoRepository(fakeTmdbNeutral, FakeFanartApi(null))
        val infoNeutral = repoNeutral.getLogoInfoForAnime(sampleAotAnime())
        assertNotNull(infoNeutral)
        assertEquals("TMDB", infoNeutral!!.provider)
        assertNull(infoNeutral!!.language)
        assertEquals("https://image.tmdb.org/t/p/original/neutral_logo.png", infoNeutral.logoUrl)
    }

    // TEST 3: TMDB returns SVG logo & Coil ImageLoader is configured with SvgDecoder
    @Test
    fun test3_tmdbReturnsSvgLogo_andCoilImageLoaderSupportsSvg() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = LogoImageLoader.create(context)
        assertNotNull(loader)

        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(95479L, "Jujutsu Kaisen", "呪術廻戦", "2020-10-03"),
            imagesBody = """
                {
                  "id": 95479,
                  "logos": [
                    { "file_path": "/jjk_en_vector.svg", "iso_639_1": "en", "width": 1200, "height": 400, "aspect_ratio": 3.0, "vote_average": 5.0 }
                  ]
                }
            """.trimIndent()
        )
        val repo = createLogoRepository(fakeTmdb, FakeFanartApi(null))
        val jjk = Anime(
            hoshikaId = 40748L,
            title = "Jujutsu Kaisen",
            englishTitle = "Jujutsu Kaisen",
            releaseYear = 2020
        )

        val info = repo.getLogoInfoForAnime(jjk)
        assertNotNull(info)
        assertEquals("TMDB", info!!.provider)
        assertEquals("en", info.language)
        assertEquals("https://image.tmdb.org/t/p/original/jjk_en_vector.svg", info.logoUrl)
    }

    // TEST 4: TMDB returns no logos -> Fanart fallback succeeds
    @Test
    fun test4_tmdbHasNoLogos_fallsBackToFanartEnglishHdtvlogo() = runTest {
        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            imagesBody = """{ "id": 1429, "logos": [] }"""
        )
        val fakeFanart = FakeFanartApi(
            fanartBody = """
                {
                  "name": "Attack on Titan",
                  "hdtvlogo": [
                    { "id": "39467", "url": "https://assets.fanart.tv/fanart/attack-on-titan-en.png", "lang": "en", "likes": "18", "width": "800", "height": "310" }
                  ]
                }
            """.trimIndent()
        )
        val repo = createLogoRepository(fakeTmdb, fakeFanart)

        val info = repo.getLogoInfoForAnime(sampleAotAnime())
        assertNotNull(info)
        assertEquals(1, fakeFanart.requestCount)
        assertEquals("tmdb", fakeFanart.lastSourceParam)
        assertEquals("FANART", info!!.provider)
        assertEquals("en", info.language)
        assertEquals("https://assets.fanart.tv/fanart/attack-on-titan-en.png", info.logoUrl)
    }

    // TEST 5: TMDB request fails -> Fanart fallback succeeds
    @Test
    fun test5_tmdbImagesRequestFails_stillAttemptsFanartFallback() = runTest {
        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            throwOnImages = true
        )
        val fakeFanart = FakeFanartApi(
            fanartBody = """
                {
                  "name": "Attack on Titan",
                  "hdtvlogo": [
                    { "id": "39467", "url": "https://assets.fanart.tv/fanart/aot-fallback.png", "lang": "en", "likes": "12", "width": "800", "height": "310" }
                  ]
                }
            """.trimIndent()
        )
        val repo = createLogoRepository(fakeTmdb, fakeFanart)

        val info = repo.getLogoInfoForAnime(sampleAotAnime())
        assertNotNull(info)
        assertEquals(1, fakeFanart.requestCount)
        assertEquals("FANART", info!!.provider)
        assertEquals("https://assets.fanart.tv/fanart/aot-fallback.png", info.logoUrl)
    }

    // TEST 6: Both TMDB and Fanart fail -> text fallback displayed, failure NOT cached in Room
    @Test
    fun test6_bothProvidersFailOrEmpty_rendersTextFallbackAndDoesNotCacheFailure() = runTest {
        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            imagesBody = """{ "id": 1429, "logos": [] }"""
        )
        val fakeFanart = FakeFanartApi(fanartBody = """{ "name": "Attack on Titan", "hdtvlogo": [] }""")
        val repo = createLogoRepository(fakeTmdb, fakeFanart)

        val anime = sampleAotAnime()
        val info = repo.getLogoInfoForAnime(anime)
        assertNull(info)

        // Verify failure was NOT permanently cached in Room
        assertNull(dao.getLogoCache(anime.hoshikaId))

        // Verify shared AnimeLogo renders text fallback cleanly
        composeTestRule.setContent {
            AnimeLogo(
                hoshikaId = anime.hoshikaId,
                animeTitle = anime.preferredTitle,
                logoUrl = info?.logoUrl
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("anime_logo_text_fallback_${anime.hoshikaId}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Attack on Titan").assertIsDisplayed()
    }

    // TEST 7: Invalid cached logo URL -> cache invalidated and retried (with multi-URL exclusion)
    @Test
    fun test7_invalidCachedUrl_invalidatesCacheAndRetriesProvidersExcludingFailedUrls() = runTest {
        val anime = sampleAotAnime()
        val brokenCachedUrl1 = "https://image.tmdb.org/t/p/original/expired_broken_logo1.png"
        val brokenCachedUrl2 = "https://image.tmdb.org/t/p/original/expired_broken_logo2.png"

        dao.insertLogoCache(
            LogoCacheEntity(
                hoshikaId = anime.hoshikaId,
                tmdbId = 1429L,
                provider = "TMDB",
                logoUrl = brokenCachedUrl1,
                language = "en",
                width = 800,
                height = 300,
                validationStatus = "VALID",
                lastUpdated = System.currentTimeMillis()
            )
        )

        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            imagesBody = """
                {
                  "id": 1429,
                  "logos": [
                    { "file_path": "/expired_broken_logo1.png", "iso_639_1": "en", "width": 950, "height": 300, "aspect_ratio": 3.16, "vote_average": 5.0 },
                    { "file_path": "/expired_broken_logo2.png", "iso_639_1": "en", "width": 920, "height": 300, "aspect_ratio": 3.06, "vote_average": 4.9 },
                    { "file_path": "/fresh_valid_logo.png", "iso_639_1": "en", "width": 900, "height": 310, "aspect_ratio": 2.9, "vote_average": 4.8 }
                  ]
                }
            """.trimIndent()
        )
        val fakeFanart = FakeFanartApi(fanartBody = null)
        val repo = createLogoRepository(fakeTmdb, fakeFanart)

        // First call returns cached URL
        assertEquals(brokenCachedUrl1, repo.getLogoForAnime(anime))

        // First retry excludes brokenCachedUrl1 -> selects brokenCachedUrl2
        val retried1 = repo.invalidateAndRetryLogo(anime, failedUrl = brokenCachedUrl1)
        assertNotNull(retried1)
        assertEquals(brokenCachedUrl2, retried1!!.logoUrl)

        // Second retry excludes BOTH brokenCachedUrl1 and brokenCachedUrl2 -> selects fresh_valid_logo.png
        val retried2 = repo.invalidateAndRetryLogo(anime, failedUrl = brokenCachedUrl2)
        assertNotNull(retried2)
        assertEquals("https://image.tmdb.org/t/p/original/fresh_valid_logo.png", retried2!!.logoUrl)
        assertEquals("https://image.tmdb.org/t/p/original/fresh_valid_logo.png", dao.getLogoCache(anime.hoshikaId)?.logoUrl)
    }

    // TEST 8: Missing API credentials -> graceful fallback without crash
    @Test
    fun test8_missingApiCredentials_gracefulFallbackWithoutCrash() = runTest {
        assertFalse(NetworkModule.isConfiguredSecret(null))
        assertFalse(NetworkModule.isConfiguredSecret(""))
        assertFalse(NetworkModule.isConfiguredSecret("MY_TMDB_API_KEY"))
        assertFalse(NetworkModule.isConfiguredSecret("MY_FANART_API_KEY"))
        assertTrue(NetworkModule.isConfiguredSecret("real_configured_token_12345"))

        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(1429L, "Attack on Titan", "進撃の巨人", "2013-04-07"),
            imagesBody = """{ "id": 1429, "logos": [{ "file_path": "/en.png", "iso_639_1": "en", "width": 800, "height": 300 }] }"""
        )
        val fakeFanart = FakeFanartApi(
            fanartBody = """{ "name": "Attack on Titan", "hdtvlogo": [{ "url": "https://assets.fanart.tv/fanart/en.png", "lang": "en" }] }"""
        )
        val repoWithMissingCredentials = LogoRepository(
            dao = dao,
            tmdbLogoProvider = TmdbLogoProvider(tmdbApi = fakeTmdb, hasValidCredentials = { false }),
            fanartLogoProvider = FanartLogoProvider(fanartApi = fakeFanart, hasValidCredentials = { false })
        )

        val result = repoWithMissingCredentials.getLogoInfoForAnime(sampleAotAnime())
        assertNull(result)
        assertEquals(0, fakeTmdb.searchRequestCount)
        assertEquals(0, fakeTmdb.imagesRequestCount)
        assertEquals(0, fakeFanart.requestCount)
    }

    // TEST 9: Home and Details share cached logo without duplicate requests, and expired cache TTL triggers refresh
    @Test
    fun test9_homeAndDetailsShareCachedLogo_andExpiredCacheTriggersRefresh() = runTest {
        val fakeTmdb = FakeTmdbApi(
            searchBody = tmdbSearchPayload(220542L, "The Apothecary Diaries", "薬屋のひとりごと", "2023-10-22"),
            imagesBody = """
                {
                  "id": 220542,
                  "logos": [
                    { "file_path": "/apothecary_en.png", "iso_639_1": "en", "width": 1000, "height": 350, "aspect_ratio": 2.85, "vote_average": 5.0 }
                  ]
                }
            """.trimIndent()
        )
        val fakeFanart = FakeFanartApi(fanartBody = null)
        val repo = createLogoRepository(fakeTmdb, fakeFanart)

        val season3Anime = Anime(
            hoshikaId = 558999L,
            title = "Kusuriya no Hitorigoto 3rd Season",
            englishTitle = "The Apothecary Diaries Season 3",
            releaseYear = 2026
        )

        // Home screen resolves logo
        val homeLogoUrl = repo.getLogoForAnime(season3Anime)
        assertEquals("https://image.tmdb.org/t/p/original/apothecary_en.png", homeLogoUrl)
        assertEquals(1, fakeTmdb.imagesRequestCount)

        // Details screen requests logo for the same anime -> hits Room cache, 0 new network calls
        val detailsLogoUrl = repo.getLogoForAnime(season3Anime)
        assertEquals(homeLogoUrl, detailsLogoUrl)
        assertEquals(1, fakeTmdb.imagesRequestCount)

        // Simulate expired cache entry (older than 7 days)
        dao.insertLogoCache(
            LogoCacheEntity(
                hoshikaId = season3Anime.hoshikaId,
                tmdbId = 220542L,
                provider = "TMDB",
                logoUrl = homeLogoUrl,
                language = "en",
                width = 1000,
                height = 350,
                validationStatus = "VALID",
                lastUpdated = System.currentTimeMillis() - LogoRepository.VALID_CACHE_TTL_MS - 60_000L
            )
        )

        // Requesting again after TTL expiration refreshes from TMDB
        val refreshedUrl = repo.getLogoForAnime(season3Anime)
        assertEquals(homeLogoUrl, refreshedUrl)
        assertEquals(2, fakeTmdb.imagesRequestCount)
    }

    private fun createLogoRepository(tmdbApi: TmdbApi, fanartApi: FanartApi): LogoRepository {
        return LogoRepository(
            dao = dao,
            tmdbLogoProvider = TmdbLogoProvider(tmdbApi = tmdbApi, hasValidCredentials = { true }),
            fanartLogoProvider = FanartLogoProvider(fanartApi = fanartApi, hasValidCredentials = { true })
        )
    }

    private fun sampleAotAnime() = Anime(
        hoshikaId = 33374L,
        anilistId = 16498L,
        title = "Shingeki no Kyojin",
        englishTitle = "Attack on Titan",
        japaneseTitle = "進撃の巨人",
        releaseYear = 2013
    )

    private fun tmdbSearchPayload(id: Long, name: String, originalName: String, airDate: String): String {
        return """
            {
              "page": 1,
              "results": [
                {
                  "id": $id,
                  "name": "$name",
                  "original_name": "$originalName",
                  "first_air_date": "$airDate",
                  "original_language": "ja",
                  "genre_ids": [16, 10759]
                }
              ]
            }
        """.trimIndent()
    }

    private class FakeTmdbApi(
        private val searchBody: String?,
        private val imagesBody: String? = null,
        private val throwOnImages: Boolean = false
    ) : TmdbApi {
        var searchRequestCount = 0
        var imagesRequestCount = 0

        override suspend fun searchTv(
            query: String,
            firstAirDateYear: Int?,
            includeAdult: Boolean
        ): Response<ResponseBody> {
            searchRequestCount++
            val body = searchBody ?: return Response.error(500, "".toResponseBody(null))
            return Response.success(body.toResponseBody("application/json".toMediaType()))
        }

        override suspend fun getTvImages(
            tvId: Long,
            includeImageLanguage: String
        ): Response<ResponseBody> {
            imagesRequestCount++
            if (throwOnImages) throw IOException("Simulated TMDB images error")
            val body = imagesBody ?: return Response.error(500, "".toResponseBody(null))
            return Response.success(body.toResponseBody("application/json".toMediaType()))
        }
    }

    private class FakeFanartApi(
        private val fanartBody: String?,
        private val statusCode: Int = 200
    ) : FanartApi {
        var requestCount = 0
        var lastSourceParam: String? = null

        override suspend fun getTvImages(tmdbTvId: Long, source: String): Response<ResponseBody> {
            requestCount++
            lastSourceParam = source
            if (statusCode != 200 || fanartBody == null) {
                return Response.error(
                    if (statusCode == 200) 404 else statusCode,
                    "".toResponseBody(null)
                )
            }
            return Response.success(fanartBody.toResponseBody("application/json".toMediaType()))
        }
    }
}
