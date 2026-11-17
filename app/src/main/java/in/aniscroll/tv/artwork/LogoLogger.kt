package `in`.aniscroll.tv.artwork

import android.util.Log
import coil.decode.DataSource
import coil.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogoFailureCategory {
    MISSING_CREDENTIALS,
    AUTH_FAILURE,
    RATE_LIMITED,
    NETWORK_ERROR,
    INVALID_RESPONSE,
    MISSING_ARTWORK,
    IMAGE_RENDER_FAILURE
}

enum class ArtworkType(val key: String) {
    POSTER("poster"),
    BANNER("banner"),
    EPISODE_THUMBNAIL("episode_thumbnail"),
    LOGO("logo")
}

enum class ArtworkErrorCategory {
    MISSING_URL,
    INVALID_URL,
    HTTP_FAILURE,
    UNSUPPORTED_FORMAT,
    DECODE_FAILURE,
    NETWORK_FAILURE,
    UNKNOWN
}

data class ArtworkDiagnosticEvent(
    val timestampMs: Long,
    val screenName: String,
    val artworkType: ArtworkType,
    val hoshikaId: Long,
    val episodeNumber: Int?,
    val host: String,
    val sanitizedPath: String,
    val httpStatus: Int? = null,
    val errorCategory: ArtworkErrorCategory? = null,
    val detail: String,
    val cacheSource: String? = null,
    val isSuccess: Boolean
)

/**
 * Structured logger and diagnostic tracker for anime posters, banners, episode thumbnails,
 * and title logos. Uses tag `AniScrollLogo` and strictly redacts query tokens and credentials.
 */
object LogoLogger {
    const val TAG = "AniScrollLogo"

    private const val MAX_DIAGNOSTIC_EVENTS = 150
    private val recentArtworkEvents = CopyOnWriteArrayList<ArtworkDiagnosticEvent>()

    private val HTTPS_UPGRADE_HOSTS = setOf(
        "assets.fanart.tv",
        "fanart.tv",
        "webservice.fanart.tv",
        "image.tmdb.org",
        "api.themoviedb.org",
        "themoviedb.org",
        "s4.anilist.co",
        "media.kitsu.io",
        "kitsu.app",
        "cdn.myanimelist.net",
        "myanimelist.net",
        "hoshika.app",
        "cdn.hoshika.app",
        "anivault-scraper-five.vercel.app",
        "img.youtube.com",
        "i.ytimg.com"
    )

    fun d(message: String) {
        runCatching { Log.d(TAG, sanitize(message)) }
    }

    fun i(message: String) {
        runCatching { Log.i(TAG, sanitize(message)) }
    }

