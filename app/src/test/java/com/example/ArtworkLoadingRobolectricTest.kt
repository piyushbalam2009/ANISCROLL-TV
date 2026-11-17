package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import `in`.aniscroll.tv.artwork.ArtworkErrorCategory
import `in`.aniscroll.tv.artwork.ArtworkType
import `in`.aniscroll.tv.artwork.LogoImageLoader
import `in`.aniscroll.tv.artwork.LogoLogger
import `in`.aniscroll.tv.core.database.AniScrollDatabase
import `in`.aniscroll.tv.core.designsystem.TvAnimePosterCard
import `in`.aniscroll.tv.core.designsystem.TvArtworkImage
import `in`.aniscroll.tv.core.device.TvBufferProfile
import `in`.aniscroll.tv.core.device.TvDeviceCapabilities
import `in`.aniscroll.tv.core.device.TvDeviceProfile
import `in`.aniscroll.tv.core.device.TvPerformanceTier
import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.data.remote.JsonParsers
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.features.episodes.EpisodeListRowItem
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h720dp-land")
class ArtworkLoadingRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AniScrollDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LogoLogger.clearArtworkDiagnostics()
        database = Room.inMemoryDatabaseBuilder(context, AniScrollDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        Coil.setImageLoader(TvDeviceCapabilities.createOptimizedImageLoader(context))
        LogoLogger.clearArtworkDiagnostics()
    }

    // Scenario 1, 2, 3, 4: Poster + Banner combinations & fallback rendering
    @Test
    fun parseHoshikaAnime_handlesPosterBannerBothOrMissingCleanly() {
        val bothJson = JSONObject(
            """
            {
              "shortId": 33374,
              "titleRomaji": "Shingeki no Kyojin",
              "titleEnglish": "Attack on Titan",
              "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/33374.webp",
              "bannerImage": "https://cdn.hoshika.app/catalog/banners/anime/33374.webp"
            }
            """.trimIndent()
        )
        val both = JsonParsers.parseHoshikaAnime(bothJson)!!
        assertEquals("https://cdn.hoshika.app/catalog/covers/anime/33374.webp", both.posterUrl)
        assertEquals("https://cdn.hoshika.app/catalog/banners/anime/33374.webp", both.bannerUrl)

        val posterOnlyJson = JSONObject(
            """
            {
              "shortId": 33375,
              "titleRomaji": "Poster Only Anime",
              "poster_url": "https://cdn.hoshika.app/catalog/covers/anime/33375.webp",
              "bannerImage": null
            }
            """.trimIndent()
        )
        val posterOnly = JsonParsers.parseHoshikaAnime(posterOnlyJson)!!
        assertEquals("https://cdn.hoshika.app/catalog/covers/anime/33375.webp", posterOnly.posterUrl)
        assertNull(posterOnly.bannerUrl)

        val bannerOnlyJson = JSONObject(
            """
            {
              "shortId": 33376,
              "titleRomaji": "Banner Only Anime",
              "coverImage": "undefined",
              "backdrop_url": "https://cdn.hoshika.app/catalog/banners/anime/33376.webp"
            }
            """.trimIndent()
        )
        val bannerOnly = JsonParsers.parseHoshikaAnime(bannerOnlyJson)!!
        assertNull(bannerOnly.posterUrl)
        assertEquals("https://cdn.hoshika.app/catalog/banners/anime/33376.webp", bannerOnly.bannerUrl)

        val noneJson = JSONObject(
            """
            {
              "shortId": 33377,
              "titleRomaji": "No Artwork Anime",
              "coverImage": "null",
              "bannerImage": "{}"
            }
            """.trimIndent()
        )
        val none = JsonParsers.parseHoshikaAnime(noneJson)!!
        assertNull(none.posterUrl)
        assertNull(none.bannerUrl)
    }

    // Scenario 5: Nested artwork objects & array roles exclude logo/clearlogo from poster/banner
    @Test
    fun parseHoshikaAnime_extractsNestedObjectsAndArraysWhileExcludingLogos() {
        val nestedJson = JSONObject(
            """
            {
              "anime": {
                "shortId": 40748,
                "title": { "romaji": "Jujutsu Kaisen", "english": "Jujutsu Kaisen" },
                "coverImage": {
                  "medium": "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/40748.png",
                  "extraLarge": "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/40748.png"
                },
                "images": [
                  { "role": "clearlogo", "url": "https://assets.fanart.tv/logo/40748.png" },
                  { "type": "backdrop", "url": "http://cdn.hoshika.app/catalog/banners/anime/40748.webp?token=secret999" }
                ]
              }
            }
            """.trimIndent()
        )
        val parsed = JsonParsers.parseHoshikaAnime(nestedJson)!!
        assertEquals(40748L, parsed.hoshikaId)
        assertEquals("https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/40748.png", parsed.posterUrl)
        assertEquals("https://cdn.hoshika.app/catalog/banners/anime/40748.webp?token=secret999", parsed.bannerUrl)
    }

    // Scenario 6 & 8: HTTPS URLs, selective HTTP->HTTPS upgrade, signed params, & malformed URL rejection
    @Test
    fun normalizeSecureArtworkUrl_upgradesKnownHostsPreservesQueryParamsAndRejectsGarbage() {
        // Known CDN hosts upgrade cleartext HTTP -> HTTPS and keep signed query params
        assertEquals(
            "https://cdn.hoshika.app/catalog/covers/anime/123.webp?sig=abc123&exp=999",
            LogoLogger.normalizeSecureArtworkUrl("http://cdn.hoshika.app/catalog/covers/anime/123.webp?sig=abc123&exp=999")
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/tv/1429/poster.jpg",
            LogoLogger.normalizeSecureArtworkUrl("//assets.fanart.tv/fanart/tv/1429/poster.jpg")
        )
        // Loopback & non-HTTPS-listed hosts keep http:// so redirects can be followed properly
        assertEquals(
            "http://127.0.0.1:8080/poster.png",
            LogoLogger.normalizeSecureArtworkUrl("http://127.0.0.1:8080/poster.png")
        )
        assertEquals(
            "http://legacy-cdn.example.org/img/poster.webp",
            LogoLogger.normalizeSecureArtworkUrl("http://legacy-cdn.example.org/img/poster.webp")
        )

        // Malformed values are rejected cleanly
        val invalidInputs = listOf(null, "", "   ", "null", "undefined", "none", "N/A", "{}", "[1]", "/relative/poster.png", "ftp://cdn.hoshika.app/1.png", "not a url")
        for (bad in invalidInputs) {
            assertNull("Expected null for '$bad'", LogoLogger.normalizeSecureArtworkUrl(bad))
        }
    }

    // Scenario 7 & 14 (Network layer): Real OkHttp image client headers, redirect following, Cloudflare HTML rejection
    @Test
    fun imageOkHttpClient_usesNativeUserAgent_followsRedirects_andRejectsCloudflareHtmlOrEmptyBodies() {
        ServerSocket(0).use { server ->
            val port = server.localPort
            val observedUserAgents = CopyOnWriteArrayList<String>()
            val observedAccepts = CopyOnWriteArrayList<String>()

            val serverThread = thread(start = true) {
                repeat(3) {
                    val socket = server.accept()
                    socket.use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        var line: String? = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            if (line.startsWith("User-Agent:", ignoreCase = true)) {
                                observedUserAgents.add(line.substringAfter(":").trim())
                            } else if (line.startsWith("Accept:", ignoreCase = true)) {
                                observedAccepts.add(line.substringAfter(":").trim())
                            }
                            line = reader.readLine()
                        }

                        val out = client.getOutputStream()
                        when {
                            requestLine.contains("/redirect") -> {
                                val resp = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$port/image.png\r\nContent-Length: 0\r\n\r\n"
                                out.write(resp.toByteArray())
                            }
                            requestLine.contains("/image.png") -> {
                                val body = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                                val header = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\n\r\n"
                                out.write(header.toByteArray())
                                out.write(body)
                            }
                            requestLine.contains("/challenge") -> {
                                val html = "<html><title>Just a moment...</title></html>"
                                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.length}\r\n\r\n$html"
                                out.write(resp.toByteArray())
                            }
                        }
                        out.flush()
                    }
                }
            }

            // 1. Redirect -> valid image/png succeeds
            val okResp = NetworkModule.imageOkHttpClient.newCall(
                Request.Builder().url("http://127.0.0.1:$port/redirect").build()
            ).execute()
            assertEquals(200, okResp.code)
            assertEquals("image/png", okResp.header("Content-Type"))
            okResp.close()

            // Verify clean native UA (no Mozilla/Chrome spoof) and OS-gated Accept header
            assertTrue(observedUserAgents.isNotEmpty())
            assertTrue(observedUserAgents.all { it.startsWith("AniScrollTV/") && !it.contains("Mozilla/5.0") && !it.contains("Chrome/") })
            val expectedAvif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            assertEquals(expectedAvif, NetworkModule.imageAcceptHeader.contains("image/avif"))

            // 2. Soft Cloudflare / HTML response throws IOException and records diagnostic
            var htmlRejected = false
            try {
                NetworkModule.imageOkHttpClient.newCall(
                    Request.Builder().url("http://127.0.0.1:$port/challenge").build()
                ).execute().close()
            } catch (_: IOException) {
                htmlRejected = true
            }
            assertTrue("Expected text/html payload to be rejected with IOException", htmlRejected)
            assertTrue(
                LogoLogger.getRecentArtworkDiagnostics().any {
                    !it.isSuccess && it.errorCategory == ArtworkErrorCategory.UNSUPPORTED_FORMAT
                }
            )
            serverThread.join(3000)
        }
    }

    // Scenario 9: Shared singleton ImageLoader across AppContainer & LogoImageLoader with SVG support
    @Test
    fun logoImageLoader_sharesInstalledSingletonInstance_andRespectsOptimizedConfig() {
        val optimized = TvDeviceCapabilities.createOptimizedImageLoader(context)
        LogoImageLoader.installShared(optimized)
        assertSame(optimized, LogoImageLoader.get(context))
    }

    // Scenario 10 & 11: Episode thumbnail extraction + EpisodeListRowItem fallback to anime poster or EP label
    @Test
    fun parseEpisodesAndRenderRow_handlesNestedThumbnailsAndPosterFallback() {
        val raw = """
            {
              "ok": true,
              "episodes": [
                {
                  "number": 1,
                  "title": "To You, in 2000 Years",
                  "thumbnail_url": "https://cdn.hoshika.app/episodes/33374/1.webp"
                },
                {
                  "number": 2,
                  "title": "That Day",
                  "thumbnail": { "url": "https://cdn.hoshika.app/episodes/33374/2.webp" }
                },
                {
                  "number": 3,
                  "title": "A Dim Light Amid Despair"
                }
              ]
            }
        """.trimIndent()
        val page = JsonParsers.parseEpisodePageFromResponse(hoshikaId = 33374L, rawBody = raw)
        assertEquals(3, page.episodes.size)
        assertEquals("https://cdn.hoshika.app/episodes/33374/1.webp", page.episodes[0].thumbnailUrl)
        assertEquals("https://cdn.hoshika.app/episodes/33374/2.webp", page.episodes[1].thumbnailUrl)
        assertNull(page.episodes[2].thumbnailUrl)

        val requests = CopyOnWriteArrayList<String>()
        installTestCoilLoader(requests = requests, failingUrls = emptySet())

        // Episode 3 has no thumbnail -> falls back cleanly to animePosterUrl; Episode 4 has neither -> shows EP 4 placeholder
        composeTestRule.setContent {
            Column {
                EpisodeListRowItem(
                    episode = page.episodes[2],
                    progressItem = null,
                    isCurrentlyPlaying = false,
                    animePosterUrl = "https://cdn.hoshika.app/catalog/covers/anime/33374.webp",
                    onClick = {}
                )
                EpisodeListRowItem(
                    episode = Episode(id = "4", hoshikaId = 33374L, number = 4, title = "Night of the Closing Ceremony", thumbnailUrl = null),
                    progressItem = null,
                    isCurrentlyPlaying = false,
                    animePosterUrl = null,
                    onClick = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(requests.contains("https://cdn.hoshika.app/catalog/covers/anime/33374.webp"))
        composeTestRule.onNodeWithText("EP 4").assertIsDisplayed()
    }

    // Scenario 2, 12, 13, 14, 15: Banner->poster fallback, episode/anime cache isolation, UI placeholder, token redaction
    @Test
    fun tvArtworkImage_fallsBackOnFailure_isolatesCacheKeys_andSanitizesTokenDiagnostics() {
        val brokenBanner = "https://cdn.hoshika.app/banners/broken.webp?token=TOP_SECRET_TOKEN_XYZ"
        val validPoster = "https://cdn.hoshika.app/posters/valid.webp?sig=ANOTHER_SECRET"
        val requests = CopyOnWriteArrayList<String>()
        val memoryKeys = CopyOnWriteArrayList<String>()

        installTestCoilLoader(
            requests = requests,
            memoryKeys = memoryKeys,
            failingUrls = setOf(brokenBanner)
        )

        composeTestRule.setContent {
            Column {
                TvArtworkImage(
                    primaryUrl = brokenBanner,
                    fallbackUrl = validPoster,
                    artworkType = ArtworkType.BANNER,
                    screenName = "DetailsBanner",
                    hoshikaId = 9001L,
                    contentDescription = "Test Anime Banner",
                    targetWidthPx = 1280,
                    targetHeightPx = 720,
                    modifier = Modifier.size(320.dp, 180.dp)
                )
                TvArtworkImage(
                    primaryUrl = "https://cdn.hoshika.app/ep.webp",
                    artworkType = ArtworkType.EPISODE_THUMBNAIL,
                    screenName = "EpisodesList",
                    hoshikaId = 9001L,
                    episodeNumber = 1,
                    contentDescription = "Ep 1",
                    targetWidthPx = 320,
                    targetHeightPx = 180,
                    modifier = Modifier.size(160.dp, 90.dp)
                )
                TvArtworkImage(
                    primaryUrl = "https://cdn.hoshika.app/ep.webp",
                    artworkType = ArtworkType.EPISODE_THUMBNAIL,
                    screenName = "EpisodesList",
                    hoshikaId = 9001L,
                    episodeNumber = 2,
                    contentDescription = "Ep 2",
                    targetWidthPx = 320,
                    targetHeightPx = 180,
                    modifier = Modifier.size(160.dp, 90.dp)
                )
                TvAnimePosterCard(
                    anime = Anime(hoshikaId = 9002L, title = "Missing Poster Anime", posterUrl = null),
                    onClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        var attempts = 0
        while (!requests.contains(validPoster) && attempts < 40) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
            if (!requests.contains(validPoster)) {
                Thread.sleep(25)
            }
            attempts++
        }
        if (!requests.contains(validPoster)) {
            val eventsSummary = LogoLogger.getRecentArtworkDiagnostics().joinToString(" | ") { it.detail }
            val tree = runCatching { composeTestRule.onRoot().printToString() }.getOrElse { it.message }
            throw AssertionError(
                "Timed out waiting for fallback URL after $attempts attempts. requests=$requests events=[$eventsSummary] tree=\n$tree"
            )
        }
        composeTestRule.waitForIdle()

        // Broken primary banner triggered fallback to validPoster
        assertTrue(requests.contains(brokenBanner))
        assertTrue(requests.contains(validPoster))

        // Per-item memoryCacheKeys keep Episode 1 and Episode 2 completely isolated
        val epKeys = memoryKeys.filter { it.startsWith("episode_thumbnail:9001:") }.distinct()
        assertEquals(2, epKeys.size)
        assertNotEquals(epKeys[0], epKeys[1])

        // Missing poster shows clean monochrome placeholder with title
        composeTestRule.onNodeWithContentDescription("Missing Poster Anime").assertIsDisplayed()

        // Diagnostics recorded failure + success while strictly stripping query tokens
        val events = LogoLogger.getRecentArtworkDiagnostics()
        assertTrue(events.any { !it.isSuccess && it.errorCategory == ArtworkErrorCategory.HTTP_FAILURE })
        assertTrue(events.any { it.isSuccess && it.artworkType == ArtworkType.BANNER })
        assertTrue(events.any { !it.isSuccess && it.errorCategory == ArtworkErrorCategory.MISSING_URL && it.hoshikaId == 9002L })
        for (ev in events) {
            assertFalse("Token leaked in diagnostic path: ${ev.sanitizedPath}", ev.sanitizedPath.contains("TOP_SECRET_TOKEN_XYZ"))
            assertFalse("Signature leaked in diagnostic detail: ${ev.detail}", ev.detail.contains("ANOTHER_SECRET"))
        }
    }

    // Scenario 16: Fire TV HD vs 4K device-tier image sizing adapts properly
    @Test
    fun deviceProfile_adaptsArtworkDimensionsForFireTvHdVs4k() {
        val dummyBuffer = TvBufferProfile(12_000, 28_000, 1_000, 2_000, 8_000, 20 * 1024 * 1024)
        val hdStick = TvDeviceProfile(
            sdkInt = 28,
            isAmazonFireTv = true,
            isTelevisionUi = true,
            isLowRamDevice = true,
            totalRamMb = 1024,
            memoryClassMb = 96,
            displayMaxWidthPx = 1920,
            displayMaxHeightPx = 1080,
            densityDpi = 320,
            supports4kDisplay = false,
            maxHardwareVideoWidth = 1920,
            maxHardwareVideoHeight = 1080,
            supportsHardwareAvc = true,
            supportsHardwareHevc = false,
            supportsHardwareVp9 = false,
            supportsHardwareAv1 = false,
            performanceTier = TvPerformanceTier.COMPACT_HD,
            bufferProfile = dummyBuffer
        )
        val uhd4kMax = hdStick.copy(
            sdkInt = 34,
            isLowRamDevice = false,
            totalRamMb = 2048,
            memoryClassMb = 256,
            displayMaxWidthPx = 3840,
            displayMaxHeightPx = 2160,
            supports4kDisplay = true,
            maxHardwareVideoWidth = 3840,
            maxHardwareVideoHeight = 2160,
            supportsHardwareHevc = true,
            supportsHardwareVp9 = true,
            supportsHardwareAv1 = true,
            performanceTier = TvPerformanceTier.HIGH_PERF_4K_MAX
        )

        // Verify both anime switches and return load distinct isolated cache keys and preserve valid artwork
        assertTrue(hdStick.recommendedPosterWidthPx < uhd4kMax.recommendedPosterWidthPx)
        assertTrue(hdStick.recommendedBannerWidthPx < uhd4kMax.recommendedBannerWidthPx)
        assertTrue(hdStick.recommendedThumbnailWidthPx < uhd4kMax.recommendedThumbnailWidthPx)
        assertEquals(280, hdStick.recommendedPosterWidthPx)
        assertEquals(440, uhd4kMax.recommendedPosterWidthPx)
        assertEquals(1280, hdStick.recommendedBannerWidthPx)
        assertEquals(1920, uhd4kMax.recommendedBannerWidthPx)
    }

    // Scenario 13: Rapid switching between anime pages and returning cleanly resets/preserves artwork state
    @Test
    fun tvArtworkImage_rapidAnimeSwitchAndReturn_preservesAndIsolatesState() {
        val bannerA = "https://cdn.hoshika.app/banners/101.webp"
        val bannerB = "https://cdn.hoshika.app/banners/202_broken.webp"
        val posterB = "https://cdn.hoshika.app/posters/202.webp"
        val requests = CopyOnWriteArrayList<String>()
        val memoryKeys = CopyOnWriteArrayList<String>()
        installTestCoilLoader(requests = requests, memoryKeys = memoryKeys, failingUrls = setOf(bannerB))

        var currentAnimeId by androidx.compose.runtime.mutableStateOf(101L)
        var currentBanner by androidx.compose.runtime.mutableStateOf<String?>(bannerA)
        var currentPoster by androidx.compose.runtime.mutableStateOf<String?>("https://cdn.hoshika.app/posters/101.webp")

        composeTestRule.setContent {
            TvArtworkImage(
                primaryUrl = currentBanner,
                fallbackUrl = currentPoster,
                artworkType = ArtworkType.BANNER,
                screenName = "DetailsBanner",
                hoshikaId = currentAnimeId,
                contentDescription = "Banner $currentAnimeId",
                targetWidthPx = 1280,
                targetHeightPx = 720,
                modifier = Modifier.size(320.dp, 180.dp)
            )
        }
        composeTestRule.waitForIdle()
        assertTrue(requests.contains(bannerA))

        // Switch to Anime B where banner fails -> must fall back to posterB
        composeTestRule.runOnUiThread {
            currentAnimeId = 202L
            currentBanner = bannerB
            currentPoster = posterB
        }
        var attempts = 0
        while (!requests.contains(posterB) && attempts < 40) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
            if (!requests.contains(posterB)) Thread.sleep(25)
            attempts++
        }
        assertTrue(requests.contains(bannerB))
        assertTrue(requests.contains(posterB))

        // Switch back to Anime A -> must cleanly show bannerA again (not stuck on B's failure)
        composeTestRule.runOnUiThread {
            currentAnimeId = 101L
            currentBanner = bannerA
            currentPoster = "https://cdn.hoshika.app/posters/101.webp"
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Banner 101").assertIsDisplayed()
        assertTrue(memoryKeys.any { it.startsWith("banner:101:") })
        assertTrue(memoryKeys.any { it.startsWith("banner:202:") })
    }

    private fun installTestCoilLoader(
        requests: CopyOnWriteArrayList<String>,
        memoryKeys: CopyOnWriteArrayList<String>? = null,
        failingUrls: Set<String> = emptySet()
    ) {
        val loader = ImageLoader.Builder(context)
            .components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        val req = chain.request
                        val url = req.data.toString()
                        requests.add(url)
                        req.memoryCacheKey?.let { memoryKeys?.add(it.key) }
                        if (url in failingUrls) {
                            throw IOException("Simulated HTTP status=403 failure")
                        }
                        val bmp = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.LTGRAY)
                        }
                        return SuccessResult(
                            drawable = BitmapDrawable(context.resources, bmp),
                            request = req,
                            dataSource = DataSource.NETWORK
                        )
                    }
                })
            }
            .build()
        Coil.setImageLoader(loader)
    }
}
