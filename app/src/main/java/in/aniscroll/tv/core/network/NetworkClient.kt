package `in`.aniscroll.tv.core.network

import com.example.BuildConfig
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.AniSkipApi
import `in`.aniscroll.tv.data.remote.HoshikaApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bounded retry interceptor with exponential backoff for transient network/5xx errors.
 * Does NOT retry 400, 401, 403, 404, or 422 responses.
 */
class BoundedRetryInterceptor(
    private val maxRetries: Int = 2,
    private val initialDelayMs: Long = 400L
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

    private fun isConfiguredSecret(value: String?): Boolean {
        return !value.isNullOrBlank() &&
            !value.startsWith("MY_") &&
            !value.equals("null", ignoreCase = true)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 AniScrollTV/1.0")

                if (original.url.host.contains("hoshika.app", ignoreCase = true)) {
                    val clientId = runCatching { BuildConfig.HOSHIKA_CLIENT_ID }.getOrNull()
                    if (isConfiguredSecret(clientId)) {
                        builder.header("X-Client-Id", clientId!!)
                    }
                }

                chain.proceed(builder.build())
            }
            .addInterceptor(BoundedRetryInterceptor(maxRetries = 2))
            .build()
    }

    val hoshikaApi: HoshikaApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://hoshika.app/api/v1/")
            .client(okHttpClient)
            .build()
            .create(HoshikaApi::class.java)
    }

    val aniScrollApi: AniScrollApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.aniscroll.in/")
            .client(okHttpClient)
            .build()
            .create(AniScrollApi::class.java)
    }

    val aniSkipApi: AniSkipApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.aniskip.com/")
            .client(okHttpClient)
            .build()
            .create(AniSkipApi::class.java)
    }
}
