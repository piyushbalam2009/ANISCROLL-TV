package `in`.aniscroll.tv.features.watchorder

import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.FranchiseRelationCategory
import `in`.aniscroll.tv.domain.model.FranchiseWatchOrder
import `in`.aniscroll.tv.domain.model.WatchOrderEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class RawRelationEdge(
    val fromAnilistId: Long,
    val toAnilistId: Long,
    val relationType: String // PREQUEL, SEQUEL, SIDE_STORY, PARENT, SUMMARY, ALTERNATIVE, SPIN_OFF, ADAPTATION, SOURCE, CHARACTER, OTHER, COMPILATION
)

data class RawFranchiseMediaNode(
    val anilistId: Long,
    val hoshikaId: Long? = null,
    val malId: Long? = null,
    val mediaType: String = "ANIME", // ANIME or MANGA
    val format: String = "TV", // TV, TV_SHORT, MOVIE, SPECIAL, OVA, ONA, MUSIC, MANGA, NOVEL
    val status: String? = null,
    val episodes: Int? = null,
    val releaseYear: Int? = null,
    val startDateNumeric: Int = 99999999,
    val titleRomaji: String,
    val titleEnglish: String? = null,
    val titleNative: String? = null,
    val description: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val relations: List<Pair<String, RawFranchiseMediaNode>> = emptyList()
) {
    val preferredTitle: String
        get() = titleEnglish?.takeIf { it.isNotBlank() } ?: titleRomaji
}

data class HoshikaRelationStub(
    val relationType: String,
    val shortId: Long,
    val title: String,
    val coverImage: String?,
    val format: String?
)

object WatchOrderGenerator {

    const val SYNTHETIC_HOSHIKA_ID_OFFSET = 900_000_000L

    fun syntheticHoshikaIdForAnilist(anilistId: Long): Long =
        SYNTHETIC_HOSHIKA_ID_OFFSET + anilistId

    fun isSyntheticHoshikaId(hoshikaId: Long): Boolean =
        hoshikaId > SYNTHETIC_HOSHIKA_ID_OFFSET

