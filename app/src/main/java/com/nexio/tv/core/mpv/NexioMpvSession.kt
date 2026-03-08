package com.nexio.tv.core.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.nexio.tv.data.local.LibmpvVideoOutputMode
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.ui.screens.player.PlayerSubtitleUtils
import com.nexio.tv.ui.screens.player.TrackInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

private const val MPV_TAG = "NexioMpvSession"
private const val MPV_ANDROID_TV_FORWARD_CACHE_BYTES = 64L * 1024L * 1024L
private const val MPV_ANDROID_TV_BACK_CACHE_BYTES = 64L * 1024L * 1024L
private const val MPV_ANDROID_TV_READAHEAD_SECS = 0
private const val MPV_AUTO_VIDEO_OUTPUT_STARTUP_TIMEOUT_MS = 3_000L

private data class NexioMpvVideoOutputCandidate(
    val videoOutput: String,
    val hardwareDecode: String,
    val gpuApi: String? = null,
    val gpuContext: String? = null,
    val openglEs: Boolean = false
)

private data class NexioMpvConfig(
    val videoOutputMode: LibmpvVideoOutputMode,
    val videoOutputCandidates: List<NexioMpvVideoOutputCandidate>,
    val gpuNextDolbyVisionReshapingEnabled: Boolean,
    val hardwareDecodeCodecs: String,
    val audioOutput: String,
    val audioSpdifCodecs: String,
    val audioChannels: String,
    val cacheEnabled: Boolean,
    val demuxerMaxBytes: Long,
    val demuxerMaxBackBytes: Long,
    val demuxerReadaheadSecs: Int,
    val videoSync: String
) {
    companion object {
        private val gpuNextVulkanCandidate = NexioMpvVideoOutputCandidate(
            videoOutput = "gpu-next",
            hardwareDecode = "mediacodec",
            gpuApi = "vulkan"
        )

        private val gpuNextAndroidOpenGlCandidate = NexioMpvVideoOutputCandidate(
            videoOutput = "gpu-next",
            hardwareDecode = "mediacodec",
            gpuApi = "opengl",
            gpuContext = "android",
            openglEs = true
        )

        private val gpuAndroidOpenGlCandidate = NexioMpvVideoOutputCandidate(
            videoOutput = "gpu",
            hardwareDecode = "mediacodec",
            gpuContext = "android",
            openglEs = true
        )

        private val mediaCodecEmbedCandidate = NexioMpvVideoOutputCandidate(
            videoOutput = "mediacodec_embed",
            hardwareDecode = "mediacodec"
        )

        fun fromPlayerSettings(settings: PlayerSettings): NexioMpvConfig {
            val videoOutputCandidates = when (settings.libmpvVideoOutputMode) {
                LibmpvVideoOutputMode.AUTO -> listOf(
                    gpuNextAndroidOpenGlCandidate,
                    gpuNextVulkanCandidate,
                    gpuAndroidOpenGlCandidate,
                    mediaCodecEmbedCandidate
                )
                LibmpvVideoOutputMode.GPU_NEXT_ANDROID_OPENGL -> listOf(gpuNextAndroidOpenGlCandidate)
                LibmpvVideoOutputMode.GPU_NEXT_VULKAN -> listOf(gpuNextVulkanCandidate)
                LibmpvVideoOutputMode.GPU_ANDROID_OPENGL -> listOf(gpuAndroidOpenGlCandidate)
                LibmpvVideoOutputMode.MEDIACODEC_EMBED -> listOf(mediaCodecEmbedCandidate)
            }
            return NexioMpvConfig(
                videoOutputMode = settings.libmpvVideoOutputMode,
                videoOutputCandidates = videoOutputCandidates,
                gpuNextDolbyVisionReshapingEnabled =
                    settings.libmpvGpuNextDolbyVisionReshapingEnabled,
                hardwareDecodeCodecs = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
                audioOutput = "audiotrack,opensles",
                audioSpdifCodecs = if (settings.libmpvAudioPassthroughEnabled) {
                    "ac3,eac3,dts,dts-hd,truehd"
                } else {
                    ""
                },
                audioChannels = "auto-safe",
                cacheEnabled = false,
                demuxerMaxBytes = MPV_ANDROID_TV_FORWARD_CACHE_BYTES,
                demuxerMaxBackBytes = MPV_ANDROID_TV_BACK_CACHE_BYTES,
                demuxerReadaheadSecs = MPV_ANDROID_TV_READAHEAD_SECS,
                videoSync = ""
            )
        }
    }
}

