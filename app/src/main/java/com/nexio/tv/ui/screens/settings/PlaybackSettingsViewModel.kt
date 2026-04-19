package com.nexio.tv.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.IecPackerChannelLayout
import com.nexio.tv.data.local.InternalPlayerEngine
import com.nexio.tv.data.local.MpvHardwareDecodeMode
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.ProgressivePlaybackDiskMode
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.SubtitleOrganizationMode
import com.nexio.tv.data.local.TrailerSettings
import com.nexio.tv.data.local.TrailerSettingsDataStore
import com.nexio.tv.data.local.VodCacheSizeMode
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.data.repository.TrackingProviderState
import com.nexio.tv.data.repository.TrackingProviderStateRepository
import com.nexio.tv.core.player.AndroidFrameRateSettings
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnostic
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageResolver
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

typealias TrackingProviderSelectorState = TrackingProviderState

sealed class DiskSpoolStorageProbeUiState {
    object NotChecked : DiskSpoolStorageProbeUiState()
    object Running : DiskSpoolStorageProbeUiState()
    data class Failed(val message: String) : DiskSpoolStorageProbeUiState()
}

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val addonRepository: AddonRepository,
    trackingProviderStateRepository: TrackingProviderStateRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private companion object {
        const val TAG = "PlaybackSettingsVM"
    }

    val playerSettings: Flow<PlayerSettings> = playerSettingsDataStore.playerSettings
    val trailerSettings: Flow<TrailerSettings> = trailerSettingsDataStore.settings
    val streamDiagnosticsEnabled: Flow<Boolean> = debugSettingsDataStore.streamDiagnosticsEnabled
    val startupPerfTelemetryEnabled: Flow<Boolean> = debugSettingsDataStore.startupPerfTelemetryEnabled
    val diskSpoolDiagnosticsEnabled: Flow<Boolean> = debugSettingsDataStore.diskSpoolDiagnosticsEnabled
    val dolbyVisionDiagnosticsEnabled: Flow<Boolean> = debugSettingsDataStore.dolbyVisionDiagnosticsEnabled
    val trackingProviderSelectorState: Flow<TrackingProviderSelectorState> = trackingProviderStateRepository.state
    fun androidFrameRateStatus(): AndroidFrameRateSettings.Status {
        return AndroidFrameRateSettings.readStatus(context)
    }

    val installedAddonNames: Flow<List<String>> = addonRepository.getInstalledAddons().map { addons ->
        addons
            .filter { addon ->
                addon.resources.any { resource ->
                    resource.name.equals("stream", ignoreCase = true)
                }
            }
            .map { it.displayName }
            .distinct()
            .sorted()
    }
    private var diskSpoolStorageProbeJob: Job? = null
    private val diskSpoolStorageProbeGeneration = AtomicLong(0L)
    private val diskSpoolStorageProbeCommitMutex = Mutex()
    internal var diskSpoolStorageProbeRunnerForTesting: (suspend (File, () -> Boolean) -> SpoolStorageProbeResult)? = null
    internal var diskSpoolDirectoryResolverForTesting: ((Context, DiskSpoolStorageLocation) -> File?)? = null
    private val _diskSpoolStorageProbeUiState =
        MutableStateFlow<DiskSpoolStorageProbeUiState>(DiskSpoolStorageProbeUiState.NotChecked)
    val diskSpoolStorageProbeUiState: StateFlow<DiskSpoolStorageProbeUiState> =
        _diskSpoolStorageProbeUiState

    suspend fun setPlayerPreference(preference: PlayerPreference) {
        playerSettingsDataStore.setPlayerPreference(preference)
    }

    suspend fun setPreferredExternalPlayerPackageName(packageName: String?) {
        playerSettingsDataStore.setPreferredExternalPlayerPackageName(packageName)
    }

    suspend fun setInternalPlayerEngine(engine: InternalPlayerEngine) {
        playerSettingsDataStore.setInternalPlayerEngine(engine)
    }

    suspend fun setAutoSwitchInternalPlayerOnError(enabled: Boolean) {
        playerSettingsDataStore.setAutoSwitchInternalPlayerOnError(enabled)
    }

    suspend fun setMpvHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        playerSettingsDataStore.setMpvHardwareDecodeMode(mode)
    }

    // Audio settings

    suspend fun setDecoderPriority(priority: Int) {
        playerSettingsDataStore.setDecoderPriority(priority)
    }

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setTunnelingEnabled(enabled)
    }

    suspend fun setDynamicVideoSchedulingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setDynamicVideoSchedulingEnabled(enabled)
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        playerSettingsDataStore.setSkipSilence(enabled)
    }

    suspend fun setPreferredAudioLanguage(language: String) {
        playerSettingsDataStore.setPreferredAudioLanguage(language)
    }

    suspend fun setSecondaryPreferredAudioLanguage(language: String?) {
        playerSettingsDataStore.setSecondaryPreferredAudioLanguage(language)
    }

    suspend fun setLoadingOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setLoadingOverlayEnabled(enabled)
    }

    suspend fun setTrailerEnabled(enabled: Boolean) {
        trailerSettingsDataStore.setEnabled(enabled)
    }

    suspend fun setTrailerDelaySeconds(seconds: Int) {
        trailerSettingsDataStore.setDelaySeconds(seconds)
    }

    suspend fun setPauseOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setPauseOverlayEnabled(enabled)
    }

    suspend fun setOsdClockEnabled(enabled: Boolean) {
        playerSettingsDataStore.setOsdClockEnabled(enabled)
    }

    suspend fun setSkipIntroEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSkipIntroEnabled(enabled)
    }

    suspend fun setExperimentalDv7ToDv81Enabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv7ToDv81Enabled(enabled)
    }

    suspend fun setExperimentalDv7HevcBaseLayerEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv7HevcBaseLayerEnabled(enabled)
    }

    suspend fun setExperimentalDtsIecPassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDtsIecPassthroughEnabled(enabled)
    }

    suspend fun setIecPackerAc3PassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setIecPackerAc3PassthroughEnabled(enabled)
    }

    suspend fun setIecPackerEac3PassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setIecPackerEac3PassthroughEnabled(enabled)
    }

    suspend fun setIecPackerDtsPassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setIecPackerDtsPassthroughEnabled(enabled)
    }

    suspend fun setIecPackerTruehdPassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setIecPackerTruehdPassthroughEnabled(enabled)
    }

    suspend fun setIecPackerDtshdPassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setIecPackerDtshdPassthroughEnabled(enabled)
    }

    suspend fun setIecPackerDtshdCoreFallbackEnabled(enabled: Boolean) {
        playerSettingsDataStore.setIecPackerDtshdCoreFallbackEnabled(enabled)
    }

    suspend fun setIecPackerMaxPcmChannelLayout(layout: IecPackerChannelLayout) {
        playerSettingsDataStore.setIecPackerMaxPcmChannelLayout(layout)
    }

    suspend fun setFireOsCompatibilityFallbackEnabled(enabled: Boolean) {
        playerSettingsDataStore.setFireOsCompatibilityFallbackEnabled(enabled)
    }

    suspend fun setFireOsIecSuperviseAudioDelayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setFireOsIecSuperviseAudioDelayEnabled(enabled)
    }

    suspend fun setFireOsIecVerboseLoggingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setFireOsIecVerboseLoggingEnabled(enabled)
    }

    suspend fun setEnableBufferLogs(enabled: Boolean) {
        playerSettingsDataStore.setEnableBufferLogs(enabled)
    }

    suspend fun setTraktScrobbleApiLoggingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setTraktScrobbleApiLoggingEnabled(enabled)
    }

    suspend fun setStreamDiagnosticsEnabled(enabled: Boolean) {
        debugSettingsDataStore.setStreamDiagnosticsEnabled(enabled)
    }

    suspend fun setStartupPerfTelemetryEnabled(enabled: Boolean) {
        debugSettingsDataStore.setStartupPerfTelemetryEnabled(enabled)
    }

    suspend fun setDiskSpoolDiagnosticsEnabled(enabled: Boolean) {
        debugSettingsDataStore.setDiskSpoolDiagnosticsEnabled(enabled)
    }

    suspend fun setDolbyVisionDiagnosticsEnabled(enabled: Boolean) {
        debugSettingsDataStore.setDolbyVisionDiagnosticsEnabled(enabled)
    }

    suspend fun setExperimentalDv5ToDv81Enabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv5ToDv81Enabled(enabled)
    }

    suspend fun setExperimentalDv5ToneMapToSdrEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv5ToneMapToSdrEnabled(enabled)
    }

    suspend fun setExperimentalDv5HardwareToneMapToSdrEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv5HardwareToneMapToSdrEnabled(enabled)
    }

    suspend fun setExperimentalDv5HardwareToneMapCpuFallbackEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv5HardwareToneMapCpuFallbackEnabled(enabled)
    }

    suspend fun setExperimentalDv7ToDv81PreserveMappingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv7ToDv81PreserveMappingEnabled(enabled)
    }

    // Subtitle style settings functions

    suspend fun setSubtitlePreferredLanguage(language: String) {
        playerSettingsDataStore.setSubtitlePreferredLanguage(language)
    }

    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        playerSettingsDataStore.setSubtitleSecondaryLanguage(language)
    }

    suspend fun setSubtitleSize(size: Int) {
        playerSettingsDataStore.setSubtitleSize(size)
    }

    suspend fun setSubtitleVerticalOffset(offset: Int) {
        playerSettingsDataStore.setSubtitleVerticalOffset(offset)
    }

    suspend fun setSubtitleBold(bold: Boolean) {
        playerSettingsDataStore.setSubtitleBold(bold)
    }

    suspend fun setSubtitleTextColor(color: Int) {
        playerSettingsDataStore.setSubtitleTextColor(color)
    }

    suspend fun setSubtitleBackgroundColor(color: Int) {
        playerSettingsDataStore.setSubtitleBackgroundColor(color)
    }

    suspend fun setSubtitleOutlineEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleOutlineEnabled(enabled)
    }

    suspend fun setSubtitleOutlineColor(color: Int) {
        playerSettingsDataStore.setSubtitleOutlineColor(color)
    }

    suspend fun setSubtitleOutlineWidth(width: Int) {
        playerSettingsDataStore.setSubtitleOutlineWidth(width)
    }

    suspend fun setSubtitleOrganizationMode(mode: SubtitleOrganizationMode) {
        playerSettingsDataStore.setSubtitleOrganizationMode(mode)
    }

    suspend fun setAddonSubtitleStartupMode(mode: AddonSubtitleStartupMode) {
        playerSettingsDataStore.setAddonSubtitleStartupMode(mode)
    }

    // Buffer settings functions

    suspend fun setBufferMinBufferMs(ms: Int) {
        playerSettingsDataStore.setBufferMinBufferMs(ms)
    }

    suspend fun setBufferMaxBufferMs(ms: Int) {
        playerSettingsDataStore.setBufferMaxBufferMs(ms)
    }

    suspend fun setBufferForPlaybackMs(ms: Int) {
        playerSettingsDataStore.setBufferForPlaybackMs(ms)
    }

    suspend fun setBufferForPlaybackAfterRebufferMs(ms: Int) {
        playerSettingsDataStore.setBufferForPlaybackAfterRebufferMs(ms)
    }

    suspend fun setBufferTargetSizeMb(mb: Int) {
        playerSettingsDataStore.setBufferTargetSizeMb(mb)
    }

    suspend fun setBufferBackBufferDurationMs(ms: Int) {
        playerSettingsDataStore.setBufferBackBufferDurationMs(ms)
    }

    suspend fun setBufferRetainBackBufferFromKeyframe(retain: Boolean) {
        playerSettingsDataStore.setBufferRetainBackBufferFromKeyframe(retain)
    }

    suspend fun resetBufferSettingsToDefaults() {
        playerSettingsDataStore.resetBufferSettingsToDefaults()
    }

    suspend fun setVodCacheSizeMode(mode: VodCacheSizeMode) {
        playerSettingsDataStore.setVodCacheSizeMode(mode)
    }

    suspend fun setVodCacheWarmAheadEnabled(enabled: Boolean) {
        playerSettingsDataStore.setVodCacheWarmAheadEnabled(enabled)
    }

    suspend fun setProgressivePlaybackDiskMode(mode: ProgressivePlaybackDiskMode) {
        playerSettingsDataStore.setProgressivePlaybackDiskMode(mode)
    }

    suspend fun setDiskSpoolStorageLocation(location: DiskSpoolStorageLocation) {
        playerSettingsDataStore.setDiskSpoolStorageLocation(location)
    }

    @MainThread
    fun runDiskSpoolStorageProbe(context: Context) {
        val probeGeneration = diskSpoolStorageProbeGeneration.incrementAndGet()
        diskSpoolStorageProbeJob?.cancel()
        _diskSpoolStorageProbeUiState.value = DiskSpoolStorageProbeUiState.Running
        val applicationContext = context.applicationContext
        diskSpoolStorageProbeJob = viewModelScope.launch(Dispatchers.IO) {
            fun isCurrentProbeGeneration(): Boolean {
                return coroutineContext.isActive &&
                    diskSpoolStorageProbeGeneration.get() == probeGeneration
            }
            suspend fun commitProbeResultIfCurrent(result: SpoolStorageProbeResult) {
                diskSpoolStorageProbeCommitMutex.withLock {
                    if (isCurrentProbeGeneration()) {
                        playerSettingsDataStore.setSpoolStorageProbeResult(result)
                        _diskSpoolStorageProbeUiState.value = DiskSpoolStorageProbeUiState.NotChecked
                    }
                }
            }
            suspend fun failProbeIfCurrent(message: String) {
                diskSpoolStorageProbeCommitMutex.withLock {
                    if (isCurrentProbeGeneration()) {
                        _diskSpoolStorageProbeUiState.value = DiskSpoolStorageProbeUiState.Failed(message)
                    }
                }
            }
            try {
                coroutineContext.ensureActive()
                if (!isCurrentProbeGeneration()) return@launch
                val settings = playerSettingsDataStore.playerSettings.first()
                val spoolDirectory = if (diskSpoolDirectoryResolverForTesting != null) {
                    diskSpoolDirectoryResolverForTesting?.invoke(
                        applicationContext,
                        settings.diskSpoolStorageLocation
                    )
                } else {
                    DiskSpoolStorageResolver.resolveSpoolDirectory(
                        applicationContext,
                        settings.diskSpoolStorageLocation
                    )
                }
                if (spoolDirectory == null) {
                    failProbeIfCurrent("Disk spool storage location is unavailable")
                    return@launch
                }
                val shouldContinue = {
                    coroutineContext.isActive && !Thread.currentThread().isInterrupted
                }
                val randomWriteEnabled = settings.useParallelConnections
                val diagnosticTotalBytes = DiskSpoolStorageDiagnostic.resolveDefaultTotalBytes(
                    availableBytes = DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(spoolDirectory),
                    randomWriteEnabled = randomWriteEnabled
                )
                val result = diskSpoolStorageProbeRunnerForTesting?.invoke(spoolDirectory, shouldContinue)
                    ?: DiskSpoolStorageDiagnostic(
                        directory = spoolDirectory,
                        totalBytes = diagnosticTotalBytes,
                        readCachePurgeBytes = DiskSpoolStorageDiagnostic.resolveDefaultReadCachePurgeBytes(),
                        randomWriteEnabled = randomWriteEnabled,
                        shouldContinue = shouldContinue
                    ).run()
                coroutineContext.ensureActive()
                if (!isCurrentProbeGeneration()) return@launch
                commitProbeResultIfCurrent(result)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                failProbeIfCurrent(error.message ?: "Disk spool storage diagnostic failed")
                Log.w(TAG, "Disk spool storage probe failed", error)
            }
        }
    }

    suspend fun setVodCacheSizeMb(mb: Int) {
        playerSettingsDataStore.setVodCacheSizeMb(mb)
    }

    suspend fun setUseParallelConnections(enabled: Boolean) {
        playerSettingsDataStore.setUseParallelConnections(enabled)
    }

    suspend fun resetNetworkSettingsToDefaults() {
        playerSettingsDataStore.resetNetworkSettingsToDefaults()
    }

    suspend fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        playerSettingsDataStore.setStreamAutoPlayMode(mode)
    }

    suspend fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        playerSettingsDataStore.setStreamAutoPlaySource(source)
    }

    suspend fun setTrackingProvider(provider: TrackingProvider) {
        playerSettingsDataStore.setTrackingProvider(provider)
    }

    suspend fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        playerSettingsDataStore.setStreamAutoPlaySelectedAddons(addons)
    }

    suspend fun setStreamAutoPlayRegex(regex: String) {
        playerSettingsDataStore.setStreamAutoPlayRegex(regex)
    }

    suspend fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(enabled)
    }

    suspend fun setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled)
    }

    suspend fun setManualBitrateLimitMbps(mbps: Double) {
        playerSettingsDataStore.setManualBitrateLimitMbps(mbps)
    }

    suspend fun setDeterministicAutoplayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setDeterministicAutoplayEnabled(enabled)
    }

    suspend fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        playerSettingsDataStore.setNextEpisodeThresholdMode(mode)
    }

    suspend fun setNextEpisodeThresholdPercent(percent: Float) {
        playerSettingsDataStore.setNextEpisodeThresholdPercent(percent)
    }

    suspend fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        playerSettingsDataStore.setNextEpisodeThresholdMinutesBeforeEnd(minutes)
    }

    suspend fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamReuseLastLinkEnabled(enabled)
    }

    suspend fun setStreamReuseLastLinkCacheHours(hours: Int) {
        playerSettingsDataStore.setStreamReuseLastLinkCacheHours(hours)
    }

    suspend fun setUniformStreamFormattingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setUniformStreamFormattingEnabled(enabled)
    }

    suspend fun setGroupStreamsAcrossAddonsEnabled(enabled: Boolean) {
        playerSettingsDataStore.setGroupStreamsAcrossAddonsEnabled(enabled)
    }

    suspend fun setDeduplicateGroupedStreamsEnabled(enabled: Boolean) {
        playerSettingsDataStore.setDeduplicateGroupedStreamsEnabled(enabled)
    }

    suspend fun setServiceWrapEnabled(enabled: Boolean) {
        playerSettingsDataStore.setServiceWrapEnabled(enabled)
    }

    suspend fun setFilterWebDolbyVisionStreamsEnabled(enabled: Boolean) {
        playerSettingsDataStore.setFilterWebDolbyVisionStreamsEnabled(enabled)
    }

    suspend fun setFilterEpisodeMismatchStreamsEnabled(enabled: Boolean) {
        playerSettingsDataStore.setFilterEpisodeMismatchStreamsEnabled(enabled)
    }

    suspend fun setFilterMovieYearMismatchStreamsEnabled(enabled: Boolean) {
        playerSettingsDataStore.setFilterMovieYearMismatchStreamsEnabled(enabled)
    }

}
