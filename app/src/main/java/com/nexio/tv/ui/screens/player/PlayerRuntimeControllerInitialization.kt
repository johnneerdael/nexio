package com.nexio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.DolbyVisionCompatibility
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.kodi.KodiNativeAudioSink
import androidx.media3.exoplayer.audio.kodi.KodiTrueHdEntryAudioSink
import androidx.media3.exoplayer.audio.kodi.KodiTrueHdNativeAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.ExperimentalDv5HardwareToneMapVideoSink
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import com.nexio.tv.core.player.BurnInProtectionState
import com.nexio.tv.core.player.buildMediaSeedKey
import com.nexio.tv.core.player.CometProxyUrlResolver
import com.nexio.tv.core.player.DoviBridge
import com.nexio.tv.core.player.Dv5HardwareToneMapRpuTap
import com.nexio.tv.core.player.FfmpegStreamMetadataProbe
import com.nexio.tv.core.player.MatroskaDolbyVisionHookInstaller
import com.nexio.tv.core.player.PlayProbeCache
import com.nexio.tv.core.player.auth.PlaybackErrorClassifier
import com.nexio.tv.core.player.computeBurnInProtectionState
import com.nexio.tv.core.player.queryDisplayHdrCapabilities
import com.nexio.tv.core.player.resolveDolbyVisionBaseLayerDecision
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nexio.tv.data.local.diskSpoolTargetBitrateMbps
import com.nexio.tv.data.repository.AssSsaSegmentSurfaceBatchPlanner
import com.nexio.tv.data.repository.mergeAssSsaSegmentBatchResponses
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.ui.screens.player.ass.AssNoOpSubtitleParserFactory
import com.nexio.tv.ui.screens.player.ass.AssSsaExtractorsFactory
import com.nexio.tv.ui.screens.player.ass.AssSsaNativeBridge
import com.nexio.tv.ui.screens.player.ass.AssSsaRenderController
import com.nexio.tv.ui.screens.player.ass.AssSsaRenderOverlayView
import com.nexio.tv.ui.screens.player.ass.AssSsaTimeRenderer
import com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSink
import com.nexio.tv.integrations.hyperhdr.capture.CaptureMode
import com.nexio.tv.integrations.hyperhdr.capture.FormatDetector
import com.nexio.tv.integrations.hyperhdr.capture.HyperHdrCaptureEffect
import com.nexio.tv.integrations.hyperhdr.data.HyperHdrConfig
import com.nexio.tv.integrations.hyperhdr.data.HyperHdrConfigDataStore
import com.nexio.tv.integrations.hyperhdr.network.ConnectionState
import com.nexio.tv.integrations.hyperhdr.network.HyperHdrFlatBufferReconnector
import com.nexio.tv.integrations.hyperhdr.network.HyperHdrJsonApiClient
import com.nexio.tv.integrations.hyperhdr.session.HyperHdrSessionState
import com.nexio.tv.integrations.hyperhdr.session.HyperHdrSessionStateHolder
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 10_000L
private const val ASS_SSA_STARTUP_PROBE_TIMEOUT_MS = 2_500L

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HyperHdrEntryPoint {
    fun hyperHdrConfigDataStore(): HyperHdrConfigDataStore
    fun displayColorCapability(): com.nexio.tv.integrations.hyperhdr.capture.DisplayColorCapability
    fun hyperHdrSessionStateHolder(): HyperHdrSessionStateHolder
}

internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

internal data class AssSsaPipelineDecisionState(
    val decisionStreamUrl: String,
    val overrideForCurrentStream: Boolean?,
    val switchInFlight: Boolean,
    val fallbackHandled: Boolean
)

internal fun resetAssSsaPipelineDecisionStateForStream(
    streamUrl: String
): AssSsaPipelineDecisionState {
    return AssSsaPipelineDecisionState(
        decisionStreamUrl = streamUrl,
        overrideForCurrentStream = null,
        switchInFlight = false,
        fallbackHandled = false
    )
}

internal fun PlayerRuntimeController.resetAssSsaPipelineDecisionForStream(streamUrl: String) {
    val reset = resetAssSsaPipelineDecisionStateForStream(streamUrl)
    assSsaPipelineDecisionStreamUrl = reset.decisionStreamUrl
    assSsaPipelineOverrideForCurrentStream = reset.overrideForCurrentStream
    assSsaPipelineSwitchInFlight = reset.switchInFlight
    assSsaPipelineFallbackHandledForCurrentStream = reset.fallbackHandled
}

private suspend fun PlayerRuntimeController.resolveBurnInProtectionState(
    enabled: Boolean,
): BurnInProtectionState {
    if (!enabled) return BurnInProtectionState.DISABLED
    val salt = playerSettingsDataStore.getOrCreateBurnInProtectionUserSalt()
    val seed = buildMediaSeedKey(
        contentId = contentId,
        season = currentSeason,
        episode = currentEpisode,
        streamUrl = currentStreamUrl,
    )
    return computeBurnInProtectionState(
        enabled = true,
        mediaSeedKey = seed,
        userSalt = salt,
        nowMs = System.currentTimeMillis(),
    )
}

internal data class AssSsaPipelineOverlayDecision(
    val useAssSsaPipeline: Boolean,
    val disableOverrideForCurrentStream: Boolean
)

internal data class AssSsaPipelineTrackAdjustment(
    val overrideForCurrentStream: Boolean?,
    val shouldReinitializePlayer: Boolean
)

internal fun resolveAssSsaPipelineOverlayDecision(
    requestedUseAssSsaPipeline: Boolean,
    overlayAttached: Boolean
): AssSsaPipelineOverlayDecision {
    return AssSsaPipelineOverlayDecision(
        useAssSsaPipeline = requestedUseAssSsaPipeline,
        disableOverrideForCurrentStream = false
    )
}

internal fun resolveAssSsaPipelineTrackAdjustment(
    desiredUseAssSsaPipeline: Boolean,
    activePlayerUsesAssSsaRenderer: Boolean,
    fallbackHandled: Boolean
): AssSsaPipelineTrackAdjustment {
    if (desiredUseAssSsaPipeline && fallbackHandled) {
        return AssSsaPipelineTrackAdjustment(
            overrideForCurrentStream = false,
            shouldReinitializePlayer = false
        )
    }
    if (desiredUseAssSsaPipeline == activePlayerUsesAssSsaRenderer) {
        return AssSsaPipelineTrackAdjustment(
            overrideForCurrentStream = null,
            shouldReinitializePlayer = false
        )
    }
    return AssSsaPipelineTrackAdjustment(
        overrideForCurrentStream = desiredUseAssSsaPipeline,
        shouldReinitializePlayer = false
    )
}

internal fun PlayerRuntimeController.setAssSsaRenderOverlayViewProvider(
    provider: (() -> AssSsaRenderOverlayView?)?
) {
    assSsaOverlayViewProvider = provider
    val overlayView = provider?.invoke()
    assSsaRenderController?.setOverlayView(overlayView)
}

internal fun shouldEnableAssSsaSampleTranslation(
    aiSubtitlesEnabled: Boolean,
    selectedAddonSubtitlePresent: Boolean,
    selectedSubtitleTrackIndex: Int,
    translationSettingsEnabled: Boolean,
    translationApiKeyPresent: Boolean
): Boolean {
    return aiSubtitlesEnabled &&
        !selectedAddonSubtitlePresent &&
        selectedSubtitleTrackIndex >= 0 &&
        translationSettingsEnabled &&
        translationApiKeyPresent
}

