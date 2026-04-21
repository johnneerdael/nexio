package com.nexio.tv.data.local

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Available subtitle languages
 */
data class SubtitleLanguage(
    val code: String,
    val name: String
)

const val SUBTITLE_LANGUAGE_FORCED = "forced"

val AVAILABLE_SUBTITLE_LANGUAGES = listOf(
    SubtitleLanguage("en", "English"),
    SubtitleLanguage("es", "Spanish"),
    SubtitleLanguage("fr", "French"),
    SubtitleLanguage("de", "German"),
    SubtitleLanguage("it", "Italian"),
    SubtitleLanguage("pt", "Portuguese (Portugal)"),
    SubtitleLanguage("pt-br", "Portuguese (Brazil)"),
    SubtitleLanguage("ru", "Russian"),
    SubtitleLanguage("ja", "Japanese"),
    SubtitleLanguage("ko", "Korean"),
    SubtitleLanguage("zh", "Chinese"),
    SubtitleLanguage("ar", "Arabic"),
    SubtitleLanguage("hi", "Hindi"),
    SubtitleLanguage("tr", "Turkish"),
    SubtitleLanguage("pl", "Polish"),
    SubtitleLanguage("nl", "Dutch"),
    SubtitleLanguage("sv", "Swedish"),
    SubtitleLanguage("da", "Danish"),
    SubtitleLanguage("no", "Norwegian"),
    SubtitleLanguage("fi", "Finnish"),
    SubtitleLanguage("th", "Thai"),
    SubtitleLanguage("vi", "Vietnamese"),
    SubtitleLanguage("id", "Indonesian"),
    SubtitleLanguage("ms", "Malay"),
    SubtitleLanguage("he", "Hebrew"),
    SubtitleLanguage("el", "Greek"),
    SubtitleLanguage("cs", "Czech"),
    SubtitleLanguage("hu", "Hungarian"),
    SubtitleLanguage("ro", "Romanian"),
    SubtitleLanguage("uk", "Ukrainian"),
    SubtitleLanguage("bg", "Bulgarian"),
    SubtitleLanguage("hr", "Croatian"),
    SubtitleLanguage("sk", "Slovak"),
    SubtitleLanguage("sl", "Slovenian"),
    SubtitleLanguage("sr", "Serbian"),
    SubtitleLanguage("ta", "Tamil"),
    SubtitleLanguage("te", "Telugu"),
    SubtitleLanguage("ml", "Malayalam"),
    SubtitleLanguage("bn", "Bengali"),
    SubtitleLanguage("mr", "Marathi"),
    SubtitleLanguage("gu", "Gujarati"),
    SubtitleLanguage("kn", "Kannada"),
    SubtitleLanguage("pa", "Punjabi")
)

/**
 * Data class representing subtitle style settings
 */
data class SubtitleStyleSettings(
    val preferredLanguage: String = "en",
    val secondaryPreferredLanguage: String? = null,
    val size: Int = 120, // Percentage (50-200)
    val verticalOffset: Int = 5, // Percentage from bottom (-20 to 50)
    val bold: Boolean = false,
    val textColor: Int = Color.White.toArgb(),
    val backgroundColor: Int = Color.Transparent.toArgb(),
    val outlineEnabled: Boolean = true,
    val outlineColor: Int = Color.Black.toArgb(),
    val outlineWidth: Int = 2 // 1-5
)

/**
 * Data class representing buffer settings
 */
data class BufferSettings(
    val minBufferMs: Int = DEFAULT_MIN_BUFFER_MS,
    val maxBufferMs: Int = DEFAULT_MAX_BUFFER_MS,
    val bufferForPlaybackMs: Int = DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    val bufferForPlaybackAfterRebufferMs: Int = DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val targetBufferSizeMb: Int = DEFAULT_TARGET_BUFFER_SIZE_MB,
    val backBufferDurationMs: Int = DEFAULT_BACK_BUFFER_DURATION_MS,
    val retainBackBufferFromKeyframe: Boolean = false
) {
    companion object {
        const val DEFAULT_MIN_BUFFER_MS = 20_000
        const val DEFAULT_MAX_BUFFER_MS = 50_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 3_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        const val DEFAULT_TARGET_BUFFER_SIZE_MB: Int = 350
        const val DEFAULT_BACK_BUFFER_DURATION_MS = 0
    }
}

data class SyncedFormatterTemplateSettings(
    val enabled: Boolean = true,
    val selectedTemplateId: String = "universal",
    val customTemplateLabel: String? = null,
    val customNameTemplate: String? = null,
    val customDescriptionTemplate: String? = null,
    val customBadgeRowTemplate: String? = null
)

/**
 * Available audio language options
 */
object AudioLanguageOption {
    const val DEFAULT = "default"  // Use media file default
    const val DEVICE = "device"    // Use device locale
    const val ORIGINAL = "original" // Prefer the content original language
}

enum class IecPackerChannelLayout(val storedValue: String, val kodiChannelLayoutValue: Int) {
    CH_2_0("2.0", 1),
    CH_2_1("2.1", 2),
    CH_3_0("3.0", 3),
    CH_3_1("3.1", 4),
    CH_4_0("4.0", 5),
    CH_4_1("4.1", 6),
    CH_5_0("5.0", 7),
    CH_5_1("5.1", 8),
    CH_7_0("7.0", 9),
    CH_7_1("7.1", 10);

    companion object {
        fun fromStoredValue(value: String?): IecPackerChannelLayout =
            entries.firstOrNull { it.storedValue == value } ?: CH_7_1
    }
}

/**
 * Data class representing player settings
 */
