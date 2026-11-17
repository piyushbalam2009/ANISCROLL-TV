package `in`.aniscroll.tv.artwork

import android.util.Log
import java.net.URI
import java.util.Locale

enum class LogoFailureCategory {
    MISSING_CREDENTIALS,
    AUTH_FAILURE,
    RATE_LIMITED,
    NETWORK_ERROR,
    INVALID_RESPONSE,
    MISSING_ARTWORK,
    IMAGE_RENDER_FAILURE
}

/**
 * Structured logger for anime title logo and artwork retrieval/rendering diagnostics.
 * Uses consistent tag `AniScrollLogo` and strictly redacts any API keys, secrets, or tokens.
 */
object LogoLogger {
    const val TAG = "AniScrollLogo"

    private val HTTPS_UPGRADE_HOSTS = setOf(
        "assets.fanart.tv",
        "fanart.tv",
        "webservice.fanart.tv",
        "image.tmdb.org",
        "api.themoviedb.org",
        "themoviedb.org",
        "s4.anilist.co",
        "media.kitsu.io",
        "cdn.myanimelist.net",
        "hoshika.app",
        "anivault-scraper-five.vercel.app"
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
     * Normalizes artwork URLs from TMDB, Fanart.tv, Hoshika, and AniList.
     * Upgrades `http://` to `https://` for supported CDN hosts so Android 9+ network security
     * policies in release APKs never block artwork loads due to cleartext HTTP.
     */
    fun normalizeSecureArtworkUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals("undefined", ignoreCase = true)
        } ?: return null

        val withScheme = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) -> "https://${trimmed.substring(7)}"
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> trimmed
            else -> trimmed
        }

        return withScheme.replace(" ", "%20")
    }

    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        return runCatching { URI(url).host ?: "unknown" }.getOrDefault("invalid-uri")
    }

    internal fun sanitize(input: String): String {
        return input
            .replace(Regex("api_key=[^&\\s]+", RegexOption.IGNORE_CASE), "api_key=REDACTED")
            .replace(Regex("api-key\\s*[:=]\\s*[^\\s,;]+", RegexOption.IGNORE_CASE), "api-key=REDACTED")
            .replace(Regex("X-Client-Id\\s*[:=]\\s*[^\\s,;]+", RegexOption.IGNORE_CASE), "X-Client-Id=REDACTED")
            .replace(Regex("Authorization\\s*[:=]\\s*[^\\r\\n]+", RegexOption.IGNORE_CASE), "Authorization: REDACTED")
            .replace(Regex("Bearer\\s+[A-Za-z0-9._\\-]+", RegexOption.IGNORE_CASE), "Bearer REDACTED")
    }
}

