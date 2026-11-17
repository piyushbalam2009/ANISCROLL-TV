package `in`.aniscroll.tv.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.media3.common.MimeTypes
import coil.EventListener
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import `in`.aniscroll.tv.artwork.LogoFailureCategory
import `in`.aniscroll.tv.artwork.LogoLogger
import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.player.StreamingDiagnostics
import java.io.File
import java.util.Locale

/**
 * Hardware performance tiers detected dynamically at runtime (never hardcoded by device model name).
 * - [COMPACT_HD]: 1080p max output or low-RAM TV sticks (e.g. Fire TV Stick HD, 1–1.5 GB RAM).
 * - [UHD_4K]: 4K UHD capable TV devices with hardware HEVC/VP9 decoding (e.g. Fire TV Stick 4K).
 * - [HIGH_PERF_4K_MAX]: High-headroom 4K UHD TV devices with hardware AV1/HEVC/VP9 and ample RAM (e.g. Fire TV Stick 4K Max).
 */
enum class TvPerformanceTier {
    COMPACT_HD,
    UHD_4K,
    HIGH_PERF_4K_MAX
}

data class CodecCapabilitySummary(
    val mimeType: String,
    val hasHardwareDecoder: Boolean,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int
) {
    val supports4k: Boolean
        get() = hasHardwareDecoder && maxSupportedWidth >= 3840 && maxSupportedHeight >= 2160

    val supports1080p: Boolean
        get() = maxSupportedWidth >= 1920 && maxSupportedHeight >= 1080
}

data class TvBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferDurationMs: Int,
    val targetBufferBytes: Int
)

data class TvDeviceProfile(
    val sdkInt: Int,
    val isAmazonFireTv: Boolean,
    val isTelevisionUi: Boolean,
    val isLowRamDevice: Boolean,
    val totalRamMb: Int,
    val memoryClassMb: Int,
    val displayMaxWidthPx: Int,
    val displayMaxHeightPx: Int,
    val densityDpi: Int,
    val supports4kDisplay: Boolean,
    val maxHardwareVideoWidth: Int,
    val maxHardwareVideoHeight: Int,
    val supportsHardwareAvc: Boolean,
    val supportsHardwareHevc: Boolean,
    val supportsHardwareVp9: Boolean,
    val supportsHardwareAv1: Boolean,
    val performanceTier: TvPerformanceTier,
    val bufferProfile: TvBufferProfile,
    val codecSummaries: Map<String, CodecCapabilitySummary> = emptyMap()
) {
    /**
     * Effective maximum video height that both the connected display AND the hardware video decoders
     * can smoothly render without dropping frames.
     */
    val effectiveMaxPlaybackHeight: Int
        get() = minOf(
            if (supports4kDisplay) 2160 else 1080,
            maxHardwareVideoHeight.coerceAtLeast(720)
        )

    val recommendedPosterWidthPx: Int
        get() = if (isLowRamDevice || performanceTier == TvPerformanceTier.COMPACT_HD) 280 else 440

    val recommendedPosterHeightPx: Int
        get() = if (isLowRamDevice || performanceTier == TvPerformanceTier.COMPACT_HD) 420 else 660

    val recommendedBannerWidthPx: Int
        get() = if (isLowRamDevice || performanceTier == TvPerformanceTier.COMPACT_HD) 1280 else 1920

    val recommendedBannerHeightPx: Int
        get() = if (isLowRamDevice || performanceTier == TvPerformanceTier.COMPACT_HD) 720 else 1080

    val recommendedThumbnailWidthPx: Int
        get() = if (isLowRamDevice || performanceTier == TvPerformanceTier.COMPACT_HD) 320 else 480

    val recommendedThumbnailHeightPx: Int
        get() = if (isLowRamDevice || performanceTier == TvPerformanceTier.COMPACT_HD) 180 else 270
}

object TvDeviceCapabilities {

    @Volatile
    private var cachedProfile: TvDeviceProfile? = null