    fun w(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) {
                Log.w(TAG, sanitize(message), throwable)
            } else {
                Log.w(TAG, sanitize(message))
            }
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) {
                Log.e(TAG, sanitize(message), throwable)
            } else {
                Log.e(TAG, sanitize(message))
            }
        }
    }

    fun classifyHttpFailure(statusCode: Int): LogoFailureCategory = when (statusCode) {
        401, 403 -> LogoFailureCategory.AUTH_FAILURE
        429 -> LogoFailureCategory.RATE_LIMITED
        404 -> LogoFailureCategory.MISSING_ARTWORK
        in 500..599 -> LogoFailureCategory.NETWORK_ERROR
        else -> LogoFailureCategory.INVALID_RESPONSE
    }

    fun logDiagnostic(
        category: LogoFailureCategory,
        provider: String,
        hoshikaId: Long,
        anilistId: Long? = null,
        detail: String,
        throwable: Throwable? = null
    ) {
        val msg = "HoshikaID=$hoshikaId AniListID=$anilistId provider=$provider category=${category.name} -> $detail"
        if (category == LogoFailureCategory.MISSING_ARTWORK) {
            i(msg)
        } else {
            w(msg, throwable)
        }
    }

    /**
     * Validates and normalizes artwork URLs from Hoshika, AniList, TMDB, and Fanart.tv.
     * - Rejects empty/blank strings, "null", "undefined", "none", "n/a", JSON strings, and non-HTTP schemes.
     * - Upgrades `http://` to `https://` when appropriate for known HTTPS-capable CDN hosts.
     * - Preserves query parameters and signed URL signatures while encoding raw spaces.
     */
    fun normalizeSecureArtworkUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { candidate ->
            candidate.isNotEmpty() &&
                !candidate.equals("null", ignoreCase = true) &&
                !candidate.equals("undefined", ignoreCase = true) &&
                !candidate.equals("none", ignoreCase = true) &&
                !candidate.equals("n/a", ignoreCase = true) &&
                !(candidate.startsWith("{") && candidate.endsWith("}")) &&
                !(candidate.startsWith("[") && candidate.endsWith("]"))
        } ?: return null

        val candidate = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) -> {
                val encodedForUri = trimmed.replace(" ", "%20")
                val host = extractHost(encodedForUri).lowercase(Locale.ROOT)
                if (host.isBlank() || host == "none" || host == "unknown" || host == "invalid-uri") {
                    return null
                }
                if (shouldUpgradeHttpToHttps(host)) {
                    "https://${trimmed.substring(7)}"
                } else {
                    "http://${trimmed.substring(7)}"
                }
            }
            trimmed.startsWith("https://", ignoreCase = true) -> "https://${trimmed.substring(8)}"
            else -> return null
        }.replace(" ", "%20")

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty() || host == "null" || host == "undefined") return null

        return candidate
    }

    private fun shouldUpgradeHttpToHttps(lowercaseHost: String): Boolean {
        if (lowercaseHost == "localhost" || lowercaseHost == "127.0.0.1" || lowercaseHost == "10.0.2.2") {
            return false
        }
        return HTTPS_UPGRADE_HOSTS.any { known ->
            lowercaseHost == known || lowercaseHost.endsWith(".$known")
        }
    }

    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        return runCatching { URI(url.replace(" ", "%20")).host ?: "unknown" }.getOrDefault("invalid-uri")
    }

    /**
     * Returns `(host, sanitizedPath)` with query parameters / signed URL tokens stripped so
     * diagnostic logs never expose credentials or temporary tokens.
     */
    fun sanitizeUrlForDiagnostics(url: String?): Pair<String, String> {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return "none" to "none"
        val uri = runCatching { URI(raw.replace(" ", "%20")) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotBlank() } ?: extractHost(raw)
        val path = uri?.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val queryPresent = !uri?.rawQuery.isNullOrBlank()
        val sanitizedPath = if (queryPresent) "$path?<redacted>" else path
        return host to sanitizedPath
    }

    fun classifyThrowable(throwable: Throwable?): Pair<ArtworkErrorCategory, Int?> {
        if (throwable == null) return ArtworkErrorCategory.UNKNOWN to null
        val chain = generateSequence(throwable) { it.cause }.take(6).toList()
        for (ex in chain) {
            if (ex is HttpException) {
                return ArtworkErrorCategory.HTTP_FAILURE to ex.response.code
            }
        }
        val combinedMsg = chain.joinToString(" | ") { "${it::class.java.simpleName}:${it.message.orEmpty()}" }
        val lower = combinedMsg.lowercase(Locale.ROOT)

        val httpMatch = Regex("""(?:http|status)[=:\s]+(\d{3})""", RegexOption.IGNORE_CASE).find(combinedMsg)
        val parsedCode = httpMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (parsedCode != null || lower.contains("non-image response")) {
            return ArtworkErrorCategory.HTTP_FAILURE to parsedCode
        }
        if (lower.contains("unsupported") || lower.contains("avif") || lower.contains("svg") || lower.contains("mime")) {
            return ArtworkErrorCategory.UNSUPPORTED_FORMAT to null
        }
        if (lower.contains("decode") || lower.contains("skimagecodec") || lower.contains("bitmapfactory") || lower.contains("image")) {
            return ArtworkErrorCategory.DECODE_FAILURE to null
        }
        if (chain.any { it is UnknownHostException || it is SocketTimeoutException || it is IOException }) {
            return ArtworkErrorCategory.NETWORK_FAILURE to null
        }
        return ArtworkErrorCategory.UNKNOWN to null
    }

    fun recordArtworkSuccess(
        screenName: String,
        artworkType: ArtworkType,
        hoshikaId: Long,
        episodeNumber: Int? = null,
        url: String,
        dataSource: DataSource
    ) {
        val (host, sanitizedPath) = sanitizeUrlForDiagnostics(url)
        val cacheLabel = when (dataSource) {
            DataSource.MEMORY_CACHE -> "HIT_MEMORY_CACHE"
            DataSource.MEMORY -> "HIT_MEMORY"
            DataSource.DISK -> "HIT_DISK"
            DataSource.NETWORK -> "MISS_NETWORK"
        }
        val detail = "screen=$screenName type=${artworkType.key} hoshikaId=$hoshikaId ep=${episodeNumber ?: "-"} " +
            "host=$host path=$sanitizedPath cache=$cacheLabel status=SUCCESS"
        d(detail)
        appendEvent(
            ArtworkDiagnosticEvent(
                timestampMs = System.currentTimeMillis(),
                screenName = screenName,
                artworkType = artworkType,
                hoshikaId = hoshikaId,
                episodeNumber = episodeNumber,
                host = host,
                sanitizedPath = sanitizedPath,
                errorCategory = null,
                detail = detail,
                cacheSource = cacheLabel,
                isSuccess = true
            )
        )
    }

    fun recordArtworkFailure(
        screenName: String,
        artworkType: ArtworkType,
        hoshikaId: Long,
        episodeNumber: Int? = null,
        url: String?,
        category: ArtworkErrorCategory,
        httpStatus: Int? = null,
        throwable: Throwable? = null,
        customReason: String? = null
    ) {
        val (host, sanitizedPath) = sanitizeUrlForDiagnostics(url)
        val reason = sanitize(customReason ?: throwable?.message ?: category.name)
        val detail = "screen=$screenName type=${artworkType.key} hoshikaId=$hoshikaId ep=${episodeNumber ?: "-"} " +
            "host=$host path=$sanitizedPath http=${httpStatus ?: "-"} category=${category.name} reason=$reason"
        if (category == ArtworkErrorCategory.MISSING_URL) {
            i(detail)
        } else {
            w(detail, throwable)
        }
        appendEvent(
            ArtworkDiagnosticEvent(
                timestampMs = System.currentTimeMillis(),
                screenName = screenName,
                artworkType = artworkType,
                hoshikaId = hoshikaId,
                episodeNumber = episodeNumber,
                host = host,
                sanitizedPath = sanitizedPath,
                httpStatus = httpStatus,
                errorCategory = category,
                detail = detail,
                cacheSource = null,
                isSuccess = false
            )
        )
    }

    fun getRecentArtworkDiagnostics(): List<ArtworkDiagnosticEvent> = recentArtworkEvents.toList()

    fun clearArtworkDiagnostics() {
        recentArtworkEvents.clear()
    }

    private fun appendEvent(event: ArtworkDiagnosticEvent) {
        recentArtworkEvents.add(event)
        while (recentArtworkEvents.size > MAX_DIAGNOSTIC_EVENTS) {
            recentArtworkEvents.removeAt(0)
        }
    }

    internal fun sanitize(input: String): String {
        return input
            .replace(Regex("api_key=[^&\\s]+", RegexOption.IGNORE_CASE), "api_key=REDACTED")
            .replace(Regex("api-key\\s*[:=]\\s*[^\\s,;]+", RegexOption.IGNORE_CASE), "api-key=REDACTED")
            .replace(Regex("X-Client-Id\\s*[:=]\\s*[^\\s,;]+", RegexOption.IGNORE_CASE), "X-Client-Id=REDACTED")
            .replace(Regex("Authorization\\s*[:=]\\s*[^\\r\\n]+", RegexOption.IGNORE_CASE), "Authorization: REDACTED")
            .replace(Regex("Bearer\\s+[A-Za-z0-9._\\-]+", RegexOption.IGNORE_CASE), "Bearer REDACTED")
            .replace(Regex("(token|sig|signature|expires|exp)=[^&\\s]+", RegexOption.IGNORE_CASE), "$1=REDACTED")
    }
}
