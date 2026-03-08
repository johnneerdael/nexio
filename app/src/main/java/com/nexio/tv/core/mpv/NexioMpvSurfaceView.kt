package com.nexio.tv.core.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import com.nexio.tv.data.local.LibmpvVideoOutputMode
import com.nexio.tv.data.local.PlayerSettings
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToLong

private const val MPV_TAG = "NexioMpvSurfaceView"
private const val MPV_ANDROID_TV_CACHE_BYTES = 64L * 1024L * 1024L
private const val MPV_SUBTITLE_VERTICAL_OFFSET_BASELINE_SHIFT = 25
private const val LIBMPV_DV_RESHAPE_EXPERIMENTAL_NATIVE_AVAILABLE = false

data class NexioMpvTrack(
    val id: Int,
    val type: String,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val isSelected: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean
)

data class NexioMpvTrackSnapshot(
    val audioTracks: List<NexioMpvTrack>,
    val subtitleTracks: List<NexioMpvTrack>
)

private data class NexioMpvViewVideoOutputCandidate(
    val videoOutput: String,
    val hardwareDecode: String,
    val gpuApi: String? = null,
    val gpuContext: String? = null,
    val openglEs: Boolean = false
)

private data class NexioMpvResolvedProfile(
    val candidate: NexioMpvViewVideoOutputCandidate,
    val enableDolbyVisionReshaping: Boolean,
    val hardwareDecode: String,
    val toneMapping: String? = null,
    val hdrComputePeak: String? = null,
    val targetColorspaceHint: String? = null
) {
    val profileKey: String =
        buildString {
            append(candidate.videoOutput)
            append('|')
            append(candidate.gpuApi ?: "")
            append('|')
            append(candidate.gpuContext ?: "")
            append('|')
            append(hardwareDecode)
            append('|')
            append(enableDolbyVisionReshaping)
        }
}