@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal fun PlayerRuntimeController.initializePlayer(url: String, headers: Map<String, String>) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = "No stream URL provided", showLoadingOverlay = false) }
        return
    }

    playbackActivityTracker.setActive(true)
    com.nexio.tv.core.player.FrameRateUtils.beginMainPlayerDisplayModeSession()

    val playbackSessionId = playbackSessionGuard.beginPlaybackSession()

    authRecoveryInterceptor.resetSessionState()
    scope.launch(Dispatchers.IO) { egressIpFingerprint.captureBaseline() }

    scope.launch {
        try {
            autoSubtitleSelected = false
            autoAudioSelected = false
            hasScannedTextTracksOnce = false
            resetLoadingOverlayForNewStream()
            playerInitializationStartedAtMs = System.currentTimeMillis()
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            lastPreferredAudioLanguage = playerSettings.preferredAudioLanguage
            lastSecondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage
            val localeList = Resources.getSystem().configuration.locales
            val deviceLanguages = List(localeList.size()) { localeList[it].isO3Language }
            val preferredAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                deviceLanguages = deviceLanguages,
                originalLanguage = originalLanguage
            )
            val experimentalFireOsIecPassthroughEnabled =
                playerSettings.experimentalDtsIecPassthroughEnabled
            val kodiCustomAudioSinkEnabled = experimentalFireOsIecPassthroughEnabled
            AudioCapabilities.setExperimentalFireOsIecPassthroughEnabled(
                experimentalFireOsIecPassthroughEnabled
            )
            AudioCapabilities.setFireOsCompatibilityFallbackEnabled(false)
            AudioCapabilities.setIecPackerAc3PassthroughEnabled(
                playerSettings.iecPackerAc3PassthroughEnabled
            )
            AudioCapabilities.setIecPackerAc3TranscodeEnabled(
                playerSettings.iecPackerAc3TranscodeEnabled
            )
            AudioCapabilities.setIecPackerEac3PassthroughEnabled(
                playerSettings.iecPackerEac3PassthroughEnabled
            )
            AudioCapabilities.setIecPackerDtsPassthroughEnabled(
                playerSettings.iecPackerDtsPassthroughEnabled
            )
            AudioCapabilities.setIecPackerTruehdPassthroughEnabled(
                playerSettings.iecPackerTruehdPassthroughEnabled
            )
            AudioCapabilities.setIecPackerDtshdPassthroughEnabled(
                playerSettings.iecPackerDtshdPassthroughEnabled
            )
            AudioCapabilities.setIecPackerDtshdCoreFallbackEnabled(
                playerSettings.iecPackerDtshdCoreFallbackEnabled
            )
            AudioCapabilities.setIecPackerAudioConfig(
                playerSettings.iecPackerAudioConfig
            )
            AudioCapabilities.setIecPackerAudioDevice(
                playerSettings.iecPackerAudioDevice
            )
            AudioCapabilities.setIecPackerPassthroughDevice(
                playerSettings.iecPackerPassthroughDevice
            )
            AudioCapabilities.setIecPackerMaxPcmChannelLayout(
                playerSettings.iecPackerMaxPcmChannelLayout.kodiChannelLayoutValue
            )
            AudioCapabilities.setFireOsIecSuperviseAudioDelayEnabled(
                playerSettings.fireOsIecSuperviseAudioDelayEnabled
            )
            AudioCapabilities.setFireOsIecVerboseLoggingEnabled(
                playerSettings.fireOsIecVerboseLoggingEnabled
            )
            if (assSsaPipelineDecisionStreamUrl != currentStreamUrl) {
                resetAssSsaPipelineDecisionForStream(currentStreamUrl)
            }
            val retainedSelectedSubtitle = _uiState.value.selectedAddonSubtitle
            Dv5HardwareToneMapRpuTap.setEnabledForPlayback(enabled = false, streamUrl = url)
            if (shouldRunEmbeddedAssSsaStartupProbe(
                    nativeAssSsaAvailable = AssSsaNativeBridge.nativeAvailable,
                    pipelineOverrideForCurrentStream = assSsaPipelineOverrideForCurrentStream,
                    url = url
                )
            ) {
                val addonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(addonBaseUrl)
                val cached = PlayProbeCache.get(url, headers)
                val metadata = cached ?: withTimeoutOrNull(ASS_SSA_STARTUP_PROBE_TIMEOUT_MS) {
                    FfmpegStreamMetadataProbe.probe(
                        url = url,
                        headers = headers,
                        addonHost = addonHost
                    )?.also { PlayProbeCache.put(url, headers, it) }
                }
                if (cached != null) {
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "ASS_SSA_RENDER: reusing per-play metadata probe " +
                            "host=${url.safeHost()} streams=${cached.streams.size}"
                    )
                }
                if (metadata?.hasEmbeddedAssSsaSubtitleStream == true) {
                    assSsaPipelineOverrideForCurrentStream = true
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "ASS_SSA_RENDER: FFmpeg startup probe detected embedded ASS/SSA " +
                            "host=${url.safeHost()}"
                    )
                } else {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "ASS_SSA_RENDER: FFmpeg startup probe did not detect embedded ASS/SSA " +
                            "host=${url.safeHost()}"
                    )
                }
            }
            val requestedUseAssSsaPipeline = AssSsaNativeBridge.nativeAvailable &&
                assSsaPipelineOverrideForCurrentStream == true
            if (!AssSsaNativeBridge.nativeAvailable &&
                assSsaPipelineOverrideForCurrentStream == true
            ) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "ASS_SSA_RENDER: native renderer unavailable; using Media3 fallback " +
                        "host=${url.safeHost()}"
                )
                assSsaPipelineOverrideForCurrentStream = false
                assSsaPipelineFallbackHandledForCurrentStream = true
            }
            val assSsaOverlayView = if (requestedUseAssSsaPipeline) {
                assSsaOverlayViewProvider?.invoke()
            } else {
                null
            }
            val overlayDecision = resolveAssSsaPipelineOverlayDecision(
                requestedUseAssSsaPipeline = requestedUseAssSsaPipeline,
                overlayAttached = assSsaOverlayView != null
            )
            if (overlayDecision.disableOverrideForCurrentStream) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "ASS_SSA_RENDER: overlay view unavailable; using Media3 fallback " +
                        "host=${url.safeHost()}"
                )
                assSsaPipelineOverrideForCurrentStream = false
                assSsaPipelineFallbackHandledForCurrentStream = true
            } else if (requestedUseAssSsaPipeline && assSsaOverlayView == null) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "ASS_SSA_RENDER: overlay view unavailable; starting pipeline and waiting for attachment " +
                        "host=${url.safeHost()}"
                )
            }
            val useAssSsaPipeline = overlayDecision.useAssSsaPipeline
            DoviBridge.resetRuntimeCounters()
            MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()
            DolbyVisionCompatibility.setMapDv7ToHevcEnabled(false)
            val displayHdrCapabilities = context.queryDisplayHdrCapabilities()
            val dv7HevcBaseLayerDecision = resolveDolbyVisionBaseLayerDecision(
                enabled = playerSettings.experimentalDv7HevcBaseLayerEnabled,
                displayCapabilities = displayHdrCapabilities
            )
            val dv7HevcBaseLayerActive = dv7HevcBaseLayerDecision.enableHevcMapping
            DolbyVisionCompatibility.setMapDv7ToHevcEnabled(dv7HevcBaseLayerActive)
            if (playerSettings.experimentalDv7HevcBaseLayerEnabled) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_HEVC_BASE: setting=true active=$dv7HevcBaseLayerActive " +
                        "decision=$dv7HevcBaseLayerDecision " +
                        "hdrCapsKnown=${displayHdrCapabilities.hdrCapsKnown} " +
                        "displayDv=${displayHdrCapabilities.supportsDolbyVision} " +
                        "displayHdr10=${displayHdrCapabilities.supportsHdr10OrHdr10Plus} " +
                        "host=${url.safeHost()}"
                )
            }
            val dv7ToDv81SettingActive =
                playerSettings.experimentalDv7ToDv81Enabled && !dv7HevcBaseLayerActive
            val dv7ToDv81Probe = if (dv7ToDv81SettingActive) {
                DoviBridge.probeRealtimeConversionSupport(url)
            } else {
                DoviBridge.RealtimeConversionProbe(
                    supported = false,
                    reason = if (dv7HevcBaseLayerActive) {
                        "dv7-hevc-base-layer-active"
                    } else {
                        "setting-disabled"
                    },
                    bridgeVersion = DoviBridge.getBridgeVersionOrNull(),
                    extractorHookReady = DoviBridge.isExtractorHookReadyInBuild,
                    selfTest = DoviBridge.SelfTestResult(
                        passed = false,
                        reason = "not-run",
                        inputBytes = 0,
                        outputBytes = 0
                    )
                )
            }
            isExperimentalDv7ToDv81ActiveForCurrentPlayback =
                dv7ToDv81SettingActive && dv7ToDv81Probe.supported
            dv7ToDv81BridgeVersionForCurrentPlayback = dv7ToDv81Probe.bridgeVersion
            dv7ToDv81LastProbeReasonForCurrentPlayback = dv7ToDv81Probe.reason
            Log.i(
                PlayerRuntimeController.TAG,
                "DV7_DOVI: setting=${playerSettings.experimentalDv7ToDv81Enabled} " +
                    "hevcBaseLayer=${playerSettings.experimentalDv7HevcBaseLayerEnabled} " +
                    "hevcBaseLayerActive=$dv7HevcBaseLayerActive " +
                    "dv5Compat=${playerSettings.experimentalDv5ToDv81Enabled} " +
                    "preserveMapping=${playerSettings.experimentalDv7ToDv81PreserveMappingEnabled} " +
                    "buildNative=${DoviBridge.isNativeEnabledInBuild} " +
                    "libraryLoaded=${DoviBridge.isLibraryLoaded} " +
                    "extractorHookReady=${dv7ToDv81Probe.extractorHookReady} " +
                    "active=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                    "reason=${dv7ToDv81Probe.reason} " +
                    "selfTest=${dv7ToDv81Probe.selfTest.reason} " +
                    "bridge=${dv7ToDv81Probe.bridgeVersion ?: "n/a"} " +
                    "host=${url.safeHost()}"
            )
            val loadControl = PlayerLoadControlFactory.build(context, playerSettings)

            mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
            mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
            mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
            mediaSourceFactory.vodCacheWarmAheadEnabled = playerSettings.vodCacheWarmAheadEnabled
            mediaSourceFactory.progressivePlaybackDiskMode = playerSettings.progressivePlaybackDiskMode
            mediaSourceFactory.diskSpoolStorageLocation = playerSettings.diskSpoolStorageLocation
            mediaSourceFactory.spoolStorageProbeResult =
                SpoolStorageProbeResult.fromJsonOrNull(playerSettings.spoolStorageProbeResultJson)
            mediaSourceFactory.diskSpoolTargetBitrateMbps = playerSettings.diskSpoolTargetBitrateMbps()
            if (kodiCustomAudioSinkEnabled) {
                safeAudioForcedStreamUrls.remove(url)
                audioDisabledForcedStreamUrls.remove(url)
            }
            // When disk spool is active, prior safe-audio / audio-disabled entries
            // for this URL are false positives caused by spool data-delivery latency,
            // not actual AudioTrack init failures.  Clear them so passthrough and
            // normal track selection work correctly.
            if (mediaSourceFactory.isDiskSpoolSessionActive()) {
                val hadSafeAudio = safeAudioForcedStreamUrls.remove(url)
                val hadAudioDisabled = audioDisabledForcedStreamUrls.remove(url)
                if (hadSafeAudio || hadAudioDisabled) {
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "AUDIO_INIT: disk spool active; cleared stale safe-audio " +
                            "flags for host=${Uri.parse(url).host ?: "unknown"} " +
                            "(safeAudio=$hadSafeAudio audioDisabled=$hadAudioDisabled)"
                    )
                }
            }
            val safeAudioModeEnabled =
                !kodiCustomAudioSinkEnabled && safeAudioForcedStreamUrls.contains(url)
            val audioDisabledForStream =
                !kodiCustomAudioSinkEnabled && audioDisabledForcedStreamUrls.contains(url)
            val vc1TrackSelectionBypassActive = vc1TrackSelectionBypassStreamUrls.contains(url)
            isSafeAudioModeActiveForCurrentPlayback = safeAudioModeEnabled
            isAudioDisabledForCurrentPlayback = audioDisabledForStream
            isVc1TrackSelectionBypassActiveForCurrentPlayback = vc1TrackSelectionBypassActive
            isKodiCustomAudioSinkActiveForCurrentPlayback = kodiCustomAudioSinkEnabled

            
            trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                )
                if (playerSettings.tunnelingEnabled && !safeAudioModeEnabled) {
                    setParameters(
                        buildUponParameters().setTunnelingEnabled(true)
                    )
                } else if (safeAudioModeEnabled) {
                    setParameters(
                        buildUponParameters()
                            .setTunnelingEnabled(false)
                            .setConstrainAudioChannelCountToDeviceCapabilities(true)
                    )
                }
                if (audioDisabledForStream) {
                    setParameters(
                        buildUponParameters().setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO))
                    )
                }
                if (vc1TrackSelectionBypassActive) {
                    setParameters(
                        buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setExceedVideoConstraintsIfNecessary(true)
                            .setExceedRendererCapabilitiesIfNecessary(true)
                            .setForceHighestSupportedBitrate(true)
                    )
                }

                if (preferredAudioLanguages.isNotEmpty()) {
                    setParameters(
                        buildUponParameters().setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
                    )
                }

                
                val appContext = this@initializePlayer.context
                val captioningManager = appContext.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
                if (captioningManager != null) {
                    if (!captioningManager.isEnabled) {
                        setParameters(
                            buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        )
                    }
                    captioningManager.locale?.let { locale ->
                        setParameters(
                            buildUponParameters().setPreferredTextLanguage(locale.isO3Language)
                        )
                    }
                }
            }

            
            val extractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

            
            subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)
            val dv5SoftwareToneMapEnabled = false
            isDv5SoftwareToneMapSettingEnabledForCurrentPlayback = dv5SoftwareToneMapEnabled
            val dv5HardwareToneMapEnabled = false
            val dv5HardwareToneMapCpuFallbackEnabled = false
            isDv5HardwareToneMapSettingEnabledForCurrentPlayback = dv5HardwareToneMapEnabled
            val dv5ToneMapNativeBuildSupported = false
            val dv5SoftwareToneMapRuntimeSupported = false
            isDv5HardwareToneMapNativeSupportedForCurrentPlayback = dv5ToneMapNativeBuildSupported
            isDv5SoftwareToneMapNativeSupportedForCurrentPlayback =
                dv5ToneMapNativeBuildSupported && dv5SoftwareToneMapRuntimeSupported
            val dv5HardwareForced = false
            val dv5SoftwareForced = false
            val dv5DetectedByProfile = dv5SoftwareForced || dv5HardwareForced
            val displaySupportsDolbyVision = displayHdrCapabilities.supportsDolbyVision
            isCurrentDisplayDolbyVisionCapable = displaySupportsDolbyVision
            val shieldDevice = context.isNvidiaShieldDevice()
            isCurrentDeviceNvidiaShield = shieldDevice
            val dv5HardwareToneMapActive = dv5HardwareToneMapEnabled &&
                isDv5HardwareToneMapNativeSupportedForCurrentPlayback &&
                shieldDevice &&
                !displaySupportsDolbyVision &&
                dv5HardwareForced
            isDv5HardwareToneMapActiveForCurrentPlayback = dv5HardwareToneMapActive
            val dv5SoftwareToneMapActive = dv5SoftwareToneMapEnabled &&
                !dv5HardwareToneMapActive &&
                isDv5SoftwareToneMapNativeSupportedForCurrentPlayback &&
                !displaySupportsDolbyVision &&
                dv5SoftwareForced
            isDv5SoftwareToneMapActiveForCurrentPlayback = dv5SoftwareToneMapActive
            if (dv5SoftwareToneMapEnabled && !isDv5SoftwareToneMapNativeSupportedForCurrentPlayback) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "DV5_SW_TONEMAP: unavailable at runtime; software path disabled " +
                        "nativeBuild=$dv5ToneMapNativeBuildSupported " +
                        "runtimeVulkan=$dv5SoftwareToneMapRuntimeSupported " +
                        "shieldDevice=$shieldDevice host=${url.safeHost()}"
                )
            }
            Dv5HardwareToneMapRpuTap.setEnabledForPlayback(
                enabled = dv5HardwareToneMapActive,
                streamUrl = url
            )
            val dolbyVisionHookInstalledForPlayback = MatroskaDolbyVisionHookInstaller.maybeInstall(
                extractorsFactory = extractorsFactory,
                enabled = dv7ToDv81SettingActive,
                allowDv5Conversion = playerSettings.experimentalDv5ToDv81Enabled,
                preserveMappingEnabled = playerSettings.experimentalDv7ToDv81PreserveMappingEnabled,
                enableRpuTap = false,
                streamUrl = url
            )
            if (dolbyVisionHookInstalledForPlayback) {
                isExperimentalDv7ToDv81ActiveForCurrentPlayback = true
                if (dv7ToDv81LastProbeReasonForCurrentPlayback != "ready") {
                    dv7ToDv81LastProbeReasonForCurrentPlayback = "extractor-hook-enabled"
                }
            }
            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback && !dolbyVisionHookInstalledForPlayback) {
                isExperimentalDv7ToDv81ActiveForCurrentPlayback = false
                dv7ToDv81LastProbeReasonForCurrentPlayback =
                    "extractor-hook-install-failed"
            }
            if (playerSettings.experimentalDv7ToDv81Enabled ||
                playerSettings.experimentalDv7HevcBaseLayerEnabled ||
                dv5HardwareToneMapActive
            ) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_DOVI: extractorHookInstalled=$dolbyVisionHookInstalledForPlayback " +
                        "active=$isExperimentalDv7ToDv81ActiveForCurrentPlayback " +
                        "hevcBaseLayerActive=$dv7HevcBaseLayerActive " +
                        "rpuTap=$dv5HardwareToneMapActive " +
                        "host=${url.safeHost()}"
                )
            }
            val vc1SoftwareFallbackActive = vc1SoftwarePreferredStreamUrls.contains(url)
            val av1FfmpegFallbackActive = av1FfmpegPreferredStreamUrls.contains(url)
            isAv1FfmpegFallbackActiveForCurrentPlayback = av1FfmpegFallbackActive
            isVc1SoftwareFallbackActiveForCurrentPlayback = vc1SoftwareFallbackActive
            val codecSelector = createDolbyVisionFallbackCodecSelector(
                forceVc1SoftwareDecode = true,
                forceDolbyVisionSoftwareDecode = false
            )
            val effectiveDecoderPriority = if (av1FfmpegFallbackActive) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else if (vc1SoftwareFallbackActive) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                playerSettings.decoderPriority
            }
            FfmpegLibrary.setExperimentalDv5ToneMapToSdrEnabled(false)
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                safeAudioModeEnabled = safeAudioModeEnabled,
                cueGroupSubtitleTranslator = builtInSubtitleCueTranslator,
                experimentalFireOsIecPassthroughEnabled =
                    playerSettings.experimentalDtsIecPassthroughEnabled,
                disableDav1dForAv1 = av1FfmpegFallbackActive,
                experimentalDv5HardwareToneMapEnabled = false,
                experimentalDv5HardwareToneMapCpuFallbackEnabled = false,
                assSsaRenderControllerProvider = { assSsaRenderController }
            )
                .setEnableMediaCodecVideoRendererDurationToProgressUs(
                    playerSettings.dynamicVideoSchedulingEnabled
                )
                .setExtensionRendererMode(effectiveDecoderPriority)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector)
            Log.i(
                PlayerRuntimeController.TAG,
                "VIDEO_PATH: decoderMode=${describeExtensionRendererMode(effectiveDecoderPriority)} " +
                    "dv5ToneMapSetting=$dv5SoftwareToneMapEnabled " +
                    "dv5HwToneMapSetting=$dv5HardwareToneMapEnabled " +
                    "dv5HwCpuFallbackSetting=$dv5HardwareToneMapCpuFallbackEnabled " +
                    "dv5ToneMapNativeBuildSupported=$dv5ToneMapNativeBuildSupported " +
                    "dv5ToneMapRuntimeVulkanSupported=$dv5SoftwareToneMapRuntimeSupported " +
                    "dv5ToneMapNativeSupported=$isDv5SoftwareToneMapNativeSupportedForCurrentPlayback " +
                    "dvDisplayCapable=$displaySupportsDolbyVision " +
                    "shieldDevice=$shieldDevice " +
                    "dv5DetectedByProfile=$dv5DetectedByProfile " +
                    "dv5Forced=$dv5SoftwareForced " +
                    "dv5HwForced=$dv5HardwareForced " +
                    "dv5HwToneMapActive=$dv5HardwareToneMapActive " +
                    "dv5ToneMapActive=$dv5SoftwareToneMapActive " +
                    "av1FfmpegFallbackActive=$av1FfmpegFallbackActive " +
                    "vc1FallbackActive=$vc1SoftwareFallbackActive " +
                    "vc1TrackBypassActive=$vc1TrackSelectionBypassActive " +
                    "dynamicVideoScheduling=${playerSettings.dynamicVideoSchedulingEnabled} " +
                    "host=${url.safeHost()}"
            )

            assSsaRenderController?.release()
            assSsaRenderController = null
            val assController = if (useAssSsaPipeline) {
                AssSsaRenderController(
                    context = context,
                    overlayView = assSsaOverlayView,
                    subtitleDelayUsProvider = subtitleDelayUs::get
                )
            } else {
                null
            }
            val assSampleSink = assController?.let { controller ->
                AssSsaTranslatingSampleSink(
                    downstream = controller,
                    scope = scope,
                    isEnabled = {
                        shouldEnableAssSsaSampleTranslation(
                            aiSubtitlesEnabled = _uiState.value.aiSubtitlesEnabled,
                            selectedAddonSubtitlePresent = _uiState.value.selectedAddonSubtitle != null,
                            selectedSubtitleTrackIndex = _uiState.value.selectedSubtitleTrackIndex,
                            translationSettingsEnabled = subtitleTranslationSettings.enabled,
                            translationApiKeyPresent = subtitleTranslationSettings.apiKey.isNotBlank()
                        )
                    },
                    translate = { surfaces ->
                        val batchResponses = AssSsaSegmentSurfaceBatchPlanner.plan(surfaces).map { batch ->
                            batch to subtitleTranslationService.translateAssSsaSegmentSurfaces(
                                surfaces = batch.units,
                                targetLanguageCode = _uiState.value.subtitleStyle.preferredLanguage,
                                sourceLanguageCode = null,
                                settings = subtitleTranslationSettings
                            ).getOrThrow()
                        }
                        mergeAssSsaSegmentBatchResponses(batchResponses)
                    },
                    diagnosticsLogger = subtitleTranslationService.diagnosticsLogger
                )
            }
            assSsaRenderController = assController
            if (assController != null) {
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = AssSsaExtractorsFactory(extractorsFactory, assSampleSink ?: assController),
                    subtitleParserFactory = AssNoOpSubtitleParserFactory()
                )
            } else {
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = null,
                    subtitleParserFactory = null
                )
            }

            _exoPlayer = ExoPlayer.Builder(context)
                .experimentalSetDynamicSchedulingEnabled(playerSettings.dynamicVideoSchedulingEnabled)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build()
                .also { assController?.setPlayer(it) }
            activePlayerUsesAssSsaRenderer = useAssSsaPipeline
            assSsaPipelineSwitchInFlight = false
            _uiState.update { it.copy(useAssSsaRenderOverlay = false) }

            val hyperHdrEntry = EntryPoints
                .get(context.applicationContext, HyperHdrEntryPoint::class.java)
            val hyperHdrStore = hyperHdrEntry.hyperHdrConfigDataStore()
            val hyperHdrDisplayCapability = hyperHdrEntry.displayColorCapability()
            val hyperHdrSessionStateHolder = hyperHdrEntry.hyperHdrSessionStateHolder()

            var hyperHdrCfg: HyperHdrConfig = HyperHdrConfig()
            var hyperHdrFbReconnector: HyperHdrFlatBufferReconnector? = null
            var hyperHdrStateCollectJob: kotlinx.coroutines.Job? = null
            var hyperHdrCurrentMode: CaptureMode? = null
            // Cached result of HyperHdrJsonApiClient.serverInfo().supportsP010 keyed by
            // "host:jsonPort". Probed lazily by detectHyperHdrCaptureMode on first need.
            // Cleared in stopHyperHdrCapture so a new session against a different server
            // re-probes. Stays per-session (matches the lifecycle of hyperHdrCurrentMode).
            var hyperHdrServerP010Cache: Pair<String, Boolean>? = null

            scope.launch {
                hyperHdrStore.config.collect { hyperHdrCfg = it }
            }

            suspend fun detectHyperHdrCaptureMode(
                cfg: HyperHdrConfig,
                colorInfo: androidx.media3.common.ColorInfo?
            ): CaptureMode {
                val cacheKey = "${cfg.host}:${cfg.jsonPort}"
                val cached = hyperHdrServerP010Cache
                val serverSupportsP010 = if (cached != null && cached.first == cacheKey) {
                    cached.second
                } else {
                    val probed = runCatching {
                        HyperHdrJsonApiClient(
                            host = cfg.host,
                            port = cfg.jsonPort,
                            token = cfg.jsonToken.ifBlank { null }
                        ).serverInfo().supportsP010
                    }.onFailure {
                        Log.w("HyperHdrIntegration", "JSON serverinfo failed; using SDR capture", it)
                    }.getOrDefault(false)
                    hyperHdrServerP010Cache = cacheKey to probed
                    probed
                }

                return FormatDetector.detect(
                    colorInfo,
                    cfg.hdrMode,
                    deviceComposesWideColor = hyperHdrDisplayCapability.composesWideColor,
                    serverSupportsP010 = serverSupportsP010
                )
            }

            suspend fun startHyperHdrCapture(targetMode: CaptureMode) {
                val player = _exoPlayer ?: return
                val cfg = hyperHdrCfg
                if (!cfg.isUsable) return

                hyperHdrStateCollectJob?.cancel()
                hyperHdrStateCollectJob = null
                hyperHdrFbReconnector?.close()
                hyperHdrFbReconnector = null

                val reconnector = HyperHdrFlatBufferReconnector(
                    host = cfg.host,
                    port = cfg.port,
                    priority = cfg.priority,
                    origin = "Nexio-HyperHDR"
                )
                reconnector.start()
                hyperHdrFbReconnector = reconnector
                Log.d("HyperHdrIntegration", "Reconnector started for ${cfg.host}:${cfg.port}, awaiting connect")

                hyperHdrSessionStateHolder.update(HyperHdrSessionState.Connecting(targetMode))
                hyperHdrStateCollectJob = scope.launch {
                    reconnector.state.collect { connState ->
                        val sessionState = when (connState) {
                            ConnectionState.CONNECTED -> HyperHdrSessionState.Connected(targetMode)
                            ConnectionState.CONNECTING -> HyperHdrSessionState.Connecting(targetMode)
                            ConnectionState.ERROR -> HyperHdrSessionState.Reconnecting(targetMode)
                            ConnectionState.DISCONNECTED -> HyperHdrSessionState.Idle
                        }
                        hyperHdrSessionStateHolder.update(sessionState)
                    }
                }

                runCatching {
                    val jsonClient = HyperHdrJsonApiClient(
                        host = cfg.host,
                        port = cfg.jsonPort,
                        token = cfg.jsonToken.ifBlank { null }
                    )
                    jsonClient.setHdrVideoMode(targetMode == CaptureMode.HDR_P010)
                }.onFailure {
                    Log.w("HyperHdrIntegration", "JSON setHdrVideoMode failed (continuing)", it)
                }

                hyperHdrCurrentMode = targetMode
                player.setVideoEffects(listOf(HyperHdrCaptureEffect(reconnector, targetMode)))
            }

            fun stopHyperHdrCapture() {
                val player = _exoPlayer ?: return
                player.setVideoEffects(emptyList())
                hyperHdrStateCollectJob?.cancel()
                hyperHdrStateCollectJob = null
                hyperHdrFbReconnector?.close()
                hyperHdrFbReconnector = null
                hyperHdrCurrentMode = null
                hyperHdrServerP010Cache = null
                hyperHdrSessionStateHolder.update(HyperHdrSessionState.Idle)
            }

            _exoPlayer?.apply {
                
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)

                
                if (playerSettings.skipSilence) {
                    skipSilenceEnabled = true
                }

                
                setHandleAudioBecomingNoisy(true)

                
                try {
                    currentMediaSession?.release()
                    if (canAdvertiseSession()) {
                        currentMediaSession = MediaSession.Builder(context, this).build()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    loudnessEnhancer?.release()
                    loudnessEnhancer =
                        if (!isKodiCustomAudioSinkActiveForCurrentPlayback &&
                            audioSessionId != C.AUDIO_SESSION_ID_UNSET &&
                            audioSessionId > 0
                        ) {
                            LoudnessEnhancer(audioSessionId)
                        } else {
                            null
                        }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                
                notifyAudioSessionUpdate(true)

                val preferred = playerSettings.subtitleStyle.preferredLanguage
                val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
                applySubtitlePreferences(preferred, secondary)
                val retainedStartupSubtitles = listOfNotNull(retainedSelectedSubtitle)
                    .distinctBy { "${it.id}|${it.url}" }
                attachedAddonSubtitleKeys = retainedStartupSubtitles
                    .distinctBy { addonSubtitleKey(it) }
                    .map(::addonSubtitleKey)
                    .toSet()
                val startupSubtitleConfigurations = retainedStartupSubtitles
                    .distinctBy { "${it.id}|${it.url}" }
                    .map { subtitle -> toSubtitleConfiguration(subtitle) }
                val playerListener = object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) {
                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        currentCueGroup = cueGroup
                        handleBuiltInCueGroupUpdate()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) {
                            lastKnownDuration = playerDuration
                        }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        _uiState.update { 
                            it.copy(
                                isBuffering = isBuffering,
                                playbackEnded = playbackState == Player.STATE_ENDED
                            )
                        }
                        _progressUiState.update {
                            it.copy(duration = playerDuration.coerceAtLeast(0L))
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                // Suppress the full-screen loading overlay
                                // while we're in the middle of an addon
                                // subtitle swap. The setMediaSource + prepare
                                // pair from the slow-path triggers a transient
                                // STATE_BUFFERING — without this guard, an
                                // auto-pick at startup (before first frame)
                                // would flash the backdrop overlay over the
                                // surface and look like a screen recompose.
                                if (state.loadingOverlayEnabled &&
                                    !state.showLoadingOverlay &&
                                    !state.isSwappingAddonSubtitle
                                ) {
                                    state.copy(showLoadingOverlay = true, showControls = false)
                                } else {
                                    state
                                }
                            }
                        }
                        if (playbackState == Player.STATE_BUFFERING &&
                            pendingSeekTelemetryAwaitingFirstFrame &&
                            pendingSeekTelemetryReadyAssumed
                        ) {
                            pendingSeekTelemetryReadyAtMs = 0L
                            pendingSeekTelemetryReadyLatencyMs = -1L
                            pendingSeekTelemetryReadyAssumed = false
                        }
                    
                        
                        if (playbackState == Player.STATE_READY) {
                            if (_uiState.value.isSwappingAddonSubtitle) {
                                _uiState.update { it.copy(isSwappingAddonSubtitle = false) }
                            }
                            if (
                                pendingSeekTelemetryRequestedAtMs > 0L &&
                                    pendingSeekTelemetryReadyAtMs <= 0L
                            ) {
                                val latencyMs =
                                    (System.currentTimeMillis() - pendingSeekTelemetryRequestedAtMs)
                                        .coerceAtLeast(0L)
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "SEEK_READY: latencyMs=$latencyMs " +
                                        "targetMs=$pendingSeekTelemetryTargetMs " +
                                        "host=${currentStreamUrl.safeHost()}"
                                )
                                pendingSeekTelemetryReadyAtMs = System.currentTimeMillis()
                                pendingSeekTelemetryReadyLatencyMs = latencyMs
                            }
                            if (!hasRenderedFirstFrame) {
                                _uiState.update { state ->
                                    state.copy(
                                        showLoadingOverlay = false,
                                        showControls = true
                                    )
                                }
                            }
                            if (shouldEnforceAutoplayOnFirstReady) {
                                shouldEnforceAutoplayOnFirstReady = false
                                if (!userPausedManually && !isPlaying) {
                                    if (!playWhenReady) {
                                        playWhenReady = true
                                    }
                                    play()
                                }
                            }
                            tryApplyPendingResumeProgress(this@apply)
                            _uiState.value.pendingSeekPosition?.let { position ->
                                seekTo(position)
                                _uiState.update { it.copy(pendingSeekPosition = null) }
                            }
                            // Re-evaluate subtitle auto-selection once player is ready.
                            tryAutoSelectPreferredSubtitleFromAvailableTracks()
                            maybeScheduleFirstFrameWatchdog()
                        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            cancelFirstFrameWatchdog()
                        }
                    
                        
                        if (playbackState == Player.STATE_ENDED) {
                            emitCompletionScrobbleStop(progressPercent = 99.5f)
                            saveWatchProgress()
                            resetNextEpisodeCardState(clearEpisode = false)
                            torBoxContext?.let { ctx ->
                                scope.launch {
                                    torBoxResumeStore.clear(torrentId = ctx.torrentId, fileId = ctx.fileId)
                                }
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!hyperHdrCfg.isUsable) {
                            if (hyperHdrFbReconnector != null) stopHyperHdrCapture()
                        } else if (isPlaying) {
                            scope.launch {
                                val cfg = hyperHdrCfg
                                val colorInfo = _exoPlayer?.videoFormat?.colorInfo
                                val mode = detectHyperHdrCaptureMode(cfg, colorInfo)
                                startHyperHdrCapture(mode)
                            }
                        } else {
                            stopHyperHdrCapture()
                        }

                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        _uiState.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            userPausedManually = false
                            playbackIdleGateState.onPlaybackResumed()
                            cancelPauseOverlay()
                            startProgressUpdates()
                            startWatchProgressSaving()
                            scheduleHideControls()
                            emitScrobbleStart()
                        } else {
                            if (userPausedManually) {
                                schedulePauseOverlay()
                            } else {
                                cancelPauseOverlay()
                            }
                            stopProgressUpdates()
                            stopWatchProgressSaving()
                            if (playbackState != Player.STATE_BUFFERING) {
                                emitStopScrobbleForCurrentProgress()
                            }
                            
                            saveWatchProgress()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (hyperHdrCfg.isUsable && _exoPlayer?.isPlaying == true) {
                            scope.launch {
                                val cfg = hyperHdrCfg
                                val colorInfo = _exoPlayer?.videoFormat?.colorInfo
                                val newMode = detectHyperHdrCaptureMode(cfg, colorInfo)
                                if (newMode != hyperHdrCurrentMode) {
                                    startHyperHdrCapture(newMode)
                                }
                            }
                        }

                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        updateAvailableTracks(tracks)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        assSsaRenderController?.setVideoSize(videoSize.width, videoSize.height)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            assSsaRenderController?.onSeekStarted()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        cancelFirstFrameWatchdog()
                        val startupMs = (System.currentTimeMillis() - playerInitializationStartedAtMs)
                            .coerceAtLeast(0L)
                        val conversionCalls = DoviBridge.getConversionCallCount()
                        val conversionSucceeded = DoviBridge.getConversionSuccessCount()
                        val signalingRewrites =
                            MatroskaDolbyVisionHookInstaller.getCodecStringRewriteCount()
                        val sourceProfile =
                            MatroskaDolbyVisionHookInstaller.getLastDetectedSourceProfile()
                        val conversionMode =
                            MatroskaDolbyVisionHookInstaller.getLastSelectedConversionMode()
                        val conversionAttempted = conversionCalls > 0 || signalingRewrites > 0
                        if (
                            pendingSeekTelemetryAwaitingFirstFrame &&
                                pendingSeekTelemetryRequestedAtMs > 0L
                        ) {
                            val now = System.currentTimeMillis()
                            val totalLatencyMs =
                                (now - pendingSeekTelemetryRequestedAtMs).coerceAtLeast(0L)
                            val readyToFirstFrameMs =
                                if (pendingSeekTelemetryReadyAtMs > 0L) {
                                    (now - pendingSeekTelemetryReadyAtMs).coerceAtLeast(0L)
                                } else {
                                    -1L
                                }
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "SEEK_FIRST_FRAME: totalLatencyMs=$totalLatencyMs " +
                                    "readyLatencyMs=$pendingSeekTelemetryReadyLatencyMs " +
                                    "readyToFirstFrameMs=$readyToFirstFrameMs " +
                                    "targetMs=$pendingSeekTelemetryTargetMs " +
                                    "host=${currentStreamUrl.safeHost()}"
                            )
                            pendingSeekTelemetryRequestedAtMs = 0L
                            pendingSeekTelemetryTargetMs = -1L
                            pendingSeekTelemetryReadyAtMs = 0L
                            pendingSeekTelemetryReadyLatencyMs = -1L
                            pendingSeekTelemetryAwaitingFirstFrame = false
                        }
                        if (!hasRenderedFirstFrame) {
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "PLAYBACK_STARTUP: firstFrameMs=$startupMs " +
                                    "dv7doviActive=$isExperimentalDv7ToDv81ActiveForCurrentPlayback " +
                                    "dv7doviAttempted=$conversionAttempted " +
                                    "dvSourceProfile=${sourceProfile ?: "n/a"} " +
                                    "dvConvertMode=${conversionMode ?: "n/a"} " +
                                    "dv7doviSignalRewrites=$signalingRewrites " +
                                    "dv7doviCalls=$conversionCalls " +
                                    "dv7doviSuccess=$conversionSucceeded " +
                                    "dv7doviReason=${dv7ToDv81LastProbeReasonForCurrentPlayback ?: "n/a"} " +
                                    "dv7doviBridge=${dv7ToDv81BridgeVersionForCurrentPlayback ?: "n/a"} " +
                                    "host=${currentStreamUrl.safeHost()}"
                            )
                        }
                        mediaSourceFactory.notifyPlaybackFirstFrameRendered()
                        hasRenderedFirstFrame = true
                        maybeSchedulePostFirstFrameBufferingWatchdog(
                            playbackSessionId = playbackSessionId,
                            kodiCustomAudioSinkEnabled = kodiCustomAudioSinkEnabled
                        )
                        resumeAutoplayAfterLifecyclePause = false
                        _uiState.update { it.copy(showLoadingOverlay = false) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        cancelFirstFrameWatchdog()
                        if (error.isVc1DecoderFailure() &&
                            !isVc1SoftwareFallbackActiveForCurrentPlayback
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "VC1 decode failure detected, retrying with FFmpeg software " +
                                    "decoder preference host=${currentStreamUrl.safeHost()} " +
                                    "positionMs=$currentPosition"
                            )
                            vc1SoftwarePreferredStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithVc1SoftwareFallback(currentPosition)
                            return
                        }

                        if (error.isDav1dNeonFailure() &&
                            !isAv1FfmpegFallbackActiveForCurrentPlayback
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "AV1 dav1d init failure detected, retrying current stream " +
                                    "with FFmpeg video fallback host=${currentStreamUrl.safeHost()} " +
                                    "positionMs=$currentPosition"
                            )
                            av1FfmpegPreferredStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithAv1FfmpegFallback(currentPosition)
                            return
                        }

                        if (error.isVc1DecoderFailure() &&
                            isVc1SoftwareFallbackActiveForCurrentPlayback &&
                            !isVc1TrackSelectionBypassActiveForCurrentPlayback
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "VC1 decode failure persists, retrying with track-selection " +
                                    "bypass host=${currentStreamUrl.safeHost()} " +
                                    "positionMs=$currentPosition"
                            )
                            vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithVc1TrackSelectionBypass(currentPosition)
                            return
                        }

                        if (error.isAudioTrackInitializationFailure() &&
                            handleAudioTrackInitializationFailure(
                                kodiCustomAudioSinkEnabled = kodiCustomAudioSinkEnabled,
                                fromPositionMs = currentPosition,
                                source = "player error"
                            )
                        ) {
                            return
                        }

                        if (error.isStuckPlayingNoProgress()) {
                            val stuckDiskSpoolActive = mediaSourceFactory.isDiskSpoolSessionActive()
                            if (stuckDiskSpoolActive) {
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "Stuck player detected with disk spool active; stall is " +
                                        "data-delivery latency, not audio failure — " +
                                        "skipping safe audio recovery " +
                                        "host=${currentStreamUrl.safeHost()} " +
                                        "positionMs=$currentPosition"
                                )
                            } else if (kodiCustomAudioSinkEnabled) {
                                Log.w(
                                    PlayerRuntimeController.TAG,
                                    "Stuck player detected with custom Kodi IEC AudioSink enabled; " +
                                        "not retrying with safe audio fallback " +
                                        "host=${currentStreamUrl.safeHost()} " +
                                        "positionMs=$currentPosition"
                                )
                            } else {
                                if (!isSafeAudioModeActiveForCurrentPlayback) {
                                    Log.w(
                                        PlayerRuntimeController.TAG,
                                        "Stuck player detected, retrying with safe audio mode " +
                                            "host=${currentStreamUrl.safeHost()} " +
                                            "positionMs=$currentPosition"
                                    )
                                    safeAudioForcedStreamUrls.add(currentStreamUrl)
                                    retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                    return
                                }
                                if (!isAudioDisabledForCurrentPlayback) {
                                    Log.w(
                                        PlayerRuntimeController.TAG,
                                        "Stuck player persists in safe audio mode, retrying with " +
                                            "audio disabled host=${currentStreamUrl.safeHost()} " +
                                            "positionMs=$currentPosition"
                                    )
                                    audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                    retryCurrentStreamWithAudioDisabled(currentPosition)
                                    return
                                }
                            }
                        }

                        val timeoutError = error.findCause<SocketTimeoutException>()
                        if (timeoutError != null &&
                            timeoutRecoveryAttempts < PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "Timeout source error code=${error.errorCode} " +
                                    "attempt=${timeoutRecoveryAttempts + 1}/" +
                                    "${PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS} " +
                                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
                                    "positionMs=$currentPosition"
                            )
                            retryCurrentStreamAfterTimeout(currentPosition)
                            return
                        }

                        if (error.isUnexpectedLoaderNullPointer() &&
                            !hasRetriedCurrentStreamAfterUnexpectedNpe
                        ) {
                            hasRetriedCurrentStreamAfterUnexpectedNpe = true
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "Unexpected source NPE detected, retrying stream once " +
                                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
                                    "positionMs=$currentPosition"
                            )
                            retryCurrentStreamAfterUnexpectedNpe(currentPosition)
                            return
                        }

                        if (error.isMediaPeriodHolderStateCrash() &&
                            !hasRetriedCurrentStreamAfterMediaPeriodHolderCrash
                        ) {
                            hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = true
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "MediaPeriodHolder state crash detected, retrying stream once " +
                                    "host=${currentStreamUrl.safeHost()} " +
                                    "positionMs=$currentPosition"
                            )
                            retryCurrentStreamAfterMediaPeriodHolderCrash(currentPosition)
                            return
                        }

                        val detailedError = buildString {
                            append(error.message ?: "Playback error")
                            val cause = error.cause
                            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                                append(" (HTTP ${cause.responseCode})")
                            } else if (cause != null) {
                                append(": ${cause.message}")
                            }
                            append(" [${error.errorCode}]")
                        }
                        val responseCode =
                            (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                        if (responseCode == 416 && !hasRetriedCurrentStreamAfter416) {
                            retryCurrentStreamFromStartAfter416()
                            return
                        }
                        val classification = PlaybackErrorClassifier.classify(error)
                        val displayError = when (classification) {
                            PlaybackErrorClassifier.Classification.LinkExpired,
                            PlaybackErrorClassifier.Classification.Forbidden -> classification.userMessage
                            PlaybackErrorClassifier.Classification.Generic -> detailedError
                        }
                        _uiState.update {
                            it.copy(
                                error = displayError,
                                showLoadingOverlay = false,
                                showPauseOverlay = false
                            )
                        }
                    }
                }
                val analyticsListener = object : AnalyticsListener {
                    override fun onAudioSinkError(
                        eventTime: AnalyticsListener.EventTime,
                        audioSinkError: Exception
                    ) {
                        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return
                        if (!audioSinkError.isAudioSinkInitializationFailure()) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "Audio sink error callback did not match init failure " +
                                    "host=${currentStreamUrl.safeHost()} " +
                                    "error=${audioSinkError.describeCauseChain()}"
                            )
                            return
                        }
                        scope.launch {
                            if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return@launch
                            val livePlayer = _exoPlayer ?: return@launch
                            handleAudioTrackInitializationFailure(
                                kodiCustomAudioSinkEnabled = kodiCustomAudioSinkEnabled,
                                fromPositionMs = livePlayer.currentPosition,
                                source = "audio sink callback"
                            )
                        }
                    }
                }
                addListener(playerListener)
                addAnalyticsListener(analyticsListener)
                if (dv5HardwareToneMapActive) {
                    setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                        Dv5HardwareToneMapRpuTap.onFrameAboutToRender(presentationTimeUs)
                    }
                }
                val initialMediaSource = withContext(Dispatchers.IO) {
                    val addonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(addonBaseUrl)
                    val playableUrl = prepareMediaSourceUrl(url, headers, addonHost)
                    mediaSourceFactory.createMediaSource(
                        url = playableUrl,
                        headers = headers,
                        subtitleConfigurations = startupSubtitleConfigurations
                    )
                }
                setMediaSource(initialMediaSource)
                playWhenReady = true
                prepare()
                launchStartupPreparationTasks(
                    url = url,
                    headers = headers,
                    playerSettings = playerSettings,
                    retainedSelectedSubtitle = retainedSelectedSubtitle
                )
                scope.launch {
                    if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return@launch
                    val resolved = resolveBurnInProtectionState(playerSettings.burnInProtection.enabled)
                    if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return@launch
                    _uiState.update { it.copy(burnInProtection = resolved) }
                }
            }
        } catch (e: Exception) {
            com.nexio.tv.core.player.FrameRateUtils.endMainPlayerDisplayModeSession()
            assSsaPipelineSwitchInFlight = false
            assSsaRenderController?.release()
            assSsaRenderController = null
            activePlayerUsesAssSsaRenderer = false
            _uiState.update { it.copy(useAssSsaRenderOverlay = false) }
            if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    error = e.message ?: "Failed to initialize player",
                    showLoadingOverlay = false
                )
            }
        }
    }
}