    fun getDeviceProfile(context: Context): TvDeviceProfile {
        cachedProfile?.let { return it }
        synchronized(this) {
            cachedProfile?.let { return it }
            val detected = detectProfile(context.applicationContext)
            cachedProfile = detected
            StreamingDiagnostics.logEvent(
                "device_profile tier=${detected.performanceTier} fire_tv=${detected.isAmazonFireTv} " +
                    "sdk=${detected.sdkInt} ram_mb=${detected.totalRamMb} low_ram=${detected.isLowRamDevice} " +
                    "display=${detected.displayMaxWidthPx}x${detected.displayMaxHeightPx} " +
                    "max_hw_video=${detected.maxHardwareVideoWidth}x${detected.maxHardwareVideoHeight} " +
                    "hw_codecs(avc=${detected.supportsHardwareAvc},hevc=${detected.supportsHardwareHevc}," +
                    "vp9=${detected.supportsHardwareVp9},av1=${detected.supportsHardwareAv1})"
            )
            return detected
        }
    }

    private fun detectProfile(context: Context): TvDeviceProfile {
        val pm = context.packageManager
        val isAmazonFireTv = runCatching {
            pm.hasSystemFeature("amazon.hardware.fire_tv") ||
                Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        }.getOrDefault(false)

        val isTelevisionUi = runCatching {
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                isAmazonFireTv
        }.getOrDefault(true)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRamFlag = activityManager?.isLowRamDevice ?: false
        val memoryClassMb = activityManager?.memoryClass ?: 128
        val largeMemoryClassMb = activityManager?.largeMemoryClass ?: memoryClassMb

        val memInfo = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(memInfo) }
        val totalRamMb = if (memInfo.totalMem > 0L) {
            (memInfo.totalMem / (1024L * 1024L)).toInt()
        } else {
            (largeMemoryClassMb * 6).coerceAtLeast(1024)
        }

        val isLowRam = isLowRamFlag || totalRamMb in 1..1600 || memoryClassMb < 128