    /**
     * Builds a batched AniList GraphQL v2 query for up to 50 anime IDs in one network request.
     * Fetches media metadata and all `relations` edges (`version: 2`).
     */
    fun buildBatchMediaRelationsQueryJson(ids: List<Long>): String {
        val distinctIds = ids.filter { it > 0L }.distinct().take(50)
        val query = """
            query (${'$'}ids: [Int]) {
              Page(perPage: 50) {
                media(id_in: ${'$'}ids, type: ANIME) {
                  id
                  idMal
                  type
                  format
                  status
                  episodes
                  seasonYear
                  startDate {
                    year
                    month
                    day
                  }
                  description(asHtml: false)
                  title {
                    romaji
                    english
                    native
                  }
                  coverImage {
                    extraLarge
                    large
                  }
                  bannerImage
                  relations {
                    edges {
                      relationType(version: 2)
                      node {
                        id
                        idMal
                        type
                        format
                        status
                        episodes
                        seasonYear
                        startDate {
                          year
                          month
                          day
                        }
                        description(asHtml: false)
                        title {
                          romaji
                          english
                          native
                        }
                        coverImage {
                          extraLarge
                          large
                        }
                        bannerImage
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("ids", JSONArray(distinctIds))
        }
        return JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }.toString()
    }

    /**
     * Parses the batched AniList GraphQL response into [RawFranchiseMediaNode] objects.
     */
    fun parseAniListBatchMediaResponse(rawJson: String): List<RawFranchiseMediaNode> {
        return runCatching {
            val root = JSONObject(rawJson.trim())
            val dataObj = root.optJSONObject("data") ?: return emptyList()

            val mediaArray = dataObj.optJSONObject("Page")?.optJSONArray("media")
                ?: dataObj.optJSONObject("Media")?.let { JSONArray().put(it) }
                ?: return emptyList()

            buildList {
                for (i in 0 until mediaArray.length()) {
                    val mediaObj = mediaArray.optJSONObject(i) ?: continue
                    parseMediaNode(mediaObj, includeRelations = true)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseMediaNode(
        obj: JSONObject,
        includeRelations: Boolean
    ): RawFranchiseMediaNode? {
        val id = obj.optLong("id", 0L).takeIf { it > 0L } ?: return null
        val idMal = obj.optLong("idMal", 0L).takeIf { it > 0L }
        val mediaType = obj.optString("type").takeNonNull()?.uppercase(Locale.ROOT) ?: "ANIME"
        val format = obj.optString("format").takeNonNull()?.uppercase(Locale.ROOT) ?: "TV"
        val status = obj.optString("status").takeNonNull()?.uppercase(Locale.ROOT)
        val episodes = obj.optInt("episodes", 0).takeIf { it > 0 }

        val startDateObj = obj.optJSONObject("startDate")
        val startYear = startDateObj?.optInt("year", 0)?.takeIf { it > 1900 }
        val startMonth = startDateObj?.optInt("month", 0)?.takeIf { it in 1..12 } ?: 13
        val startDay = startDateObj?.optInt("day", 0)?.takeIf { it in 1..31 } ?: 32
        val releaseYear = obj.optInt("seasonYear", 0).takeIf { it > 1900 } ?: startYear

        val startDateNumeric = if (releaseYear != null) {
            releaseYear * 10000 + startMonth * 100 + startDay
        } else {
            99999999
        }

        val titleObj = obj.optJSONObject("title")
        val romaji = titleObj?.optString("romaji").takeNonNull()
            ?: titleObj?.optString("english").takeNonNull()
            ?: titleObj?.optString("native").takeNonNull()
            ?: obj.optString("title").takeNonNull()
            ?: return null
        val english = titleObj?.optString("english").takeNonNull()
        val nativeTitle = titleObj?.optString("native").takeNonNull()

        val rawDesc = obj.optString("description").takeNonNull()
        val cleanDesc = rawDesc
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&quot;", "\"")
            ?.replace("&#039;", "'")
            ?.replace("&amp;", "&")
            ?.trim()

        val coverObj = obj.optJSONObject("coverImage")
        val posterUrl = coverObj?.optString("extraLarge").takeNonNull()
            ?: coverObj?.optString("large").takeNonNull()
            ?: obj.optString("coverImage").takeNonNull()
        val bannerUrl = obj.optString("bannerImage").takeNonNull()

        val relations = mutableListOf<Pair<String, RawFranchiseMediaNode>>()
        if (includeRelations) {
            val edgesArr = obj.optJSONObject("relations")?.optJSONArray("edges")
            if (edgesArr != null) {
                for (i in 0 until edgesArr.length()) {
                    val edgeObj = edgesArr.optJSONObject(i) ?: continue
                    val relType = edgeObj.optString("relationType").takeNonNull()?.uppercase(Locale.ROOT)
                        ?: "OTHER"
                    val nodeObj = edgeObj.optJSONObject("node") ?: continue
                    val relatedNode = parseMediaNode(nodeObj, includeRelations = false) ?: continue
                    relations.add(relType to relatedNode)
                }
            }
        }

        return RawFranchiseMediaNode(
            anilistId = id,
            malId = idMal,
            mediaType = mediaType,
            format = format,
            status = status,
            episodes = episodes,
            releaseYear = releaseYear,
            startDateNumeric = startDateNumeric,
            titleRomaji = romaji,
            titleEnglish = english,
            titleNative = nativeTitle,
            description = cleanDesc,
            posterUrl = posterUrl,
            bannerUrl = bannerUrl,
            relations = relations
        )
    }

    /**
     * Parses `relations` array from Hoshika's `/api/v1/anime/{shortId}` JSON response.
     */
    fun parseHoshikaRelationsFromDetails(rawJson: String): List<HoshikaRelationStub> {
        return runCatching {
            val root = JSONObject(rawJson.trim())
            val animeObj = root.optJSONObject("anime")
                ?: root.optJSONObject("data")
                ?: root
            val relArr = animeObj.optJSONArray("relations") ?: return emptyList()
            buildList {
                for (i in 0 until relArr.length()) {
                    val item = relArr.optJSONObject(i) ?: continue
                    val shortId = item.optLong("shortId", 0L).takeIf { it > 0L }
                        ?: item.optLong("id", 0L).takeIf { it > 0L }
                        ?: continue
                    val relType = item.optString("relationType").takeNonNull()?.uppercase(Locale.ROOT)
                        ?: "OTHER"
                    val title = item.optString("title").takeNonNull()
                        ?: item.optString("titleEnglish").takeNonNull()
                        ?: item.optString("titleRomaji").takeNonNull()
                        ?: continue
                    val coverImage = item.optString("coverImage").takeNonNull()
                        ?: item.optString("poster").takeNonNull()
                    val format = item.optString("format").takeNonNull()?.uppercase(Locale.ROOT)
                    add(
                        HoshikaRelationStub(
                            relationType = relType,
                            shortId = shortId,
                            title = title,
                            coverImage = coverImage,
                            format = format
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Relevant relation types for building a franchise watch order graph.
     */
    private val TRAVERSABLE_ANIME_RELATIONS = setOf(
        "PREQUEL",
        "SEQUEL",
        "PARENT",
        "SIDE_STORY",
        "SUMMARY",
        "ALTERNATIVE",
        "SPIN_OFF",
        "COMPILATION",
        "CONTAINS",
        "ADAPTATION",
        "SOURCE",
        "CHARACTER",
        "OTHER"
    )

    /**
     * Core relation types that justify expanding further hops in BFS so all seasons/sequels/movies
     * of a franchise are discovered without pulling in unrelated crossover universes.
     */
    val CORE_EXPANSION_RELATIONS = setOf(
        "PREQUEL",
        "SEQUEL",
        "PARENT",
        "SIDE_STORY",
        "SPIN_OFF",
        "SUMMARY",
        "ALTERNATIVE"
    )

    fun shouldIncludeAnimeNode(
        node: RawFranchiseMediaNode,
        relationFromParent: String
    ): Boolean {
        if (!node.mediaType.equals("ANIME", ignoreCase = true)) return false
        val fmt = node.format.uppercase(Locale.ROOT)
        // Exclude music videos
        if (fmt == "MUSIC") return false
        val rel = relationFromParent.uppercase(Locale.ROOT)
        if (rel !in TRAVERSABLE_ANIME_RELATIONS) return false
        // Exclude pure CHARACTER cameos if they are short commercials/specials with unrelated titles,
        // unless they are genuine TV/MOVIE/OVA/ONA spin-offs
        if (rel == "CHARACTER" && fmt !in setOf("TV", "MOVIE", "OVA", "ONA")) {
            return false
        }
        return true
    }

    /**
     * Generates the complete [FranchiseWatchOrder] (Recommended, Release, and Chronological orders)
     * from the collected franchise nodes and directed edges.
     */
    fun generateWatchOrder(
        rootAnime: Anime,
        rootAnilistId: Long,
        nodesById: Map<Long, RawFranchiseMediaNode>,
        edges: List<RawRelationEdge>,
        resolvedHoshikaIdsByAnilistId: Map<Long, Long>,
        watchStatusByHoshikaId: Map<Long, String>
    ): FranchiseWatchOrder {
        // Extract source material note if ADAPTATION or SOURCE points to MANGA/NOVEL
        val sourceNode = nodesById[rootAnilistId]?.relations
            ?.firstOrNull { (rel, target) ->
                (rel == "SOURCE" || rel == "ADAPTATION") &&
                    !target.mediaType.equals("ANIME", ignoreCase = true)
            }?.second

        val sourceMaterialNote = sourceNode?.let { src ->
            val srcFormat = when (src.format.uppercase(Locale.ROOT)) {
                "NOVEL", "LIGHT_NOVEL" -> "Light Novel"
                "MANGA" -> "Manga"
                "ONE_SHOT" -> "One-Shot Manga"
                else -> "Source Material"
            }
            "Adapted from $srcFormat: ${src.preferredTitle}"
        }

        // Filter to valid watchable ANIME nodes, deduplicating by anilistId
        val animeNodes = nodesById.values
            .filter { it.mediaType.equals("ANIME", ignoreCase = true) && it.format.uppercase(Locale.ROOT) != "MUSIC" }
            .distinctBy { it.anilistId }
            .associateBy { it.anilistId }
            .toMutableMap()

        // Ensure the root anime is always present in animeNodes
        if (!animeNodes.containsKey(rootAnilistId)) {
            animeNodes[rootAnilistId] = RawFranchiseMediaNode(
                anilistId = rootAnilistId,
                hoshikaId = rootAnime.hoshikaId,
                malId = rootAnime.malId,
                mediaType = "ANIME",
                format = rootAnime.format ?: "TV",
                status = rootAnime.status,
                episodes = rootAnime.totalEpisodes,
                releaseYear = rootAnime.releaseYear,
                startDateNumeric = (rootAnime.releaseYear ?: 9999) * 10000 + 1332,
                titleRomaji = rootAnime.title,
                titleEnglish = rootAnime.englishTitle,
                titleNative = rootAnime.japaneseTitle,
                description = rootAnime.description,
                posterUrl = rootAnime.posterUrl,
                bannerUrl = rootAnime.bannerUrl
            )
        }

        // Build adjacency maps restricted to watchable animeNodes
        val outgoingEdges = mutableMapOf<Long, MutableList<RawRelationEdge>>()
        val incomingEdges = mutableMapOf<Long, MutableList<RawRelationEdge>>()
        for (edge in edges) {
            if (edge.fromAnilistId in animeNodes && edge.toAnilistId in animeNodes) {
                outgoingEdges.getOrPut(edge.fromAnilistId) { mutableListOf() }.add(edge)
                incomingEdges.getOrPut(edge.toAnilistId) { mutableListOf() }.add(edge)
            }
        }

        // Determine the Main Storyline Spine
        val mainSpineIds = buildMainStorylineSpine(
            rootAnilistId = rootAnilistId,
            animeNodes = animeNodes,
            outgoingEdges = outgoingEdges,
            incomingEdges = incomingEdges
        )

        val mainRootNode = mainSpineIds.firstOrNull()?.let { animeNodes[it] }
            ?: animeNodes[rootAnilistId]!!

        // Classify every node in the franchise relative to the main storyline spine
        val classifiedNodes = classifyFranchiseNodes(
            rootAnilistId = rootAnilistId,
            mainSpineIds = mainSpineIds,
            animeNodes = animeNodes,
            outgoingEdges = outgoingEdges,
            incomingEdges = incomingEdges
        )

        // 1. Build Recommended Order
        val recommendedSequence = buildRecommendedSequence(
            mainSpineIds = mainSpineIds,
            animeNodes = animeNodes,
            classifiedNodes = classifiedNodes
        )

        // 2. Build Release Order (strictly by original release date)
        val releaseSequence = animeNodes.values
            .sortedWith(
                compareBy<RawFranchiseMediaNode> { it.startDateNumeric }
                    .thenBy { it.releaseYear ?: 9999 }
                    .thenBy { formatPriority(it.format) }
                    .thenBy { it.anilistId }
            )
            .map { it.anilistId }

        // 3. Build Chronological Order (in-universe timeline: prequels before the entries they precede)
        val chronologicalSequence = buildChronologicalSequence(
            mainSpineIds = mainSpineIds,
            animeNodes = animeNodes,
            classifiedNodes = classifiedNodes
        )

        fun buildEntriesForSequence(
            sequence: List<Long>,
            mode: `in`.aniscroll.tv.domain.model.WatchOrderMode
        ): List<WatchOrderEntry> {
            return sequence.mapIndexedNotNull { idx, id ->
                val node = animeNodes[id] ?: return@mapIndexedNotNull null
                val classification = classifiedNodes[id] ?: NodeClassification(
                    category = FranchiseRelationCategory.MAIN_SERIES,
                    rawRelationType = "MAIN",
                    anchorAnilistId = null,
                    isLaterReleasedPrequel = false
                )
                val anchorTitle = classification.anchorAnilistId?.let { animeNodes[it]?.preferredTitle }
                val explanation = buildRelationshipExplanation(
                    node = node,
                    classification = classification,
                    anchorTitle = anchorTitle,
                    position = idx + 1,
                    mode = mode
                )
                val resolvedHoshikaId = when {
                    id == rootAnilistId -> rootAnime.hoshikaId
                    node.hoshikaId != null && node.hoshikaId > 0L -> node.hoshikaId
                    else -> resolvedHoshikaIdsByAnilistId[id] ?: syntheticHoshikaIdForAnilist(id)
                }

                WatchOrderEntry(
                    position = idx + 1,
                    anilistId = id,
                    hoshikaId = resolvedHoshikaId,
                    malId = node.malId,
                    title = node.titleRomaji,
                    englishTitle = node.titleEnglish,
                    japaneseTitle = node.titleNative,
                    posterUrl = node.posterUrl ?: if (id == rootAnilistId) rootAnime.posterUrl else null,
                    bannerUrl = node.bannerUrl ?: if (id == rootAnilistId) rootAnime.bannerUrl else null,
                    description = node.description ?: if (id == rootAnilistId) rootAnime.description else null,
                    format = formatDisplayLabel(node.format),
                    releaseYear = node.releaseYear,
                    startDateNumeric = node.startDateNumeric,
                    episodes = node.episodes,
                    status = node.status,
                    rawRelationType = classification.rawRelationType,
                    category = classification.category,
                    relationshipBadge = badgeForCategory(classification.category, node.format),
                    relationshipExplanation = explanation,
                    isOptional = isOptionalCategory(classification.category),
                    viewingTierBadge = viewingTierBadgeFor(classification.category),
                    isCurrentAnime = (id == rootAnilistId || resolvedHoshikaId == rootAnime.hoshikaId),
                    watchStatusLabel = watchStatusByHoshikaId[resolvedHoshikaId]
                )
            }
        }

        val recommendedEntries = buildEntriesForSequence(
            recommendedSequence,
            `in`.aniscroll.tv.domain.model.WatchOrderMode.RECOMMENDED
        )
        val releaseEntries = buildEntriesForSequence(
            releaseSequence,
            `in`.aniscroll.tv.domain.model.WatchOrderMode.RELEASE
        )
        val chronologicalEntries = buildEntriesForSequence(
            chronologicalSequence,
            `in`.aniscroll.tv.domain.model.WatchOrderMode.CHRONOLOGICAL
        )

        val franchiseTitle = deriveFranchiseTitle(mainRootNode.preferredTitle, rootAnime.preferredTitle)
        val franchiseBanner = mainRootNode.bannerUrl
            ?: rootAnime.bannerUrl
            ?: recommendedEntries.firstOrNull { !it.bannerUrl.isNullOrBlank() }?.bannerUrl
            ?: mainRootNode.posterUrl
            ?: rootAnime.posterUrl
        val franchiseCover = mainRootNode.posterUrl
            ?: rootAnime.posterUrl
            ?: recommendedEntries.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl

        return FranchiseWatchOrder(
            rootHoshikaId = rootAnime.hoshikaId,
            rootAnilistId = rootAnilistId,
            franchiseTitle = franchiseTitle,
            franchiseBannerUrl = franchiseBanner,
            franchiseCoverUrl = franchiseCover,
            sourceMaterialNote = sourceMaterialNote,
            totalEntries = recommendedEntries.size,
            recommendedOrder = recommendedEntries,
            releaseOrder = releaseEntries,
            chronologicalOrder = chronologicalEntries
        )
    }

    private data class NodeClassification(
        val category: FranchiseRelationCategory,
        val rawRelationType: String,
        val anchorAnilistId: Long?,
        val isLaterReleasedPrequel: Boolean
    )

    private fun isCoreSpineFormat(format: String): Boolean {
        return when (format.uppercase(Locale.ROOT)) {
            "TV", "TV_SHORT", "ONA", "MOVIE" -> true
            else -> false
        }
    }

    private fun buildMainStorylineSpine(
        rootAnilistId: Long,
        animeNodes: Map<Long, RawFranchiseMediaNode>,
        outgoingEdges: Map<Long, List<RawRelationEdge>>,
        incomingEdges: Map<Long, List<RawRelationEdge>>
    ): List<Long> {
        // If the starting anime is an OVA/Side Story/Summary/Spin-Off with a PARENT edge,
        // anchor the spine from its PARENT main series first
        var startId = rootAnilistId
        val parentTarget = outgoingEdges[startId]
            ?.firstOrNull { it.relationType == "PARENT" && isCoreSpineFormat(animeNodes[it.toAnilistId]?.format ?: "") }
            ?.toAnilistId
            ?: incomingEdges[startId]
                ?.firstOrNull { it.relationType == "SIDE_STORY" || it.relationType == "SUMMARY" }
                ?.fromAnilistId
        if (parentTarget != null && parentTarget in animeNodes) {
            startId = parentTarget
        }

        // Collect all nodes reachable strictly via PREQUEL / SEQUEL edges among core formats
        val spineCluster = linkedSetOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(startId)
        spineCluster.add(startId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val out = outgoingEdges[current].orEmpty()
            for (edge in out) {
                if (edge.relationType == "PREQUEL" || edge.relationType == "SEQUEL") {
                    val target = animeNodes[edge.toAnilistId] ?: continue
                    if (isCoreSpineFormat(target.format) && spineCluster.add(target.anilistId)) {
                        queue.add(target.anilistId)
                    }
                }
            }
            val inc = incomingEdges[current].orEmpty()
            for (edge in inc) {
                if (edge.relationType == "PREQUEL" || edge.relationType == "SEQUEL") {
                    val source = animeNodes[edge.fromAnilistId] ?: continue
                    if (isCoreSpineFormat(source.format) && spineCluster.add(source.anilistId)) {
                        queue.add(source.anilistId)
                    }
                }
            }
        }

        if (spineCluster.isEmpty()) {
            return listOf(rootAnilistId)
        }

        // Find the original earliest-released TV/ONA/MOVIE entry in the spineCluster as the Main Series root
        val originalMainNode = spineCluster
            .mapNotNull { animeNodes[it] }
            .sortedWith(
                compareBy<RawFranchiseMediaNode> {
                    // Prefer full TV/ONA series over standalone prequel movies when finding the original release root
                    if (it.format.uppercase(Locale.ROOT) in setOf("TV", "ONA")) 0 else 1
                }
                    .thenBy { it.startDateNumeric }
                    .thenBy { it.releaseYear ?: 9999 }
                    .thenBy { it.anilistId }
            )
            .first()

        // Walk forward from originalMainNode along SEQUEL edges (or reverse PREQUEL edges) to build the forward spine
        val orderedSpine = mutableListOf<Long>()
        val visited = mutableSetOf<Long>()

        fun walkSequels(fromId: Long) {
            if (!visited.add(fromId)) return
            orderedSpine.add(fromId)

            val directSequels = buildList {
                outgoingEdges[fromId].orEmpty()
                    .filter { it.relationType == "SEQUEL" && it.toAnilistId in spineCluster }
                    .forEach { add(it.toAnilistId) }
                incomingEdges[fromId].orEmpty()
                    .filter { it.relationType == "PREQUEL" && it.fromAnilistId in spineCluster }
                    .forEach { add(it.fromAnilistId) }
            }
                .distinct()
                .mapNotNull { animeNodes[it] }
                .sortedWith(
                    compareBy<RawFranchiseMediaNode> { it.startDateNumeric }
                        .thenBy { formatPriority(it.format) }
                        .thenBy { it.anilistId }
                )

            for (seq in directSequels) {
                walkSequels(seq.anilistId)
            }
        }

        walkSequels(originalMainNode.anilistId)

        // Any remaining spineCluster nodes not yet visited (e.g. prequels to originalMainNode)
        // that were released BEFORE originalMainNode belong at the front;
        // those released AFTER originalMainNode are later-released prequels handled by classification.
        val unvisitedSpineNodes = spineCluster
            .filter { it !in visited }
            .mapNotNull { animeNodes[it] }
            .sortedBy { it.startDateNumeric }

        val earlierPrequels = unvisitedSpineNodes.filter {
            it.startDateNumeric < originalMainNode.startDateNumeric
        }.map { it.anilistId }

        return earlierPrequels + orderedSpine
    }

    private fun classifyFranchiseNodes(
        rootAnilistId: Long,
        mainSpineIds: List<Long>,
        animeNodes: Map<Long, RawFranchiseMediaNode>,
        outgoingEdges: Map<Long, List<RawRelationEdge>>,
        incomingEdges: Map<Long, List<RawRelationEdge>>
    ): Map<Long, NodeClassification> {
        val result = mutableMapOf<Long, NodeClassification>()
        val mainSpineSet = mainSpineIds.toSet()
        val firstMainId = mainSpineIds.firstOrNull() ?: rootAnilistId
        val firstMainNode = animeNodes[firstMainId]

        // 1. Classify main spine nodes
        mainSpineIds.forEachIndexed { index, id ->
            val node = animeNodes[id] ?: return@forEachIndexed
            val prevSpineId = if (index > 0) mainSpineIds[index - 1] else null
            val fmt = node.format.uppercase(Locale.ROOT)
            val category = when {
                index == 0 -> FranchiseRelationCategory.MAIN_SERIES
                fmt == "MOVIE" -> FranchiseRelationCategory.CANONICAL_MOVIE
                else -> FranchiseRelationCategory.DIRECT_SEQUEL
            }
            result[id] = NodeClassification(
                category = category,
                rawRelationType = if (index == 0) "MAIN" else "SEQUEL",
                anchorAnilistId = prevSpineId,
                isLaterReleasedPrequel = false
            )
        }

        // 2. Classify non-spine nodes based on their relation to the spine (or closest classified node)
        val remainingIds = animeNodes.keys.filter { it !in mainSpineSet }
            .sortedWith(
                compareBy<Long> { animeNodes[it]?.startDateNumeric ?: 99999999 }
                    .thenBy { it }
            )

        for (id in remainingIds) {
            val node = animeNodes[id] ?: continue
            val fmt = node.format.uppercase(Locale.ROOT)
            val out = outgoingEdges[id].orEmpty()
            val inc = incomingEdges[id].orEmpty()

            // Find best relation connecting this node to a spine node (or any already-classified node)
            var matchedAnchorId: Long? = null
            var matchedRelationType: String? = null

            // Check edges between `id` and `mainSpineSet` first
            for (edge in inc) {
                if (edge.fromAnilistId in mainSpineSet) {
                    matchedAnchorId = edge.fromAnilistId
                    matchedRelationType = edge.relationType // e.g., main -> PREQUEL -> id means id is a PREQUEL to main
                    break
                }
            }
            if (matchedAnchorId == null) {
                for (edge in out) {
                    if (edge.toAnilistId in mainSpineSet) {
                        matchedAnchorId = edge.toAnilistId
                        matchedRelationType = invertRelationType(edge.relationType)
                        break
                    }
                }
            }
            // Fallback to any edge
            if (matchedAnchorId == null) {
                val anyInc = inc.firstOrNull()
                val anyOut = out.firstOrNull()
                if (anyInc != null) {
                    matchedAnchorId = anyInc.fromAnilistId
                    matchedRelationType = anyInc.relationType
                } else if (anyOut != null) {
                    matchedAnchorId = anyOut.toAnilistId
                    matchedRelationType = invertRelationType(anyOut.relationType)
                }
            }

            val anchorId = matchedAnchorId ?: firstMainId
            val relType = (matchedRelationType ?: "SIDE_STORY").uppercase(Locale.ROOT)
            val anchorNode = animeNodes[anchorId] ?: firstMainNode

            val isLaterPrequel = relType == "PREQUEL" &&
                anchorNode != null &&
                node.startDateNumeric > anchorNode.startDateNumeric

            // If it's a later-released prequel, find which main spine season it was released right after
            val effectiveAnchorForRecommended = if (isLaterPrequel) {
                mainSpineIds
                    .mapNotNull { animeNodes[it] }
                    .lastOrNull { it.startDateNumeric <= node.startDateNumeric }
                    ?.anilistId
                    ?: anchorId
            } else {
                anchorId
            }

            val category = when {
                relType == "SUMMARY" || relType == "COMPILATION" -> FranchiseRelationCategory.SUMMARY_RECAP
                relType == "ALTERNATIVE" -> FranchiseRelationCategory.ALTERNATIVE
                relType == "PREQUEL" -> FranchiseRelationCategory.DIRECT_PREQUEL
                relType == "SEQUEL" && fmt == "MOVIE" -> FranchiseRelationCategory.CANONICAL_MOVIE
                relType == "SEQUEL" -> FranchiseRelationCategory.DIRECT_SEQUEL
                relType == "SPIN_OFF" || relType == "CHARACTER" -> FranchiseRelationCategory.SPIN_OFF
                fmt == "MOVIE" && (relType == "SIDE_STORY" || relType == "PARENT" || relType == "OTHER") ->
                    FranchiseRelationCategory.CANONICAL_MOVIE
                fmt == "OVA" || fmt == "SPECIAL" -> FranchiseRelationCategory.OVA_SPECIAL
                else -> FranchiseRelationCategory.SIDE_STORY
            }

            result[id] = NodeClassification(
                category = category,
                rawRelationType = relType,
                anchorAnilistId = effectiveAnchorForRecommended,
                isLaterReleasedPrequel = isLaterPrequel
            )
        }

        return result
    }

    /**
     * Builds the Recommended viewing sequence:
     * 1. Main series & direct sequels along the spine, with canonical movies, later-released prequels
     *    (spoiler-safe), relevant OVAs/specials, and side stories grouped right after their parent season.
     * 2. Franchise spin-offs.
     * 3. Alternate versions / settings.
     * 4. Summary / recap compilations.
     */
    private fun buildRecommendedSequence(
        mainSpineIds: List<Long>,
        animeNodes: Map<Long, RawFranchiseMediaNode>,
        classifiedNodes: Map<Long, NodeClassification>
    ): List<Long> {
        val ordered = LinkedHashSet<Long>()
        val allIds = animeNodes.keys

        val attachedByAnchor = allIds
            .filter { it !in mainSpineIds }
            .groupBy { id -> classifiedNodes[id]?.anchorAnilistId ?: mainSpineIds.firstOrNull() }

        for (spineId in mainSpineIds) {
            ordered.add(spineId)
            val attached = attachedByAnchor[spineId].orEmpty()
            // Attach Direct Prequels (released after this season), Canonical Movies, OVAs/Specials, and Side Stories
            val inlineAttachments = attached
                .filter { id ->
                    val cat = classifiedNodes[id]?.category
                    cat == FranchiseRelationCategory.DIRECT_PREQUEL ||
                        cat == FranchiseRelationCategory.CANONICAL_MOVIE ||
                        cat == FranchiseRelationCategory.DIRECT_SEQUEL ||
                        cat == FranchiseRelationCategory.OVA_SPECIAL ||
                        cat == FranchiseRelationCategory.SIDE_STORY
                }
                .sortedWith(
                    compareBy<Long> { categoryPriorityInRecommended(classifiedNodes[it]?.category) }
                        .thenBy { animeNodes[it]?.startDateNumeric ?: 99999999 }
                        .thenBy { it }
                )
            ordered.addAll(inlineAttachments)
        }

        // Remaining categories in priority order: Prequels -> Movies -> OVAs -> Side Stories -> Spin-offs -> Alternatives -> Recaps
        val remaining = allIds
            .filter { it !in ordered }
            .sortedWith(
                compareBy<Long> { categoryPriorityInRecommended(classifiedNodes[it]?.category) }
                    .thenBy { animeNodes[it]?.startDateNumeric ?: 99999999 }
                    .thenBy { it }
            )
        ordered.addAll(remaining)

        return ordered.toList()
    }

    /**
     * Builds the Chronological viewing sequence:
     * 1. Direct Prequels placed BEFORE the main series they chronologically precede.
     * 2. Main series spine with in-universe OVAs, side stories, and canonical movies.
     * 3. Spin-offs, Alternate settings, and Recaps.
     */
    private fun buildChronologicalSequence(
        mainSpineIds: List<Long>,
        animeNodes: Map<Long, RawFranchiseMediaNode>,
        classifiedNodes: Map<Long, NodeClassification>
    ): List<Long> {
        val ordered = LinkedHashSet<Long>()
        val allIds = animeNodes.keys

        // Place all DIRECT_PREQUEL entries first in chronological order
        val prequels = allIds
            .filter { classifiedNodes[it]?.category == FranchiseRelationCategory.DIRECT_PREQUEL }
            .sortedWith(
                compareBy<Long> { animeNodes[it]?.startDateNumeric ?: 99999999 }
                    .thenBy { it }
            )
        ordered.addAll(prequels)

        val attachedByAnchor = allIds
            .filter { it !in mainSpineIds && it !in ordered }
            .groupBy { id -> classifiedNodes[id]?.anchorAnilistId ?: mainSpineIds.firstOrNull() }

        for (spineId in mainSpineIds) {
            ordered.add(spineId)
            val attached = attachedByAnchor[spineId].orEmpty()
                .filter { id ->
                    val cat = classifiedNodes[id]?.category
                    cat == FranchiseRelationCategory.CANONICAL_MOVIE ||
                        cat == FranchiseRelationCategory.DIRECT_SEQUEL ||
                        cat == FranchiseRelationCategory.OVA_SPECIAL ||
                        cat == FranchiseRelationCategory.SIDE_STORY
                }
                .sortedWith(
                    compareBy<Long> { animeNodes[it]?.startDateNumeric ?: 99999999 }
                        .thenBy { it }
                )
            ordered.addAll(attached)
        }

        val remaining = allIds
            .filter { it !in ordered }
            .sortedWith(
                compareBy<Long> { categoryPriorityInRecommended(classifiedNodes[it]?.category) }
                    .thenBy { animeNodes[it]?.startDateNumeric ?: 99999999 }
                    .thenBy { it }
            )
        ordered.addAll(remaining)

        return ordered.toList()
    }

    private fun categoryPriorityInRecommended(category: FranchiseRelationCategory?): Int = when (category) {
        FranchiseRelationCategory.MAIN_SERIES -> 1
        FranchiseRelationCategory.DIRECT_SEQUEL -> 2
        FranchiseRelationCategory.DIRECT_PREQUEL -> 3
        FranchiseRelationCategory.CANONICAL_MOVIE -> 4
        FranchiseRelationCategory.OVA_SPECIAL -> 5
        FranchiseRelationCategory.SIDE_STORY -> 6
        FranchiseRelationCategory.SPIN_OFF -> 7
        FranchiseRelationCategory.ALTERNATIVE -> 8
        FranchiseRelationCategory.SUMMARY_RECAP -> 9
        null -> 10
    }

    private fun invertRelationType(relationType: String): String = when (relationType.uppercase(Locale.ROOT)) {
        "PREQUEL" -> "SEQUEL"
        "SEQUEL" -> "PREQUEL"
        "PARENT" -> "SIDE_STORY"
        "CONTAINS" -> "SIDE_STORY"
        else -> relationType.uppercase(Locale.ROOT)
    }

    private fun formatPriority(format: String): Int = when (format.uppercase(Locale.ROOT)) {
        "TV" -> 1
        "ONA" -> 2
        "MOVIE" -> 3
        "TV_SHORT" -> 4
        "OVA" -> 5
        "SPECIAL" -> 6
        else -> 7
    }

    fun formatDisplayLabel(format: String?): String = when (format?.uppercase(Locale.ROOT)?.trim()) {
        "TV" -> "TV"
        "TV_SHORT" -> "TV Short"
        "MOVIE" -> "Movie"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "SPECIAL" -> "Special"
        null, "" -> "TV"
        else -> format.uppercase(Locale.ROOT)
    }

    private fun badgeForCategory(category: FranchiseRelationCategory, format: String): String = when (category) {
        FranchiseRelationCategory.MAIN_SERIES -> "Main Series"
        FranchiseRelationCategory.DIRECT_SEQUEL -> "Sequel"
        FranchiseRelationCategory.DIRECT_PREQUEL -> "Prequel"
        FranchiseRelationCategory.CANONICAL_MOVIE -> "Movie"
        FranchiseRelationCategory.OVA_SPECIAL -> if (format.equals("SPECIAL", ignoreCase = true)) "Special" else "OVA"
        FranchiseRelationCategory.SIDE_STORY -> "Side Story"
        FranchiseRelationCategory.SPIN_OFF -> "Spin-Off"
        FranchiseRelationCategory.SUMMARY_RECAP -> "Recap / Summary"
        FranchiseRelationCategory.ALTERNATIVE -> "Alternate Version"
    }

    private fun isOptionalCategory(category: FranchiseRelationCategory): Boolean = when (category) {
        FranchiseRelationCategory.MAIN_SERIES,
        FranchiseRelationCategory.DIRECT_SEQUEL -> false
        FranchiseRelationCategory.DIRECT_PREQUEL,
        FranchiseRelationCategory.CANONICAL_MOVIE -> false
        FranchiseRelationCategory.OVA_SPECIAL,
        FranchiseRelationCategory.SIDE_STORY,
        FranchiseRelationCategory.SPIN_OFF,
        FranchiseRelationCategory.SUMMARY_RECAP,
        FranchiseRelationCategory.ALTERNATIVE -> true
    }

    private fun viewingTierBadgeFor(category: FranchiseRelationCategory): String = when (category) {
        FranchiseRelationCategory.MAIN_SERIES,
        FranchiseRelationCategory.DIRECT_SEQUEL -> "Essential"
        FranchiseRelationCategory.DIRECT_PREQUEL,
        FranchiseRelationCategory.CANONICAL_MOVIE -> "Recommended"
        FranchiseRelationCategory.OVA_SPECIAL,
        FranchiseRelationCategory.SIDE_STORY,
        FranchiseRelationCategory.SPIN_OFF -> "Optional"
        FranchiseRelationCategory.SUMMARY_RECAP -> "Recap • Optional"
        FranchiseRelationCategory.ALTERNATIVE -> "Alternate • Optional"
    }

    private fun buildRelationshipExplanation(
        node: RawFranchiseMediaNode,
        classification: NodeClassification,
        anchorTitle: String?,
        position: Int,
        mode: `in`.aniscroll.tv.domain.model.WatchOrderMode
    ): String {
        val ref = anchorTitle ?: "the main series"
        val yearSuffix = node.releaseYear?.let { " ($it)" } ?: ""
        return when (classification.category) {
            FranchiseRelationCategory.MAIN_SERIES -> {
                if (position == 1) {
                    "Main Series: Starting point of the core franchise storyline$yearSuffix."
                } else {
                    "Main Series: Core foundational installment of the main story$yearSuffix."
                }
            }
            FranchiseRelationCategory.DIRECT_SEQUEL -> {
                "Sequel: Directly continues the main storyline after $ref."
            }
            FranchiseRelationCategory.DIRECT_PREQUEL -> {
                when {
                    mode == `in`.aniscroll.tv.domain.model.WatchOrderMode.CHRONOLOGICAL ->
                        "Prequel: Takes place chronologically before the main series events."
                    classification.isLaterReleasedPrequel ->
                        "Prequel: Set before the main story, placed after $ref to preserve key story reveals."
                    else ->
                        "Prequel: Takes place before $ref and sets up the main story."
                }
            }
            FranchiseRelationCategory.CANONICAL_MOVIE -> {
                if (classification.rawRelationType == "SEQUEL") {
                    "Movie: Theatrical continuation directly following $ref."
                } else {
                    "Movie: Related theatrical installment best watched after $ref."
                }
            }
            FranchiseRelationCategory.OVA_SPECIAL -> {
                val label = if (node.format.equals("SPECIAL", ignoreCase = true)) "Special" else "OVA"
                "$label: Additional bonus episode expanding on $ref (Optional)."
            }
            FranchiseRelationCategory.SIDE_STORY -> {
                "Side Story: Explores another part of the franchise alongside $ref (Optional)."
            }
            FranchiseRelationCategory.SPIN_OFF -> {
                "Spin-off: Focuses on another character or storyline in the franchise universe (Optional)."
            }
            FranchiseRelationCategory.SUMMARY_RECAP -> {
                "Summary: Condensed recap of earlier episodes; optional if you watched the main series."
            }
            FranchiseRelationCategory.ALTERNATIVE -> {
                "Alternate Version: Retelling or alternate setting separate from the main continuity (Optional)."
            }
        }
    }

    private fun deriveFranchiseTitle(mainRootTitle: String, fallbackTitle: String): String {
        val base = mainRootTitle.ifBlank { fallbackTitle }
            .replace(Regex("\\s*(Season\\s*\\d+|Part\\s*\\d+|Cour\\s*\\d+|The\\s+Final\\s+Season).*$", RegexOption.IGNORE_CASE), "")
            .trim()
        return if (base.isNotEmpty()) base else mainRootTitle
    }

    private fun String?.takeNonNull(): String? {
        if (this == null) return null
        val trimmed = this.trim()
        return if (trimmed.isNotEmpty() && !trimmed.equals("null", ignoreCase = true)) trimmed else null
    }
}