data class PlayerSettings(
    val playerPreference: PlayerPreference = PlayerPreference.INTERNAL,
    val preferredExternalPlayerPackageName: String? = null,
    val subtitleStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    val bufferSettings: BufferSettings = BufferSettings(),
    // Audio settings
    val decoderPriority: Int = 1, // EXTENSION_RENDERER_MODE_ON (0=off, 1=on, 2=prefer)
    val tunnelingEnabled: Boolean = false,
    // Experimental: allow Media3 to sleep the playback loop until video renderer progress is possible.
    val dynamicVideoSchedulingEnabled: Boolean = false,
    val skipSilence: Boolean = false,
    val preferredAudioLanguage: String = AudioLanguageOption.ORIGINAL,
    val secondaryPreferredAudioLanguage: String? = null,
    val loadingOverlayEnabled: Boolean = true,
    val pauseOverlayEnabled: Boolean = true,
    val osdClockEnabled: Boolean = true,
    val skipIntroEnabled: Boolean = true,
    // Prefer DV7 HEVC HDR10 base-layer playback by default; DV8.1 conversion remains opt-in.
    val experimentalDv7ToDv81Enabled: Boolean = false,
    // Map DV7 to its HEVC HDR10 base layer on non-Dolby Vision displays.
    val experimentalDv7HevcBaseLayerEnabled: Boolean = true,
    // Experimental: enable the Kodi-style IEC packer custom AudioSink path.
    // When disabled, the normal Media3 passthrough path remains active.
    val experimentalDtsIecPassthroughEnabled: Boolean = false,
    // IEC packer codec-level passthrough toggles modeled after Kodi.
    val iecPackerAc3PassthroughEnabled: Boolean = true,
    val iecPackerAc3TranscodeEnabled: Boolean = false,
    val iecPackerEac3PassthroughEnabled: Boolean = true,
    val iecPackerDtsPassthroughEnabled: Boolean = true,
    val iecPackerTruehdPassthroughEnabled: Boolean = true,
    val iecPackerDtshdPassthroughEnabled: Boolean = true,
    val iecPackerDtshdCoreFallbackEnabled: Boolean = true,
    val iecPackerAudioConfig: Int = 0,
    val iecPackerAudioDevice: String = "",
    val iecPackerPassthroughDevice: String = "",
    val iecPackerMaxPcmChannelLayout: IecPackerChannelLayout = IecPackerChannelLayout.CH_7_1,
    // Legacy fallback toggle kept in storage for compatibility with older installs.
    val fireOsCompatibilityFallbackEnabled: Boolean = false,
    // IEC startup delay supervision workaround (Kodi superviseaudiodelay equivalent).
    val fireOsIecSuperviseAudioDelayEnabled: Boolean = false,
    // Debug: verbose IEC packer logs for ADB troubleshooting.
    val fireOsIecVerboseLoggingEnabled: Boolean = false,
    // Debug: emit Trakt scrobble/check-in write requests and responses to logcat.
    val traktScrobbleApiLoggingEnabled: Boolean = false,
    // Experimental: allow DV5 streams to use the compatibility DV8.1 remap path.
    val experimentalDv5ToDv81Enabled: Boolean = false,
    // Experimental: force DV5 streams to FFmpeg software decode for tone mapping on non-DV displays.
    val experimentalDv5ToneMapToSdrEnabled: Boolean = false,
    // Experimental: keep hardware decode and tap DV5 RPU metadata for native tone-map experiments.
    val experimentalDv5HardwareToneMapToSdrEnabled: Boolean = false,
    // Experimental: force DV5 hardware tone-map path to CPU-readable YUV input (Shield fallback).
    val experimentalDv5HardwareToneMapCpuFallbackEnabled: Boolean = false,
    // Experimental: preserve mapping metadata when converting DV7 -> DV8.1.
    val experimentalDv7ToDv81PreserveMappingEnabled: Boolean = false,
    // Display settings
    val frameRateMatchingMode: FrameRateMatchingMode = FrameRateMatchingMode.OFF,
    val resolutionMatchingEnabled: Boolean = false,
    // Stream selection settings
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlaySource: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
    val trackingProvider: TrackingProvider = TrackingProvider.TRAKT,
    val streamAutoPlaySelectedAddons: Set<String> = emptySet(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean = true,
    val deterministicAutoplayEnabled: Boolean = true,
    val manualBitrateLimitMbps: Double = 40.0,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val uniformStreamFormattingEnabled: Boolean = true,
    val syncedFormatterTemplate: SyncedFormatterTemplateSettings = SyncedFormatterTemplateSettings(),
    val groupStreamsAcrossAddonsEnabled: Boolean = true,
    val deduplicateGroupedStreamsEnabled: Boolean = true,
    val serviceWrapEnabled: Boolean = false,
    val shadowAutoplayDataCollectionEnabled: Boolean = false,
    val filterWebDolbyVisionStreamsEnabled: Boolean = false,
    val filterEpisodeMismatchStreamsEnabled: Boolean = true,
    val filterMovieYearMismatchStreamsEnabled: Boolean = true,
    val subtitleOrganizationMode: SubtitleOrganizationMode = SubtitleOrganizationMode.BY_LANGUAGE,
    val addonSubtitleStartupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
    val vodCacheSizeMode: VodCacheSizeMode = DEFAULT_VOD_CACHE_SIZE_MODE,
    val vodCacheSizeMb: Int = DEFAULT_VOD_CACHE_SIZE_MB,
    val vodCacheWarmAheadEnabled: Boolean = DEFAULT_VOD_CACHE_WARM_AHEAD_ENABLED,
    val progressivePlaybackDiskMode: ProgressivePlaybackDiskMode = ProgressivePlaybackDiskMode.OFF,
    val diskSpoolSizeMb: Int = DEFAULT_DISK_SPOOL_SIZE_MB,
    val diskSpoolStartupBufferMb: Int = DEFAULT_DISK_SPOOL_STARTUP_BUFFER_MB,
    val diskSpoolStorageLocation: DiskSpoolStorageLocation = DEFAULT_DISK_SPOOL_STORAGE_LOCATION,
    val spoolStorageProbeResultJson: String? = null,
    val useParallelConnections: Boolean = DEFAULT_USE_PARALLEL_CONNECTIONS,
    val parallelConnectionCount: Int = DEFAULT_PARALLEL_CONNECTION_COUNT,
    val parallelChunkSizeMb: Int = DEFAULT_PARALLEL_CHUNK_SIZE_MB,
    val enableBufferLogs: Boolean = false
) {
    companion object {
        const val DEFAULT_VOD_CACHE_SIZE_MB = 500
        const val DEFAULT_VOD_CACHE_WARM_AHEAD_ENABLED = true
        const val MIN_VOD_CACHE_SIZE_MB = 100
        const val MAX_VOD_CACHE_SIZE_MB = 65_536
        const val DEFAULT_DISK_SPOOL_SIZE_MB = 512
        const val DEFAULT_DISK_SPOOL_STARTUP_BUFFER_MB = 100
        val DEFAULT_DISK_SPOOL_STORAGE_LOCATION: DiskSpoolStorageLocation = DiskSpoolStorageLocation.EXTERNAL
        const val MIN_DISK_SPOOL_SIZE_MB = 512
        const val MAX_DISK_SPOOL_SIZE_MB = 2_048
        const val MIN_DISK_SPOOL_STARTUP_BUFFER_MB = 0
        val DEFAULT_VOD_CACHE_SIZE_MODE: VodCacheSizeMode = VodCacheSizeMode.OFF
        const val DEFAULT_USE_PARALLEL_CONNECTIONS = false
        const val DEFAULT_PARALLEL_CONNECTION_COUNT = 2
        const val DEFAULT_PARALLEL_CHUNK_SIZE_MB = 16
        const val MIN_PARALLEL_CONNECTION_COUNT = 2
        const val MAX_PARALLEL_CONNECTION_COUNT = 4
        const val MIN_PARALLEL_CHUNK_SIZE_MB = 8
        const val MAX_PARALLEL_CHUNK_SIZE_MB = 128
    }
}

fun PlayerSettings.diskSpoolTargetBitrateMbps(): Double? {
    return manualBitrateLimitMbps.takeIf { it.isFinite() && it > 0.0 }
}

private val migrationStreamSelectionDefaultsV2EnabledKey =
    booleanPreferencesKey("migration_stream_selection_defaults_v2_enabled")
private val uniformStreamFormattingEnabledMigrationKey =
    booleanPreferencesKey("uniform_stream_formatting_enabled")
private val groupStreamsAcrossAddonsEnabledMigrationKey =
    booleanPreferencesKey("group_streams_across_addons_enabled")
private val deduplicateGroupedStreamsEnabledMigrationKey =
    booleanPreferencesKey("deduplicate_grouped_streams_enabled")
private val filterEpisodeMismatchStreamsEnabledMigrationKey =
    booleanPreferencesKey("filter_episode_mismatch_streams_enabled")
private val filterMovieYearMismatchStreamsEnabledMigrationKey =
    booleanPreferencesKey("filter_movie_year_mismatch_streams_enabled")
private val migrationPreferredAudioOriginalEnabledKey =
    booleanPreferencesKey("migration_preferred_audio_original_enabled")
private val migrationLegacyStreamAutoplaySelectionRetiredEnabledKey =
    booleanPreferencesKey("migration_legacy_stream_autoplay_selection_retired_enabled")
private val migrationDisableBuggyPlaybackPathDoneKey =
    booleanPreferencesKey("migration_disable_buggy_playback_path_done")
private val migrationAutoplayManualDefaultsDoneKey =
    booleanPreferencesKey("migration_autoplay_manual_defaults_done")
private val migrationSafePlaybackDefaultsDoneKey =
    booleanPreferencesKey("migration_safe_playback_defaults_done")
private val experimentalDv7ToDv81EnabledMigrationKey =
    booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
private val experimentalDv7HevcBaseLayerEnabledMigrationKey =
    booleanPreferencesKey("experimental_dv7_hevc_base_layer_enabled")
private val experimentalDv7ToDv81PreserveMappingEnabledMigrationKey =
    booleanPreferencesKey("experimental_dv7_to_dv81_preserve_mapping_enabled")
private val vodCacheSizeModeMigrationKey =
    stringPreferencesKey("vod_cache_size_mode")
private val useParallelConnectionsMigrationKey =
    booleanPreferencesKey("use_parallel_connections")
private val preferredAudioLanguageMigrationKey =
    stringPreferencesKey("preferred_audio_language")
private val streamAutoPlayModeMigrationKey = stringPreferencesKey("stream_auto_play_mode")
private val streamAutoPlaySourceMigrationKey = stringPreferencesKey("stream_auto_play_source")
private val streamAutoPlaySelectedAddonsMigrationKey =
    stringSetPreferencesKey("stream_auto_play_selected_addons")
private val streamAutoPlayRegexMigrationKey = stringPreferencesKey("stream_auto_play_regex")
private val deterministicAutoplayEnabledMigrationKey =
    booleanPreferencesKey("deterministic_autoplay_enabled")
private val autoplayBandwidthModeMigrationKey = stringPreferencesKey("autoplay_bandwidth_mode")
private val manualBitrateLimitMbpsMigrationKey = doublePreferencesKey("manual_bitrate_limit_mbps")
internal fun applyPlayerSettingsMigrations(prefs: MutablePreferences) {
    val streamSelectionDefaultsEnabled =
        prefs[migrationStreamSelectionDefaultsV2EnabledKey] ?: false
    if (!streamSelectionDefaultsEnabled) {
        prefs[uniformStreamFormattingEnabledMigrationKey] = true
        prefs[groupStreamsAcrossAddonsEnabledMigrationKey] = true
        prefs[deduplicateGroupedStreamsEnabledMigrationKey] = true
        prefs[filterEpisodeMismatchStreamsEnabledMigrationKey] = true
        prefs[filterMovieYearMismatchStreamsEnabledMigrationKey] = true
        prefs[migrationStreamSelectionDefaultsV2EnabledKey] = true
    } else if (prefs[groupStreamsAcrossAddonsEnabledMigrationKey] != true) {
        prefs[groupStreamsAcrossAddonsEnabledMigrationKey] = true
    }

    val preferredAudioOriginalEnabled = prefs[migrationPreferredAudioOriginalEnabledKey] ?: false
    if (!preferredAudioOriginalEnabled) {
        prefs[preferredAudioLanguageMigrationKey] = AudioLanguageOption.ORIGINAL
        prefs[migrationPreferredAudioOriginalEnabledKey] = true
    }

    val legacyStreamAutoplaySelectionRetired =
        prefs[migrationLegacyStreamAutoplaySelectionRetiredEnabledKey] ?: false
    if (!legacyStreamAutoplaySelectionRetired) {
        // Legacy stream auto-selection is retired from UX in favor of deterministic autoplay.
        prefs[streamAutoPlayModeMigrationKey] = StreamAutoPlayMode.MANUAL.name
        prefs[streamAutoPlaySourceMigrationKey] = StreamAutoPlaySource.ALL_SOURCES.name
        prefs.remove(streamAutoPlaySelectedAddonsMigrationKey)
        prefs.remove(streamAutoPlayRegexMigrationKey)
        prefs[migrationLegacyStreamAutoplaySelectionRetiredEnabledKey] = true
    }

    val disableBuggyPlaybackPathDone = prefs[migrationDisableBuggyPlaybackPathDoneKey] ?: false
    if (!disableBuggyPlaybackPathDone) {
        prefs[migrationDisableBuggyPlaybackPathDoneKey] = true
    }

    val autoplayManualDefaultsDone = prefs[migrationAutoplayManualDefaultsDoneKey] ?: false
    if (!autoplayManualDefaultsDone) {
        prefs[deterministicAutoplayEnabledMigrationKey] = true
        prefs[autoplayBandwidthModeMigrationKey] = "MANUAL"
        prefs[manualBitrateLimitMbpsMigrationKey] = 40.0
        prefs[migrationAutoplayManualDefaultsDoneKey] = true
    }

    val safePlaybackDefaultsDone = prefs[migrationSafePlaybackDefaultsDoneKey] ?: false
    if (!safePlaybackDefaultsDone) {
        prefs[experimentalDv7ToDv81EnabledMigrationKey] = false
        prefs[experimentalDv7HevcBaseLayerEnabledMigrationKey] = true
        prefs[experimentalDv7ToDv81PreserveMappingEnabledMigrationKey] = false
        prefs[vodCacheSizeModeMigrationKey] = VodCacheSizeMode.OFF.name
        prefs[useParallelConnectionsMigrationKey] = false
        prefs[migrationSafePlaybackDefaultsDoneKey] = true
    }
}

internal fun uniformStreamFormattingEnabledFromPreferences(prefs: Preferences): Boolean = true

enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH
}

