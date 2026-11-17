package `in`.aniscroll.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

data class AudioTrackDiagnosticInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val id: String?,
    val label: String?,
    val language: String?,
    val mimeType: String?,
    val channelCount: Int,
    val bitrate: Int,
    val isDefault: Boolean,
    val isAutoSelect: Boolean,
    val isSupported: Boolean,
    val isSelected: Boolean
)

/**
 * Manages Media3 audio language preferences, stale override cleanup across episode/language
 * switches, HLS audio rendition diagnostics, and deterministic audio track selection for
 * multi-audio HLS streams (e.g. playlists where Japanese is DEFAULT=YES and English is DEFAULT=NO).
 */
@OptIn(UnstableApi::class)
class AudioTrackManager {

    companion object {
        const val AUDIO_SUB = "sub"
        const val AUDIO_DUB = "dub"

        private val ENGLISH_LANG_CODES = setOf("en", "eng", "en-us", "en-gb", "en-au", "en-ca")
        private val JAPANESE_LANG_CODES = setOf("ja", "jpn", "jp", "ja-jp")

        /**
         * Normalizes any incoming audio selection string into strict "sub" or "dub",
         * returning null when absent or unrecognized so callers can safely fall back to user preferences.
         */
        fun normalizeAudioType(raw: String?): String? {
            val trimmed = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                trimmed == AUDIO_DUB || trimmed in ENGLISH_LANG_CODES || trimmed == "english" -> AUDIO_DUB
                trimmed == AUDIO_SUB || trimmed in JAPANESE_LANG_CODES || trimmed == "japanese" -> AUDIO_SUB
                else -> null
            }
        }

        fun preferredLanguageCodesFor(audioType: String): Array<String> {
            return if (normalizeAudioType(audioType) == AUDIO_DUB) {
                arrayOf("en", "en-us", "en-gb")
            } else {
                arrayOf("ja", "ja-jp")
            }
        }

