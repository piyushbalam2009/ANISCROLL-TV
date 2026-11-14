package `in`.aniscroll.tv.features.launch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.aniscroll.tv.core.datastore.UserPreferencesRepository
import `in`.aniscroll.tv.core.designsystem.AniScrollBrandLogo
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.features.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class LaunchUiState(
    val isInitializing: Boolean = true,
    val isReadyToNavigate: Boolean = false,
    val statusText: String = TvContentLoadingCopy.INITIALIZING_ANISCROLL
)

/**
 * Coordinates one-time asynchronous application startup initialization off the main thread.
 * Warms up user preferences, local watch progress/watchlist state, and awaits essential Home screen
 * readiness with a strict non-blocking timeout cap so slow networks or API errors never trap users
 * on the launch screen. Never triggers again during ordinary screen navigation.
 */
class LaunchViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val animeRepository: AnimeRepository,
    private val homeViewModel: HomeViewModel,
    private val minCinematicDisplayMs: Long = 520L,
    private val maxStartupWaitMs: Long = 1600L
) : ViewModel() {

    private val _uiState = MutableStateFlow(LaunchUiState())
    val uiState: StateFlow<LaunchUiState> = _uiState.asStateFlow()

    @Volatile
    private var hasStartedInitialization = false

    init {
        startInitializationIfNeeded()
    }

    fun startInitializationIfNeeded() {
        if (hasStartedInitialization || _uiState.value.isReadyToNavigate) return
        hasStartedInitialization = true

        viewModelScope.launch {
            val minDisplayDeferred = async {
                if (minCinematicDisplayMs > 0L) {
                    delay(minCinematicDisplayMs)
                }
            }

            // Initialize essential local services & await initial Home readiness (with a hard timeout cap)
            val essentialInitDeferred = async(Dispatchers.IO) {
                runCatching { preferencesRepository.settingsFlow.first() }
                runCatching { animeRepository.continueWatchingFlow.first() }
                runCatching { animeRepository.watchlistFlow.first() }

                withTimeoutOrNull(maxStartupWaitMs) {
                    homeViewModel.uiState.first { state ->
                        !state.isLoading || state.featuredAnime != null || state.sections.isNotEmpty() || state.errorMessage != null
                    }
                }
            }

            minDisplayDeferred.await()
            essentialInitDeferred.await()

            _uiState.update {
                it.copy(
                    isInitializing = false,
                    isReadyToNavigate = true
                )
            }
        }
    }

    /**
     * Marks launch initialization as complete immediately (e.g., if skipped or already initialized).
     */
    fun markStartupComplete() {
        _uiState.update {
            it.copy(
                isInitializing = false,
                isReadyToNavigate = true
            )
        }
    }
}

/**
 * Cinematic, minimalist AniScroll TV App Launch Screen.
 * - Pure black (#000000) full-screen background.
 * - Subtle monochrome anime/manga character silhouette & atmospheric ink illustration at low opacity.
 * - Radial and vertical vignette overlays so the centered white AniScroll logo remains the main focus.
 * - Smooth fade-in of background artwork and logo scale animation from 0.94f to 1.0f.
 * - Subtle animated white loading indicator and "INITIALIZING ANISCROLL" uppercase typography below the logo.
 */
@Composable
fun AniScrollLaunchScreen(
    statusText: String = TvContentLoadingCopy.INITIALIZING_ANISCROLL,
    modifier: Modifier = Modifier
) {
    var animationTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        animationTriggered = true
    }

    val backdropAlpha by animateFloatAsState(
        targetValue = if (animationTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing),
        label = "launch_backdrop_fade"
    )

    val logoAlpha by animateFloatAsState(
        targetValue = if (animationTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "launch_logo_fade"
    )

    val logoScale by animateFloatAsState(
        targetValue = if (animationTriggered) 1.0f else 0.94f,
        animationSpec = tween(durationMillis = 680, easing = FastOutSlowInEasing),
        label = "launch_logo_scale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .focusable(false)
            .testTag("aniscroll_launch_screen"),
        contentAlignment = Alignment.Center
    ) {
        // 1. Subtle Monochrome Anime / Manga Atmospheric Character Silhouette Backdrop
        MonochromeMangaAtmosphereBackdrop(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha }
                .testTag("launch_monochrome_artwork")
        )

        // 2. Subtle Radial & Edge Vignette Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            TvColors.MainBackground.copy(alpha = 0.55f),
                            TvColors.MainBackground.copy(alpha = 0.94f),
                            TvColors.MainBackground
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TvColors.MainBackground.copy(alpha = 0.85f),
                            Color.Transparent,
                            Color.Transparent,
                            TvColors.MainBackground.copy(alpha = 0.90f)
                        )
                    )
                )
        )

        // 3. Centered Official White AniScroll Logo + Subtle Loading Indicator + "INITIALIZING ANISCROLL"
        Column(
            modifier = Modifier
                .padding(32.dp)
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AniScrollBrandLogo(
                size = 136.dp,
                contentDescription = "AniScroll Official Logo",
                modifier = Modifier.testTag("launch_logo_image")
            )

            Spacer(modifier = Modifier.height(28.dp))

            CircularProgressIndicator(
                color = TvColors.PrimaryText,
                trackColor = TvColors.PrimaryText.copy(alpha = 0.14f),
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("launch_loading_indicator")
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusText.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.2.sp,
                    fontSize = 11.sp
                ),
                color = TvColors.PrimaryText.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("launch_status_text")
            )
        }
    }
}

