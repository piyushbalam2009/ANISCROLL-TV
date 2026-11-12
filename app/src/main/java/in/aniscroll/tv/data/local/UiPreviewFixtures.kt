package `in`.aniscroll.tv.data.local

import `in`.aniscroll.tv.data.repository.HomeSectionRow
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.ScheduleItem

/**
 * Isolated UI Preview Fixtures (Section 36):
 * "Use temporary local preview fixtures only for UI development, clearly separated from
 * production repositories. Do not represent fixture data as live API results."
 *
 * These fixtures are NEVER returned automatically by AnimeRepository. They are only loaded
 * if the developer explicitly clicks "Load UI Preview Fixtures (Dev Mode)" when testing UI
 * navigation in an offline/sandboxed environment.
 */
object UiPreviewFixtures {

    val sampleAnimeList: List<Anime> = listOf(
        Anime(
            hoshikaId = 900101L,
            anilistId = 1535L,
            title = "Death Note",
            englishTitle = "Death Note",
            japaneseTitle = "デスノート",
            description = "An intelligent high school student goes on a secret crusade to eliminate criminals from the world after discovering a notebook capable of killing anyone whose name is written into it.",
            posterUrl = null,
            bannerUrl = null,
            releaseYear = 2006,
            season = "FALL",
            status = "FINISHED",
            format = "TV",
            totalEpisodes = 37,
            genres = listOf("Mystery", "Psychological", "Supernatural", "Thriller")
        ),
        Anime(
            hoshikaId = 900102L,
            anilistId = 154587L,
            title = "Sousou no Frieren",
            englishTitle = "Frieren: Beyond Journey's End",
            japaneseTitle = "葬送のフリーレン",
            description = "After a ten-year quest to defeat the Demon King alongside the hero Himmel and his party, the elven mage Frieren embarks on a new journey to understand humanity and the passage of time.",
            posterUrl = null,
            bannerUrl = null,
            releaseYear = 2023,
            season = "FALL",
            status = "FINISHED",
            format = "TV",
            totalEpisodes = 28,
            genres = listOf("Adventure", "Drama", "Fantasy")
        ),
        Anime(
            hoshikaId = 900103L,
            anilistId = 113415L,
            title = "Jujutsu Kaisen",
            englishTitle = "Jujutsu Kaisen",
            japaneseTitle = "呪術廻戦",
            description = "Yuji Itadori joins a secret organization of Jujutsu Sorcerers in order to eliminate a powerful Curse named Ryomen Sukuna, of whom Yuji becomes the host.",
            posterUrl = null,
            bannerUrl = null,
            releaseYear = 2020,
            season = "FALL",
            status = "FINISHED",
            format = "TV",
            totalEpisodes = 24,
            genres = listOf("Action", "Supernatural")
        ),
        Anime(
            hoshikaId = 900104L,
            anilistId = 16498L,
            title = "Shingeki no Kyojin",
            englishTitle = "Attack on Titan",
            japaneseTitle = "進撃の巨人",
            description = "Centuries ago, mankind was slaughtered to near extinction by monstrous humanoid creatures called Titans, forcing humans to hide in fear behind enormous concentric walls.",
            posterUrl = null,
            bannerUrl = null,
            releaseYear = 2013,
            season = "SPRING",
            status = "FINISHED",
            format = "TV",
            totalEpisodes = 25,
            genres = listOf("Action", "Drama", "Fantasy", "Mystery")
        ),
        Anime(
            hoshikaId = 900105L,
            anilistId = 9253L,
            title = "Steins;Gate",
            englishTitle = "Steins;Gate",
            japaneseTitle = "シュタインズ・ゲート",
            description = "Self-proclaimed mad scientist Rintaro Okabe rents out a room in a rickety old building in Akihabara, where he and his lab members accidentally invent a device capable of sending messages into the past.",
            posterUrl = null,
            bannerUrl = null,
            releaseYear = 2011,
            season = "SPRING",
            status = "FINISHED",
            format = "TV",
            totalEpisodes = 24,
            genres = listOf("Drama", "Psychological", "Sci-Fi", "Thriller")
        ),
        Anime(
            hoshikaId = 900106L,
            anilistId = 145064L,
            title = "Solo Leveling",
            englishTitle = "Solo Leveling",
            japaneseTitle = "俺だけレベルアップな件",
            description = "In a world where hunters must battle deadly monsters to protect humanity, Sung Jinwoo, notoriously known as the weakest hunter of all mankind, finds himself in a relentless struggle for survival.",
            posterUrl = null,
            bannerUrl = null,
            releaseYear = 2024,
            season = "WINTER",
            status = "FINISHED",
            format = "TV",
            totalEpisodes = 12,
            genres = listOf("Action", "Adventure", "Fantasy")
        )
    )

    fun getPreviewHomeSections(): List<HomeSectionRow> = listOf(
        HomeSectionRow("Trending Anime (UI Preview Fixture)", sampleAnimeList),
        HomeSectionRow("Popular Anime (UI Preview Fixture)", sampleAnimeList.reversed()),
        HomeSectionRow("Action (UI Preview Fixture)", sampleAnimeList.filter { "Action" in it.genres }),
        HomeSectionRow("Fantasy (UI Preview Fixture)", sampleAnimeList.filter { "Fantasy" in it.genres })
    )

    fun getPreviewEpisodes(hoshikaId: Long): List<Episode> {
        val anime = sampleAnimeList.find { it.hoshikaId == hoshikaId }
        val count = (anime?.totalEpisodes ?: 12).coerceAtMost(24)
        return (1..count).map { epNum ->
            Episode(
                id = "fixture_${hoshikaId}_ep_$epNum",
                hoshikaId = hoshikaId,
                number = epNum,
                title = "Episode $epNum",
                description = "UI preview fixture episode $epNum for ${anime?.preferredTitle ?: "Anime"}.",
                durationSeconds = 1420
            )
        }
    }

    fun getPreviewSchedule(day: String): List<ScheduleItem> {
        return sampleAnimeList.mapIndexed { idx, anime ->
            ScheduleItem(
                id = "fixture_sched_${day}_$idx",
                anime = anime,
                dayOfWeek = day,
                episodeNumber = idx + 1,
                formattedLocalTime = "${18 + idx}:30 Local",
                releaseStatus = "UI Preview Fixture"
            )
        }
    }
}
