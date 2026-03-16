package com.nexio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.kodi.KodiNativeAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
import com.nexio.tv.core.player.DoviBridge
import com.nexio.tv.core.player.Dv5HardwareToneMapRpuTap
import com.nexio.tv.core.player.MatroskaDolbyVisionHookInstaller
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nexio.tv.data.local.FrameRateMatchingMode
import com.nexio.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 10_000L

internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(url: String, headers: Map<String, String>) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = "No stream URL provided", showLoadingOverlay = false) }
        return
    }

    scope.launch {
        try {
            autoSubtitleSelected = false
            hasScannedTextTracksOnce = false
            resetLoadingOverlayForNewStream()
            playerInitializationStartedAtMs = System.currentTimeMillis()
            val playerSettings = playerSettingsDataStore.playerSettings.first()
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
            _uiState.update {
                it.copy(
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode
                )
            }
            requestedUseLibassByUser = playerSettings.useLibass
            if (libassPipelineDecisionStreamUrl != currentStreamUrl) {
                libassPipelineDecisionStreamUrl = currentStreamUrl
                libassPipelineOverrideForCurrentStream = null
                libassPipelineSwitchInFlight = false
            }
            val retainedSelectedSubtitle = _uiState.value.selectedAddonSubtitle
            Dv5HardwareToneMapRpuTap.setEnabledForPlayback(enabled = false, streamUrl = url)
            val useLibass = when {
                !requestedUseLibassByUser -> false
                libassPipelineOverrideForCurrentStream != null -> libassPipelineOverrideForCurrentStream == true
                else -> false
            }
            val requestedLibassRenderType = playerSettings.libassRenderType.toAssRenderType()
            val libassRenderType = when {
                !useLibass -> requestedLibassRenderType
                requestedLibassRenderType == AssRenderType.OVERLAY_OPEN_GL -> AssRenderType.EFFECTS_OPEN_GL
                requestedLibassRenderType == AssRenderType.OVERLAY_CANVAS -> AssRenderType.EFFECTS_CANVAS
                else -> requestedLibassRenderType
            }
            DoviBridge.resetRuntimeCounters()
            MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()
            val dv7ToDv81Probe = if (playerSettings.experimentalDv7ToDv81Enabled) {
                DoviBridge.probeRealtimeConversionSupport(url)
            } else {
                DoviBridge.RealtimeConversionProbe(
                    supported = false,
                    reason = "setting-disabled",
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
                playerSettings.experimentalDv7ToDv81Enabled && dv7ToDv81Probe.supported
            dv7ToDv81BridgeVersionForCurrentPlayback = dv7ToDv81Probe.bridgeVersion
            dv7ToDv81LastProbeReasonForCurrentPlayback = dv7ToDv81Probe.reason
            Log.i(
                PlayerRuntimeController.TAG,
                "DV7_DOVI: setting=${playerSettings.experimentalDv7ToDv81Enabled} " +
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
            val loadControl = DefaultLoadControl.Builder().build()

            mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
            mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
            mediaSourceFactory.parallelChunkSizeMb = playerSettings.parallelChunkSizeMb
            mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
            mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
            if (kodiCustomAudioSinkEnabled) {
                safeAudioForcedStreamUrls.remove(url)
                audioDisabledForcedStreamUrls.remove(url)
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

                val localeList = Resources.getSystem().configuration.locales
                val deviceLanguages = List(localeList.size()) { localeList[it].isO3Language }
                val preferredAudioLanguages = resolvePreferredAudioLanguages(
                    preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                    deviceLanguages = deviceLanguages
                )
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
            val dv5SoftwareToneMapEnabled = playerSettings.experimentalDv5ToneMapToSdrEnabled
            isDv5SoftwareToneMapSettingEnabledForCurrentPlayback = dv5SoftwareToneMapEnabled
            val dv5HardwareToneMapEnabled =
                playerSettings.experimentalDv5HardwareToneMapToSdrEnabled
            val dv5HardwareToneMapCpuFallbackEnabled =
                playerSettings.experimentalDv5HardwareToneMapCpuFallbackEnabled
            isDv5HardwareToneMapSettingEnabledForCurrentPlayback = dv5HardwareToneMapEnabled
            val dv5ToneMapNativeBuildSupported = FfmpegLibrary.supportsExperimentalDv5ToneMapToSdr()
            val dv5SoftwareToneMapRuntimeSupported =
                FfmpegLibrary.supportsExperimentalDv5SoftwareToneMapToSdrRuntime()
            isDv5HardwareToneMapNativeSupportedForCurrentPlayback = dv5ToneMapNativeBuildSupported
            isDv5SoftwareToneMapNativeSupportedForCurrentPlayback =
                dv5ToneMapNativeBuildSupported && dv5SoftwareToneMapRuntimeSupported
            val dv5HardwareForced = dv5HardwareToneMapPreferredStreamUrls.contains(url)
            val dv5SoftwareForced = !dv5HardwareForced &&
                dv5SoftwareToneMapPreferredStreamUrls.contains(url)
            val dv5DetectedByProfile = dv5SoftwareForced || dv5HardwareForced
            val displaySupportsDolbyVision = context.supportsDolbyVisionHdrOutput()
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
                enabled = playerSettings.experimentalDv7ToDv81Enabled,
                allowDv5Conversion = playerSettings.experimentalDv5ToDv81Enabled,
                preserveMappingEnabled = playerSettings.experimentalDv7ToDv81PreserveMappingEnabled,
                enableRpuTap = dv5HardwareToneMapActive,
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
            if (playerSettings.experimentalDv7ToDv81Enabled || dv5HardwareToneMapActive) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_DOVI: extractorHookInstalled=$dolbyVisionHookInstalledForPlayback " +
                        "active=$isExperimentalDv7ToDv81ActiveForCurrentPlayback " +
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
                forceDolbyVisionSoftwareDecode = dv5SoftwareToneMapActive
            )
            val effectiveDecoderPriority = if (av1FfmpegFallbackActive) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else if (vc1SoftwareFallbackActive) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else if (dv5SoftwareToneMapActive) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                playerSettings.decoderPriority
            }
            FfmpegLibrary.setExperimentalDv5ToneMapToSdrEnabled(dv5SoftwareToneMapActive)
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                safeAudioModeEnabled = safeAudioModeEnabled,
                cueGroupSubtitleTranslator = builtInSubtitleCueTranslator,
                experimentalFireOsIecPassthroughEnabled =
                    playerSettings.experimentalDtsIecPassthroughEnabled,
                disableDav1dForAv1 = av1FfmpegFallbackActive,
                experimentalDv5HardwareToneMapEnabled = dv5HardwareToneMapActive,
                experimentalDv5HardwareToneMapCpuFallbackEnabled =
                    dv5HardwareToneMapCpuFallbackEnabled
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
                    "host=${url.safeHost()}"
            )

            val buildDefaultPlayer = {
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = null,
                    subtitleParserFactory = null
                )
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .build()
            }

            _exoPlayer = if (useLibass) {
                ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                    .buildWithAssSupportCompat(
                        context = context,
                        renderType = libassRenderType,
                        playerMediaSourceFactory = mediaSourceFactory,
                        extractorsFactory = extractorsFactory,
                        renderersFactory = renderersFactory
                    )
            } else {
                buildDefaultPlayer()
            }
            activePlayerUsesLibass = useLibass
            libassPipelineSwitchInFlight = false

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
                        currentCueGroup = cueGroup
                        handleBuiltInCueGroupUpdate()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) {
                            lastKnownDuration = playerDuration
                        }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        _uiState.update { 
                            it.copy(
                                isBuffering = isBuffering,
                                playbackEnded = playbackState == Player.STATE_ENDED,
                                duration = playerDuration.coerceAtLeast(0L)
                            )
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
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
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            userPausedManually = false
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
                        updateAvailableTracks(tracks)
                    }

                    override fun onRenderedFirstFrame() {
                        cancelFirstFrameWatchdog()
                        mediaSourceFactory.notifyPlaybackFirstFrameRendered()
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
                        hasRenderedFirstFrame = true
                        _uiState.update { it.copy(showLoadingOverlay = false) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
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

                        if (error.isAudioTrackInitializationFailure()) {
                            if (kodiCustomAudioSinkEnabled) {
                                Log.w(
                                    PlayerRuntimeController.TAG,
                                    "AudioTrack init failed with custom Kodi IEC AudioSink enabled; " +
                                        "not retrying with safe audio fallback " +
                                        "host=${currentStreamUrl.safeHost()} " +
                                        "positionMs=$currentPosition"
                                )
                            } else {
                                if (!isSafeAudioModeActiveForCurrentPlayback) {
                                    Log.w(
                                        PlayerRuntimeController.TAG,
                                        "AudioTrack init failed, retrying with safe audio mode " +
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
                                        "AudioTrack init still failing in safe audio mode, retrying " +
                                            "with audio disabled host=${currentStreamUrl.safeHost()} " +
                                            "positionMs=$currentPosition"
                                    )
                                    audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                    retryCurrentStreamWithAudioDisabled(currentPosition)
                                    return
                                }
                            }
                        }

                        if (error.isStuckPlayingNoProgress()) {
                            if (kodiCustomAudioSinkEnabled) {
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
                        _uiState.update {
                            it.copy(
                                error = detailedError,
                                showLoadingOverlay = false,
                                showPauseOverlay = false
                            )
                        }
                    }
                }
                addListener(playerListener)
                if (dv5HardwareToneMapActive) {
                    setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                        Dv5HardwareToneMapRpuTap.onFrameAboutToRender(presentationTimeUs)
                    }
                }
                val initialMediaSource = withContext(Dispatchers.IO) {
                    mediaSourceFactory.createMediaSource(
                        url = url,
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
            }
        } catch (e: Exception) {
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
        headers = headers,
        frameRateMatchingMode = playerSettings.frameRateMatchingMode,
        resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
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
    deviceLanguages: List<String>
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = language
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            SUBTITLE_LANGUAGE_FORCED -> null
            else -> normalized
        }
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
    hasRenderedFirstFrame = false
    shouldEnforceAutoplayOnFirstReady = true
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
    private val experimentalDv5HardwareToneMapCpuFallbackEnabled: Boolean
) : DefaultRenderersFactory(context) {

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
            return KodiNativeAudioSink(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build()
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
        out.add(
            SubtitleOffsetRenderer(
                TextRenderer(
                    output,
                    outputLooper,
                    androidx.media3.exoplayer.text.SubtitleDecoderFactory.DEFAULT,
                    cueGroupSubtitleTranslator
                ),
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

@Suppress("DEPRECATION")
private fun Context.supportsDolbyVisionHdrOutput(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    val displayManager = getSystemService(DisplayManager::class.java) ?: return false
    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: return false
    return hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
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
    val supportedEncodings = mutableListOf<Int>()
    val knownEncodings = intArrayOf(
        C.ENCODING_PCM_16BIT,
        C.ENCODING_AC3,
        C.ENCODING_AC4,
        C.ENCODING_DTS,
        C.ENCODING_E_AC3_JOC,
        C.ENCODING_E_AC3,
        C.ENCODING_DOLBY_TRUEHD
    )
    for (encoding in knownEncodings) {
        if (detected.supportsEncoding(encoding)) {
            supportedEncodings += encoding
        }
    }
    // Force DTS-HD/DTS:X passthrough down to DTS core. This avoids AudioTrack init failures on
    // devices that advertise DTS-HD direct playback but fail to initialize the encoded sink.
    if ((detected.supportsEncoding(C.ENCODING_DTS_HD) ||
            detected.supportsEncoding(C.ENCODING_DTS_UHD_P2)) &&
        C.ENCODING_DTS !in supportedEncodings
    ) {
        supportedEncodings += C.ENCODING_DTS
    }
    return AudioCapabilities(
        supportedEncodings.toIntArray(),
        detected.maxChannelCount
    )
}
