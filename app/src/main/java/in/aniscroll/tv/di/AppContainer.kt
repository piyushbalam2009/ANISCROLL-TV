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
        // Install hardware-tier-tuned Coil ImageLoader (e.g. RGB_565 & 14% heap cap on Fire TV Stick HD)
        Coil.setImageLoader(
            TvDeviceCapabilities.createOptimizedImageLoader(
                context = appContext,
                profile = deviceProfile
            )
        )
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
            aniScrollApi = NetworkModule.aniScrollApi
        )
    }

    val fanartLogoProvider: FanartLogoProvider by lazy {
        FanartLogoProvider(
            fanartApi = NetworkModule.fanartApi,
            aniScrollApi = NetworkModule.aniScrollApi
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