enum class StreamAutoPlaySource {
    ALL_SOURCES,
    INSTALLED_ADDONS_ONLY
}

enum class VodCacheSizeMode {
    OFF,
    ON
}

enum class ProgressivePlaybackDiskMode {
    OFF,
    SPOOL
}

enum class FrameRateMatchingMode {
    OFF,
    START,
    START_STOP
}

enum class NextEpisodeThresholdMode {
    PERCENTAGE,
    MINUTES_BEFORE_END
}

enum class SubtitleOrganizationMode {
    NONE,
    BY_LANGUAGE,
    BY_ADDON
}

enum class AddonSubtitleStartupMode {
    FAST_STARTUP,
    PREFERRED_ONLY,
    ALL_SUBTITLES
}

enum class PlayerPreference {
    INTERNAL,
    EXTERNAL,
    ASK_EVERY_TIME
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "player_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private fun <T> profileFlow(extract: (Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            store(pid).data
                .onStart { store(pid).edit { applyAllPlayerMigrations(it) } }
                .map { prefs -> extract(prefs) }
        }

    // Player preference key
    private val playerPreferenceKey = stringPreferencesKey("player_preference")
    private val preferredExternalPlayerPackageNameKey =
        stringPreferencesKey("preferred_external_player_package_name")
    // Audio settings keys
    private val decoderPriorityKey = intPreferencesKey("decoder_priority")
    private val tunnelingEnabledKey = booleanPreferencesKey("tunneling_enabled")
    private val dynamicVideoSchedulingEnabledKey =
        booleanPreferencesKey("dynamic_video_scheduling_enabled")
    private val skipSilenceKey = booleanPreferencesKey("skip_silence")
    private val preferredAudioLanguageKey = stringPreferencesKey("preferred_audio_language")
    private val secondaryPreferredAudioLanguageKey = stringPreferencesKey("secondary_preferred_audio_language")
    private val loadingOverlayEnabledKey = booleanPreferencesKey("loading_overlay_enabled")
    private val pauseOverlayEnabledKey = booleanPreferencesKey("pause_overlay_enabled")
    private val osdClockEnabledKey = booleanPreferencesKey("osd_clock_enabled")
    private val skipIntroEnabledKey = booleanPreferencesKey("skip_intro_enabled")
    private val experimentalDv7ToDv81EnabledKey =
        booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
    private val experimentalDv7HevcBaseLayerEnabledKey =
        booleanPreferencesKey("experimental_dv7_hevc_base_layer_enabled")
    private val experimentalDtsIecPassthroughEnabledKey =
        booleanPreferencesKey("experimental_dts_iec_passthrough_enabled")
    private val iecPackerAc3PassthroughEnabledKey =
        booleanPreferencesKey("iec_packer_ac3_passthrough_enabled")
    private val iecPackerAc3TranscodeEnabledKey =
        booleanPreferencesKey("iec_packer_ac3_transcode_enabled")
    private val iecPackerEac3PassthroughEnabledKey =
        booleanPreferencesKey("iec_packer_eac3_passthrough_enabled")
    private val iecPackerDtsPassthroughEnabledKey =
        booleanPreferencesKey("iec_packer_dts_passthrough_enabled")
    private val iecPackerTruehdPassthroughEnabledKey =
        booleanPreferencesKey("iec_packer_truehd_passthrough_enabled")
    private val iecPackerDtshdPassthroughEnabledKey =
        booleanPreferencesKey("iec_packer_dtshd_passthrough_enabled")
    private val iecPackerDtshdCoreFallbackEnabledKey =
        booleanPreferencesKey("iec_packer_dtshd_core_fallback_enabled")
    private val iecPackerAudioConfigKey =
        intPreferencesKey("iec_packer_audio_config")
    private val iecPackerAudioDeviceKey =
        stringPreferencesKey("iec_packer_audio_device")
    private val iecPackerPassthroughDeviceKey =
        stringPreferencesKey("iec_packer_passthrough_device")
    private val iecPackerMaxPcmChannelLayoutKey =
        stringPreferencesKey("iec_packer_max_pcm_channel_layout")
    private val fireOsCompatibilityFallbackEnabledKey =
        booleanPreferencesKey("fire_os_compatibility_fallback_enabled")
    private val fireOsIecSuperviseAudioDelayEnabledKey =
        booleanPreferencesKey("fire_os_iec_supervise_audio_delay_enabled")
    private val fireOsIecVerboseLoggingEnabledKey =
        booleanPreferencesKey("fire_os_iec_verbose_logging_enabled")
    private val experimentalDv5ToDv81EnabledKey =
        booleanPreferencesKey("experimental_dv5_to_dv81_enabled")
    private val experimentalDv5ToneMapToSdrEnabledKey =
        booleanPreferencesKey("experimental_dv5_tonemap_to_sdr_enabled")
    private val experimentalDv5HardwareToneMapToSdrEnabledKey =
        booleanPreferencesKey("experimental_dv5_hardware_tonemap_to_sdr_enabled")
    private val experimentalDv5HardwareToneMapCpuFallbackEnabledKey =
        booleanPreferencesKey("experimental_dv5_hardware_tonemap_cpu_fallback_enabled")
    private val experimentalDv7ToDv81PreserveMappingEnabledKey =
        booleanPreferencesKey("experimental_dv7_to_dv81_preserve_mapping_enabled")
    private val frameRateMatchingKey = booleanPreferencesKey("frame_rate_matching")
    private val frameRateMatchingModeKey = stringPreferencesKey("frame_rate_matching_mode")
    private val resolutionMatchingEnabledKey = booleanPreferencesKey("resolution_matching_enabled")
    private val streamAutoPlayModeKey = stringPreferencesKey("stream_auto_play_mode")
    private val streamAutoPlaySourceKey = stringPreferencesKey("stream_auto_play_source")
    private val trackingProviderKey = stringPreferencesKey("tracking_provider")
    private val streamAutoPlaySelectedAddonsKey = stringSetPreferencesKey("stream_auto_play_selected_addons")
    private val streamAutoPlayRegexKey = stringPreferencesKey("stream_auto_play_regex")
    private val streamAutoPlayNextEpisodeEnabledKey = booleanPreferencesKey("stream_auto_play_next_episode_enabled")
    private val streamAutoPlayPreferBingeGroupForNextEpisodeKey =
        booleanPreferencesKey("stream_auto_play_prefer_binge_group_for_next_episode")
    private val deterministicAutoplayEnabledKey = booleanPreferencesKey("deterministic_autoplay_enabled")
    private val manualBitrateLimitMbpsKey = doublePreferencesKey("manual_bitrate_limit_mbps")
    private val nextEpisodeThresholdModeKey = stringPreferencesKey("next_episode_threshold_mode")
    private val nextEpisodeThresholdPercentLegacyKey = intPreferencesKey("next_episode_threshold_percent")
    private val nextEpisodeThresholdMinutesBeforeEndLegacyKey = intPreferencesKey("next_episode_threshold_minutes_before_end")
    private val nextEpisodeThresholdPercentKey = floatPreferencesKey("next_episode_threshold_percent_v2")
    private val nextEpisodeThresholdMinutesBeforeEndKey = floatPreferencesKey("next_episode_threshold_minutes_before_end_v2")
    private val streamReuseLastLinkEnabledKey = booleanPreferencesKey("stream_reuse_last_link_enabled")
    private val streamReuseLastLinkCacheHoursKey = intPreferencesKey("stream_reuse_last_link_cache_hours")
    private val uniformStreamFormattingEnabledKey = booleanPreferencesKey("uniform_stream_formatting_enabled")
    private val syncedFormatterEnabledKey = booleanPreferencesKey("synced_formatter_enabled")
    private val syncedFormatterSelectedTemplateIdKey = stringPreferencesKey("synced_formatter_selected_template_id")
    private val syncedFormatterCustomTemplateLabelKey = stringPreferencesKey("synced_formatter_custom_template_label")
    private val syncedFormatterCustomNameTemplateKey = stringPreferencesKey("synced_formatter_custom_name_template")
    private val syncedFormatterCustomDescriptionTemplateKey = stringPreferencesKey("synced_formatter_custom_description_template")
    private val syncedFormatterCustomBadgeRowTemplateKey = stringPreferencesKey("synced_formatter_custom_badge_row_template")
    private val groupStreamsAcrossAddonsEnabledKey = booleanPreferencesKey("group_streams_across_addons_enabled")
    private val deduplicateGroupedStreamsEnabledKey = booleanPreferencesKey("deduplicate_grouped_streams_enabled")
    private val serviceWrapEnabledKey = booleanPreferencesKey("service_wrap_enabled")
    private val shadowAutoplayDataCollectionEnabledKey = booleanPreferencesKey("shadow_autoplay_data_collection_enabled")
    private val filterWebDolbyVisionStreamsEnabledKey = booleanPreferencesKey("filter_web_dolby_vision_streams_enabled")
    private val filterEpisodeMismatchStreamsEnabledKey = booleanPreferencesKey("filter_episode_mismatch_streams_enabled")
    private val filterMovieYearMismatchStreamsEnabledKey = booleanPreferencesKey("filter_movie_year_mismatch_streams_enabled")
    private val subtitleOrganizationModeKey = stringPreferencesKey("subtitle_organization_mode")
    private val vodCacheSizeModeKey = stringPreferencesKey("vod_cache_size_mode")
    private val vodCacheSizeMbKey = intPreferencesKey("vod_cache_size_mb")
    private val vodCacheWarmAheadEnabledKey = booleanPreferencesKey("vod_cache_warm_ahead_enabled")
    private val progressivePlaybackDiskModeKey = stringPreferencesKey("progressive_playback_disk_mode")
    private val diskSpoolSizeMbKey = intPreferencesKey("disk_spool_size_mb")
    private val diskSpoolStartupBufferMbKey = intPreferencesKey("disk_spool_startup_buffer_mb")
    private val diskSpoolStorageLocationKey = stringPreferencesKey("disk_spool_storage_location")
    private val spoolStorageProbeResultJsonKey = stringPreferencesKey("spool_storage_probe_result_json")
    private val useParallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
    private val parallelConnectionCountKey = intPreferencesKey("parallel_connection_count")
    private val parallelChunkSizeMbKey = intPreferencesKey("parallel_chunk_size_mb")
    private val addonSubtitleStartupModeKey = stringPreferencesKey("addon_subtitle_startup_mode")
    private val enableBufferLogsKey = booleanPreferencesKey("enable_buffer_logs")
    private val traktScrobbleApiLoggingEnabledKey = booleanPreferencesKey("trakt_scrobble_api_logging_enabled")

    // Subtitle style settings keys
    private val subtitlePreferredLanguageKey = stringPreferencesKey("subtitle_preferred_language")
    private val subtitleSecondaryLanguageKey = stringPreferencesKey("subtitle_secondary_language")
    private val subtitleSizeKey = intPreferencesKey("subtitle_size")
    private val subtitleVerticalOffsetKey = intPreferencesKey("subtitle_vertical_offset")
    private val subtitleBoldKey = booleanPreferencesKey("subtitle_bold")
    private val subtitleTextColorKey = intPreferencesKey("subtitle_text_color")
    private val subtitleBackgroundColorKey = intPreferencesKey("subtitle_background_color")
    private val subtitleOutlineEnabledKey = booleanPreferencesKey("subtitle_outline_enabled")
    private val subtitleOutlineColorKey = intPreferencesKey("subtitle_outline_color")
    private val subtitleOutlineWidthKey = intPreferencesKey("subtitle_outline_width")

    // Buffer settings keys
    private val minBufferMsKey = intPreferencesKey("min_buffer_ms")
    private val maxBufferMsKey = intPreferencesKey("max_buffer_ms")
    private val bufferForPlaybackMsKey = intPreferencesKey("buffer_for_playback_ms")
    private val bufferForPlaybackAfterRebufferMsKey = intPreferencesKey("buffer_for_playback_after_rebuffer_ms")
    private val targetBufferSizeMbKey = intPreferencesKey("target_buffer_size_mb")
    private val backBufferDurationMsKey = intPreferencesKey("back_buffer_duration_ms")
    private val retainBackBufferFromKeyframeKey = booleanPreferencesKey("retain_back_buffer_from_keyframe")

    private val migrationLoadControlDefaultsAlignedDoneKey = booleanPreferencesKey("migration_load_control_defaults_aligned_done")
    private val migrationLoadControlDefaultsRetunedDoneKey =
        booleanPreferencesKey("migration_load_control_defaults_retuned_done")
    private val migrationLoadControlMinBufferRetunedDoneKey =
        booleanPreferencesKey("migration_load_control_min_buffer_retuned_done")
    private val migrationStreamSelectionDefaultsEnabledKey =
        migrationStreamSelectionDefaultsV2EnabledKey

    private fun applyAllPlayerMigrations(prefs: MutablePreferences) {
        val loadControlMigrated = prefs[migrationLoadControlDefaultsAlignedDoneKey] ?: false
        if (!loadControlMigrated) {
            val currentMin = prefs[minBufferMsKey]
            val currentMax = prefs[maxBufferMsKey]

            val legacyDefaultsDetected = (currentMin == null && currentMax == null) ||
                (currentMin == 15_000 && currentMax == 25_000)

            if (legacyDefaultsDetected) {
                prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
            }

            prefs[migrationLoadControlDefaultsAlignedDoneKey] = true
        }

        val loadControlRetuned = prefs[migrationLoadControlDefaultsRetunedDoneKey] ?: false
        if (!loadControlRetuned) {
            val currentMin = prefs[minBufferMsKey]
            val currentMax = prefs[maxBufferMsKey]
            val currentPlayback = prefs[bufferForPlaybackMsKey]
            val currentPlaybackAfterRebuffer = prefs[bufferForPlaybackAfterRebufferMsKey]
            val currentTargetBuffer = prefs[targetBufferSizeMbKey]

            val previousDefaultsDetected =
                currentMin == 50_000 &&
                    currentMax == 50_000 &&
                    currentPlayback == 2_500 &&
                    currentPlaybackAfterRebuffer == 5_000 &&
                    currentTargetBuffer == 0

            val olderDefaultsDetected =
                currentMin == 15_000 &&
                    currentMax == 25_000

            if (previousDefaultsDetected || olderDefaultsDetected) {
                prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
                prefs[bufferForPlaybackMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                prefs[bufferForPlaybackAfterRebufferMsKey] =
                    BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
            }

            prefs[migrationLoadControlDefaultsRetunedDoneKey] = true
        }

        val minBufferRetuned = prefs[migrationLoadControlMinBufferRetunedDoneKey] ?: false
        if (!minBufferRetuned) {
            val currentMin = prefs[minBufferMsKey]
            val currentMax = prefs[maxBufferMsKey]
            val currentPlayback = prefs[bufferForPlaybackMsKey]
            val currentPlaybackAfterRebuffer = prefs[bufferForPlaybackAfterRebufferMsKey]
            val currentTargetBuffer = prefs[targetBufferSizeMbKey]
            val currentBackBuffer = prefs[backBufferDurationMsKey]
            val currentRetainBackBuffer = prefs[retainBackBufferFromKeyframeKey]

            val previousRetunedDefaultsDetected =
                currentMin == 50_000 &&
                    currentMax == 50_000 &&
                    currentPlayback == BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS &&
                    currentPlaybackAfterRebuffer ==
                    BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS &&
                    currentTargetBuffer == BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB &&
                    (currentBackBuffer == null ||
                        currentBackBuffer == BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS) &&
                    (currentRetainBackBuffer == null || !currentRetainBackBuffer)

            if (previousRetunedDefaultsDetected) {
                prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
            }

            prefs[migrationLoadControlMinBufferRetunedDoneKey] = true
        }

        applyPlayerSettingsMigrations(prefs)

        val min = prefs[minBufferMsKey]
        val max = prefs[maxBufferMsKey]
        if (min != null && max != null && max < min) {
            prefs[maxBufferMsKey] = min
        }

        val preferredAudioLanguage = prefs[preferredAudioLanguageKey]
        if (preferredAudioLanguage != null) {
            val normalizedPreferredAudioLanguage =
                normalizeSelectableLanguageCode(preferredAudioLanguage)
            if (normalizedPreferredAudioLanguage != preferredAudioLanguage) {
                prefs[preferredAudioLanguageKey] = normalizedPreferredAudioLanguage
            }
        }

        val secondaryPreferredAudioLanguage = prefs[secondaryPreferredAudioLanguageKey]
        if (secondaryPreferredAudioLanguage != null) {
            val normalizedSecondaryPreferredAudioLanguage =
                normalizeSecondaryAudioLanguageCode(secondaryPreferredAudioLanguage)
            if (normalizedSecondaryPreferredAudioLanguage != secondaryPreferredAudioLanguage) {
                if (normalizedSecondaryPreferredAudioLanguage != null) {
                    prefs[secondaryPreferredAudioLanguageKey] = normalizedSecondaryPreferredAudioLanguage
                } else {
                    prefs.remove(secondaryPreferredAudioLanguageKey)
                }
            }
        }

        val preferredSubtitleLanguage = prefs[subtitlePreferredLanguageKey]
        if (preferredSubtitleLanguage != null) {
            val normalizedPreferredSubtitleLanguage =
                normalizeSelectableLanguageCode(preferredSubtitleLanguage)
            if (normalizedPreferredSubtitleLanguage != preferredSubtitleLanguage) {
                prefs[subtitlePreferredLanguageKey] = normalizedPreferredSubtitleLanguage
            }
        }

        val secondarySubtitleLanguage = prefs[subtitleSecondaryLanguageKey]
        if (secondarySubtitleLanguage != null) {
            val normalizedSecondarySubtitleLanguage =
                normalizeSelectableLanguageCode(secondarySubtitleLanguage)
            if (normalizedSecondarySubtitleLanguage != secondarySubtitleLanguage) {
                prefs[subtitleSecondaryLanguageKey] = normalizedSecondarySubtitleLanguage
            }
        }
    }

    /**
     * Flow of current player settings
     */
    val playerSettings: Flow<PlayerSettings> = profileFlow { prefs ->
            PlayerSettings(
                playerPreference = prefs[playerPreferenceKey]?.let {
                    runCatching { PlayerPreference.valueOf(it) }.getOrDefault(PlayerPreference.INTERNAL)
                } ?: PlayerPreference.INTERNAL,
                preferredExternalPlayerPackageName = prefs[preferredExternalPlayerPackageNameKey]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
                decoderPriority = prefs[decoderPriorityKey] ?: 1,
                tunnelingEnabled = prefs[tunnelingEnabledKey] ?: false,
                dynamicVideoSchedulingEnabled = prefs[dynamicVideoSchedulingEnabledKey] ?: false,
                skipSilence = prefs[skipSilenceKey] ?: false,
                preferredAudioLanguage = normalizeSelectableLanguageCode(
                    prefs[preferredAudioLanguageKey] ?: AudioLanguageOption.ORIGINAL
                ),
                secondaryPreferredAudioLanguage = prefs[secondaryPreferredAudioLanguageKey]
                    ?.let(::normalizeSecondaryAudioLanguageCode),
                loadingOverlayEnabled = prefs[loadingOverlayEnabledKey] ?: true,
                pauseOverlayEnabled = prefs[pauseOverlayEnabledKey] ?: true,
                osdClockEnabled = prefs[osdClockEnabledKey] ?: true,
                skipIntroEnabled = prefs[skipIntroEnabledKey] ?: true,
                experimentalDv7HevcBaseLayerEnabled =
                    prefs[experimentalDv7HevcBaseLayerEnabledKey] ?: true,
                experimentalDv7ToDv81Enabled =
                    if (prefs[experimentalDv7HevcBaseLayerEnabledKey] != false) {
                        false
                    } else {
                        prefs[experimentalDv7ToDv81EnabledKey] ?: false
                    },
                experimentalDtsIecPassthroughEnabled =
                    prefs[experimentalDtsIecPassthroughEnabledKey] ?: false,
                iecPackerAc3PassthroughEnabled =
                    prefs[iecPackerAc3PassthroughEnabledKey] ?: true,
                iecPackerAc3TranscodeEnabled =
                    prefs[iecPackerAc3TranscodeEnabledKey] ?: false,
                iecPackerEac3PassthroughEnabled =
                    prefs[iecPackerEac3PassthroughEnabledKey] ?: true,
                iecPackerDtsPassthroughEnabled =
                    prefs[iecPackerDtsPassthroughEnabledKey] ?: true,
                iecPackerTruehdPassthroughEnabled =
                    prefs[iecPackerTruehdPassthroughEnabledKey] ?: true,
                iecPackerDtshdPassthroughEnabled =
                    prefs[iecPackerDtshdPassthroughEnabledKey] ?: true,
                iecPackerDtshdCoreFallbackEnabled =
                    prefs[iecPackerDtshdCoreFallbackEnabledKey] ?: true,
                iecPackerAudioConfig =
                    prefs[iecPackerAudioConfigKey] ?: 0,
                iecPackerAudioDevice =
                    prefs[iecPackerAudioDeviceKey] ?: "",
                iecPackerPassthroughDevice =
                    prefs[iecPackerPassthroughDeviceKey] ?: "",
                iecPackerMaxPcmChannelLayout = IecPackerChannelLayout.fromStoredValue(
                    prefs[iecPackerMaxPcmChannelLayoutKey]
                ),
                fireOsCompatibilityFallbackEnabled =
                    prefs[fireOsCompatibilityFallbackEnabledKey] ?: false,
                fireOsIecSuperviseAudioDelayEnabled =
                    prefs[fireOsIecSuperviseAudioDelayEnabledKey] ?: false,
                fireOsIecVerboseLoggingEnabled =
                    prefs[fireOsIecVerboseLoggingEnabledKey] ?: false,
                experimentalDv5ToDv81Enabled = prefs[experimentalDv5ToDv81EnabledKey] ?: false,
                experimentalDv5ToneMapToSdrEnabled =
                    prefs[experimentalDv5ToneMapToSdrEnabledKey] ?: false,
                experimentalDv5HardwareToneMapToSdrEnabled =
                    prefs[experimentalDv5HardwareToneMapToSdrEnabledKey] ?: false,
                experimentalDv5HardwareToneMapCpuFallbackEnabled =
                    prefs[experimentalDv5HardwareToneMapCpuFallbackEnabledKey] ?: false,
                experimentalDv7ToDv81PreserveMappingEnabled =
                    if (prefs[experimentalDv7HevcBaseLayerEnabledKey] != false) {
                        false
                    } else {
                        prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] ?: false
                    },
                frameRateMatchingMode = prefs[frameRateMatchingModeKey]?.let {
                    runCatching { FrameRateMatchingMode.valueOf(it) }.getOrNull()
                } ?: if (prefs[frameRateMatchingKey] == true) {
                    FrameRateMatchingMode.START_STOP
                } else {
                    FrameRateMatchingMode.OFF
                },
                resolutionMatchingEnabled = prefs[resolutionMatchingEnabledKey] ?: false,
                streamAutoPlayMode = prefs[streamAutoPlayModeKey]?.let {
                    runCatching { StreamAutoPlayMode.valueOf(it) }.getOrDefault(StreamAutoPlayMode.MANUAL)
                } ?: StreamAutoPlayMode.MANUAL,
                streamAutoPlaySource = prefs[streamAutoPlaySourceKey]?.let {
                    runCatching { StreamAutoPlaySource.valueOf(it) }.getOrDefault(StreamAutoPlaySource.ALL_SOURCES)
                } ?: StreamAutoPlaySource.ALL_SOURCES,
                trackingProvider = prefs[trackingProviderKey]?.let {
                    runCatching { TrackingProvider.valueOf(it) }.getOrDefault(TrackingProvider.TRAKT)
                } ?: TrackingProvider.TRAKT,
                streamAutoPlaySelectedAddons = prefs[streamAutoPlaySelectedAddonsKey] ?: emptySet(),
                streamAutoPlayRegex = prefs[streamAutoPlayRegexKey] ?: "",
                streamAutoPlayNextEpisodeEnabled = prefs[streamAutoPlayNextEpisodeEnabledKey] ?: false,
                streamAutoPlayPreferBingeGroupForNextEpisode =
                    prefs[streamAutoPlayPreferBingeGroupForNextEpisodeKey] ?: true,
                deterministicAutoplayEnabled = prefs[deterministicAutoplayEnabledKey] ?: true,
                manualBitrateLimitMbps = normalizeManualBitrateLimit(
                    prefs[manualBitrateLimitMbpsKey] ?: 40.0
                ),
                nextEpisodeThresholdMode = prefs[nextEpisodeThresholdModeKey]?.let {
                    runCatching { NextEpisodeThresholdMode.valueOf(it) }.getOrDefault(NextEpisodeThresholdMode.PERCENTAGE)
                } ?: NextEpisodeThresholdMode.PERCENTAGE,
                nextEpisodeThresholdPercent = normalizeHalfStep(
                    value = prefs[nextEpisodeThresholdPercentKey]
                        ?: prefs[nextEpisodeThresholdPercentLegacyKey]?.toFloat()
                        ?: 99f,
                    min = 97f,
                    max = 99.5f
                ),
                nextEpisodeThresholdMinutesBeforeEnd = normalizeHalfStep(
                    value = prefs[nextEpisodeThresholdMinutesBeforeEndKey]
                        ?: prefs[nextEpisodeThresholdMinutesBeforeEndLegacyKey]?.toFloat()
                        ?: 2f,
                    min = 1f,
                    max = 3.5f
                ),
                streamReuseLastLinkEnabled = prefs[streamReuseLastLinkEnabledKey] ?: false,
                streamReuseLastLinkCacheHours = (prefs[streamReuseLastLinkCacheHoursKey] ?: 24).coerceIn(1, 168),
                uniformStreamFormattingEnabled =
                    uniformStreamFormattingEnabledFromPreferences(prefs),
                syncedFormatterTemplate = SyncedFormatterTemplateSettings(
                    enabled = prefs[syncedFormatterEnabledKey] ?: true,
                    selectedTemplateId = prefs[syncedFormatterSelectedTemplateIdKey] ?: "universal",
                    customTemplateLabel = prefs[syncedFormatterCustomTemplateLabelKey],
                    customNameTemplate = prefs[syncedFormatterCustomNameTemplateKey],
                    customDescriptionTemplate = prefs[syncedFormatterCustomDescriptionTemplateKey],
                    customBadgeRowTemplate = prefs[syncedFormatterCustomBadgeRowTemplateKey]
                ),
                groupStreamsAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = prefs[deduplicateGroupedStreamsEnabledKey] ?: true,
                serviceWrapEnabled = prefs[serviceWrapEnabledKey] ?: false,
                shadowAutoplayDataCollectionEnabled = prefs[shadowAutoplayDataCollectionEnabledKey] ?: false,
                filterWebDolbyVisionStreamsEnabled = prefs[filterWebDolbyVisionStreamsEnabledKey] ?: false,
                filterEpisodeMismatchStreamsEnabled = prefs[filterEpisodeMismatchStreamsEnabledKey] ?: true,
                filterMovieYearMismatchStreamsEnabled = prefs[filterMovieYearMismatchStreamsEnabledKey] ?: true,
                subtitleOrganizationMode = parseSubtitleOrganizationMode(prefs[subtitleOrganizationModeKey]),
                vodCacheSizeMode = parseVodCacheSizeMode(prefs[vodCacheSizeModeKey]),
                vodCacheSizeMb = (prefs[vodCacheSizeMbKey] ?: PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB)
                    .coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB),
                vodCacheWarmAheadEnabled =
                    prefs[vodCacheWarmAheadEnabledKey] ?: PlayerSettings.DEFAULT_VOD_CACHE_WARM_AHEAD_ENABLED,
                progressivePlaybackDiskMode = parseProgressivePlaybackDiskMode(
                    prefs[progressivePlaybackDiskModeKey]
                ),
                diskSpoolSizeMb = (prefs[diskSpoolSizeMbKey] ?: PlayerSettings.DEFAULT_DISK_SPOOL_SIZE_MB)
                    .coerceIn(PlayerSettings.MIN_DISK_SPOOL_SIZE_MB, PlayerSettings.MAX_DISK_SPOOL_SIZE_MB),
                diskSpoolStartupBufferMb = (prefs[diskSpoolStartupBufferMbKey]
                    ?: PlayerSettings.DEFAULT_DISK_SPOOL_STARTUP_BUFFER_MB)
                    .coerceIn(
                        PlayerSettings.MIN_DISK_SPOOL_STARTUP_BUFFER_MB,
                        (prefs[diskSpoolSizeMbKey] ?: PlayerSettings.DEFAULT_DISK_SPOOL_SIZE_MB)
                            .coerceIn(
                                PlayerSettings.MIN_DISK_SPOOL_SIZE_MB,
                                PlayerSettings.MAX_DISK_SPOOL_SIZE_MB
                            )
                    ),
                diskSpoolStorageLocation = parseDiskSpoolStorageLocation(
                    prefs[diskSpoolStorageLocationKey]
                ),
                spoolStorageProbeResultJson = prefs[spoolStorageProbeResultJsonKey]
                    ?.takeIf { SpoolStorageProbeResult.fromJsonOrNull(it) != null },
                useParallelConnections =
                    prefs[useParallelConnectionsKey] ?: PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS,
                parallelConnectionCount = (prefs[parallelConnectionCountKey]
                    ?: PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT).coerceIn(
                    PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                    PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                ),
                parallelChunkSizeMb = (prefs[parallelChunkSizeMbKey]
                    ?: PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB).coerceIn(
                    PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                    PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                ),
                addonSubtitleStartupMode = parseAddonSubtitleStartupMode(prefs[addonSubtitleStartupModeKey]),
                enableBufferLogs = prefs[enableBufferLogsKey] ?: false,
                traktScrobbleApiLoggingEnabled = prefs[traktScrobbleApiLoggingEnabledKey] ?: false,
                subtitleStyle = SubtitleStyleSettings(
                    preferredLanguage = normalizeSelectableLanguageCode(
                        prefs[subtitlePreferredLanguageKey] ?: "en"
                    ),
                    secondaryPreferredLanguage = prefs[subtitleSecondaryLanguageKey]
                        ?.let(::normalizeSelectableLanguageCode),
                    size = prefs[subtitleSizeKey] ?: 100,
                    verticalOffset = prefs[subtitleVerticalOffsetKey] ?: 5,
                    bold = prefs[subtitleBoldKey] ?: false,
                    textColor = prefs[subtitleTextColorKey] ?: Color.White.toArgb(),
                    backgroundColor = prefs[subtitleBackgroundColorKey] ?: Color.Transparent.toArgb(),
                    outlineEnabled = prefs[subtitleOutlineEnabledKey] ?: true,
                    outlineColor = prefs[subtitleOutlineColorKey] ?: Color.Black.toArgb(),
                    outlineWidth = prefs[subtitleOutlineWidthKey] ?: 2
                ),
                bufferSettings = BufferSettings(
                    minBufferMs = prefs[minBufferMsKey] ?: BufferSettings.DEFAULT_MIN_BUFFER_MS,
                    maxBufferMs = prefs[maxBufferMsKey] ?: BufferSettings.DEFAULT_MAX_BUFFER_MS,
                    bufferForPlaybackMs = prefs[bufferForPlaybackMsKey]
                        ?: BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    bufferForPlaybackAfterRebufferMs = prefs[bufferForPlaybackAfterRebufferMsKey]
                        ?: BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    targetBufferSizeMb = prefs[targetBufferSizeMbKey]?.coerceAtLeast(0)
                        ?: BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB,
                    backBufferDurationMs = prefs[backBufferDurationMsKey]
                        ?: BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS,
                    retainBackBufferFromKeyframe = prefs[retainBackBufferFromKeyframeKey] ?: false
                )
            )
    }

    internal val spoolStorageProbeResult: Flow<SpoolStorageProbeResult?> = profileFlow { prefs ->
        SpoolStorageProbeResult.fromJsonOrNull(prefs[spoolStorageProbeResultJsonKey])
    }

    // Player preference setter

    suspend fun setPlayerPreference(preference: PlayerPreference) {
        store().edit { prefs ->
            prefs[playerPreferenceKey] = preference.name
        }
    }

    suspend fun setPreferredExternalPlayerPackageName(packageName: String?) {
        store().edit { prefs ->
            val normalizedPackageName = packageName?.trim()?.takeIf { it.isNotBlank() }
            if (normalizedPackageName == null) {
                prefs.remove(preferredExternalPlayerPackageNameKey)
            } else {
                prefs[preferredExternalPlayerPackageNameKey] = normalizedPackageName
            }
        }
    }

    // Audio settings setters

    suspend fun setDecoderPriority(priority: Int) {
        store().edit { prefs ->
            prefs[decoderPriorityKey] = priority.coerceIn(0, 2)
        }
    }

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[tunnelingEnabledKey] = enabled
        }
    }

    suspend fun setDynamicVideoSchedulingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[dynamicVideoSchedulingEnabledKey] = enabled
        }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        store().edit { prefs ->
            prefs[skipSilenceKey] = enabled
        }
    }

    suspend fun setPreferredAudioLanguage(language: String) {
        store().edit { prefs ->
            prefs[preferredAudioLanguageKey] = normalizeSelectableLanguageCode(
                language.ifBlank { AudioLanguageOption.ORIGINAL }
            )
        }
    }

    suspend fun setSecondaryPreferredAudioLanguage(language: String?) {
        store().edit { prefs ->
            val normalizedLanguage = language
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeSecondaryAudioLanguageCode)
            if (normalizedLanguage != null) {
                prefs[secondaryPreferredAudioLanguageKey] = normalizedLanguage
            } else {
                prefs.remove(secondaryPreferredAudioLanguageKey)
            }
        }
    }

    suspend fun setPauseOverlayEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[pauseOverlayEnabledKey] = enabled
        }
    }

    suspend fun setOsdClockEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[osdClockEnabledKey] = enabled
        }
    }

    suspend fun setSkipIntroEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[skipIntroEnabledKey] = enabled
        }
    }

    suspend fun setLoadingOverlayEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[loadingOverlayEnabledKey] = enabled
        }
    }

    suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        store().edit { prefs ->
            prefs[frameRateMatchingModeKey] = mode.name
            prefs[frameRateMatchingKey] = mode != FrameRateMatchingMode.OFF
        }
    }

    suspend fun setResolutionMatchingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[resolutionMatchingEnabledKey] = enabled
        }
    }

    suspend fun setFrameRateMatching(enabled: Boolean) {
        setFrameRateMatchingMode(
            if (enabled) FrameRateMatchingMode.START_STOP else FrameRateMatchingMode.OFF
        )
    }

    suspend fun setEnableBufferLogs(enabled: Boolean) {
        store().edit { prefs ->
            prefs[enableBufferLogsKey] = enabled
        }
    }

    suspend fun setTraktScrobbleApiLoggingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[traktScrobbleApiLoggingEnabledKey] = enabled
        }
    }

    suspend fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        store().edit { prefs ->
            prefs[streamAutoPlayModeKey] = mode.name
        }
    }

    suspend fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        store().edit { prefs ->
            prefs[streamAutoPlaySourceKey] = source.name
        }
    }

    suspend fun setTrackingProvider(provider: TrackingProvider) {
        store().edit { prefs ->
            prefs[trackingProviderKey] = provider.name
        }
    }

    suspend fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        store().edit { prefs ->
            prefs[streamAutoPlaySelectedAddonsKey] = addons
        }
    }

    suspend fun setStreamAutoPlayRegex(regex: String) {
        store().edit { prefs ->
            prefs[streamAutoPlayRegexKey] = regex.trim()
        }
    }

    suspend fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[streamAutoPlayNextEpisodeEnabledKey] = enabled
        }
    }

    suspend fun setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[streamAutoPlayPreferBingeGroupForNextEpisodeKey] = enabled
        }
    }

    suspend fun setDeterministicAutoplayEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[deterministicAutoplayEnabledKey] = enabled
        }
    }

    suspend fun setManualBitrateLimitMbps(mbps: Double) {
        store().edit { prefs ->
            prefs[manualBitrateLimitMbpsKey] = normalizeManualBitrateLimit(mbps)
        }
    }

    private fun normalizeManualBitrateLimit(mbps: Double): Double {
        if (!mbps.isFinite()) return 40.0
        return mbps.coerceIn(5.0, 200.0)
    }

    suspend fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        store().edit { prefs ->
            prefs[nextEpisodeThresholdModeKey] = mode.name
        }
    }

    suspend fun setNextEpisodeThresholdPercent(percent: Float) {
        store().edit { prefs ->
            prefs[nextEpisodeThresholdPercentKey] = normalizeHalfStep(
                value = percent,
                min = 97f,
                max = 99.5f
            )
        }
    }

    suspend fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        store().edit { prefs ->
            prefs[nextEpisodeThresholdMinutesBeforeEndKey] = normalizeHalfStep(
                value = minutes,
                min = 1f,
                max = 3.5f
            )
        }
    }

    private fun normalizeHalfStep(value: Float, min: Float, max: Float): Float {
        val clamped = value.coerceIn(min, max)
        return (clamped * 2f).roundToInt() / 2f
    }

    suspend fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[streamReuseLastLinkEnabledKey] = enabled
        }
    }

    suspend fun setStreamReuseLastLinkCacheHours(hours: Int) {
        store().edit { prefs ->
            prefs[streamReuseLastLinkCacheHoursKey] = hours.coerceIn(1, 168)
        }
    }

    suspend fun setUniformStreamFormattingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[uniformStreamFormattingEnabledKey] = true
        }
    }

    suspend fun setSyncedFormatterEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[syncedFormatterEnabledKey] = enabled
        }
    }

    suspend fun setSyncedFormatterSelectedTemplateId(templateId: String) {
        store().edit { prefs ->
            prefs[syncedFormatterSelectedTemplateIdKey] = templateId.trim().ifBlank { "universal" }
        }
    }

    suspend fun setSyncedFormatterCustomTemplate(
        label: String?,
        nameTemplate: String?,
        descriptionTemplate: String?,
        badgeRowTemplate: String? = null
    ) {
        store().edit { prefs ->
            val normalizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedName = nameTemplate?.takeIf { it.isNotBlank() }
            val normalizedDescription = descriptionTemplate?.takeIf { it.isNotBlank() }
            val normalizedBadgeRow = badgeRowTemplate?.takeIf { it.isNotBlank() }
            if (normalizedLabel != null && normalizedName != null && normalizedDescription != null) {
                prefs[syncedFormatterCustomTemplateLabelKey] = normalizedLabel
                prefs[syncedFormatterCustomNameTemplateKey] = normalizedName
                prefs[syncedFormatterCustomDescriptionTemplateKey] = normalizedDescription
                if (normalizedBadgeRow != null) {
                    prefs[syncedFormatterCustomBadgeRowTemplateKey] = normalizedBadgeRow
                } else {
                    prefs.remove(syncedFormatterCustomBadgeRowTemplateKey)
                }
            } else {
                prefs.remove(syncedFormatterCustomTemplateLabelKey)
                prefs.remove(syncedFormatterCustomNameTemplateKey)
                prefs.remove(syncedFormatterCustomDescriptionTemplateKey)
                prefs.remove(syncedFormatterCustomBadgeRowTemplateKey)
            }
        }
    }

    suspend fun setGroupStreamsAcrossAddonsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[groupStreamsAcrossAddonsEnabledKey] = true
        }
    }

    suspend fun setDeduplicateGroupedStreamsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[deduplicateGroupedStreamsEnabledKey] = enabled
        }
    }

    suspend fun setServiceWrapEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[serviceWrapEnabledKey] = enabled
        }
    }

    suspend fun setShadowAutoplayDataCollectionEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[shadowAutoplayDataCollectionEnabledKey] = enabled
        }
    }

    suspend fun setFilterWebDolbyVisionStreamsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[filterWebDolbyVisionStreamsEnabledKey] = enabled
        }
    }

    suspend fun setFilterEpisodeMismatchStreamsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[filterEpisodeMismatchStreamsEnabledKey] = enabled
        }
    }

    suspend fun setFilterMovieYearMismatchStreamsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[filterMovieYearMismatchStreamsEnabledKey] = enabled
        }
    }

    suspend fun setSubtitleOrganizationMode(mode: SubtitleOrganizationMode) {
        store().edit { prefs ->
            prefs[subtitleOrganizationModeKey] = SubtitleOrganizationMode.BY_LANGUAGE.name
        }
    }

    suspend fun setAddonSubtitleStartupMode(mode: AddonSubtitleStartupMode) {
        store().edit { prefs ->
            prefs[addonSubtitleStartupModeKey] = mode.name
        }
    }

    private fun parseSubtitleOrganizationMode(value: String?): SubtitleOrganizationMode {
        return SubtitleOrganizationMode.BY_LANGUAGE
    }

    private fun parseAddonSubtitleStartupMode(value: String?): AddonSubtitleStartupMode {
        return when (value) {
            null, "ALL_SUBTITLES" -> AddonSubtitleStartupMode.ALL_SUBTITLES
            "PREFERRED_ONLY" -> AddonSubtitleStartupMode.PREFERRED_ONLY
            "FAST_STARTUP" -> AddonSubtitleStartupMode.FAST_STARTUP
            else -> AddonSubtitleStartupMode.ALL_SUBTITLES
        }
    }

    private fun parseVodCacheSizeMode(value: String?): VodCacheSizeMode {
        return when (value?.trim()?.uppercase()) {
            "ON", "AUTO", "MANUAL" -> VodCacheSizeMode.ON
            "OFF" -> VodCacheSizeMode.OFF
            else -> PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
        }
    }

    private fun parseProgressivePlaybackDiskMode(value: String?): ProgressivePlaybackDiskMode {
        return when (value?.trim()?.uppercase()) {
            "SPOOL" -> ProgressivePlaybackDiskMode.SPOOL
            "OFF" -> ProgressivePlaybackDiskMode.OFF
            else -> ProgressivePlaybackDiskMode.OFF
        }
    }

    private fun parseDiskSpoolStorageLocation(value: String?): DiskSpoolStorageLocation {
        return when (value?.trim()?.uppercase()) {
            "EXTERNAL" -> DiskSpoolStorageLocation.EXTERNAL
            "BUILTIN" -> DiskSpoolStorageLocation.BUILTIN
            else -> PlayerSettings.DEFAULT_DISK_SPOOL_STORAGE_LOCATION
        }
    }

    private fun normalizeSelectableLanguageCode(language: String): String {
        val code = language.trim().lowercase()
        return when (code) {
            "pt-br", "pt_br", "br", "pob" -> "pt-br"
            "pt-pt", "pt_pt", "por" -> "pt"
            "forced", "force", "forc" -> SUBTITLE_LANGUAGE_FORCED
            AudioLanguageOption.ORIGINAL -> AudioLanguageOption.ORIGINAL
            else -> code
        }
    }

    private fun normalizeSecondaryAudioLanguageCode(language: String): String? {
        val normalized = normalizeSelectableLanguageCode(language)
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            AudioLanguageOption.ORIGINAL,
            SUBTITLE_LANGUAGE_FORCED -> null
            else -> normalized
        }
    }

    suspend fun setExperimentalDv7ToDv81Enabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv7ToDv81EnabledKey] = enabled
            if (enabled) {
                prefs[experimentalDv7HevcBaseLayerEnabledKey] = false
            } else {
                prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] = false
            }
        }
    }

    suspend fun setExperimentalDv7HevcBaseLayerEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv7HevcBaseLayerEnabledKey] = enabled
            if (enabled) {
                prefs[experimentalDv7ToDv81EnabledKey] = false
                prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] = false
            }
        }
    }

    suspend fun setExperimentalDtsIecPassthroughEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDtsIecPassthroughEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerAc3PassthroughEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[iecPackerAc3PassthroughEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerAc3TranscodeEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[iecPackerAc3TranscodeEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerEac3PassthroughEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[iecPackerEac3PassthroughEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerDtsPassthroughEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[iecPackerDtsPassthroughEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerTruehdPassthroughEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[iecPackerTruehdPassthroughEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerDtshdPassthroughEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[iecPackerDtshdPassthroughEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerDtshdCoreFallbackEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[iecPackerDtshdCoreFallbackEnabledKey] = enabled
        }
    }

    suspend fun setIecPackerAudioConfig(config: Int) {
        store().edit { prefs ->
            prefs[iecPackerAudioConfigKey] = config
        }
    }

    suspend fun setIecPackerAudioDevice(device: String) {
        store().edit { prefs ->
            prefs[iecPackerAudioDeviceKey] = device
        }
    }

    suspend fun setIecPackerPassthroughDevice(device: String) {
        store().edit { prefs ->
            prefs[iecPackerPassthroughDeviceKey] = device
        }
    }

    suspend fun setIecPackerMaxPcmChannelLayout(layout: IecPackerChannelLayout) {
        store().edit { prefs ->
            prefs[iecPackerMaxPcmChannelLayoutKey] = layout.storedValue
        }
    }

    suspend fun setFireOsCompatibilityFallbackEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[fireOsCompatibilityFallbackEnabledKey] = enabled
        }
    }

    suspend fun setFireOsIecVerboseLoggingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[fireOsIecVerboseLoggingEnabledKey] = enabled
        }
    }

    suspend fun setFireOsIecSuperviseAudioDelayEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[fireOsIecSuperviseAudioDelayEnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDv5ToDv81Enabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv5ToDv81EnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDv5ToneMapToSdrEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv5ToneMapToSdrEnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDv5HardwareToneMapToSdrEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv5HardwareToneMapToSdrEnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDv5HardwareToneMapCpuFallbackEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv5HardwareToneMapCpuFallbackEnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDv7ToDv81PreserveMappingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] =
                enabled &&
                    prefs[experimentalDv7ToDv81EnabledKey] != false &&
                    prefs[experimentalDv7HevcBaseLayerEnabledKey] != true
        }
    }

    // Subtitle style settings functions

    suspend fun setSubtitlePreferredLanguage(language: String) {
        store().edit { prefs ->
            prefs[subtitlePreferredLanguageKey] = normalizeSelectableLanguageCode(
                language.ifBlank { "en" }
            )
        }
    }

    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        store().edit { prefs ->
            val normalizedLanguage = language
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeSelectableLanguageCode)
            if (normalizedLanguage != null) {
                prefs[subtitleSecondaryLanguageKey] = normalizedLanguage
            } else {
                prefs.remove(subtitleSecondaryLanguageKey)
            }
        }
    }

    suspend fun setSubtitleSize(size: Int) {
        store().edit { prefs ->
            prefs[subtitleSizeKey] = size.coerceIn(50, 200)
        }
    }

    suspend fun setSubtitleVerticalOffset(offset: Int) {
        store().edit { prefs ->
            prefs[subtitleVerticalOffsetKey] = offset.coerceIn(-20, 50)
        }
    }

    suspend fun setSubtitleBold(bold: Boolean) {
        store().edit { prefs ->
            prefs[subtitleBoldKey] = bold
        }
    }

    suspend fun setSubtitleTextColor(color: Int) {
        store().edit { prefs ->
            prefs[subtitleTextColorKey] = color
        }
    }

    suspend fun setSubtitleBackgroundColor(color: Int) {
        store().edit { prefs ->
            prefs[subtitleBackgroundColorKey] = color
        }
    }

    suspend fun setSubtitleOutlineEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[subtitleOutlineEnabledKey] = enabled
        }
    }

    suspend fun setSubtitleOutlineColor(color: Int) {
        store().edit { prefs ->
            prefs[subtitleOutlineColorKey] = color
        }
    }

    suspend fun setSubtitleOutlineWidth(width: Int) {
        store().edit { prefs ->
            prefs[subtitleOutlineWidthKey] = width.coerceIn(1, 5)
        }
    }

    // Buffer settings functions

    suspend fun setBufferMinBufferMs(ms: Int) {
        store().edit { prefs ->
            val newMin = ms.coerceIn(5_000, 120_000)
            prefs[minBufferMsKey] = newMin
            val currentMax = prefs[maxBufferMsKey] ?: BufferSettings.DEFAULT_MAX_BUFFER_MS
            if (currentMax < newMin) {
                prefs[maxBufferMsKey] = newMin
            }
        }
    }

    suspend fun setBufferMaxBufferMs(ms: Int) {
        store().edit { prefs ->
            val currentMin = prefs[minBufferMsKey] ?: BufferSettings.DEFAULT_MIN_BUFFER_MS
            prefs[maxBufferMsKey] = ms.coerceIn(currentMin, 120_000)
        }
    }

    suspend fun setBufferForPlaybackMs(ms: Int) {
        store().edit { prefs ->
            prefs[bufferForPlaybackMsKey] = ms.coerceIn(1_000, 30_000)
        }
    }

    suspend fun setBufferForPlaybackAfterRebufferMs(ms: Int) {
        store().edit { prefs ->
            prefs[bufferForPlaybackAfterRebufferMsKey] = ms.coerceIn(1_000, 60_000)
        }
    }

    suspend fun setBufferTargetSizeMb(mb: Int) {
        store().edit { prefs ->
            prefs[targetBufferSizeMbKey] = mb.coerceAtLeast(0)
        }
    }

    suspend fun setBufferBackBufferDurationMs(ms: Int) {
        store().edit { prefs ->
            prefs[backBufferDurationMsKey] = ms.coerceIn(0, 120_000)
        }
    }

    suspend fun setBufferRetainBackBufferFromKeyframe(retain: Boolean) {
        store().edit { prefs ->
            prefs[retainBackBufferFromKeyframeKey] = retain
        }
    }

    suspend fun resetBufferSettingsToDefaults() {
        store().edit { prefs ->
            prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
            prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
            prefs[bufferForPlaybackMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
            prefs[bufferForPlaybackAfterRebufferMsKey] =
                BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
            prefs[backBufferDurationMsKey] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
            prefs[retainBackBufferFromKeyframeKey] = false
        }
    }

    suspend fun resetNetworkSettingsToDefaults() {
        store().edit { prefs ->
            prefs[vodCacheSizeModeKey] = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE.name
            prefs[vodCacheSizeMbKey] = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB
            prefs[vodCacheWarmAheadEnabledKey] = PlayerSettings.DEFAULT_VOD_CACHE_WARM_AHEAD_ENABLED
            prefs[useParallelConnectionsKey] = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
            prefs[parallelConnectionCountKey] = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
            prefs[parallelChunkSizeMbKey] = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
        }
    }

    suspend fun setVodCacheSizeMode(mode: VodCacheSizeMode) {
        store().edit { prefs ->
            prefs[vodCacheSizeModeKey] = mode.name
            if (mode == VodCacheSizeMode.ON) {
                prefs[progressivePlaybackDiskModeKey] = ProgressivePlaybackDiskMode.OFF.name
            }
        }
    }

    suspend fun setVodCacheSizeMb(mb: Int) {
        store().edit { prefs ->
            val normalized = mb.coerceIn(
                PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                PlayerSettings.MAX_VOD_CACHE_SIZE_MB
            )
            prefs[vodCacheSizeMbKey] = normalized
        }
    }

    suspend fun setVodCacheWarmAheadEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[vodCacheWarmAheadEnabledKey] = enabled
        }
    }

    suspend fun setProgressivePlaybackDiskMode(mode: ProgressivePlaybackDiskMode) {
        store().edit { prefs ->
            prefs[progressivePlaybackDiskModeKey] = mode.name
            if (mode == ProgressivePlaybackDiskMode.SPOOL) {
                prefs[vodCacheSizeModeKey] = VodCacheSizeMode.OFF.name
            }
        }
    }

    suspend fun setDiskSpoolSizeMb(mb: Int) {
        store().edit { prefs ->
            val normalized = mb.coerceIn(
                PlayerSettings.MIN_DISK_SPOOL_SIZE_MB,
                PlayerSettings.MAX_DISK_SPOOL_SIZE_MB
            )
            prefs[diskSpoolSizeMbKey] = normalized
            val currentStartup = (prefs[diskSpoolStartupBufferMbKey]
                ?: PlayerSettings.DEFAULT_DISK_SPOOL_STARTUP_BUFFER_MB)
            if (currentStartup > normalized) {
                prefs[diskSpoolStartupBufferMbKey] = normalized
            }
        }
    }

    suspend fun setDiskSpoolStartupBufferMb(mb: Int) {
        store().edit { prefs ->
            val currentSpoolSize = (prefs[diskSpoolSizeMbKey] ?: PlayerSettings.DEFAULT_DISK_SPOOL_SIZE_MB)
                .coerceIn(PlayerSettings.MIN_DISK_SPOOL_SIZE_MB, PlayerSettings.MAX_DISK_SPOOL_SIZE_MB)
            prefs[diskSpoolStartupBufferMbKey] = mb.coerceIn(
                PlayerSettings.MIN_DISK_SPOOL_STARTUP_BUFFER_MB,
                currentSpoolSize
            )
        }
    }

    suspend fun setDiskSpoolStorageLocation(location: DiskSpoolStorageLocation) {
        store().edit { prefs ->
            val current = parseDiskSpoolStorageLocation(prefs[diskSpoolStorageLocationKey])
            if (current != location) {
                prefs.remove(spoolStorageProbeResultJsonKey)
            }
            prefs[diskSpoolStorageLocationKey] = location.name
        }
    }

    internal suspend fun setSpoolStorageProbeResult(result: SpoolStorageProbeResult?) {
        store().edit { prefs ->
            val json = result?.toJsonOrNull()
            if (json != null) {
                prefs[spoolStorageProbeResultJsonKey] = json
            } else {
                prefs.remove(spoolStorageProbeResultJsonKey)
            }
        }
    }

    internal suspend fun setSpoolStorageProbeResultJsonForTesting(rawJson: String) {
        store().edit { prefs ->
            prefs[spoolStorageProbeResultJsonKey] = rawJson
        }
    }

    suspend fun setUseParallelConnections(enabled: Boolean) {
        store().edit { prefs ->
            prefs[useParallelConnectionsKey] = enabled
        }
    }

    suspend fun setParallelConnectionCount(count: Int) {
        store().edit { prefs ->
            val normalized = count.coerceIn(
                PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
            )
            prefs[parallelConnectionCountKey] = normalized
        }
    }

    suspend fun setParallelChunkSizeMb(mb: Int) {
        store().edit { prefs ->
            val normalized = mb.coerceIn(
                PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
            )
            prefs[parallelChunkSizeMbKey] = normalized
        }
    }

    suspend fun updateMemorySettings(
        targetBufferSizeMb: Int? = null,
        vodCacheSizeMode: VodCacheSizeMode? = null,
        vodCacheSizeMb: Int? = null,
        vodCacheWarmAheadEnabled: Boolean? = null,
        useParallelConnections: Boolean? = null,
        parallelConnectionCount: Int? = null,
        parallelChunkSizeMb: Int? = null
    ) {
        store().edit { prefs ->
            targetBufferSizeMb?.let { prefs[targetBufferSizeMbKey] = it.coerceAtLeast(0) }
            vodCacheSizeMode?.let {
                prefs[vodCacheSizeModeKey] = it.name
            }
            vodCacheSizeMb?.let {
                val normalized = it.coerceIn(
                    PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                    PlayerSettings.MAX_VOD_CACHE_SIZE_MB
                )
                prefs[vodCacheSizeMbKey] = normalized
            }
            vodCacheWarmAheadEnabled?.let {
                prefs[vodCacheWarmAheadEnabledKey] = it
            }
            useParallelConnections?.let {
                prefs[useParallelConnectionsKey] = it
            }
            parallelConnectionCount?.let {
                val normalized = it.coerceIn(
                    PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                    PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                )
                prefs[parallelConnectionCountKey] = normalized
            }
            parallelChunkSizeMb?.let {
                val normalized = it.coerceIn(
                    PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                    PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                )
                prefs[parallelChunkSizeMbKey] = normalized
            }
        }
    }
}