private fun PlayerRuntimeController.launchStartupPreparationTasks(
    url: String,
    headers: Map<String, String>,
    playerSettings: com.nexio.tv.data.local.PlayerSettings,
    retainedSelectedSubtitle: Subtitle?
) {
    launchStartupAfrPreflight(
        url = url,
        headers = headers
    )

    startupSubtitlePreparationJob?.cancel()
    startupSubtitlePreparationJob = scope.launch {
        val startupSubtitlePreparation = prepareStartupSubtitles(
            mode = playerSettings.addonSubtitleStartupMode,
            preferredLanguage = playerSettings.subtitleStyle.preferredLanguage,
            secondaryLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage,
            retainedSelectedSubtitle = retainedSelectedSubtitle
        )
        if (currentStreamUrl != url) {
            return@launch
        }
        if (startupSubtitlePreparation.fetchCompleted) {
            _uiState.update {
                it.copy(
                    addonSubtitles = startupSubtitlePreparation.fetchedSubtitles,
                    isLoadingAddonSubtitles = false,
                    addonSubtitlesError = null
                )
            }
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } else {
            fetchAddonSubtitlesForCurrentStream(url)
        }
    }
}

private suspend fun PlayerRuntimeController.fetchAddonSubtitlesForCurrentStream(
    url: String
) {
    if (currentStreamUrl != url) {
        return
    }
    if (buildSubtitleFetchRequest() == null) {
        _uiState.update { it.copy(isLoadingAddonSubtitles = false, addonSubtitlesError = null) }
        return
    }
    _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }
    try {
        val subtitles = fetchAddonSubtitlesNow()
        if (currentStreamUrl != url) {
            return
        }
        _uiState.update {
            it.copy(
                addonSubtitles = subtitles,
                isLoadingAddonSubtitles = false,
                addonSubtitlesError = null
            )
        }
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
    } catch (e: Exception) {
        if (currentStreamUrl != url) {
            return
        }
        _uiState.update {
            it.copy(
                isLoadingAddonSubtitles = false,
                addonSubtitlesError = e.message
            )
        }
    }
}

internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    originalLanguage: String?
): List<String> {
    fun normalize(language: String?): String? {
        val raw = language
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        // Reject sentinel preference strings before hitting the language
        // normalizer (they are not real languages).
        if (raw == AudioLanguageOption.DEFAULT ||
            raw == AudioLanguageOption.DEVICE ||
            raw == AudioLanguageOption.ORIGINAL ||
            raw == SUBTITLE_LANGUAGE_FORCED
        ) {
            return null
        }
        // Route through the ISO normalizer so full display names
        // ("English"), ISO-3 codes ("eng"), and region-tagged tags
        // ("en-us") collapse to the canonical ISO-2 form the audio
        // track matcher expects.
        return PlayerSubtitleUtils.normalizeLanguageCode(raw)
            .takeIf { it.isNotBlank() }
    }

    return when (preferredAudioLanguage.trim().lowercase()) {
        AudioLanguageOption.DEFAULT -> listOfNotNull(
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
        AudioLanguageOption.DEVICE -> (
            deviceLanguages
            .mapNotNull(::normalize)
            + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
            ).distinct()
        AudioLanguageOption.ORIGINAL -> listOfNotNull(
            normalize(originalLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
    }
}

internal suspend fun PlayerRuntimeController.prepareStartupSubtitles(
    mode: AddonSubtitleStartupMode,
    preferredLanguage: String,
    secondaryLanguage: String?,
    retainedSelectedSubtitle: Subtitle?
): StartupSubtitlePreparation {
    val retainedAttachedSubtitles = listOfNotNull(retainedSelectedSubtitle)
        .distinctBy { "${it.id}|${it.url}" }

    if (mode == AddonSubtitleStartupMode.FAST_STARTUP) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = retainedAttachedSubtitles,
            fetchCompleted = false
        )
    }

    if (buildSubtitleFetchRequest() == null) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = retainedAttachedSubtitles,
            fetchCompleted = false
        )
    }

    val preferredTargets = when (PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)) {
        "none" -> listOfNotNull(
            secondaryLanguage
                ?.takeIf { it.isNotBlank() }
        )
        else -> listOfNotNull(
            preferredLanguage,
            secondaryLanguage?.takeIf { it.isNotBlank() }
        )
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }
        .distinct()

    if (mode == AddonSubtitleStartupMode.PREFERRED_ONLY && preferredTargets.isEmpty()) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = retainedAttachedSubtitles,
            fetchCompleted = false
        )
    }

    _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }

    val fetchedSubtitles = withTimeoutOrNull(STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS) {
        fetchAddonSubtitlesNow(
            preferredLanguageOverride = preferredLanguage,
            secondaryLanguageOverride = secondaryLanguage
        )
    } ?: return StartupSubtitlePreparation(
        fetchedSubtitles = emptyList(),
        attachedSubtitles = retainedAttachedSubtitles,
        fetchCompleted = false
    )

    val attachedSubtitles = (when (mode) {
        AddonSubtitleStartupMode.ALL_SUBTITLES -> fetchedSubtitles
        AddonSubtitleStartupMode.PREFERRED_ONLY -> fetchedSubtitles.filter { subtitle ->
            preferredTargets.any { target ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
            }
        }
        AddonSubtitleStartupMode.FAST_STARTUP -> emptyList()
    } + retainedAttachedSubtitles).distinctBy { "${it.id}|${it.url}" }

    return StartupSubtitlePreparation(
        fetchedSubtitles = fetchedSubtitles,
        attachedSubtitles = attachedSubtitles,
        fetchCompleted = true
    )
}

internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    cancelFirstFrameWatchdog()
    cancelPostFirstFrameBufferingWatchdog()
    hasRenderedFirstFrame = false
    shouldEnforceAutoplayOnFirstReady = true
    resumeAutoplayAfterLifecyclePause = false
    userPausedManually = false
    timeoutRecoveryAttempts = 0
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    isExperimentalDv7ToDv81ActiveForCurrentPlayback = false
    isAv1FfmpegFallbackActiveForCurrentPlayback = false
    isVc1SoftwareFallbackActiveForCurrentPlayback = false
    isVc1TrackSelectionBypassActiveForCurrentPlayback = false
    isSafeAudioModeActiveForCurrentPlayback = false
    isAudioDisabledForCurrentPlayback = false
    isKodiCustomAudioSinkActiveForCurrentPlayback = false
    dv7ToDv81BridgeVersionForCurrentPlayback = null
    dv7ToDv81LastProbeReasonForCurrentPlayback = null
    playerInitializationStartedAtMs = 0L
    pendingSeekTelemetryRequestedAtMs = 0L
    pendingSeekTelemetryTargetMs = -1L
    pendingSeekTelemetryReadyAtMs = 0L
    pendingSeekTelemetryReadyLatencyMs = -1L
    pendingSeekTelemetryAwaitingFirstFrame = false
    pendingSeekTelemetryReadyAssumed = false
    lastKnownDuration = 0L
    currentStreamHasVideoTrack = false
    currentVideoTrackIsLikelyVc1 = false
    currentVideoTrackMimeType = null
    currentVideoTrackCodecs = null
    currentVideoTrackWidth = 0
    currentVideoTrackHeight = 0
    currentVideoTrackSelected = false
    currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
    lastLoggedVideoTrackSignature = null
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false
        )
    }
}