        // Inspect physical display modes and metrics
        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        var maxDisplayW = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels).coerceAtLeast(1920)
        var maxDisplayH = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels).coerceAtLeast(1080)

        runCatching {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay: Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val modes = defaultDisplay?.supportedModes
            if (!modes.isNullOrEmpty()) {
                for (mode in modes) {
                    val w = maxOf(mode.physicalWidth, mode.physicalHeight)
                    val h = minOf(mode.physicalWidth, mode.physicalHeight)
                    if (w >= maxDisplayW && h >= maxDisplayH) {
                        maxDisplayW = w
                        maxDisplayH = h
                    }
                }
            }
        }

        val supports4kDisplay = maxDisplayW >= 3840 || maxDisplayH >= 2160

        // Inspect hardware MediaCodec video decoders at runtime
        val codecSummaries = inspectVideoDecoders()
        val avcSummary = codecSummaries[MimeTypes.VIDEO_H264]
        val hevcSummary = codecSummaries[MimeTypes.VIDEO_H265]
        val vp9Summary = codecSummaries[MimeTypes.VIDEO_VP9]
        val av1Summary = codecSummaries[MimeTypes.VIDEO_AV1]

        val supportsHardwareAvc = avcSummary?.hasHardwareDecoder ?: true
        val supportsHardwareHevc = hevcSummary?.hasHardwareDecoder ?: false
        val supportsHardwareVp9 = vp9Summary?.hasHardwareDecoder ?: false
        val supportsHardwareAv1 = av1Summary?.hasHardwareDecoder ?: false

        val maxHwWidth = codecSummaries.values
            .filter { it.hasHardwareDecoder }
            .maxOfOrNull { it.maxSupportedWidth }
            ?.coerceAtLeast(1920)
            ?: 1920

        val maxHwHeight = codecSummaries.values
            .filter { it.hasHardwareDecoder }
            .maxOfOrNull { it.maxSupportedHeight }
            ?.coerceAtLeast(1080)
            ?: 1080

        val canDecode4kHardware = maxHwHeight >= 2160 && (supportsHardwareHevc || supportsHardwareVp9 || supportsHardwareAv1 || avcSummary?.supports4k == true)

        val performanceTier = when {
            isLowRam || !supports4kDisplay || !canDecode4kHardware -> TvPerformanceTier.COMPACT_HD
            supportsHardwareAv1 || totalRamMb >= 1900 -> TvPerformanceTier.HIGH_PERF_4K_MAX
            else -> TvPerformanceTier.UHD_4K
        }

        val bufferProfile = buildBufferProfileForTier(performanceTier, isLowRam)

        return TvDeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            isAmazonFireTv = isAmazonFireTv,
            isTelevisionUi = isTelevisionUi,
            isLowRamDevice = isLowRam,
            totalRamMb = totalRamMb,
            memoryClassMb = memoryClassMb,
            displayMaxWidthPx = maxDisplayW,
            displayMaxHeightPx = maxDisplayH,
            densityDpi = displayMetrics.densityDpi,
            supports4kDisplay = supports4kDisplay,
            maxHardwareVideoWidth = maxHwWidth,
            maxHardwareVideoHeight = maxHwHeight,
            supportsHardwareAvc = supportsHardwareAvc,
            supportsHardwareHevc = supportsHardwareHevc,
            supportsHardwareVp9 = supportsHardwareVp9,
            supportsHardwareAv1 = supportsHardwareAv1,
            performanceTier = performanceTier,
            bufferProfile = bufferProfile,
            codecSummaries = codecSummaries
        )
    }

    /**
     * Selects conservative memory-safe buffering on Fire TV Stick HD / low-RAM devices,
     * and high-headroom buffering on 4K / 4K Max devices.
     */
    fun buildBufferProfileForTier(
        tier: TvPerformanceTier,
        isLowRam: Boolean
    ): TvBufferProfile {
        return when {
            tier == TvPerformanceTier.COMPACT_HD && isLowRam -> TvBufferProfile(
                minBufferMs = 12_000,
                maxBufferMs = 28_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferDurationMs = 8_000,
                targetBufferBytes = 20 * 1024 * 1024 // 20 MB cap for 1 GB - 1.5 GB RAM HD sticks
            )
            tier == TvPerformanceTier.COMPACT_HD -> TvBufferProfile(
                minBufferMs = 14_000,
                maxBufferMs = 36_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferDurationMs = 10_000,
                targetBufferBytes = 28 * 1024 * 1024
            )
            tier == TvPerformanceTier.UHD_4K -> TvBufferProfile(
                minBufferMs = 15_000,
                maxBufferMs = 45_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferDurationMs = 15_000,
                targetBufferBytes = 44 * 1024 * 1024
            )
            else -> TvBufferProfile(
                minBufferMs = 15_000,
                maxBufferMs = 50_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferDurationMs = 15_000,
                targetBufferBytes = 60 * 1024 * 1024
            )
        }
    }

    private fun inspectVideoDecoders(): Map<String, CodecCapabilitySummary> {
        val targetMimes = listOf(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_VP9,
            MimeTypes.VIDEO_AV1
        )
        val results = mutableMapOf<String, CodecCapabilitySummary>()

        runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val infos = codecList.codecInfos ?: emptyArray()

            for (mime in targetMimes) {
                var hasHw = false
                var maxW = 0
                var maxH = 0

                for (info in infos) {
                    if (info.isEncoder) continue
                    val supportedTypes = info.supportedTypes ?: continue
                    if (supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue

                    val isHardware = isHardwareAcceleratedCodec(info)
                    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                    val videoCaps = caps?.videoCapabilities

                    val supported4k = videoCaps?.isSizeSupported(3840, 2160) ?: false
                    val supported1080p = videoCaps?.isSizeSupported(1920, 1080) ?: true
                    val codecMaxW = when {
                        supported4k -> 3840
                        supported1080p -> 1920
                        else -> videoCaps?.supportedWidths?.upper ?: 1280
                    }
                    val codecMaxH = when {
                        supported4k -> 2160
                        supported1080p -> 1080
                        else -> videoCaps?.supportedHeights?.upper ?: 720
                    }

                    if (isHardware) {
                        hasHw = true
                        if (codecMaxW > maxW) maxW = codecMaxW
                        if (codecMaxH > maxH) maxH = codecMaxH
                    } else if (!hasHw) {
                        if (codecMaxW > maxW) maxW = codecMaxW
                        if (codecMaxH > maxH) maxH = codecMaxH
                    }
                }

                if (maxW > 0 && maxH > 0) {
                    results[mime] = CodecCapabilitySummary(
                        mimeType = mime,
                        hasHardwareDecoder = hasHw,
                        maxSupportedWidth = maxW,
                        maxSupportedHeight = maxH
                    )
                }
            }
        }

        // Safe fallback if MediaCodecList is empty (e.g. in unit test JVM environment)
        if (results.isEmpty()) {
            results[MimeTypes.VIDEO_H264] = CodecCapabilitySummary(
                mimeType = MimeTypes.VIDEO_H264,
                hasHardwareDecoder = true,
                maxSupportedWidth = 1920,
                maxSupportedHeight = 1080
            )
        }

        return results
    }

    private fun isHardwareAcceleratedCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        return !isSoftwareCodecName(info.name)
    }

    fun isSoftwareCodecName(decoderName: String): Boolean {
        val name = decoderName.lowercase(Locale.ROOT)
        return name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.contains(".sw.") ||
            name.contains("ffmpeg") ||
            name.contains("dav1d")
    }

    /**
     * Checks at runtime whether the device's hardware decoder supports the given video format,
     * codec profile (e.g. detecting 10-bit H.264 Hi10P `avc1.6e` which requires software decoding
     * on standard Fire TV hardware AVC decoders), and resolution.
     */
    fun canHardwareDecodeFormat(
        profile: TvDeviceProfile,
        mimeType: String?,
        width: Int,
        height: Int,
        codecs: String? = null
    ): Boolean {
        val mime = mimeType?.lowercase(Locale.ROOT) ?: return true
        val lowerCodecs = codecs?.lowercase(Locale.ROOT).orEmpty()

        // H.264 High 10 Profile (Hi10P: profile_idc 110 = 0x6E, 122 = 0x7A, 244 = 0xF4)
        // is not supported by standard Fire TV / Android TV hardware AVC decoders.
        if (mime == MimeTypes.VIDEO_H264.lowercase(Locale.ROOT) &&
            (lowerCodecs.startsWith("avc1.6e") ||
                lowerCodecs.startsWith("avc1.7a") ||
                lowerCodecs.startsWith("avc1.f4"))
        ) {
            return false
        }

        val summary = profile.codecSummaries[mimeType]
        if (summary != null && !summary.hasHardwareDecoder) {
            return false
        }

        val checkW = if (width > 0) maxOf(width, height) else 1920
        val checkH = if (height > 0) minOf(width, height) else 1080
        val maxW = summary?.maxSupportedWidth ?: profile.maxHardwareVideoWidth
        val maxH = summary?.maxSupportedHeight ?: profile.maxHardwareVideoHeight

        return checkW <= maxW && checkH <= maxH
    }

    /**
     * Builds a memory-tuned Coil [ImageLoader] tailored to the detected Fire TV / Android TV hardware tier.
     * - On Fire TV Stick HD / low-RAM devices: uses RGB_565 bitmaps and a 15% memory cache limit to prevent OOM.
     * - On Fire TV Stick 4K and 4K Max: uses ARGB_8888 with a 22% memory cache limit for crisp 4K posters and logos.
     */
    fun createOptimizedImageLoader(context: Context, profile: TvDeviceProfile = getDeviceProfile(context)): ImageLoader {
        val appCtx = context.applicationContext
        val memPercent = if (profile.isLowRamDevice || profile.performanceTier == TvPerformanceTier.COMPACT_HD) {
            0.14
        } else {
            0.22
        }

        return ImageLoader.Builder(appCtx)
            .okHttpClient { NetworkModule.imageOkHttpClient }
            .components {
                add(SvgDecoder.Factory())
            }
            // Keep ARGB_8888 default so transparent PNG artwork never loses alpha; allowRgb565 optimizes opaque posters on low-RAM devices
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowRgb565(profile.isLowRamDevice)
            .crossfade(true)
            .respectCacheHeaders(true)
            .memoryCache {
                MemoryCache.Builder(appCtx)
                    .maxSizePercent(memPercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appCtx.cacheDir, "aniscroll_image_cache"))
                    .maxSizeBytes(if (profile.isLowRamDevice) 32L * 1024 * 1024 else 64L * 1024 * 1024)
                    .build()
            }
            .eventListener(object : EventListener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    val (host, sanitizedPath) = LogoLogger.sanitizeUrlForDiagnostics(request.data?.toString())
                    val (category, httpStatus) = LogoLogger.classifyThrowable(result.throwable)
                    LogoLogger.w(
                        "Coil global artwork error host=$host path=$sanitizedPath http=${httpStatus ?: "-"} " +
                            "category=${category.name}: ${result.throwable.message}",
                        result.throwable
                    )
                }
            })
            .build()
    }
}
