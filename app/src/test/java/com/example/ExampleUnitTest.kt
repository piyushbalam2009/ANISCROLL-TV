package com.example

import `in`.aniscroll.tv.data.remote.JsonParsers
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.SkipIntervals
import `in`.aniscroll.tv.domain.model.TimeRange
import `in`.aniscroll.tv.player.ActiveSkipType
import `in`.aniscroll.tv.player.AniSkipManager
import `in`.aniscroll.tv.player.NextEpisodeManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {

    @Test
    fun aniSkipManager_showsAndDismissesIntroAndOutroCorrectly() {
        val manager = AniSkipManager()
        manager.setIntervals(
            SkipIntervals(
                recap = TimeRange(startTimeSec = 1f, endTimeSec = 8f, skipId = "recap-1"),
                intro = TimeRange(startTimeSec = 10f, endTimeSec = 100f, skipId = "op-1"),
                outro = TimeRange(startTimeSec = 1300f, endTimeSec = 1390f, skipId = "ed-1")
            )
        )

        // Inside recap interval
        val recapPrompt = manager.evaluateCurrentPosition(4_000L)
        assertNotNull(recapPrompt)
        assertEquals(ActiveSkipType.RECAP, recapPrompt?.type)
        assertEquals("Skip Recap", recapPrompt?.label)
        assertEquals(8_000L, recapPrompt?.targetTimeMs)

        // Before intro (after recap)
        assertNull(manager.evaluateCurrentPosition(9_000L))

        // Inside intro interval
        val introPrompt = manager.evaluateCurrentPosition(25_000L)
        assertNotNull(introPrompt)
        assertEquals(ActiveSkipType.INTRO, introPrompt?.type)
        assertEquals("Skip Intro", introPrompt?.label)
        assertEquals(100_000L, introPrompt?.targetTimeMs)

        // Mark skipped -> should no longer appear during same intro
        manager.markSkipped(introPrompt!!)
        assertNull(manager.evaluateCurrentPosition(35_000L))

        // Inside outro interval
        val outroPrompt = manager.evaluateCurrentPosition(1_320_000L)
        assertNotNull(outroPrompt)
        assertEquals(ActiveSkipType.OUTRO, outroPrompt?.type)
        assertEquals("Skip Outro", outroPrompt?.label)
    }

    @Test
    fun nextEpisodeManager_resolvesNonConsecutiveHoshikaEpisodes() {
        val manager = NextEpisodeManager()
        val episodes = listOf(
            Episode(id = "1", hoshikaId = 500L, number = 1, title = "Ep 1"),
            Episode(id = "2", hoshikaId = 500L, number = 2, title = "Ep 2"),
            Episode(id = "5", hoshikaId = 500L, number = 5, title = "Special Ep 5")
        )

        val nextAfter2 = manager.findNextEpisode(episodes, 2)
        assertEquals(5, nextAfter2?.number)

        val prevBefore5 = manager.findPreviousEpisode(episodes, 5)
        assertEquals(2, prevBefore5?.number)

        assertNull(manager.findNextEpisode(episodes, 5))
    }

    @Test
    fun jsonParsers_parsesAniSkipAndAniScrollStreamPayloads() {
        val aniSkipJson = """
            {
              "found": true,
              "results": [
                {
                  "interval": { "startTime": 12.5, "endTime": 102.5 },
                  "skipType": "op",
                  "skipId": "uuid-op"
                },
                {
                  "interval": { "startTime": 1310.0, "endTime": 1400.0 },
                  "skipType": "ed",
                  "skipId": "uuid-ed"
                }
              ],
              "statusCode": 200
            }
        """.trimIndent()

        val intervals = JsonParsers.parseAniSkipResponse(aniSkipJson)
        assertNotNull(intervals.intro)
        assertEquals(12.5f, intervals.intro!!.startTimeSec)
        assertEquals(102.5f, intervals.intro!!.endTimeSec)
        assertNotNull(intervals.outro)
        assertEquals(1310.0f, intervals.outro!!.startTimeSec)

        val streamJson = """
            {
              "sources": [
                { "url": "https://cdn.example.com/master.m3u8", "quality": "auto", "isM3U8": true },
                { "url": "https://cdn.example.com/1080p.m3u8", "quality": "1080p", "isM3U8": true }
              ],
              "subtitles": [
                { "url": "https://cdn.example.com/en.vtt", "lang": "English", "label": "English", "default": true }
              ]
            }
        """.trimIndent()

        val streamData = JsonParsers.parseAniScrollStreamResponse(
            anilistId = 1535L,
            episodeNumber = 1,
            language = "sub",
            rawBody = streamJson
        )
        assertNotNull(streamData)
        assertEquals(2, streamData!!.sources.size)
        assertEquals("Auto", streamData.sources[0].quality)
        assertEquals("1080p", streamData.sources[1].quality)
        assertEquals(1, streamData.subtitles.size)
        assertEquals("English", streamData.subtitles[0].language)
    }

    @Test
    fun jsonParsers_parsesHoshikaV1HomeAndScheduleSchema() {
        val hoshikaItemJson = """
            {
              "id": "00b35fed-fa64-4ccd-9e59-c5f450a60e0d",
              "shortId": 558999,
              "slug": "the-apothecary-diaries-season-3",
              "titleRomaji": "Kusuriya no Hitorigoto 3rd Season",
              "titleEnglish": "The Apothecary Diaries Season 3",
              "titleLocalized": "The Apothecary Diaries Season 3",
              "coverImage": "https://cdn.hoshika.app/catalog/anime/contrib/d922ca0e.webp",
              "bannerImage": "https://cdn.hoshika.app/catalog/anime/contrib/281a843c.webp",
              "averageScore": 87,
              "format": "TV",
              "episodes": 12,
              "status": "RELEASING",
              "seasonYear": 2026,
              "synopsis": "Maomao has returned to her work as an apothecary."
            }
        """.trimIndent()

        val anime = JsonParsers.parseSingleAnimeFromResponse(hoshikaItemJson)
        assertNotNull(anime)
        assertEquals(558999L, anime!!.hoshikaId)
        assertEquals("The Apothecary Diaries Season 3", anime.preferredTitle)
        assertEquals("https://cdn.hoshika.app/catalog/anime/contrib/d922ca0e.webp", anime.posterUrl)
        assertEquals("https://cdn.hoshika.app/catalog/anime/contrib/281a843c.webp", anime.bannerUrl)
        assertEquals(2026, anime.releaseYear)
        assertEquals(12, anime.totalEpisodes)

        val searchJson = """
            {
              "query": "Attack on Titan",
              "count": 2,
              "results": [
                {
                  "id": 16498,
                  "malId": 16498,
                  "title": "Attack on Titan",
                  "episodes": 25,
                  "status": "FINISHED",
                  "format": "TV"
                }
              ]
            }
        """.trimIndent()

        val targetAot = anime.copy(
            hoshikaId = 33374L,
            title = "Shingeki no Kyojin",
            englishTitle = "Attack on Titan",
            format = "TV",
            totalEpisodes = 25
        )
        val resolvedAnilistId = JsonParsers.parseAniScrollIdSearch(searchJson, targetAot)
        assertEquals(16498L, resolvedAnilistId)

        val zokoWatchJson = """
            {
              "source": "zokoanime",
              "anilistId": 16498,
              "malId": 16498,
              "title": "Attack on Titan",
              "episode": 1,
              "type": "sub",
              "embedUrl": "https://zokoanime.video/stream/mal/16498/1/sub",
              "m3u8": "https://hls.dramahot.top/p/master.m3u8",
              "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=master.m3u8",
              "qualities": [
                {
                  "quality": "1080p",
                  "resolution": "1920x1080",
                  "width": 1920,
                  "height": 1080,
                  "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=1080.m3u8"
                }
              ],
              "subtitles": [
                {
                  "lang": "en",
                  "label": "English",
                  "default": true,
                  "src": "https://hls.dramahot.top/subs/en.vtt",
                  "url": "https://anivault-scraper-five.vercel.app/api/proxy/subtitle?url=en.vtt"
                }
              ],
              "skip": {
                "intro": { "start": 138, "end": 215 },
                "outro": { "start": 1452, "end": 1542 }
              }
            }
        """.trimIndent()

        val zokoStream = JsonParsers.parseAniScrollStreamResponse(16498L, 1, "sub", zokoWatchJson)
        assertNotNull(zokoStream)
        assertEquals("https://zokoanime.video/stream/mal/16498/1/sub", zokoStream!!.headers["Referer"])
        assertEquals(138f, zokoStream.introStartSec)
        assertEquals(215f, zokoStream.introEndSec)
        assertEquals(1452f, zokoStream.outroStartSec)
        assertEquals(1542f, zokoStream.outroEndSec)
        assertTrue(zokoStream.sources.any { it.quality == "Auto" })
        assertTrue(zokoStream.sources.any { it.quality == "1080p" })
        assertEquals(
            "https://anivault-scraper-five.vercel.app/",
            `in`.aniscroll.tv.core.network.NetworkModule.ANISCROLL_STREAMING_BASE_URL
        )
    }
}
