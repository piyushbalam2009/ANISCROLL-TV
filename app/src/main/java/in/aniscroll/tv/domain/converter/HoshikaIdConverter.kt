package `in`.aniscroll.tv.domain.converter

import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.AnimeIdMappingEntity
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.JsonParsers
import `in`.aniscroll.tv.domain.model.Anime

/**
 * Dedicated Hoshika ID -> AniList ID Converter (Section 8).
 *
 * Rules enforced:
 * 1. Never uses the AniList GraphQL API (graphql.anilist.co).
 * 2. Never passes a Hoshika ID or MAL ID directly as an AniList ID.
 * 3. Checks local Room `anime_id_mappings` cache first.
 * 4. Checks Hoshika's `anilist_id` / `mal_id` fields, and resolves via the AniScroll backend
 *    (`https://api.aniscroll.in/api/anilist/id` or `https://api.aniscroll.in/api/search`) when
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
        val hoshikaId = anime.hoshikaId
        if (hoshikaId <= 0L) return null

        // 1. Check Room cache first
        val cached = dao.getMapping(hoshikaId)
        if (cached != null && cached.anilistId > 0L) {
            return cached.anilistId
        }

        // 2. Direct anilistId from Hoshika metadata if present
        val directAnilist = anime.anilistId?.takeIf { it > 0L && it != hoshikaId }
        if (directAnilist != null) {
            saveMapping(hoshikaId, directAnilist)
            return directAnilist
        }

        // 3. Ensure we have full Hoshika details (English/Romaji titles, episodes, format, malId)
        val detailedAnime = if (anime.englishTitle.isNullOrBlank() && anime.totalEpisodes == null) {
            fetchHoshikaAnimeDetails(hoshikaId) ?: anime
        } else {
            anime
        }

        detailedAnime.anilistId?.takeIf { it > 0L && it != hoshikaId }?.let { resolved ->
            saveMapping(hoshikaId, resolved)
            return resolved
        }

        // 4. If Hoshika exposed malId, resolve via AniScroll backend /api/anilist/id
        detailedAnime.malId?.takeIf { it > 0L }?.let { malId ->
            val fromMal = resolveFromMalId(malId)
            if (fromMal != null && fromMal > 0L) {
                saveMapping(hoshikaId, fromMal)
                return fromMal
            }
        }

        // 5. Resolve via AniScroll backend /api/search using English / Romaji titles
        val candidateQueries = listOfNotNull(
            detailedAnime.englishTitle?.trim()?.takeIf { it.isNotEmpty() },
            detailedAnime.title.trim().takeIf { it.isNotEmpty() },
            detailedAnime.japaneseTitle?.trim()?.takeIf { it.isNotEmpty() }
        ).distinct()

        for (query in candidateQueries) {
            val resolvedId = resolveFromAniScrollSearch(query, detailedAnime)
            if (resolvedId != null && resolvedId > 0L) {
                saveMapping(hoshikaId, resolvedId)
                return resolvedId
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

    private suspend fun resolveFromAniScrollSearch(query: String, targetAnime: Anime): Long? {
        val resp = runCatching { aniScrollApi.searchAnimeIds(query = query, limit = 10) }.getOrNull() ?: return null
        if (!resp.isSuccessful) return null
        val body = resp.body()?.string() ?: return null
        return JsonParsers.parseAniScrollIdSearch(body, targetAnime)
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