private data class NexioMpvViewConfig(
    val videoOutputMode: LibmpvVideoOutputMode,
    val videoOutputCandidates: List<NexioMpvViewVideoOutputCandidate>,
    val gpuNextDolbyVisionReshapingEnabled: Boolean,
    val hardwareDecodeCodecs: String,
    val audioOutput: String,
    val audioSpdifCodecs: String,
    val audioChannels: String,
) {
    companion object {
        private val gpuNextVulkanCandidate = NexioMpvViewVideoOutputCandidate(
            videoOutput = "gpu-next",
            hardwareDecode = "mediacodec",
            gpuApi = "vulkan"
        )

        private val gpuNextAndroidOpenGlCandidate = NexioMpvViewVideoOutputCandidate(
            videoOutput = "gpu-next",
            hardwareDecode = "mediacodec",
            gpuApi = "opengl",
            gpuContext = "android",
            openglEs = true
        )

        private val gpuAndroidOpenGlCandidate = NexioMpvViewVideoOutputCandidate(
            videoOutput = "gpu",
            hardwareDecode = "mediacodec",
            gpuContext = "android",
            openglEs = true
        )

        private val mediaCodecEmbedCandidate = NexioMpvViewVideoOutputCandidate(
            videoOutput = "mediacodec_embed",
            hardwareDecode = "mediacodec"
        )

        fun fromPlayerSettings(settings: PlayerSettings): NexioMpvViewConfig {
            val gpuNextDolbyVisionReshapingEnabled =
                settings.libmpvGpuNextDolbyVisionReshapingEnabled
            val candidates = when (settings.libmpvVideoOutputMode) {
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
            return NexioMpvViewConfig(
                videoOutputMode = settings.libmpvVideoOutputMode,
                videoOutputCandidates = candidates,
                gpuNextDolbyVisionReshapingEnabled = gpuNextDolbyVisionReshapingEnabled,
                hardwareDecodeCodecs = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
                // Keep the proven mpv-android AO order and let audio-spdif
                // decide passthrough, instead of forcing a custom AO split.
                audioOutput = "audiotrack,opensles",
                audioSpdifCodecs = if (settings.libmpvAudioPassthroughEnabled) {
                    "ac3,eac3,dts,dts-hd,truehd"
                } else {
                    ""
                },
                audioChannels = "auto-safe"
            )
        }
    }
}

class NexioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    private var initialized = false
    private var hasQueuedInitialMedia = false
    private var lastMediaRequestKey: String? = null
    private var subtitleVisibility: Boolean = true
    private var currentConfig = NexioMpvViewConfig.fromPlayerSettings(PlayerSettings())
    private var currentStreamNameHint: String? = null
    private var currentFilenameHint: String? = null
    private var activeProfileKey: String? = null

    private val _subtitleCueState = MutableStateFlow(NexioMpvSubtitleCueState())
    val subtitleCueState: StateFlow<NexioMpvSubtitleCueState> = _subtitleCueState.asStateFlow()

    fun configure(settings: PlayerSettings) {
        currentConfig = NexioMpvViewConfig.fromPlayerSettings(settings)
        Log.i(
            MPV_TAG,
            "configure: videoOutputMode=${settings.libmpvVideoOutputMode} " +
                "dvReshape=${settings.libmpvGpuNextDolbyVisionReshapingEnabled} " +
                "audioPassthrough=${settings.libmpvAudioPassthroughEnabled} " +
                "audioSpdif=\"${currentConfig.audioSpdifCodecs}\""
        )
    }

    fun ensureInitialized() {
        if (initialized) return
        copyRequiredAssets()
        initialize(
            configDir = context.filesDir.path,
            cacheDir = context.cacheDir.path
        )
        initialized = true
    }

    fun setMedia(
        url: String,
        headers: Map<String, String>,
        streamName: String? = null,
        filename: String? = null
    ) {
        currentStreamNameHint = streamName?.takeIf { it.isNotBlank() }
        currentFilenameHint = filename?.takeIf { it.isNotBlank() }
        val desiredProfile = resolveProfile()
        if (initialized && activeProfileKey != desiredProfile.profileKey) {
            runCatching { destroy() }
                .onFailure { Log.w(MPV_TAG, "Failed to recreate libmpv for profile switch: ${it.message}") }
            initialized = false
            hasQueuedInitialMedia = false
            lastMediaRequestKey = null
            activeProfileKey = null
        }
        ensureInitialized()
        if (!initialized) return

        val requestKey = buildMediaRequestKey(url = url, headers = headers)
        if (hasQueuedInitialMedia && requestKey == lastMediaRequestKey) {
            return
        }
        applyHeaders(headers)
        if (hasQueuedInitialMedia) {
            MPVLib.command(arrayOf("loadfile", url, "replace"))
        } else {
            playFile(url)
            hasQueuedInitialMedia = true
        }
        lastMediaRequestKey = requestKey
        runCatching {
            MPVLib.setPropertyString("aid", "auto")
            MPVLib.setPropertyString("sid", "auto")
            MPVLib.setPropertyBoolean("sub-visibility", true)
        }.onFailure {
            Log.w(MPV_TAG, "Failed to reset default A/V track selection: ${it.message}")
        }
        clearSubtitleCueState()
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        MPVLib.setPropertyBoolean("pause", paused)
    }

    fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return MPVLib.getPropertyBoolean("pause") == false
    }

    fun isPausedForCacheNow(): Boolean {
        if (!initialized) return false
        return MPVLib.getPropertyBoolean("paused-for-cache") == true
    }

    fun isCoreIdleNow(): Boolean {
        if (!initialized) return false
        return MPVLib.getPropertyBoolean("core-idle") == true
    }

    fun seekToMs(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        MPVLib.setPropertyDouble("time-pos", seconds)
    }

    fun currentPositionMs(): Long {
        if (!initialized) return 0L
        val seconds = MPVLib.getPropertyDouble("time-pos/full")
            ?: MPVLib.getPropertyDouble("time-pos")
            ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        if (!initialized) return 0L
        val seconds = MPVLib.getPropertyDouble("duration/full") ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    fun stopPlayback() {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("stop")) }
            .onFailure { Log.w(MPV_TAG, "Failed to stop libmpv playback: ${it.message}") }
    }

    fun applyAudioLanguagePreferences(languages: List<String>) {
        if (!initialized) return
        val normalized = languages
            .mapNotNull { language ->
                language.trim().takeIf { it.isNotBlank() }
            }
            .distinct()
        runCatching {
            MPVLib.setPropertyString("alang", normalized.joinToString(","))
            MPVLib.setPropertyString("aid", "auto")
        }.onFailure {
            Log.w(MPV_TAG, "Failed to set audio language preference: ${it.message}")
        }
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        if (!initialized) return
        runCatching {
            MPVLib.setPropertyDouble("sub-delay", delayMs / 1000.0)
        }.onFailure {
            Log.w(MPV_TAG, "Failed to set subtitle delay on mpv: ${it.message}")
        }
    }

    fun setSubtitleVisibility(visible: Boolean) {
        if (!initialized) return
        if (subtitleVisibility == visible) return
        subtitleVisibility = visible
        runCatching {
            MPVLib.setPropertyBoolean("sub-visibility", visible)
        }.onFailure {
            Log.w(MPV_TAG, "Failed to set subtitle visibility=$visible: ${it.message}")
        }
    }

    fun applySubtitleStyle(style: com.nexio.tv.data.local.SubtitleStyleSettings) {
        if (!initialized) return
        runCatching {
            val scale = (style.size / 100.0).coerceIn(0.5, 3.0)
            val effectiveVerticalOffset = style.verticalOffset - MPV_SUBTITLE_VERTICAL_OFFSET_BASELINE_SHIFT
            val normalizedOffset = ((effectiveVerticalOffset + 20).coerceIn(0, 70)) / 70.0
            val subPos = (95.0 - (normalizedOffset * 25.0)).coerceIn(65.0, 100.0)
            val outlineSize = if (style.outlineEnabled) {
                style.outlineWidth.coerceIn(1, 6).toDouble()
            } else {
                0.0
            }
            val backgroundAlpha = (style.backgroundColor ushr 24) and 0xFF
            val borderStyle = if (backgroundAlpha > 0) "opaque-box" else "outline-and-shadow"

            MPVLib.setPropertyDouble("sub-scale", scale)
            MPVLib.setPropertyBoolean("sub-bold", style.bold)
            MPVLib.setPropertyDouble("sub-outline-size", outlineSize)
            MPVLib.setPropertyDouble("sub-pos", subPos)
            MPVLib.setPropertyDouble("sub-shadow-offset", 0.0)
            MPVLib.setPropertyString("sub-border-style", borderStyle)
            MPVLib.setPropertyString("sub-color", toMpvColor(style.textColor))
            MPVLib.setPropertyString("sub-back-color", toMpvColor(style.backgroundColor))
            MPVLib.setPropertyString("sub-outline-color", toMpvColor(style.outlineColor))
        }.onFailure {
            Log.w(MPV_TAG, "Failed to apply subtitle style on mpv: ${it.message}")
        }
    }

    fun selectAudioTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            MPVLib.setPropertyInt("aid", trackId)
            true
        }.getOrElse {
            Log.w(MPV_TAG, "Failed to select audio track id=$trackId: ${it.message}")
            false
        }
    }

    fun selectSubtitleTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            MPVLib.setPropertyBoolean("sub-visibility", true)
            MPVLib.setPropertyInt("sid", trackId)
            true
        }.getOrElse {
            Log.w(MPV_TAG, "Failed to select subtitle track id=$trackId: ${it.message}")
            false
        }
    }

    fun disableSubtitles(): Boolean {
        if (!initialized) return false
        return runCatching {
            MPVLib.setPropertyString("sid", "no")
            MPVLib.setPropertyBoolean("sub-visibility", false)
            true
        }.getOrElse {
            Log.w(MPV_TAG, "Failed to disable subtitles: ${it.message}")
            false
        }
    }

    fun addAndSelectExternalSubtitle(
        url: String,
        title: String? = null,
        language: String? = null
    ): Boolean {
        if (!initialized) return false
        if (url.isBlank()) return false
        return runCatching {
            val safeTitle = title?.takeIf { it.isNotBlank() }
            val safeLanguage = language?.takeIf { it.isNotBlank() }
            when {
                safeTitle != null && safeLanguage != null ->
                    MPVLib.command(arrayOf("sub-add", url, "cached", safeTitle, safeLanguage))
                safeTitle != null ->
                    MPVLib.command(arrayOf("sub-add", url, "cached", safeTitle))
                else ->
                    MPVLib.command(arrayOf("sub-add", url, "cached"))
            }
            MPVLib.setPropertyBoolean("sub-visibility", true)
            true
        }.getOrElse {
            Log.w(MPV_TAG, "Failed to add external subtitle: ${it.message}")
            false
        }
    }

    fun applySubtitleLanguagePreferences(preferred: String, secondary: String?) {
        if (!initialized) return
        val languages = listOfNotNull(
            preferred.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
            secondary?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        )
        if (languages.isEmpty()) return
        runCatching {
            MPVLib.setPropertyString("slang", languages.joinToString(","))
        }.onFailure {
            Log.w(MPV_TAG, "Failed to set subtitle language preference: ${it.message}")
        }
    }

    fun readTrackSnapshot(): NexioMpvTrackSnapshot {
        if (!initialized) return NexioMpvTrackSnapshot(emptyList(), emptyList())
        val trackCount = runCatching { MPVLib.getPropertyInt("track-list/count") ?: 0 }
            .getOrDefault(0)
        if (trackCount <= 0) {
            return NexioMpvTrackSnapshot(emptyList(), emptyList())
        }

        val selectedAudioTrackId = MPVLib.getPropertyString("aid")?.toIntOrNull()
            ?: MPVLib.getPropertyInt("current-tracks/audio/id")
        val selectedSubtitleTrackId = MPVLib.getPropertyString("sid")?.toIntOrNull()
            ?: MPVLib.getPropertyInt("current-tracks/sub/id")

        val audioTracks = mutableListOf<NexioMpvTrack>()
        val subtitleTracks = mutableListOf<NexioMpvTrack>()

        for (i in 0 until trackCount) {
            val type = MPVLib.getPropertyString("track-list/$i/type")?.lowercase() ?: continue
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            val language = MPVLib.getPropertyString("track-list/$i/lang")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val title = MPVLib.getPropertyString("track-list/$i/title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val codec = MPVLib.getPropertyString("track-list/$i/codec")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val selectedByFlag = MPVLib.getPropertyBoolean("track-list/$i/selected") == true
            val external = MPVLib.getPropertyBoolean("track-list/$i/external") == true
            val channelCount = MPVLib.getPropertyInt("track-list/$i/demux-channel-count")
                ?: MPVLib.getPropertyInt("track-list/$i/audio-channels")
                ?: MPVLib.getPropertyInt("track-list/$i/channels")
            val forced = (MPVLib.getPropertyBoolean("track-list/$i/forced") == true) || listOfNotNull(title, language).any {
                it.contains("forced", ignoreCase = true)
            }
            val selected = when (type) {
                "audio" -> (selectedAudioTrackId != null && selectedAudioTrackId == id) || selectedByFlag
                "sub" -> (selectedSubtitleTrackId != null && selectedSubtitleTrackId == id) || selectedByFlag
                else -> selectedByFlag
            }

            when (type) {
                "audio" -> {
                    audioTracks += NexioMpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: "Audio $id",
                        language = language,
                        codec = codec,
                        channelCount = channelCount,
                        isSelected = selected,
                        isForced = false,
                        isExternal = external
                    )
                }
                "sub" -> {
                    subtitleTracks += NexioMpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: "Subtitle $id",
                        language = language,
                        codec = codec,
                        channelCount = null,
                        isSelected = selected,
                        isForced = forced,
                        isExternal = external
                    )
                }
            }
        }

        return NexioMpvTrackSnapshot(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks
        )
    }

    fun releasePlayer() {
        if (!initialized) return
        runCatching { destroy() }
            .onFailure { Log.w(MPV_TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
        initialized = false
        hasQueuedInitialMedia = false
        lastMediaRequestKey = null
        subtitleVisibility = true
        activeProfileKey = null
        clearSubtitleCueState()
    }

    fun setResizeMode(mode: Int) = Unit

    fun setVideoSize(width: Int, height: Int) = Unit

    override fun initOptions() {
        val profile = resolveProfile()
        val candidate = profile.candidate
        Log.i(
            MPV_TAG,
            "initOptions: vo=${candidate.videoOutput} gpuApi=${candidate.gpuApi ?: "n/a"} " +
                "gpuContext=${candidate.gpuContext ?: "n/a"} hwdec=${profile.hardwareDecode} " +
                "dvReshape=${profile.enableDolbyVisionReshaping} " +
                "audioSpdif=\"${currentConfig.audioSpdifCodecs}\""
        )
        MPVLib.setOptionString("profile", "fast")
        setVo(candidate.videoOutput)
        candidate.gpuContext?.let { MPVLib.setOptionString("gpu-context", it) }
        candidate.gpuApi?.let { MPVLib.setOptionString("gpu-api", it) }
        if (candidate.openglEs) {
            MPVLib.setOptionString("opengl-es", "yes")
        }
        MPVLib.setOptionString("sub-ass-override", "force")
        MPVLib.setOptionString("hwdec", profile.hardwareDecode)
        MPVLib.setOptionString("hwdec-codecs", currentConfig.hardwareDecodeCodecs)
        profile.toneMapping?.let { MPVLib.setOptionString("tone-mapping", it) }
        profile.hdrComputePeak?.let { MPVLib.setOptionString("hdr-compute-peak", it) }
        profile.targetColorspaceHint?.let { MPVLib.setOptionString("target-colorspace-hint", it) }
        MPVLib.setOptionString("ao", currentConfig.audioOutput)
        MPVLib.setOptionString("audio-set-media-role", "yes")
        MPVLib.setOptionString("audio-spdif", currentConfig.audioSpdifCodecs)
        MPVLib.setOptionString("audio-channels", currentConfig.audioChannels)
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        MPVLib.setOptionString("input-default-bindings", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", MPV_ANDROID_TV_CACHE_BYTES.toString())
        MPVLib.setOptionString("demuxer-max-back-bytes", MPV_ANDROID_TV_CACHE_BYTES.toString())
        MPVLib.setOptionString("keep-open", "yes")
        activeProfileKey = profile.profileKey
    }

    override fun postInitOptions() {
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setPropertyString("audio-spdif", currentConfig.audioSpdifCodecs)
        MPVLib.setPropertyString("audio-channels", currentConfig.audioChannels)
        Log.i(
            MPV_TAG,
            "postInitOptions: audioSpdif=\"${currentConfig.audioSpdifCodecs}\" " +
                "audioChannels=${currentConfig.audioChannels}"
        )
    }

    override fun observeProperties() {
        // Progress is polled by PlayerRuntimeController, matching NuvioTV-mpv.
    }

    private fun applyHeaders(headers: Map<String, String>) {
        val raw = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = ",") { "${it.key}: ${it.value}" }
        MPVLib.setPropertyString("http-header-fields", raw)
    }

    private fun buildMediaRequestKey(url: String, headers: Map<String, String>): String {
        val normalizedHeaders = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = "|") { "${it.key.trim()}:${it.value.trim()}" }
        return "$url#$normalizedHeaders"
    }

    private fun resolveProfile(): NexioMpvResolvedProfile {
        val candidate = currentConfig.videoOutputCandidates.first()
        val enableReshaping =
            LIBMPV_DV_RESHAPE_EXPERIMENTAL_NATIVE_AVAILABLE &&
                currentConfig.gpuNextDolbyVisionReshapingEnabled &&
                candidate.videoOutput == "gpu-next" &&
                isLikelyDolbyVisionProfile5Stream()
        return if (enableReshaping) {
            NexioMpvResolvedProfile(
                candidate = candidate,
                enableDolbyVisionReshaping = true,
                // DV reshaping needs copy-back surfaces; normal Android playback should stay on zero-copy.
                hardwareDecode = "mediacodec-copy",
                toneMapping = "bt.2446a",
                hdrComputePeak = "yes",
                targetColorspaceHint = "no"
            )
        } else {
            NexioMpvResolvedProfile(
                candidate = candidate,
                enableDolbyVisionReshaping = false,
                hardwareDecode = candidate.hardwareDecode
            )
        }
    }

    private fun isLikelyDolbyVisionProfile5Stream(): Boolean {
        val text = listOfNotNull(currentFilenameHint, currentStreamNameHint)
            .joinToString(" ")
            .ifBlank { return false }
            .lowercase(Locale.ROOT)
        val hasDolbyVision = Regex("""(^|[^a-z0-9])(dovi|dolby[ ._-]?vision|dv)([^a-z0-9]|$)""")
            .containsMatchIn(text)
        if (!hasDolbyVision) return false
        val isWebBased = Regex("""web[ ._-]?dl|web[ ._-]?rip|amzn|atvp|dsnp|nf|netflix|hmax|hulu""")
            .containsMatchIn(text)
        val isDiscBased = Regex("""blu[ ._-]?ray|bdrip|bdremux|remux|uhd[ ._-]?blu""")
            .containsMatchIn(text)
        return isWebBased && !isDiscBased
    }

    private fun clearSubtitleCueState() {
        _subtitleCueState.value = NexioMpvSubtitleCueState()
    }

    private fun copyRequiredAssets() {
        copyAssetIfMissingOrChanged("cacert.pem")
        copyAssetIfMissingOrChanged("subfont.ttf")
    }

    private fun copyAssetIfMissingOrChanged(assetName: String) {
        val output = File(context.filesDir, assetName)
        runCatching {
            context.assets.open(assetName).use { input ->
                val bytes = input.readBytes()
                if (output.exists() && output.length() == bytes.size.toLong()) {
                    return
                }
                FileOutputStream(output).use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
            }
        }.onFailure {
            Log.w(MPV_TAG, "Failed to copy asset $assetName: ${it.message}")
        }
    }

    private fun toMpvColor(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        return "#%02X%02X%02X%02X".format(alpha, red, green, blue)
    }
}