private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val safeAudioModeEnabled: Boolean,
    private val cueGroupSubtitleTranslator: CueGroupSubtitleTranslator?,
    private val experimentalFireOsIecPassthroughEnabled: Boolean,
    private val disableDav1dForAv1: Boolean,
    private val experimentalDv5HardwareToneMapEnabled: Boolean,
    private val experimentalDv5HardwareToneMapCpuFallbackEnabled: Boolean,
    private val assSsaRenderControllerProvider: () -> AssSsaRenderController?
) : DefaultRenderersFactory(context) {

    override fun createRenderers(
        eventHandler: android.os.Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {
        val renderers = super.createRenderers(
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput
        ).toMutableList()
        assSsaRenderControllerProvider()?.let { controller ->
            renderers += AssSsaTimeRenderer(controller)
        }
        return renderers.toTypedArray()
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: android.os.Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
        if (disableDav1dForAv1) {
            out.removeAll { renderer ->
                renderer is androidx.media3.decoder.av1.Libdav1dVideoRenderer
            }
        }
        if (!experimentalDv5HardwareToneMapEnabled) {
            return
        }
        val mediaCodecRendererIndex = out.indexOfFirst { it is MediaCodecVideoRenderer }
        if (mediaCodecRendererIndex < 0) {
            Log.w(PlayerRuntimeController.TAG, "DV5_HW_RENDER: MediaCodec renderer not found")
            return
        }
        val dv5HardwareSink =
            ExperimentalDv5HardwareToneMapVideoSink(
                context,
                allowedVideoJoiningTimeMs,
                experimentalDv5HardwareToneMapCpuFallbackEnabled
            )
        val replacementRenderer = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(getCodecAdapterFactory())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            .setVideoSink(dv5HardwareSink)
            .build()
        out[mediaCodecRendererIndex] = replacementRenderer
        Log.i(
            PlayerRuntimeController.TAG,
            "DV5_HW_RENDER: enabled custom MediaCodec sink at index=$mediaCodecRendererIndex"
        )
    }

    @Suppress("DEPRECATION")
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        if (experimentalFireOsIecPassthroughEnabled) {
            fun createBaselineKodiSink(): KodiNativeAudioSink =
                KodiNativeAudioSink(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .build()
                )
            fun createTrueHdKodiSink(): KodiTrueHdNativeAudioSink =
                KodiTrueHdNativeAudioSink(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .build()
                )
            return KodiTrueHdEntryAudioSink(
                createBaselineKodiSink(),
                createTrueHdKodiSink()
            )
        }
        if (!safeAudioModeEnabled) {
            val builder = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            return builder.build()
        }
        val filteredCapabilities = buildStableAudioCapabilities(context)
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(filteredCapabilities)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val textRenderer = TextRenderer(
            output,
            outputLooper,
            androidx.media3.exoplayer.text.SubtitleDecoderFactory.DEFAULT,
            cueGroupSubtitleTranslator
        )
        if (shouldEnableLegacyTextDecodingForAssSsaPipeline(assSsaRenderControllerProvider() != null)) {
            textRenderer.experimentalSetLegacyDecodingEnabled(true)
        }
        out.add(
            SubtitleOffsetRenderer(
                textRenderer,
                subtitleDelayUsProvider
            )
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }
}

private class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val offset = subtitleDelayUsProvider()
        val adjustedPositionUs = (positionUs - offset).coerceAtLeast(0L)
        
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun PlaybackException.isDolbyVisionDecoderFailure(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("dolby-vision", ignoreCase = true) &&
        details.contains("decoder failed", ignoreCase = true)
}

private fun PlaybackException.isUnexpectedLoaderNullPointer(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("unexpected nullpointerexception", ignoreCase = true) ||
        (details.contains("nullpointerexception", ignoreCase = true) &&
            details.contains("matroskaextractor", ignoreCase = true))
}

private fun PlaybackException.isAudioTrackInitializationFailure(): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("audiotrack init failed", ignoreCase = true)
}

