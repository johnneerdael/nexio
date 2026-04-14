package com.nexio.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import com.nexio.tv.data.local.MpvHardwareDecodeMode
import com.nexio.tv.data.local.SubtitleStyleSettings
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.Utils
import java.util.Locale
import kotlin.math.roundToLong

class NexioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    private var initialized = false
    private var hasQueuedInitialMedia = false
    private var lastMediaRequestKey: String? = null
    private var hardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE

    fun ensureInitialized() {
        if (initialized) return
        Utils.copyAssets(context)
        initialize(
            configDir = context.filesDir.path,
            cacheDir = context.cacheDir.path
        )
        initialized = true
    }

    fun setMedia(url: String, headers: Map<String, String>) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url = url, headers = headers)
        if (hasQueuedInitialMedia && requestKey == lastMediaRequestKey) return

        applyHeaders(headers)
        if (hasQueuedInitialMedia) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            mpv.command("loadfile", url, "replace")
        } else {
            playFile(url)
            ensureSurfaceAttachedIfAlreadyAvailable()
            hasQueuedInitialMedia = true
        }
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
    }

    private fun ensureSurfaceAttachedIfAlreadyAvailable() {
        if (!initialized) return
        val currentHolder = holder
        val currentSurface = currentHolder.surface ?: return
        if (!currentSurface.isValid) return
        runCatching {
            surfaceCreated(currentHolder)
        }.onFailure {
            Log.w(TAG, "Failed to force MPV surface attach: ${it.message}")
        }
    }

    private fun applyDefaultTrackSelectionForNewLoad() {
        runCatching {
            mpv.setPropertyString("aid", "auto")
            mpv.setPropertyString("sid", "auto")
            mpv.setPropertyBoolean("sub-visibility", true)
        }.onFailure {
            Log.w(TAG, "Failed to reset default A/V track selection: ${it.message}")
        }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", paused)
    }

    fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("pause") == false
    }

    fun isPausedForCacheNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("paused-for-cache") == true
    }

    fun isCoreIdleNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("core-idle") == true
    }

    fun seekToMs(positionMs: Long) {
        if (!initialized) return
        mpv.setPropertyDouble("time-pos", positionMs.coerceAtLeast(0L) / 1000.0)
    }

    fun currentPositionMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("time-pos/full")
            ?: mpv.getPropertyDouble("time-pos")
            ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("duration/full") ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun hasVideoTrackSelectedNow(): Boolean {
        if (!initialized) return false
        val vid = mpv.getPropertyString("vid")?.trim()
        return !vid.isNullOrBlank() && !vid.equals("no", ignoreCase = true)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    fun applyAudioLanguagePreferences(languages: List<String>) {
        if (!initialized) return
        val normalized = languages
            .mapNotNull { language -> language.trim().takeIf { it.isNotBlank() } }
            .distinct()
        runCatching {
            mpv.setPropertyString("alang", normalized.joinToString(","))
            mpv.setPropertyString("aid", "auto")
        }.onFailure {
            Log.w(TAG, "Failed to set audio language preference: ${it.message}")
        }
    }

    fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        hardwareDecodeMode = mode
        if (!initialized) return
        runCatching {
            mpv.setPropertyString("hwdec", mode.toMpvHwdecValue())
        }.onFailure {
            Log.w(TAG, "Failed to apply mpv hardware decode mode ($mode): ${it.message}")
        }
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        if (!initialized) return
        runCatching {
            mpv.setPropertyDouble("sub-delay", delayMs / 1000.0)
        }.onFailure {
            Log.w(TAG, "Failed to set subtitle delay on mpv: ${it.message}")
        }
    }

    fun applyResizeMode(resizeMode: Int) {
        when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> applyCoverAspectScale()
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                scaleX = 1.3333f
                scaleY = 1.0f
            }
            else -> {
                scaleX = 1.0f
                scaleY = 1.0f
            }
        }
    }

    fun applySubtitleStyle(style: SubtitleStyleSettings) {
        if (!initialized) return
        runCatching {
            val scale = (style.size / 100.0).coerceIn(0.5, 3.0)
            val clampedOffset = style.verticalOffset.coerceIn(
                SUBTITLE_VERTICAL_OFFSET_MIN,
                SUBTITLE_VERTICAL_OFFSET_MAX
            )
            val normalizedOffset = (clampedOffset - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble() /
                (SUBTITLE_VERTICAL_OFFSET_MAX - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble()
            val subPos = MPV_SUB_POS_AT_BOTTOM -
                (normalizedOffset * (MPV_SUB_POS_AT_BOTTOM - MPV_SUB_POS_AT_TOP))
            val subMarginY = (MPV_SUB_MARGIN_Y_MIN +
                (normalizedOffset * (MPV_SUB_MARGIN_Y_MAX - MPV_SUB_MARGIN_Y_MIN))).toInt()
            val outlineSize = when {
                !style.outlineEnabled -> 0.0
                isAssOrSsaSubtitleSelectedNow() -> style.outlineWidth.coerceIn(1, 6).toDouble()
                else -> 1.0
            }
            val backgroundAlpha = (style.backgroundColor ushr 24) and 0xFF
            val borderStyle = if (backgroundAlpha > 0) "opaque-box" else "outline-and-shadow"

            mpv.setPropertyDouble("sub-scale", scale)
            mpv.setPropertyBoolean("sub-bold", style.bold)
            mpv.setPropertyDouble("sub-outline-size", outlineSize)
            mpv.setPropertyDouble("sub-pos", subPos)
            mpv.setPropertyInt("sub-margin-y", subMarginY)
            mpv.setPropertyDouble("sub-shadow-offset", 0.0)
            mpv.setPropertyString("sub-border-style", borderStyle)
            mpv.setPropertyString("sub-color", toMpvColor(style.textColor))
            mpv.setPropertyString("sub-back-color", toMpvColor(style.backgroundColor))
            mpv.setPropertyString("sub-outline-color", toMpvColor(style.outlineColor))
        }.onFailure {
            Log.w(TAG, "Failed to apply subtitle style on mpv: ${it.message}")
        }
    }

    private fun isAssOrSsaSubtitleSelectedNow(): Boolean {
        if (!initialized) return false
        val trackCount = mpv.getPropertyInt("track-list/count") ?: return false
        if (trackCount <= 0) return false

        val selectedSubtitleId = mpv.getPropertyString("sid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/sub/id")

        for (index in 0 until trackCount) {
            val type = mpv.getPropertyString("track-list/$index/type")?.lowercase(Locale.US) ?: continue
            if (type != "sub") continue

            val selectedByFlag = mpv.getPropertyBoolean("track-list/$index/selected") == true
            val trackId = mpv.getPropertyInt("track-list/$index/id")
            val selected = selectedByFlag || (selectedSubtitleId != null && trackId == selectedSubtitleId)
            if (!selected) continue

            val codec = mpv.getPropertyString("track-list/$index/codec")
                ?.trim()
                ?.lowercase(Locale.US)
                ?: return false
            return codec.contains("ass") || codec.contains("ssa")
        }
        return false
    }

    fun selectAudioTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyInt("aid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select audio track id=$trackId: ${it.message}")
            false
        }
    }

    fun selectSubtitleTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyBoolean("sub-visibility", true)
            mpv.setPropertyInt("sid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select subtitle track id=$trackId: ${it.message}")
            false
        }
    }

    fun disableSubtitles(): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyString("sid", "no")
            mpv.setPropertyBoolean("sub-visibility", false)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to disable subtitles: ${it.message}")
            false
        }
    }

    fun addAndSelectExternalSubtitle(
        url: String,
        title: String? = null,
        language: String? = null
    ): Boolean {
        if (!initialized || url.isBlank()) return false
        return runCatching {
            val safeTitle = title?.takeIf { it.isNotBlank() }
            val safeLanguage = language?.takeIf { it.isNotBlank() }
            when {
                safeTitle != null && safeLanguage != null ->
                    mpv.command("sub-add", url, "cached", safeTitle, safeLanguage)
                safeTitle != null ->
                    mpv.command("sub-add", url, "cached", safeTitle)
                else ->
                    mpv.command("sub-add", url, "cached")
            }
            mpv.setPropertyBoolean("sub-visibility", true)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to add external subtitle: ${it.message}")
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
            mpv.setPropertyString("slang", languages.joinToString(","))
        }.onFailure {
            Log.w(TAG, "Failed to set subtitle language preference: ${it.message}")
        }
    }

    fun readTrackSnapshot(): MpvTrackSnapshot {
        if (!initialized) return MpvTrackSnapshot(emptyList(), emptyList())
        val trackCount = runCatching { mpv.getPropertyInt("track-list/count") ?: 0 }
            .getOrDefault(0)
        if (trackCount <= 0) return MpvTrackSnapshot(emptyList(), emptyList())

        val selectedAudioTrackId = mpv.getPropertyString("aid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/audio/id")
        val selectedSubtitleTrackId = mpv.getPropertyString("sid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/sub/id")

        val audioTracks = mutableListOf<MpvTrack>()
        val subtitleTracks = mutableListOf<MpvTrack>()

        for (index in 0 until trackCount) {
            val type = mpv.getPropertyString("track-list/$index/type")?.lowercase(Locale.US) ?: continue
            val id = mpv.getPropertyInt("track-list/$index/id") ?: continue
            val language = mpv.getPropertyString("track-list/$index/lang")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val title = mpv.getPropertyString("track-list/$index/title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val codec = mpv.getPropertyString("track-list/$index/codec")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val selectedByFlag = mpv.getPropertyBoolean("track-list/$index/selected") == true
            val external = mpv.getPropertyBoolean("track-list/$index/external") == true
            val channelCount = mpv.getPropertyInt("track-list/$index/demux-channel-count")
                ?: mpv.getPropertyInt("track-list/$index/audio-channels")
                ?: mpv.getPropertyInt("track-list/$index/channels")
            val forced = (mpv.getPropertyBoolean("track-list/$index/forced") == true) ||
                listOfNotNull(title, language).any { it.contains("forced", ignoreCase = true) }
            val selected = when (type) {
                "audio" -> (selectedAudioTrackId != null && selectedAudioTrackId == id) || selectedByFlag
                "sub" -> (selectedSubtitleTrackId != null && selectedSubtitleTrackId == id) || selectedByFlag
                else -> selectedByFlag
            }

            when (type) {
                "audio" -> audioTracks += MpvTrack(
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
                "sub" -> subtitleTracks += MpvTrack(
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

        return MpvTrackSnapshot(audioTracks = audioTracks, subtitleTracks = subtitleTracks)
    }

    fun releasePlayer() {
        if (!initialized) return
        runCatching { destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
        initialized = false
        hasQueuedInitialMedia = false
        lastMediaRequestKey = null
    }

    override fun initOptions() {
        mpv.setOptionString("profile", "fast")
        setVo("gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("sub-ass-override", "no")
        mpv.setOptionString("sub-font", "Roboto")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", "yes")
        mpv.setOptionString("hwdec", hardwareDecodeMode.toMpvHwdecValue())
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("input-default-bindings", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("keep-open", "yes")
    }

    override fun postInitOptions() {
        mpv.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() {
        // Progress is polled by PlayerRuntimeController.
    }

    private fun applyHeaders(headers: Map<String, String>) {
        val raw = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = ",") { "${it.key}: ${it.value}" }
        mpv.setPropertyString("http-header-fields", raw)
    }

    private fun buildMediaRequestKey(url: String, headers: Map<String, String>): String {
        val normalizedHeaders = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = "|") { "${it.key.trim()}:${it.value.trim()}" }
        return "$url#$normalizedHeaders"
    }

    private fun MpvHardwareDecodeMode.toMpvHwdecValue(): String {
        return when (this) {
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> "mediacodec,mediacodec-copy"
            MpvHardwareDecodeMode.AUTO_SAFE -> "auto-safe"
            MpvHardwareDecodeMode.HARDWARE_COPY -> "mediacodec-copy"
            MpvHardwareDecodeMode.HARDWARE_DIRECT -> "mediacodec"
            MpvHardwareDecodeMode.DISABLED -> "no"
        }
    }

    private fun toMpvColor(color: Int): String {
        return String.format(Locale.US, "#%08X", color)
    }

    private fun applyCoverAspectScale() {
        val viewAspect = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
        val videoAspect = readVideoAspectRatio()

        if (videoAspect != null && videoAspect > 0f && viewAspect > 0f) {
            if (videoAspect > viewAspect) {
                scaleX = 1.0f
                scaleY = videoAspect / viewAspect
            } else {
                scaleX = viewAspect / videoAspect
                scaleY = 1.0f
            }
            return
        }

        scaleX = MPV_COVER_FALLBACK_SCALE
        scaleY = MPV_COVER_FALLBACK_SCALE
    }

    private fun readVideoAspectRatio(): Float? {
        if (!initialized) return null

        val directAspect = runCatching {
            mpv.getPropertyDouble("video-out-params/aspect")
                ?: mpv.getPropertyDouble("video-params/aspect")
        }.getOrNull()
        if (directAspect != null && directAspect > 0.0) return directAspect.toFloat()

        val width = runCatching {
            mpv.getPropertyInt("video-out-params/dw")
                ?: mpv.getPropertyInt("video-params/w")
        }.getOrNull() ?: return null
        val height = runCatching {
            mpv.getPropertyInt("video-out-params/dh")
                ?: mpv.getPropertyInt("video-params/h")
        }.getOrNull() ?: return null
        if (width <= 0 || height <= 0) return null

        return width.toFloat() / height.toFloat()
    }

    companion object {
        private const val TAG = "NexioMpvSurfaceView"
        private const val MPV_COVER_FALLBACK_SCALE = 1.15f
        private const val SUBTITLE_VERTICAL_OFFSET_MIN = -20
        private const val SUBTITLE_VERTICAL_OFFSET_MAX = 50
        private const val MPV_SUB_POS_AT_BOTTOM = 103.4
        private const val MPV_SUB_POS_AT_TOP = 72.4
        private const val MPV_SUB_MARGIN_Y_MIN = 0
        private const val MPV_SUB_MARGIN_Y_MAX = 60
    }
}

data class MpvTrackSnapshot(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>
)

data class MpvTrack(
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
