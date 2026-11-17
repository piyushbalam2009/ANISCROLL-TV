package `in`.aniscroll.tv.di

import android.content.Context
import coil.Coil
import `in`.aniscroll.tv.artwork.FanartLogoProvider
import `in`.aniscroll.tv.artwork.LogoRepository
import `in`.aniscroll.tv.artwork.TmdbLogoProvider
import `in`.aniscroll.tv.core.database.AniScrollDatabase
import `in`.aniscroll.tv.core.datastore.UserPreferencesRepository
import `in`.aniscroll.tv.core.device.TvDeviceCapabilities
import `in`.aniscroll.tv.core.device.TvDeviceProfile
import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.core.network.NetworkMonitor
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.PlaybackRepository
import `in`.aniscroll.tv.player.ExoPlayerManager

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val deviceProfile: TvDeviceProfile = TvDeviceCapabilities.getDeviceProfile(appContext)

    init {
        // Install a single hardware-tier-tuned Coil ImageLoader shared across all posters, banners,
        // episode thumbnails, and title logos to avoid duplicate memory/disk caches on TV devices.
        val sharedImageLoader = TvDeviceCapabilities.createOptimizedImageLoader(
            context = appContext,
            profile = deviceProfile
        )
        Coil.setImageLoader(sharedImageLoader)
        `in`.aniscroll.tv.artwork.LogoImageLoader.installShared(sharedImageLoader)
    }

    val database: AniScrollDatabase by lazy {
        AniScrollDatabase.getInstance(appContext)
    }

    val dao by lazy {
        database.dao()
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(appContext)
    }

    val networkMonitor: NetworkMonitor by lazy {
        NetworkMonitor(appContext)
    }

    val tmdbLogoProvider: TmdbLogoProvider by lazy {
        TmdbLogoProvider(
            tmdbApi = NetworkModule.tmdbApi,
            aniScrollApi = NetworkModule.aniScrollApi,
            tmdbMovieApi = NetworkModule.tmdbMovieApi
        )
    }

    val fanartLogoProvider: FanartLogoProvider by lazy {
        FanartLogoProvider(
            fanartApi = NetworkModule.fanartApi,
            aniScrollApi = NetworkModule.aniScrollApi,
            fanartMovieApi = NetworkModule.fanartMovieApi
        )
    }

    val logoRepository: LogoRepository by lazy {
        LogoRepository(
            dao = dao,
            tmdbLogoProvider = tmdbLogoProvider,
            fanartLogoProvider = fanartLogoProvider
        )
    }

    val animeRepository: AnimeRepository by lazy {
        AnimeRepository(
            hoshikaApi = NetworkModule.hoshikaApi,
            aniScrollApi = NetworkModule.aniScrollApi,
            dao = dao,
            logoRepository = logoRepository
        )
    }

    val playbackRepository: PlaybackRepository by lazy {
        PlaybackRepository(
            aniScrollApi = NetworkModule.aniScrollApi,
            aniSkipApi = NetworkModule.aniSkipApi,
            dao = dao,
            idConverter = animeRepository.idConverter
        )
    }

    val exoPlayerManager: ExoPlayerManager by lazy {
        ExoPlayerManager(appContext)
    }
}