private fun Exception.isAudioSinkInitializationFailure(): Boolean {
    if (causeChain().any { it is AudioSink.InitializationException }) return true
    val details = describeCauseChain()
    return details.contains("audiotrack init failed", ignoreCase = true) ||
        details.contains("cannot create audiotrack", ignoreCase = true)
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    var depth = 0
    while (current != null && depth < 12) {
        yield(current)
        current = current.cause
        depth++
    }
}

private fun Throwable.describeCauseChain(): String {
    return causeChain().joinToString(" <- ") { throwable ->
        "${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}"
    }
}

private fun PlayerRuntimeController.handleAudioTrackInitializationFailure(
    kodiCustomAudioSinkEnabled: Boolean,
    fromPositionMs: Long,
    source: String
): Boolean {
    if (kodiCustomAudioSinkEnabled) {
        Log.w(
            PlayerRuntimeController.TAG,
            "AudioTrack init failed via $source with custom Kodi IEC AudioSink enabled; " +
                "not retrying with safe audio fallback " +
                "host=${currentStreamUrl.safeHost()} " +
                "positionMs=$fromPositionMs"
        )
        return false
    }
    if (!isSafeAudioModeActiveForCurrentPlayback) {
        Log.w(
            PlayerRuntimeController.TAG,
            "AudioTrack init failed via $source, retrying with safe audio mode " +
                "host=${currentStreamUrl.safeHost()} " +
                "positionMs=$fromPositionMs"
        )
        safeAudioForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithSafeAudioFallback(fromPositionMs)
        return true
    }
    if (!isAudioDisabledForCurrentPlayback) {
        Log.w(
            PlayerRuntimeController.TAG,
            "AudioTrack init still failing via $source in safe audio mode, retrying " +
                "with audio disabled host=${currentStreamUrl.safeHost()} " +
                "positionMs=$fromPositionMs"
        )
        audioDisabledForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithAudioDisabled(fromPositionMs)
        return true
    }
    return false
}

