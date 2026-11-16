package `in`.aniscroll.tv.core.network

import com.example.BuildConfig
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.AniSkipApi
import `in`.aniscroll.tv.data.remote.FanartApi
import `in`.aniscroll.tv.data.remote.HoshikaApi
import `in`.aniscroll.tv.data.remote.TmdbApi
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bounded retry interceptor with fast exponential backoff for transient network/5xx errors.
 * Does NOT retry 400, 401, 403, 404, 422, or 429 responses.
 */
class BoundedRetryInterceptor(
    private val maxRetries: Int = 2,
    private val initialDelayMs: Long = 200L
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        var attempt = 0
        var lastException: IOException? = null

        while (attempt <= maxRetries) {
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || response.code in 400..499) {
                    return response
                }
                if (attempt < maxRetries && response.code in 500..599) {
                    response.close()
                } else {
                    return response
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt >= maxRetries) {
                    throw e
                }
            }
            attempt++
            try {
                Thread.sleep(initialDelayMs * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw lastException ?: IOException("Interrupted during retry")
            }
        }
        throw lastException ?: IOException("Network request failed")
    }
}

object NetworkModule {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 AniScrollTV/1.0"

    /**
     * Shared HTTP connection pool and dispatcher reused across Retrofit API calls AND ExoPlayer
     * HLS manifest/segment transfers so TLS/HTTP2 sockets to https://anivault-scraper-five.vercel.app
     * stay warm and concurrent segment loads never bottleneck behind a 5-per-host limit.
     */
    private val sharedConnectionPool: ConnectionPool by lazy {
        ConnectionPool(maxIdleConnections = 20, keepAliveDuration = 5, TimeUnit.MINUTES)
    }

    private val sharedDispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
    }

    fun isConfiguredSecret(value: String?): Boolean {
        val trimmed = value?.trim()
        return !trimmed.isNullOrEmpty() &&
            !trimmed.startsWith("MY_", ignoreCase = true) &&
            !trimmed.startsWith("YOUR_", ignoreCase = true) &&
            !trimmed.equals("null", ignoreCase = true) &&
            !trimmed.equals("PLACEHOLDER", ignoreCase = true)
    }

    fun hasValidTmdbCredentials(): Boolean {
        val key = runCatching { BuildConfig.TMDB_API_KEY }.getOrNull()
        return isConfiguredSecret(key)
    }

    fun hasValidFanartCredentials(): Boolean {
        val key = runCatching { BuildConfig.FANART_API_KEY }.getOrNull()
        return isConfiguredSecret(key)
    }

    /**
     * Shared OkHttpClient for REST API requests (Hoshika, AniScroll Watch, AniSkip, TMDB, Fanart).
     */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val host = original.url.host.lowercase()
                var url = original.url
                val builder = original.newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", DEFAULT_USER_AGENT)

                when {
                    host.contains("hoshika.app") -> {
                        val clientId = runCatching { BuildConfig.HOSHIKA_CLIENT_ID }.getOrNull()
                        if (isConfiguredSecret(clientId)) {
                            builder.header("X-Client-Id", clientId!!)
                        }
                    }

                    host.contains("api.themoviedb.org") -> {
                        val tmdbSecret = runCatching { BuildConfig.TMDB_API_KEY }.getOrNull()?.trim()
                        if (isConfiguredSecret(tmdbSecret)) {
                            if (tmdbSecret!!.startsWith("eyJ") || tmdbSecret.length > 40) {
                                builder.header("Authorization", "Bearer $tmdbSecret")
                            } else if (url.queryParameter("api_key") == null) {
                                url = url.newBuilder()
                                    .addQueryParameter("api_key", tmdbSecret)
                                    .build()
                                builder.url(url)
                            }
                        }
                    }

                    host.contains("fanart.tv") -> {
                        val fanartSecret = runCatching { BuildConfig.FANART_API_KEY }.getOrNull()?.trim()
                        val urlBuilder = url.newBuilder()
                        var changed = false
                        if (isConfiguredSecret(fanartSecret) && url.queryParameter("api_key") == null) {
                            urlBuilder.addQueryParameter("api_key", fanartSecret!!)
                            changed = true
                        }
                        if (url.queryParameter("source") == null) {
                            urlBuilder.addQueryParameter("source", "tmdb")
                            changed = true
                        }
                        if (changed) {
                            url = urlBuilder.build()
                            builder.url(url)
                        }
                    }
                }

                chain.proceed(builder.build())
            }
            .addInterceptor(BoundedRetryInterceptor(maxRetries = 1, initialDelayMs = 150L))
            .build()
    }

    /**
     * Dedicated OkHttpClient for Media3 ExoPlayer HLS manifest, segment, and subtitle requests.
     * Shares [sharedConnectionPool] and [sharedDispatcher] with API clients for warm HTTP/2 reuse,
     * follows cross-protocol/CDN redirects cleanly, and avoids API-only JSON headers.
     */
    val streamingOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .addInterceptor(BoundedRetryInterceptor(maxRetries = 2, initialDelayMs = 120L))
            .build()
    }

    val hoshikaApi: HoshikaApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://hoshika.app/api/v1/")
            .client(okHttpClient)
            .build()
            .create(HoshikaApi::class.java)
    }

    const val ANISCROLL_STREAMING_BASE_URL = "https://anivault-scraper-five.vercel.app/"

    val aniScrollApi: AniScrollApi by lazy {
        Retrofit.Builder()
            .baseUrl(ANISCROLL_STREAMING_BASE_URL)
            .client(okHttpClient)
            .build()
            .create(AniScrollApi::class.java)
    }

    val tmdbApi: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(okHttpClient)
            .build()
            .create(TmdbApi::class.java)
    }

    val fanartApi: FanartApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://webservice.fanart.tv/v3.2/")
            .client(okHttpClient)
            .build()
            .create(FanartApi::class.java)
    }

    val aniSkipApi: AniSkipApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.aniskip.com/")
            .client(okHttpClient)
            .build()
            .create(AniSkipApi::class.java)
    }

    val aniListGraphQlApi: `in`.aniscroll.tv.data.remote.AniListGraphQlApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://graphql.anilist.co/")
            .client(okHttpClient)
            .build()
            .create(`in`.aniscroll.tv.data.remote.AniListGraphQlApi::class.java)
    }
}