/**
 * Renders a subtle, low-opacity monochrome manga/anime illustration in the background:
 * - Atmospheric full moon halo and diagonal manga speed/rain ink lines
 * - Dark anime swordsman / character silhouette in profile with wind-swept hair, high collar cloak, and katana
 * - Framing manga panel borders in low-opacity white/gray so the centered white AniScroll logo remains the main focus.
 */
@Composable
private fun MonochromeMangaAtmosphereBackdrop(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val inkFaint = Color.White.copy(alpha = 0.035f)
        val inkSoft = Color.White.copy(alpha = 0.065f)
        val inkMedium = Color.White.copy(alpha = 0.095f)
        val silhouetteFill = Color(0xFF141414).copy(alpha = 0.55f)
        val silhouetteRim = Color.White.copy(alpha = 0.11f)

        // 1. Subtle Manga Panel Framing Lines
        val insetX = w * 0.06f
        val insetY = h * 0.08f
        drawRect(
            color = inkFaint,
            topLeft = Offset(insetX, insetY),
            size = Size(w - insetX * 2f, h - insetY * 2f),
            style = Stroke(width = 1.dp.toPx())
        )

        // Vertical manga panel divider strokes on left and right periphery
        drawLine(
            color = inkFaint,
            start = Offset(w * 0.22f, insetY),
            end = Offset(w * 0.18f, h - insetY),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = inkFaint,
            start = Offset(w * 0.82f, insetY),
            end = Offset(w * 0.78f, h - insetY),
            strokeWidth = 1.dp.toPx()
        )

        // 2. Atmospheric Monochrome Moon / Halo behind silhouette
        val moonCenter = Offset(w * 0.68f, h * 0.38f)
        val moonRadius = minOf(w, h) * 0.26f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.075f),
                    Color.White.copy(alpha = 0.025f),
                    Color.Transparent
                ),
                center = moonCenter,
                radius = moonRadius * 1.4f
            ),
            radius = moonRadius * 1.4f,
            center = moonCenter
        )
        drawCircle(
            color = inkSoft,
            radius = moonRadius,
            center = moonCenter,
            style = Stroke(width = 1.2.dp.toPx())
        )

        // 3. Atmospheric Diagonal Manga Ink / Wind Streaks
        val streakOffsets = listOf(
            0.12f to 0.20f,
            0.28f to 0.15f,
            0.74f to 0.18f,
            0.86f to 0.30f,
            0.15f to 0.65f,
            0.80f to 0.72f
        )
        for ((rx, ry) in streakOffsets) {
            val sx = w * rx
            val sy = h * ry
            drawLine(
                color = inkSoft,
                start = Offset(sx, sy),
                end = Offset(sx - w * 0.07f, sy + h * 0.14f),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 4. Stylized Dark Anime Character Silhouette (Right-of-center atmospheric profile)
        val charAnchorX = w * 0.68f
        val charBaseY = h * 0.92f
        val charScale = minOf(w, h) / 720f

        val cloakPath = Path().apply {
            // Flowing high-collar anime coat / cloak & shoulders
            moveTo(charAnchorX - 110f * charScale, charBaseY)
            cubicTo(
                charAnchorX - 95f * charScale, charBaseY - 140f * charScale,
                charAnchorX - 55f * charScale, charBaseY - 250f * charScale,
                charAnchorX - 28f * charScale, charBaseY - 310f * charScale
            )
            // High dramatic collar left
            lineTo(charAnchorX - 36f * charScale, charBaseY - 348f * charScale)
            lineTo(charAnchorX - 14f * charScale, charBaseY - 342f * charScale)
            // Head & spiked wind-swept anime hair silhouette
            cubicTo(
                charAnchorX - 26f * charScale, charBaseY - 375f * charScale,
                charAnchorX - 18f * charScale, charBaseY - 418f * charScale,
                charAnchorX + 8f * charScale, charBaseY - 426f * charScale
            )
            // Hair spikes sweeping right in the wind
            lineTo(charAnchorX + 38f * charScale, charBaseY - 416f * charScale)
            lineTo(charAnchorX + 22f * charScale, charBaseY - 402f * charScale)
            lineTo(charAnchorX + 48f * charScale, charBaseY - 392f * charScale)
            lineTo(charAnchorX + 26f * charScale, charBaseY - 380f * charScale)
            lineTo(charAnchorX + 42f * charScale, charBaseY - 366f * charScale)
            lineTo(charAnchorX + 20f * charScale, charBaseY - 354f * charScale)
            // High collar right & flowing cape
            lineTo(charAnchorX + 40f * charScale, charBaseY - 344f * charScale)
            cubicTo(
                charAnchorX + 78f * charScale, charBaseY - 270f * charScale,
                charAnchorX + 135f * charScale, charBaseY - 150f * charScale,
                charAnchorX + 165f * charScale, charBaseY
            )
            close()
        }

        drawPath(
            path = cloakPath,
            color = silhouetteFill
        )
        drawPath(
            path = cloakPath,
            color = silhouetteRim,
            style = Stroke(width = 1.4.dp.toPx())
        )

        // Diagonal Katana Scabbard / Blade Rim Line across the character silhouette
        drawLine(
            color = inkMedium,
            start = Offset(charAnchorX - 95f * charScale, charBaseY - 80f * charScale),
            end = Offset(charAnchorX + 92f * charScale, charBaseY - 330f * charScale),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