internal fun PlayerRuntimeController.cancelPostFirstFrameBufferingWatchdog() {
    postFirstFrameBufferingWatchdogJob?.cancel()
    postFirstFrameBufferingWatchdogJob = null
}

private fun PlayerRuntimeController.maybeSchedulePostFirstFrameBufferingWatchdog(
    playbackSessionId: Long,
    kodiCustomAudioSinkEnabled: Boolean
) {
    if (postFirstFrameBufferingWatchdogJob?.isActive == true) return
    postFirstFrameBufferingWatchdogJob = scope.launch {
        delay(PlayerRuntimeController.POST_FIRST_FRAME_BUFFERING_TIMEOUT_MS)

        if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return@launch
        if (!hasRenderedFirstFrame || userPausedManually) return@launch
        val livePlayer = _exoPlayer ?: return@launch
        if (!livePlayer.playWhenReady || livePlayer.playbackState != Player.STATE_BUFFERING) return@launch
        val currentPosition = livePlayer.currentPosition.coerceAtLeast(0L)
        if (currentPosition > PlayerRuntimeController.POST_FIRST_FRAME_STUCK_POSITION_MS) return@launch

        val diskSpoolActive = mediaSourceFactory.isDiskSpoolSessionActive()
        Log.w(
            PlayerRuntimeController.TAG,
            "POST_FIRST_FRAME_BUFFERING: stuck buffering after first frame " +
                "delayMs=${PlayerRuntimeController.POST_FIRST_FRAME_BUFFERING_TIMEOUT_MS} " +
                "positionMs=$currentPosition " +
                "safeAudio=$isSafeAudioModeActiveForCurrentPlayback " +
                "audioDisabled=$isAudioDisabledForCurrentPlayback " +
                "diskSpool=$diskSpoolActive " +
                "host=${currentStreamUrl.safeHost()}"
        )
        if (diskSpoolActive) {
            Log.i(
                PlayerRuntimeController.TAG,
                "POST_FIRST_FRAME_BUFFERING: disk spool active; buffering stall is " +
                    "data-delivery latency, not an audio init failure — skipping safe audio recovery"
            )
            return@launch
        }
        handleAudioTrackInitializationFailure(
            kodiCustomAudioSinkEnabled = kodiCustomAudioSinkEnabled,
            fromPositionMs = currentPosition,
            source = "post-first-frame buffering watchdog"
        )
    }
}

private fun PlaybackException.isStuckPlayingNoProgress(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_TIMEOUT) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("stuck playing with no progress", ignoreCase = true)
}

private fun PlaybackException.isMediaPeriodHolderStateCrash(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("mediaperiodholder", ignoreCase = true) &&
        details.contains(".info", ignoreCase = true) &&
        details.contains("null", ignoreCase = true)
}

private fun String.safeHost(): String {
    return runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
}

internal fun shouldRunEmbeddedAssSsaStartupProbe(
    nativeAssSsaAvailable: Boolean,
    pipelineOverrideForCurrentStream: Boolean?,
    url: String
): Boolean {
    return nativeAssSsaAvailable &&
        pipelineOverrideForCurrentStream == null &&
        shouldProbeEmbeddedAssSsaBeforePlayerInit(url)
}

internal fun shouldEnableLegacyTextDecodingForAssSsaPipeline(
    assSsaRenderActive: Boolean
): Boolean {
    return assSsaRenderActive
}

internal fun shouldEnableAssSsaPipelineForProgressiveFallback(
    url: String,
    filename: String?
): Boolean {
    return false
}

private fun shouldProbeEmbeddedAssSsaBeforePlayerInit(url: String): Boolean {
    return isProgressiveMediaUrl(url)
}

private fun isProgressiveMediaUrl(url: String): Boolean {
    val normalized = url
        .substringBefore('?')
        .substringBefore('#')
        .lowercase(Locale.US)
    return !normalized.endsWith(".m3u8") && !normalized.endsWith(".mpd")
}

private fun createDolbyVisionFallbackCodecSelector(
    forceVc1SoftwareDecode: Boolean,
    forceDolbyVisionSoftwareDecode: Boolean
): MediaCodecSelector {
    if (!forceVc1SoftwareDecode && !forceDolbyVisionSoftwareDecode) {
        return MediaCodecSelector.DEFAULT
    }

    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        if (forceVc1SoftwareDecode && mimeType == MimeTypes.VIDEO_VC1) {
            emptyList()
        } else if (forceDolbyVisionSoftwareDecode && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            emptyList()
        } else {
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
        }
    }
}

private fun Context.isNvidiaShieldDevice(): Boolean {
    val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
    val model = Build.MODEL.orEmpty().lowercase(Locale.ROOT)
    val hardware = Build.HARDWARE.orEmpty().lowercase(Locale.ROOT)
    val device = Build.DEVICE.orEmpty().lowercase(Locale.ROOT)
    return manufacturer.contains("nvidia") ||
        model.contains("shield") ||
        hardware.contains("tegra") ||
        device.contains("darcy") ||
        device.contains("foster")
}

private fun PlaybackException.isVc1DecoderFailure(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
        errorCode != PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    ) {
        return false
    }
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }.lowercase()
    return details.contains("video/wvc1") ||
        details.contains("vc-1") ||
        details.contains(" wvc1")
}

private fun PlaybackException.isDav1dNeonFailure(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
        errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    ) {
        return false
    }
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }.lowercase()
    return details.contains("libdav1dvideorenderer") &&
        details.contains("neon is not supported")
}

private fun describeExtensionRendererMode(mode: Int): String {
    return when (mode) {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> "off"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> "on"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> "prefer"
        else -> mode.toString()
    }
}

@Suppress("DEPRECATION")
private fun buildStableAudioCapabilities(context: Context): AudioCapabilities {
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    return AudioCapabilities(
        safeAudioModeSupportedEncodingsForTesting(),
        detected.maxChannelCount
    )
}

internal fun safeAudioModeSupportedEncodingsForTesting(): IntArray {
    return intArrayOf(C.ENCODING_PCM_16BIT)
}
