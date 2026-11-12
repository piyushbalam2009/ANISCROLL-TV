package `in`.aniscroll.tv.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import `in`.aniscroll.tv.domain.model.SubtitleBackground
import `in`.aniscroll.tv.domain.model.SubtitlePreferences
import `in`.aniscroll.tv.domain.model.SubtitleTrack

/**
 * Manages embedded and external subtitle track selection and visual styling (Section 20).
 */
@OptIn(UnstableApi::class)
class SubtitleManager {

    fun applySubtitlePreferencesToView(
        subtitleView: SubtitleView,
        prefs: SubtitlePreferences
    ) {
        val fgColor = prefs.color.argb
        val bgAlpha = when (prefs.background) {
            SubtitleBackground.TRANSPARENT -> 0
            SubtitleBackground.SEMI_TRANSPARENT -> ((prefs.backgroundOpacityPercent / 100f) * 255).toInt().coerceIn(0, 255)
            SubtitleBackground.SOLID_BLACK -> 255
        }
        val bgColor = Color.argb(bgAlpha, 0, 0, 0)
        val edgeType = if (prefs.outlineEnabled) {
            CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } else {
            CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val edgeColor = Color.BLACK

        val style = CaptionStyleCompat(
            fgColor,
            bgColor,
            Color.TRANSPARENT,
            edgeType,
            edgeColor,
            Typeface.DEFAULT_BOLD
        )

        subtitleView.setStyle(style)
        subtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.size.fontSizeSp)
        subtitleView.setBottomPaddingFraction(prefs.position.bottomPaddingFraction)
    }

    fun applyTrackSelection(
        player: ExoPlayer,
        enabled: Boolean,
        preferredLanguage: String,
        availableSubtitles: List<SubtitleTrack>
    ) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (!enabled) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            if (preferredLanguage.isNotBlank()) {
                builder.setPreferredTextLanguage(preferredLanguage.lowercase())
            }
        }
        player.trackSelectionParameters = builder.build()
    }
}