data class NexioMpvRenderState(
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
)

data class NexioMpvPlaybackState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val playbackEnded: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val subtitleDelayMs: Int = 0,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val selectedAudioTrackIndex: Int = -1,
    val selectedSubtitleTrackIndex: Int = -1,
    val selectedExternalSubtitleUrl: String? = null,
    val errorMessage: String? = null
)

data class NexioMpvSubtitleCueState(
    val cueGroup: CueGroup = CueGroup.EMPTY_TIME_ZERO,
    val rawText: String? = null,
    val assText: String? = null,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null
)

private data class MpvTrackEntry(
    val id: Int,
    val type: String,
    val title: String?,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val forced: Boolean,
    val external: Boolean,
    val externalFilename: String?,
    val selected: Boolean
)

class NexioMpvSession(
    private val context: Context,
    private val externalScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : NexioMpvLib.EventObserver, NexioMpvLib.LogObserver {
    private val sessionScope = CoroutineScope(externalScope.coroutineContext + SupervisorJob())
    private val operationMutex = Mutex()
    private var initialized = false
    private var destroyed = false
    private var observersRegistered = false
    private var pendingInitialSeekMs: Long = 0L
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var selectedExternalSubtitleUrl: String? = null
    private var activeExternalSubtitleTrackId: Int? = null
    private var currentSubtitleText: String? = null
    private var currentSubtitleAssText: String? = null
    private var currentSubtitleStartMs: Long? = null
    private var currentSubtitleEndMs: Long? = null
    private var subtitleVisibility: Boolean = true
    private var currentConfig: NexioMpvConfig = buildConfig(PlayerSettings())
    private var currentVideoOutputIndex: Int = 0
    private var surfaceAttached: Boolean = false
    private var surfaceDetachInFlight: Boolean = false
    private var videoOutputFallbackPending: Boolean = false
    private var internalLoadInFlight: Boolean = false
    private var videoOutputStartupTimeoutJob: Job? = null
    private var pendingLoadUntilSurfaceAttach: Boolean = false

    private val _playbackState = MutableStateFlow(NexioMpvPlaybackState())
    val playbackState: StateFlow<NexioMpvPlaybackState> = _playbackState.asStateFlow()

    private val _renderState = MutableStateFlow(NexioMpvRenderState())
    val renderState: StateFlow<NexioMpvRenderState> = _renderState.asStateFlow()

    private val _subtitleCueState = MutableStateFlow(NexioMpvSubtitleCueState())
    val subtitleCueState: StateFlow<NexioMpvSubtitleCueState> = _subtitleCueState.asStateFlow()

    fun configure(settings: PlayerSettings) {
        if (destroyed) return
        val nextConfig = buildConfig(settings)
        val videoConfigChanged =
            nextConfig.videoOutputMode != currentConfig.videoOutputMode ||
                nextConfig.videoOutputCandidates != currentConfig.videoOutputCandidates
        currentConfig = nextConfig
        if (videoConfigChanged) {
            currentVideoOutputIndex = 0
        }
        val activeCandidate = activeVideoOutputCandidate()
        Log.i(
            MPV_TAG,
            "Applying mpv config vo=${activeCandidate.videoOutput} hwdec=${activeCandidate.hardwareDecode} " +
                "spdif=${currentConfig.audioSpdifCodecs.ifBlank { "off" }} cache=${currentConfig.demuxerMaxBytes}"
        )
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        if (videoConfigChanged) {
            applyActiveVideoOutputProperties()
            if (surfaceAttached) {
                setPropertyString("force-window", "yes")
            }
        }
        applyRuntimeProperties()
    }

    private fun buildConfig(settings: PlayerSettings): NexioMpvConfig {
        return NexioMpvConfig.fromPlayerSettings(settings)
    }

    fun ensureInitialized() {
        if (initialized || destroyed) return
        if (!NexioMpvLib.isAvailable) {
            val message = NexioMpvLib.availabilityError()?.message ?: "libmpv native libraries are unavailable"
            _playbackState.value = _playbackState.value.copy(errorMessage = message, isBuffering = false)
            return
        }
        initialized = true
        copyRequiredAssets()
        NexioMpvLib.create(context.applicationContext)
        setOption("profile", "fast")
        setOption("config", "yes")
        setOption("config-dir", context.filesDir.path)
        setOption("sub-ass-override", "force")
        setOption("input-default-bindings", "yes")
        setOption("keep-open", "yes")
        setOption("gpu-shader-cache-dir", context.cacheDir.path)
        setOption("icc-cache-dir", context.cacheDir.path)
        setOption("tls-verify", "yes")
        setOption("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        applyInitialConfig()
        NexioMpvLib.init()
        setOption("force-window", "no")
        setOption("idle", "once")
        setOption("save-position-on-quit", "no")
        NexioMpvLib.addObserver(this)
        NexioMpvLib.addLogObserver(this)
        observersRegistered = true
        observeProperties()
    }

    fun destroy() {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                finalizeDestroyLocked()
            }
        }
    }

    fun attachSurface(surface: Surface) {
        if (destroyed) return
        if (!NexioMpvLib.isAvailable) return
        ensureInitialized()
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                if (destroyed) return@withLock
                Log.d(MPV_TAG, "attachSurface pendingLoad=$pendingLoadUntilSurfaceAttach currentVo=${activeVideoOutputCandidate().videoOutput}")
                runCatching {
                    NexioMpvLib.attachSurface(surface)
                    surfaceAttached = true
                    surfaceDetachInFlight = false
                    applyActiveVideoOutputProperties()
                    setPropertyString("force-window", "yes")
                    if (pendingLoadUntilSurfaceAttach) {
                        performLoadCurrentMediaLocked()
                    }
                }.onFailure {
                    emitError("Failed to attach libmpv surface: ${it.message}")
                }
            }
        }
    }

    fun detachSurface() {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                Log.d(MPV_TAG, "detachSurface surfaceAttached=$surfaceAttached detachInFlight=$surfaceDetachInFlight")
                if (!surfaceAttached || surfaceDetachInFlight) {
                    return@withLock
                }
                surfaceDetachInFlight = true
                runCatching {
                    surfaceAttached = false
                    setPropertyString("vo", "null")
                    setPropertyString("force-window", "no")
                    NexioMpvLib.detachSurface()
                }
                surfaceDetachInFlight = false
            }
        }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable || width <= 0 || height <= 0) return
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                setPropertyString("android-surface-size", "${width}x$height")
            }
        }
    }

    fun load(
        url: String,
        headers: Map<String, String>,
        title: String?,
        startPositionMs: Long = 0L
    ) {
        ensureInitialized()
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        currentUrl = url
        currentTitle = title
        currentHeaders = headers
        pendingInitialSeekMs = startPositionMs
        selectedExternalSubtitleUrl = null
        activeExternalSubtitleTrackId = null
        subtitleVisibility = true
        clearSubtitleCueState()
        _playbackState.value = _playbackState.value.copy(
            isBuffering = true,
            playbackEnded = false,
            errorMessage = null,
            selectedSubtitleTrackIndex = -1
        )
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                runCatching {
                    Log.d(MPV_TAG, "load surfaceAttached=$surfaceAttached detachInFlight=$surfaceDetachInFlight startPositionMs=$startPositionMs url=${url.take(96)}")
                    if (!surfaceAttached || surfaceDetachInFlight) {
                        pendingLoadUntilSurfaceAttach = true
                    } else {
                        performLoadCurrentMediaLocked()
                    }
                }.onFailure {
                    internalLoadInFlight = false
                    emitError("Failed to open libmpv stream: ${it.message}")
                }
            }
        }
    }

    fun play() = setPaused(false)
    fun pause() = setPaused(true)

    fun stop() {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                runCatching { NexioMpvLib.command(arrayOf("stop")) }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        val seconds = positionMs.toDouble() / 1000.0
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                runCatching {
                    NexioMpvLib.command(
                        arrayOf("seek", seconds.toString(), "absolute+exact")
                    )
                }.onFailure {
                    runCatching { NexioMpvLib.setPropertyDouble("time-pos", seconds) }
                }
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        runCatching { NexioMpvLib.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun setSubtitleDelay(delayMs: Int) {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        runCatching { NexioMpvLib.setPropertyDouble("sub-delay", delayMs / 1000.0) }
    }

    fun setSubtitleVisibility(visible: Boolean) {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        if (subtitleVisibility == visible) return
        subtitleVisibility = visible
        runCatching { NexioMpvLib.setPropertyBoolean("sub-visibility", visible) }
            .onFailure { Log.w(MPV_TAG, "Failed to set subtitle visibility=$visible", it) }
    }

    fun selectAudioTrack(trackIndex: Int) {
        if (destroyed) return
        val track = _playbackState.value.audioTracks.getOrNull(trackIndex) ?: return
        val trackId = track.trackId ?: return
        runCatching { NexioMpvLib.setPropertyString("aid", trackId) }
        refreshTrackStateAsync()
    }

    fun selectSubtitleTrack(trackIndex: Int) {
        if (destroyed) return
        val track = _playbackState.value.subtitleTracks.getOrNull(trackIndex) ?: return
        val trackId = track.trackId ?: return
        runCatching { NexioMpvLib.setPropertyString("sid", trackId) }
        setSubtitleVisibility(true)
        clearSubtitleCueState()
        selectedExternalSubtitleUrl = null
        activeExternalSubtitleTrackId = null
        refreshTrackStateAsync()
    }

    fun disableSubtitles() {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        runCatching { NexioMpvLib.setPropertyString("sid", "no") }
        setSubtitleVisibility(true)
        clearSubtitleCueState()
        selectedExternalSubtitleUrl = null
        activeExternalSubtitleTrackId = null
        refreshTrackStateAsync()
    }

    fun selectAddonSubtitle(subtitle: Subtitle) {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        val normalizedLanguage = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                runCatching {
                    activeExternalSubtitleTrackId?.let { NexioMpvLib.command(arrayOf("sub-remove", it.toString())) }
                    NexioMpvLib.command(
                        arrayOf(
                            "sub-add",
                            subtitle.url,
                            "select",
                            subtitle.addonName.takeIf { it.isNotBlank() } ?: subtitle.id,
                            normalizedLanguage
                        )
                    )
                    setSubtitleVisibility(true)
                    clearSubtitleCueState()
                    selectedExternalSubtitleUrl = subtitle.url
                    refreshTrackState()
                }.onFailure {
                    emitError("Failed to load external subtitle in libmpv: ${it.message}")
                }
            }
        }
    }

    private fun setPaused(paused: Boolean) {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        runCatching { NexioMpvLib.setPropertyBoolean("pause", paused) }
    }

    private fun applyInitialConfig() {
        val activeCandidate = activeVideoOutputCandidate()
        setOption("vo", activeCandidate.videoOutput)
        setOption("hwdec", activeCandidate.hardwareDecode)
        setOption("hwdec-codecs", currentConfig.hardwareDecodeCodecs)
        setOption("ao", currentConfig.audioOutput)
        setOption("audio-spdif", currentConfig.audioSpdifCodecs)
        setOption("audio-channels", currentConfig.audioChannels)
        setOption("demuxer-max-bytes", currentConfig.demuxerMaxBytes.toString())
        setOption("demuxer-max-back-bytes", currentConfig.demuxerMaxBackBytes.toString())
        setOption("audio-set-media-role", "yes")
        activeCandidate.gpuApi?.let { setOption("gpu-api", it) }
        activeCandidate.gpuContext?.let { setOption("gpu-context", it) }
        if (activeCandidate.openglEs) {
            setOption("opengl-es", "yes")
        }
    }

    private fun applyRuntimeProperties() {
        setPropertyString("audio-spdif", currentConfig.audioSpdifCodecs)
        setPropertyString("audio-channels", currentConfig.audioChannels)
        setPropertyString("demuxer-max-bytes", currentConfig.demuxerMaxBytes.toString())
        setPropertyString("demuxer-max-back-bytes", currentConfig.demuxerMaxBackBytes.toString())
    }

    private fun applyActiveVideoOutputProperties() {
        val activeCandidate = activeVideoOutputCandidate()
        setPropertyString("vo", activeCandidate.videoOutput)
        setPropertyString("hwdec", activeCandidate.hardwareDecode)
        activeCandidate.gpuApi?.let { setPropertyString("gpu-api", it) }
        activeCandidate.gpuContext?.let { setPropertyString("gpu-context", it) }
        if (activeCandidate.openglEs) {
            setPropertyString("opengl-es", "yes")
        }
    }

    private fun activeVideoOutputCandidate(): NexioMpvVideoOutputCandidate {
        return currentConfig.videoOutputCandidates.getOrElse(currentVideoOutputIndex) {
            currentConfig.videoOutputCandidates.last()
        }
    }

    private fun scheduleVideoOutputFallback(reason: String) {
        if (destroyed) return
        if (currentConfig.videoOutputMode != LibmpvVideoOutputMode.AUTO) return
        if (videoOutputFallbackPending) return
        val nextIndex = currentVideoOutputIndex + 1
        if (nextIndex >= currentConfig.videoOutputCandidates.size) {
            Log.w(MPV_TAG, "libmpv auto video output exhausted after ${activeVideoOutputCandidate().videoOutput}: $reason")
            return
        }
        videoOutputFallbackPending = true
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                currentVideoOutputIndex = nextIndex
                val candidate = activeVideoOutputCandidate()
                Log.w(MPV_TAG, "Falling back libmpv video output to ${candidate.videoOutput}: $reason")
                runCatching {
                    applyActiveVideoOutputProperties()
                    if (surfaceAttached) {
                        setPropertyString("force-window", "yes")
                    }
                    reloadCurrentMediaLocked()
                }.onFailure {
                    Log.w(MPV_TAG, "Failed to switch libmpv video output to ${candidate.videoOutput}", it)
                }
                videoOutputFallbackPending = false
            }
        }
    }

    private fun reloadCurrentMediaLocked() {
        val url = currentUrl ?: return
        val positionMs = _playbackState.value.currentPositionMs
        pendingInitialSeekMs = positionMs.coerceAtLeast(0L)
        if (!surfaceAttached || surfaceDetachInFlight) {
            pendingLoadUntilSurfaceAttach = true
            return
        }
        performLoadCurrentMediaLocked()
    }

    private fun performLoadCurrentMediaLocked() {
        val url = currentUrl ?: return
        pendingLoadUntilSurfaceAttach = false
        internalLoadInFlight = true
        Log.d(MPV_TAG, "performLoadCurrentMediaLocked vo=${activeVideoOutputCandidate().videoOutput} title=${currentTitle ?: "unknown"}")
        applyRuntimeProperties()
        applyRequestHeaders(currentHeaders)
        currentTitle?.let { NexioMpvLib.setPropertyString("force-media-title", it) }
        NexioMpvLib.command(arrayOf("loadfile", url, "replace"))
        armVideoOutputStartupWatchdog()
    }

    private fun armVideoOutputStartupWatchdog() {
        videoOutputStartupTimeoutJob?.cancel()
        if (destroyed) return
        if (currentConfig.videoOutputMode != LibmpvVideoOutputMode.AUTO) return
        if (activeVideoOutputCandidate().videoOutput == "mediacodec_embed") return
        videoOutputStartupTimeoutJob = sessionScope.launch(ioDispatcher) {
            delay(MPV_AUTO_VIDEO_OUTPUT_STARTUP_TIMEOUT_MS)
            if (destroyed) return@launch
            if (_playbackState.value.isReady) return@launch
            scheduleVideoOutputFallback("startup timed out after ${MPV_AUTO_VIDEO_OUTPUT_STARTUP_TIMEOUT_MS}ms")
        }
    }

    private fun setOption(name: String, value: String) {
        val result = runCatching { NexioMpvLib.setOptionString(name, value) }.getOrElse {
            Log.w(MPV_TAG, "Failed to set mpv option $name=$value", it)
            return
        }
        if (result < 0) {
            Log.w(MPV_TAG, "mpv rejected option $name=$value (code=$result)")
        }
    }

    private fun copyRequiredAssets() {
        listOf("subfont.ttf", "cacert.pem").forEach { assetName ->
            runCatching {
                val destination = File(context.filesDir, assetName)
                context.assets.open(assetName).use { input ->
                    val assetSize = input.available().toLong()
                    if (destination.exists() && destination.length() == assetSize) {
                        return@runCatching
                    }
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure {
                Log.w(MPV_TAG, "Failed to copy required mpv asset $assetName", it)
            }
        }
    }

    private fun setPropertyString(name: String, value: String) {
        runCatching { NexioMpvLib.setPropertyString(name, value) }
            .onFailure { Log.w(MPV_TAG, "Failed to set mpv property $name=$value", it) }
    }

    private fun observeProperties() {
        val observers = listOf(
            "pause" to NexioMpvLib.Format.FLAG,
            "time-pos" to NexioMpvLib.Format.DOUBLE,
            "duration" to NexioMpvLib.Format.DOUBLE,
            "speed" to NexioMpvLib.Format.DOUBLE,
            "paused-for-cache" to NexioMpvLib.Format.FLAG,
            "track-list/count" to NexioMpvLib.Format.INT64,
            "current-vo" to NexioMpvLib.Format.STRING,
            "aid" to NexioMpvLib.Format.STRING,
            "sid" to NexioMpvLib.Format.STRING,
            "sub-delay" to NexioMpvLib.Format.DOUBLE,
            "sub-text" to NexioMpvLib.Format.STRING,
            "sub-text/ass" to NexioMpvLib.Format.STRING,
            "sub-start/full" to NexioMpvLib.Format.DOUBLE,
            "sub-end/full" to NexioMpvLib.Format.DOUBLE,
            "video-params/w" to NexioMpvLib.Format.INT64,
            "video-params/h" to NexioMpvLib.Format.INT64,
            "eof-reached" to NexioMpvLib.Format.FLAG
        )
        observers.forEach { (property, format) ->
            runCatching { NexioMpvLib.observeProperty(property, format) }
        }
    }

    private fun applyRequestHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) {
            runCatching { NexioMpvLib.setPropertyString("http-header-fields", "") }
            return
        }
        val headerFields = headers.entries.joinToString(",") { (key, value) ->
            "$key: $value"
        }
        NexioMpvLib.setPropertyString("http-header-fields", headerFields)
    }

    private fun refreshTrackStateAsync() {
        if (!initialized || destroyed || !NexioMpvLib.isAvailable) return
        sessionScope.launch(ioDispatcher) {
            operationMutex.withLock {
                refreshTrackState()
            }
        }
    }

    private fun refreshTrackState() {
        if (destroyed) return
        val count = NexioMpvLib.getPropertyInt("track-list/count") ?: 0
        val entries = buildList {
            for (index in 0 until count) {
                val prefix = "track-list/$index"
                val id = NexioMpvLib.getPropertyInt("$prefix/id") ?: continue
                val type = NexioMpvLib.getPropertyString("$prefix/type") ?: continue
                add(
                    MpvTrackEntry(
                        id = id,
                        type = type,
                        title = NexioMpvLib.getPropertyString("$prefix/title"),
                        language = NexioMpvLib.getPropertyString("$prefix/lang"),
                        codec = NexioMpvLib.getPropertyString("$prefix/codec"),
                        channelCount = NexioMpvLib.getPropertyInt("$prefix/demux-channel-count"),
                        forced = NexioMpvLib.getPropertyBoolean("$prefix/forced") == true,
                        external = NexioMpvLib.getPropertyBoolean("$prefix/external") == true,
                        externalFilename = NexioMpvLib.getPropertyString("$prefix/external-filename"),
                        selected = NexioMpvLib.getPropertyBoolean("$prefix/selected") == true
                    )
                )
            }
        }

        val audioTracks = mutableListOf<TrackInfo>()
        val subtitleTracks = mutableListOf<TrackInfo>()
        var selectedAudioIndex = -1
        var selectedSubtitleIndex = -1
        activeExternalSubtitleTrackId = null
        entries.forEach { entry ->
            when (entry.type) {
                "audio" -> {
                    val displayName = entry.title
                        ?: entry.language
                        ?: "Audio ${audioTracks.size + 1}"
                    val trackInfo = TrackInfo(
                        index = audioTracks.size,
                        name = displayName,
                        language = entry.language,
                        trackId = entry.id.toString(),
                        codec = entry.codec,
                        channelCount = entry.channelCount,
                        isSelected = entry.selected
                    )
                    if (entry.selected) selectedAudioIndex = audioTracks.size
                    audioTracks += trackInfo
                }
                "sub" -> {
                    if (entry.external) {
                        if (entry.selected) {
                            activeExternalSubtitleTrackId = entry.id
                            selectedExternalSubtitleUrl = entry.externalFilename ?: selectedExternalSubtitleUrl
                        }
                        return@forEach
                    }
                    val displayName = entry.title
                        ?: entry.language
                        ?: "Subtitle ${subtitleTracks.size + 1}"
                    val trackInfo = TrackInfo(
                        index = subtitleTracks.size,
                        name = displayName,
                        language = entry.language,
                        trackId = entry.id.toString(),
                        codec = entry.codec,
                        isForced = entry.forced,
                        isSelected = entry.selected
                    )
                    if (entry.selected) selectedSubtitleIndex = subtitleTracks.size
                    subtitleTracks += trackInfo
                }
            }
        }

        _playbackState.value = _playbackState.value.copy(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = selectedSubtitleIndex,
            selectedExternalSubtitleUrl = selectedExternalSubtitleUrl
        )
    }

    private fun emitError(message: String) {
        Log.e(MPV_TAG, message)
        _playbackState.value = _playbackState.value.copy(
            errorMessage = message,
            isBuffering = false
        )
    }

    private fun clearSubtitleCueState() {
        currentSubtitleText = null
        currentSubtitleAssText = null
        currentSubtitleStartMs = null
        currentSubtitleEndMs = null
        _subtitleCueState.value = NexioMpvSubtitleCueState()
    }

    private fun updateSubtitleCueState() {
        val text = currentSubtitleText?.trim()?.takeIf { it.isNotBlank() }
        val assText = currentSubtitleAssText?.trim()?.takeIf { it.isNotBlank() }
        val cueGroup = if (text == null) {
            CueGroup.EMPTY_TIME_ZERO
        } else {
            CueGroup(
                listOf(Cue.Builder().setText(text).build()),
                (currentSubtitleStartMs ?: 0L) * 1000L
            )
        }
        _subtitleCueState.value = NexioMpvSubtitleCueState(
            cueGroup = cueGroup,
            rawText = text,
            assText = assText,
            startTimeMs = currentSubtitleStartMs,
            endTimeMs = currentSubtitleEndMs
        )
    }

    override fun eventProperty(property: String) {
        if (destroyed) return
        when (property) {
            "sub-text", "sub-text/ass", "sub-start/full", "sub-end/full" -> clearSubtitleCueState()
        }
    }

    override fun eventProperty(property: String, value: Long) {
        if (destroyed) return
        when (property) {
            "track-list/count", "video-params/w", "video-params/h" -> {
                if (property.startsWith("video-params/")) {
                    _renderState.value = _renderState.value.copy(
                        videoWidth = if (property.endsWith("/w")) value.toInt() else _renderState.value.videoWidth,
                        videoHeight = if (property.endsWith("/h")) value.toInt() else _renderState.value.videoHeight
                    )
                }
                refreshTrackStateAsync()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (destroyed) return
        when (property) {
            "pause" -> _playbackState.value = _playbackState.value.copy(isPlaying = !value)
            "paused-for-cache" -> _playbackState.value = _playbackState.value.copy(isBuffering = value)
            "eof-reached" -> {
                if (!internalLoadInFlight) {
                    _playbackState.value = _playbackState.value.copy(playbackEnded = value)
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (destroyed) return
        when (property) {
            "current-vo" -> {
                if (currentConfig.videoOutputMode == LibmpvVideoOutputMode.AUTO && value.isBlank()) {
                    scheduleVideoOutputFallback("current-vo became empty")
                }
            }
            "aid", "sid" -> refreshTrackStateAsync()
            "sub-text" -> {
                currentSubtitleText = value
                updateSubtitleCueState()
            }
            "sub-text/ass" -> {
                currentSubtitleAssText = value
                updateSubtitleCueState()
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        if (destroyed) return
        when (property) {
            "time-pos" -> _playbackState.value = _playbackState.value.copy(
                currentPositionMs = (value * 1000.0).toLong()
            )
            "duration" -> _playbackState.value = _playbackState.value.copy(
                durationMs = (value * 1000.0).toLong()
            )
            "speed" -> _playbackState.value = _playbackState.value.copy(playbackSpeed = value.toFloat())
            "sub-delay" -> _playbackState.value = _playbackState.value.copy(subtitleDelayMs = (value * 1000.0).toInt())
            "sub-start/full" -> {
                currentSubtitleStartMs = (value * 1000.0).toLong()
                updateSubtitleCueState()
            }
            "sub-end/full" -> {
                currentSubtitleEndMs = (value * 1000.0).toLong()
                updateSubtitleCueState()
            }
        }
    }

    override fun event(eventId: Int) {
        if (destroyed) return
        when (eventId) {
            NexioMpvLib.Event.START_FILE -> {
                _playbackState.value = _playbackState.value.copy(
                    isBuffering = true,
                    playbackEnded = false,
                    errorMessage = null
                )
            }
            NexioMpvLib.Event.FILE_LOADED,
            NexioMpvLib.Event.VIDEO_RECONFIG,
            NexioMpvLib.Event.AUDIO_RECONFIG,
            NexioMpvLib.Event.PLAYBACK_RESTART -> {
                videoOutputStartupTimeoutJob?.cancel()
                videoOutputStartupTimeoutJob = null
                if (currentConfig.videoOutputMode == LibmpvVideoOutputMode.AUTO) {
                    sessionScope.launch(ioDispatcher) {
                        val currentVo = runCatching { NexioMpvLib.getPropertyString("current-vo") }.getOrNull()
                        if (currentVo.isNullOrBlank()) {
                            scheduleVideoOutputFallback("video output did not become active")
                        }
                    }
                }
                _playbackState.value = _playbackState.value.copy(
                    isReady = true,
                    isBuffering = false,
                    playbackEnded = false,
                    errorMessage = null
                )
                internalLoadInFlight = false
                videoOutputFallbackPending = false
                if (pendingInitialSeekMs > 0L) {
                    seekTo(pendingInitialSeekMs)
                    pendingInitialSeekMs = 0L
                }
                refreshTrackStateAsync()
            }
            NexioMpvLib.Event.END_FILE -> {
                videoOutputStartupTimeoutJob?.cancel()
                videoOutputStartupTimeoutJob = null
                clearSubtitleCueState()
                if (!internalLoadInFlight) {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        isBuffering = false,
                        playbackEnded = true
                    )
                }
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (destroyed) return
        if (currentConfig.videoOutputMode == LibmpvVideoOutputMode.AUTO) {
            val normalized = text.lowercase()
            if (
                normalized.contains("failed initializing any suitable video output") ||
                normalized.contains("error opening/initializing the selected video_out") ||
                normalized.contains("failed initializing any suitable gpu context") ||
                normalized.contains("video output creation failed") ||
                normalized.contains("acquirelatestimage failed") ||
                normalized.contains("waiting for frame timed out") ||
                normalized.contains("failed to retrieve an image from image reader") ||
                normalized.contains("image reader")
            ) {
                scheduleVideoOutputFallback(text)
            }
        }
        Log.d(MPV_TAG, "[$prefix/$level] $text")
    }

    fun isReusableForPlayback(): Boolean {
        return !destroyed
    }

    private fun removeObserversLocked() {
        if (!observersRegistered) return
        observersRegistered = false
        runCatching { NexioMpvLib.removeObserver(this@NexioMpvSession) }
        runCatching { NexioMpvLib.removeLogObserver(this@NexioMpvSession) }
    }

    private fun finalizeDestroyLocked() {
        if (destroyed) return
        destroyed = true
        runCatching {
            setPropertyString("vo", "null")
            setPropertyString("force-window", "no")
            if (surfaceAttached) {
                NexioMpvLib.detachSurface()
            }
        }
        initialized = false
        surfaceAttached = false
        surfaceDetachInFlight = false
        internalLoadInFlight = false
        videoOutputFallbackPending = false
        pendingLoadUntilSurfaceAttach = false
        videoOutputStartupTimeoutJob?.cancel()
        videoOutputStartupTimeoutJob = null
        removeObserversLocked()
        runCatching { NexioMpvLib.destroy() }
        sessionScope.cancel()
    }
}
