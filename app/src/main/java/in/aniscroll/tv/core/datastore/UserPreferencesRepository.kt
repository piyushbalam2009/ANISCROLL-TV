package `in`.aniscroll.tv.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import `in`.aniscroll.tv.domain.model.AppSettings
import `in`.aniscroll.tv.domain.model.SubtitleBackground
import `in`.aniscroll.tv.domain.model.SubtitleColor
import `in`.aniscroll.tv.domain.model.SubtitlePosition
import `in`.aniscroll.tv.domain.model.SubtitlePreferences
import `in`.aniscroll.tv.domain.model.SubtitleSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aniscroll_tv_prefs")

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        val AUTO_NEXT_EPISODE = booleanPreferencesKey("auto_next_episode")
        val AUTO_SKIP_INTRO = booleanPreferencesKey("auto_skip_intro")
        val RESUME_PLAYBACK = booleanPreferencesKey("resume_playback")

        val SUBTITLE_ENABLED = booleanPreferencesKey("subtitle_enabled")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        val SUBTITLE_COLOR = stringPreferencesKey("subtitle_color")
        val SUBTITLE_BACKGROUND = stringPreferencesKey("subtitle_background")
        val SUBTITLE_OPACITY = intPreferencesKey("subtitle_opacity")
        val SUBTITLE_OUTLINE = booleanPreferencesKey("subtitle_outline")
        val SUBTITLE_POSITION = stringPreferencesKey("subtitle_position")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val subPrefs = SubtitlePreferences(
            enabled = prefs[Keys.SUBTITLE_ENABLED] ?: true,
            preferredLanguage = prefs[Keys.SUBTITLE_LANGUAGE] ?: "English",
            size = runCatching {
                SubtitleSize.valueOf(prefs[Keys.SUBTITLE_SIZE] ?: SubtitleSize.MEDIUM.name)
            }.getOrDefault(SubtitleSize.MEDIUM),
            color = runCatching {
                SubtitleColor.valueOf(prefs[Keys.SUBTITLE_COLOR] ?: SubtitleColor.WHITE.name)
            }.getOrDefault(SubtitleColor.WHITE),
            background = runCatching {
                SubtitleBackground.valueOf(
                    prefs[Keys.SUBTITLE_BACKGROUND] ?: SubtitleBackground.SEMI_TRANSPARENT.name
                )
            }.getOrDefault(SubtitleBackground.SEMI_TRANSPARENT),
            backgroundOpacityPercent = prefs[Keys.SUBTITLE_OPACITY] ?: 75,
            outlineEnabled = prefs[Keys.SUBTITLE_OUTLINE] ?: true,
            position = runCatching {
                SubtitlePosition.valueOf(prefs[Keys.SUBTITLE_POSITION] ?: SubtitlePosition.BOTTOM.name)
            }.getOrDefault(SubtitlePosition.BOTTOM)
        )

        AppSettings(
            defaultQuality = prefs[Keys.DEFAULT_QUALITY] ?: "Auto",
            preferredAudioLanguage = prefs[Keys.PREFERRED_LANGUAGE] ?: "sub",
            autoNextEpisode = prefs[Keys.AUTO_NEXT_EPISODE] ?: true,
            autoSkipIntro = prefs[Keys.AUTO_SKIP_INTRO] ?: false,
            resumePlayback = prefs[Keys.RESUME_PLAYBACK] ?: true,
            subtitlePreferences = subPrefs
        )
    }

    suspend fun setDefaultQuality(quality: String) {
        context.dataStore.edit { it[Keys.DEFAULT_QUALITY] = quality }
    }

    suspend fun setPreferredLanguage(language: String) {
        context.dataStore.edit { it[Keys.PREFERRED_LANGUAGE] = language.lowercase() }
    }

    suspend fun setAutoNextEpisode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_NEXT_EPISODE] = enabled }
    }

    suspend fun setAutoSkipIntro(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SKIP_INTRO] = enabled }
    }

    suspend fun setResumePlayback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RESUME_PLAYBACK] = enabled }
    }

    suspend fun setSubtitleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SUBTITLE_ENABLED] = enabled }
    }

    suspend fun setSubtitleLanguage(language: String) {
        context.dataStore.edit { it[Keys.SUBTITLE_LANGUAGE] = language }
    }

    suspend fun setSubtitleSize(size: SubtitleSize) {
        context.dataStore.edit { it[Keys.SUBTITLE_SIZE] = size.name }
    }

    suspend fun setSubtitleColor(color: SubtitleColor) {
        context.dataStore.edit { it[Keys.SUBTITLE_COLOR] = color.name }
    }

    suspend fun setSubtitleBackground(background: SubtitleBackground) {
        context.dataStore.edit { it[Keys.SUBTITLE_BACKGROUND] = background.name }
    }

    suspend fun setSubtitleOpacity(opacityPercent: Int) {
        context.dataStore.edit { it[Keys.SUBTITLE_OPACITY] = opacityPercent.coerceIn(0, 100) }
    }

    suspend fun setSubtitleOutline(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SUBTITLE_OUTLINE] = enabled }
    }

    suspend fun setSubtitlePosition(position: SubtitlePosition) {
        context.dataStore.edit { it[Keys.SUBTITLE_POSITION] = position.name }
    }

    suspend fun resetAllSettings() {
        context.dataStore.edit { it.clear() }
    }
}