        /**
         * Scores how well a Media3 [Format] matches the requested [audioType] ("dub" -> English, "sub" -> Japanese).
         * Returns 0 if the track does not match the target language.
         */
        fun scoreTrackMatch(format: Format, audioType: String): Int {
            val normalizedType = normalizeAudioType(audioType) ?: AUDIO_SUB
            val lang = format.language?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()
            val label = format.label?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val id = format.id?.trim()?.lowercase(Locale.ROOT).orEmpty()

            return if (normalizedType == AUDIO_DUB) {
                val isExplicitOtherLang = lang.isNotEmpty() &&
                    lang != "und" &&
                    lang !in ENGLISH_LANG_CODES &&
                    !lang.startsWith("en-")
                when {
                    lang == "en" || lang == "eng" -> 100
                    lang in ENGLISH_LANG_CODES || lang.startsWith("en-") -> 95
                    !isExplicitOtherLang && (label.contains("english") || label == "dub" || label.contains("(dub")) -> 75
                    !isExplicitOtherLang && (id.endsWith(":english") || id.contains("/audio/en")) -> 65
                    else -> 0
                }
            } else {
                val isExplicitOtherLang = lang.isNotEmpty() &&
                    lang != "und" &&
                    lang !in JAPANESE_LANG_CODES &&
                    !lang.startsWith("ja-")
                when {
                    lang == "ja" || lang == "jpn" -> 100
                    lang in JAPANESE_LANG_CODES || lang.startsWith("ja-") -> 95
                    !isExplicitOtherLang && (label.contains("japanese") || label.contains("original") || label == "sub") -> 75
                    !isExplicitOtherLang && (id.endsWith(":japanese") || id.contains("/audio/ja")) -> 65
                    else -> 0
                }
            }
        }
    }

    @Volatile
    private var lastLoggedSignature: String? = null

    fun resetSessionDiagnostics() {
        lastLoggedSignature = null
    }

    /**
     * Clears any stale audio TrackSelectionOverrides from previous episodes/streams and configures
     * Media3 preferred audio languages BEFORE preparing the new MediaSource.
     */
    fun configureAudioTrackParameters(
        player: ExoPlayer,
        audioType: String
    ) {
        val normalized = normalizeAudioType(audioType) ?: AUDIO_SUB
        val preferredCodes = preferredLanguageCodesFor(normalized)

        val updated = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioRoleFlags(0)
            .setPreferredAudioLanguages(*preferredCodes)
            .build()

        player.trackSelectionParameters = updated

        StreamingDiagnostics.logEvent(
            "audio_params_configured desired=$normalized preferred_langs=${preferredCodes.joinToString(",")}"
        )
    }

    /**
     * Inspects discovered Media3 [Tracks] for all audio renditions, logs diagnostic telemetry,
     * and ensures the intended audio language ("en" for dub, "ja" for sub) is actually selected
     * when the stream contains a matching supported rendition.
     *
     * Never forces a non-existent track if the stream only carries a single or undetermined track.
     */
    fun inspectAndEnsureAudioSelection(
        player: ExoPlayer,
        tracks: Tracks,
        desiredAudioType: String
    ): AudioTrackDiagnosticInfo? {
        val normalized = normalizeAudioType(desiredAudioType) ?: AUDIO_SUB
        val audioTracks = extractAudioTrackDiagnostics(tracks)
        if (audioTracks.isEmpty()) return null

        val currentlySelected = audioTracks.firstOrNull { it.isSelected }

        val signature = buildString {
            append(normalized)
            append("|")
            audioTracks.forEach { t ->
                append("${t.groupIndex}:${t.trackIndex}:${t.language}:${t.label}:${t.isSelected};")
            }
        }
        if (signature != lastLoggedSignature) {
            lastLoggedSignature = signature
            val renditionsSummary = audioTracks.joinToString(" | ") { t ->
                "[lang=${t.language ?: "und"},label=${t.label ?: "none"},def=${t.isDefault},auto=${t.isAutoSelect},sup=${t.isSupported},sel=${t.isSelected}]"
            }
            StreamingDiagnostics.logEvent(
                "hls_audio_tracks_discovered desired=$normalized count=${audioTracks.size} " +
                    "selected=${currentlySelected?.let { "${it.language ?: "und"}(${it.label ?: "none"})" } ?: "none"} " +
                    "renditions=$renditionsSummary"
            )
        }

        // Rank supported tracks that match the requested audio type
        var bestGroupIndex = -1
        var bestTrackIndex = -1
        var bestCandidateScore = Int.MIN_VALUE

        for ((groupIdx, group) in tracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (trackIdx in 0 until group.length) {
                if (!group.isTrackSupported(trackIdx)) continue
                val format = group.getTrackFormat(trackIdx)
                val langScore = scoreTrackMatch(format, normalized)
                if (langScore <= 0) continue

                val isSelectedBonus = if (group.isTrackSelected(trackIdx)) 5 else 0
                val stereoBonus = if (format.channelCount == 2) 3 else 0
                val totalScore = langScore * 10 + isSelectedBonus + stereoBonus
                if (totalScore > bestCandidateScore) {
                    bestCandidateScore = totalScore
                    bestGroupIndex = groupIdx
                    bestTrackIndex = trackIdx
                }
            }
        }

        if (bestGroupIndex >= 0 && bestTrackIndex >= 0) {
            val targetGroup = tracks.groups[bestGroupIndex]
            val targetFormat = targetGroup.getTrackFormat(bestTrackIndex)
            val alreadySelected = targetGroup.isTrackSelected(bestTrackIndex)

            if (alreadySelected) {
                return audioTracks.firstOrNull {
                    it.groupIndex == bestGroupIndex && it.trackIndex == bestTrackIndex
                }
            }

            // Media3 selected a different track (e.g. Japanese because HLS marked it DEFAULT=YES, AUTOSELECT=YES
            // and English DEFAULT=NO, AUTOSELECT=NO). Override explicitly to the matching audio rendition.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setPreferredAudioLanguages(*preferredLanguageCodesFor(normalized))
                .setOverrideForType(
                    TrackSelectionOverride(targetGroup.mediaTrackGroup, listOf(bestTrackIndex))
                )
                .build()

            StreamingDiagnostics.logEvent(
                "audio_track_override_applied desired=$normalized " +
                    "target_lang=${targetFormat.language ?: "und"} target_label=${targetFormat.label ?: "none"} " +
                    "previous_lang=${currentlySelected?.language ?: "none"} previous_label=${currentlySelected?.label ?: "none"}"
            )
            return audioTracks.firstOrNull {
                it.groupIndex == bestGroupIndex && it.trackIndex == bestTrackIndex
            }
        }

        // Stream has no matching language rendition (e.g., single-track or undetermined language stream).
        // Do not blindly force English; ensure no stale override blocks the stream's available audio.
        if (player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_AUDIO }) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
        }

        StreamingDiagnostics.logEvent(
            "audio_track_fallback_preserved desired=$normalized " +
                "selected=${currentlySelected?.let { "${it.language ?: "und"}(${it.label ?: "none"})" } ?: "none"}"
        )
        return currentlySelected
    }

    fun extractAudioTrackDiagnostics(tracks: Tracks): List<AudioTrackDiagnosticInfo> {
        val result = mutableListOf<AudioTrackDiagnosticInfo>()
        for ((groupIndex, group) in tracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (trackIndex in 0 until group.length) {
                val fmt = group.getTrackFormat(trackIndex)
                result.add(
                    AudioTrackDiagnosticInfo(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        id = fmt.id,
                        label = fmt.label,
                        language = fmt.language,
                        mimeType = fmt.sampleMimeType,
                        channelCount = fmt.channelCount,
                        bitrate = fmt.bitrate,
                        isDefault = (fmt.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                        isAutoSelect = (fmt.selectionFlags and C.SELECTION_FLAG_AUTOSELECT) != 0,
                        isSupported = group.isTrackSupported(trackIndex),
                        isSelected = group.isTrackSelected(trackIndex)
                    )
                )
            }
        }
        return result
    }
}
