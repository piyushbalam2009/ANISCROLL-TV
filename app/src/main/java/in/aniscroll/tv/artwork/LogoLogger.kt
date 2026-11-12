package `in`.aniscroll.tv.artwork

import android.util.Log
import java.net.URI

/**
 * Structured logger for anime title logo retrieval and rendering (Section 11).
 * Uses consistent tag `AniScrollLogo` and never logs API keys or Bearer tokens.
 */
object LogoLogger {
    const val TAG = "AniScrollLogo"

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

    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        return runCatching { URI(url).host ?: "unknown" }.getOrDefault("invalid-uri")
    }

    private fun sanitize(input: String): String {
        return input
            .replace(Regex("api_key=[^&\\s]+", RegexOption.IGNORE_CASE), "api_key=REDACTED")
            .replace(Regex("Bearer\\s+[A-Za-z0-9._\\-]+", RegexOption.IGNORE_CASE), "Bearer REDACTED")
    }
}
