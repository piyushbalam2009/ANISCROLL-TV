package `in`.aniscroll.tv.features.search

import android.util.Log

/**
 * Structured diagnostic logger for AniScroll TV Search flow.
 * Uses tag `AniScrollSearch` and redacts any sensitive query parameters or tokens.
 */
object SearchLogger {
    const val TAG = "AniScrollSearch"

    private val secretParamRegex = Regex(
        "([?&](?:api_key|apikey|token|key|client_secret|authorization)=)([^&\\s]+)",
        RegexOption.IGNORE_CASE
    )

    fun sanitize(message: String): String {
        return secretParamRegex.replace(message) { matchResult ->
            "${matchResult.groupValues[1]}<REDACTED>"
        }
    }

    fun d(message: String) {
        val safe = sanitize(message)
        runCatching { Log.d(TAG, safe) }
    }

    fun i(message: String) {
        val safe = sanitize(message)
        runCatching { Log.i(TAG, safe) }
    }

    fun w(message: String, throwable: Throwable? = null) {
        val safe = sanitize(message)
        runCatching {
            if (throwable != null) Log.w(TAG, safe, throwable) else Log.w(TAG, safe)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        val safe = sanitize(message)
        runCatching {
            if (throwable != null) Log.e(TAG, safe, throwable) else Log.e(TAG, safe)
        }
    }
}
