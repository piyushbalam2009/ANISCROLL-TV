package `in`.aniscroll.tv.domain.converter

import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.AnimeIdMappingEntity
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.JsonParsers
import `in`.aniscroll.tv.domain.model.Anime

data class ResolvedStreamingTarget(
    val anilistId: Long,
    val format: String?
)

/**
 * Dedicated Hoshika ID -> AniList ID Converter (Section 8).
 *
 * Rules enforced:
 * 1. Never uses the AniList GraphQL API (graphql.anilist.co).
 * 2. Never passes a Hoshika ID or MAL ID directly as an AniList ID.
 * 3. Checks local Room `anime_id_mappings` cache first.
 * 4. Checks Hoshika's `anilist_id` / `mal_id` fields, and resolves via the AniScroll backend
 *    (`https://anivault-scraper-five.vercel.app/api/anilist/id` or `https://anivault-scraper-five.vercel.app/api/search`) when
 *    Hoshika's public v1 response only exposes `shortId`.
 * 5. Caches the verified mapping in Room (`anime_id_mappings`).
 */
class HoshikaIdConverter(
    private val dao: AniScrollDao,
    private val aniScrollApi: AniScrollApi,
    private val fetchHoshikaAnimeDetails: suspend (Long) -> Anime?
) {

    suspend fun convert(hoshikaId: Long): Long? {
        if (hoshikaId <= 0L) return null

        val cached = dao.getMapping(hoshikaId)
        if (cached != null && cached.anilistId > 0L) {
            return cached.anilistId
        }

        val anime = fetchHoshikaAnimeDetails(hoshikaId) ?: return null
        return convert(anime)
    }

    suspend fun convert(anime: Anime): Long? {
        return resolveTarget(anime)?.anilistId
    }

    /**
     * Resolves both the verified AniList ID and the anime format (e.g. "MOVIE", "TV", "SPECIAL", "OVA", "ONA")
     * from existing metadata, Room cache, Hoshika details, or AniScroll's AniList search metadata.
     */
    suspend fun resolveTarget(anime: Anime): ResolvedStreamingTarget? {
        val hoshikaId = anime.hoshikaId
        if (hoshikaId <= 0L) return null

        var resolvedFormat: String? = anime.format?.trim()?.takeIf { it.isNotEmpty() }
            ?: dao.getCachedAnime(hoshikaId)?.format?.trim()?.takeIf { it.isNotEmpty() }

        // 1. Check Room cache first
        val cached = dao.getMapping(hoshikaId)
        if (cached != null && cached.anilistId > 0L) {
            if (resolvedFormat == null) {
                resolvedFormat = fetchHoshikaAnimeDetails(hoshikaId)?.format?.trim()?.takeIf { it.isNotEmpty() }
            }
            return ResolvedStreamingTarget(anilistId = cached.anilistId, format = resolvedFormat)
        }

        // 2. Direct anilistId from metadata if present
        val directAnilist = anime.anilistId?.takeIf { it > 0L && it != hoshikaId }
        if (directAnilist != null) {
            saveMapping(hoshikaId, directAnilist)
            if (resolvedFormat == null) {
                resolvedFormat = fetchHoshikaAnimeDetails(hoshikaId)?.format?.trim()?.takeIf { it.isNotEmpty() }
            }
            return ResolvedStreamingTarget(anilistId = directAnilist, format = resolvedFormat)
        }

        // 3. Ensure we have full Hoshika details (English/Romaji titles, episodes, format, malId)
        val detailedAnime = if (
            anime.englishTitle.isNullOrBlank() ||
            anime.format.isNullOrBlank() ||
            anime.totalEpisodes == null
        ) {
            fetchHoshikaAnimeDetails(hoshikaId) ?: anime
        } else {
            anime
        }

        if (resolvedFormat == null) {
            resolvedFormat = detailedAnime.format?.trim()?.takeIf { it.isNotEmpty() }
        }

        detailedAnime.anilistId?.takeIf { it > 0L && it != hoshikaId }?.let { resolved ->
            saveMapping(hoshikaId, resolved)
            return ResolvedStreamingTarget(anilistId = resolved, format = resolvedFormat)
        }

        // 4. If Hoshika exposed malId, resolve via AniScroll backend /api/anilist/id
        detailedAnime.malId?.takeIf { it > 0L }?.let { malId ->
            val fromMal = resolveFromMalId(malId)
            if (fromMal != null && fromMal > 0L) {
                saveMapping(hoshikaId, fromMal)
                return ResolvedStreamingTarget(anilistId = fromMal, format = resolvedFormat)
            }
        }

        // 5. Resolve via AniScroll backend /api/search using English / Romaji titles
        val candidateQueries = listOfNotNull(
            detailedAnime.englishTitle?.trim()?.takeIf { it.isNotEmpty() },
            detailedAnime.title.trim().takeIf { it.isNotEmpty() },
            detailedAnime.japaneseTitle?.trim()?.takeIf { it.isNotEmpty() }
        ).distinct()

        for (query in candidateQueries) {
            val match = resolveMatchFromAniScrollSearch(query, detailedAnime)
            if (match != null && match.anilistId > 0L) {
                saveMapping(hoshikaId, match.anilistId)
                return ResolvedStreamingTarget(
                    anilistId = match.anilistId,
                    format = resolvedFormat ?: match.format
                )
            }
        }

        return null
    }

    private suspend fun resolveFromMalId(malId: Long): Long? {
        val resp = runCatching { aniScrollApi.getAniListIdFromMal(malId) }.getOrNull() ?: return null
        if (!resp.isSuccessful) return null
        val body = resp.body()?.string() ?: return null
        return JsonParsers.parseAniListIdFromMalResponse(body)
    }

    private suspend fun resolveMatchFromAniScrollSearch(
        query: String,
        targetAnime: Anime
    ): JsonParsers.AniScrollSearchMatch? {
        val resp = runCatching { aniScrollApi.searchAnimeIds(query = query, limit = 10) }.getOrNull() ?: return null
        if (!resp.isSuccessful) return null
        val body = resp.body()?.string() ?: return null
        return JsonParsers.parseAniScrollSearchMatch(body, targetAnime)
    }

    suspend fun cacheMappingIfValid(hoshikaId: Long, anilistId: Long?) {
        val validAnilist = anilistId?.takeIf { it > 0L && it != hoshikaId } ?: return
        if (hoshikaId <= 0L) return
        saveMapping(hoshikaId, validAnilist)
    }

    private suspend fun saveMapping(hoshikaId: Long, anilistId: Long) {
        dao.insertMapping(
            AnimeIdMappingEntity(
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }
}
