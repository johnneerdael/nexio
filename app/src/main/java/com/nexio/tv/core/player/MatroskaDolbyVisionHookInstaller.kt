package com.nexio.tv.core.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.DolbyVisionCompatibility
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.DolbyVisionConfig
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object MatroskaDolbyVisionHookInstaller {
    private const val TAG = "Dv7ExtractorHook"
    private const val MATROSKA_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.mkv.MatroskaExtractor\$DolbyVisionSampleTransformer"
    private const val MP4_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.mp4.Mp4Extractor\$DolbyVisionSampleTransformer"
    private const val FMP4_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.mp4.FragmentedMp4Extractor\$DolbyVisionSampleTransformer"
    private const val TS_H265_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.ts.H265Reader\$DolbyVisionNalTransformer"
    private const val MATROSKA_SETTER_NAME = "setMatroskaDolbyVisionSampleTransformer"
    private const val MP4_SETTER_NAME = "setMp4DolbyVisionSampleTransformer"
    private const val FMP4_SETTER_NAME = "setFragmentedMp4DolbyVisionSampleTransformer"
    private const val TS_H265_SETTER_NAME = "setTsDolbyVisionNalTransformer"
    private const val NAL_TYPE_UNSPEC62 = 62
    private val codecStringRewriteCount = AtomicLong(0L)
    private val lastDetectedSourceProfile = AtomicReference<Int?>(null)
    private val lastSelectedConversionMode = AtomicReference<Int?>(null)

    fun resetRuntimeCounters() {
        codecStringRewriteCount.set(0L)
        lastDetectedSourceProfile.set(null)
        lastSelectedConversionMode.set(null)
    }

    fun getCodecStringRewriteCount(): Long = codecStringRewriteCount.get()
    fun getLastDetectedSourceProfile(): Int? = lastDetectedSourceProfile.get()
    fun getLastSelectedConversionMode(): Int? = lastSelectedConversionMode.get()

    fun maybeInstall(
        extractorsFactory: DefaultExtractorsFactory,
        enabled: Boolean,
        allowDv5Conversion: Boolean,
        preserveMappingEnabled: Boolean,
        enableRpuTap: Boolean,
        streamUrl: String
    ): Boolean {
        if (!enabled && !enableRpuTap) {
            clearGlobalHooks()
            return false
        }
        val conversionEnabled = enabled && DoviBridge.isAvailable()
        if (enabled && !conversionEnabled && !enableRpuTap) {
            clearGlobalHooks()
            Log.i(TAG, "Skip install: DoviBridge unavailable host=${streamUrl.safeHost()}")
            return false
        }

        return runCatching {
            val handler = createInvocationHandler(
                host = streamUrl.safeHost(),
                conversionEnabled = conversionEnabled,
                allowDv5Conversion = allowDv5Conversion,
                preserveMappingEnabled = preserveMappingEnabled,
                enableRpuTap = enableRpuTap
            )
            val matroskaInstalled = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = MATROSKA_TRANSFORMER_CLASS_NAME,
                setterName = MATROSKA_SETTER_NAME,
                invocationHandler = handler
            )
            val mp4Installed = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = MP4_TRANSFORMER_CLASS_NAME,
                setterName = MP4_SETTER_NAME,
                invocationHandler = handler
            )
            val fmp4Installed = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = FMP4_TRANSFORMER_CLASS_NAME,
                setterName = FMP4_SETTER_NAME,
                invocationHandler = handler
            )
            val tsInstalled = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = TS_H265_TRANSFORMER_CLASS_NAME,
                setterName = TS_H265_SETTER_NAME,
                invocationHandler = handler
            )
            matroskaInstalled || mp4Installed || fmp4Installed || tsInstalled
        }.onFailure {
            Log.w(TAG, "Failed to install hook host=${streamUrl.safeHost()}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installHook(
        extractorsFactory: DefaultExtractorsFactory,
        transformerClassName: String,
        setterName: String,
        invocationHandler: InvocationHandler
    ): Boolean {
        return runCatching {
            val transformerClass = Class.forName(transformerClassName)
            val setter = extractorsFactory.javaClass.getMethod(setterName, transformerClass)
            val proxy = Proxy.newProxyInstance(
                transformerClass.classLoader,
                arrayOf(transformerClass),
                invocationHandler
            )
            setter.invoke(extractorsFactory, proxy)
            when (setterName) {
                MATROSKA_SETTER_NAME -> DolbyVisionCompatibility.setMatroskaDolbyVisionSampleTransformer(proxy)
                MP4_SETTER_NAME -> DolbyVisionCompatibility.setMp4DolbyVisionSampleTransformer(proxy)
                FMP4_SETTER_NAME -> DolbyVisionCompatibility.setFragmentedMp4DolbyVisionSampleTransformer(proxy)
                TS_H265_SETTER_NAME -> DolbyVisionCompatibility.setTsDolbyVisionNalTransformer(proxy)
            }
            true
        }.getOrDefault(false)
    }

    private fun clearGlobalHooks() {
        DolbyVisionCompatibility.setMatroskaDolbyVisionSampleTransformer(null)
        DolbyVisionCompatibility.setMp4DolbyVisionSampleTransformer(null)
        DolbyVisionCompatibility.setFragmentedMp4DolbyVisionSampleTransformer(null)
        DolbyVisionCompatibility.setTsDolbyVisionNalTransformer(null)
    }

    private fun createInvocationHandler(
        host: String,
        conversionEnabled: Boolean,
        allowDv5Conversion: Boolean,
        preserveMappingEnabled: Boolean,
        enableRpuTap: Boolean
    ): InvocationHandler {
        val lastDetectedProfile = AtomicReference<Int?>(null)
        val nonDv7ProfileLogged = AtomicBoolean(false)
        val conversionUnavailableLogged = AtomicBoolean(false)

        fun rememberProfile(profile: Int?): Int? {
            if (profile != null) {
                lastDetectedProfile.set(profile)
                lastDetectedSourceProfile.set(profile)
            }
            return profile ?: lastDetectedProfile.get()
        }

        fun shouldAllowConversion(profile: Int?): Boolean {
            if (!conversionEnabled) {
                lastSelectedConversionMode.set(null)
                if (conversionUnavailableLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "Dolby Vision conversion disabled (native bridge unavailable) host=$host"
                    )
                }
                return false
            }
            val resolvedProfile = rememberProfile(profile)
            if (resolvedProfile == 7) {
                return true
            }
            if (resolvedProfile == 5 && allowDv5Conversion) {
                return true
            }
            lastSelectedConversionMode.set(null)
            if (resolvedProfile != null && nonDv7ProfileLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Skipping experimental DV conversion for unsupported profile=$resolvedProfile host=$host"
                )
            }
            return false
        }

        fun selectedConversionMode(profile: Int?): Int {
            val resolvedProfile = rememberProfile(profile)
            val mode = if (resolvedProfile == 7 && preserveMappingEnabled) 5 else 2
            lastSelectedConversionMode.set(mode)
            return mode
        }

        return InvocationHandler { proxy, method, args ->
            val invocationArgs = args ?: emptyArray()
            when (method.name) {
                "onDolbyVisionBlockAdditionalData" -> {
                    val blockAdditionalData = invocationArgs.getOrNull(0) as? ByteArray
                        ?: return@InvocationHandler null
                    if (enableRpuTap) {
                        tapPotentialRpuNal(
                            nalPayload = blockAdditionalData,
                            sampleTimeUs = C.TIME_UNSET,
                            source = "mkv:blockAdditional"
                        )
                    }
                    val dolbyVisionConfigBytes = invocationArgs.getOrNull(2) as? ByteArray
                    val profile = resolveDolbyVisionProfile(configBytes = dolbyVisionConfigBytes)
                    if (!shouldAllowConversion(profile)) {
                        return@InvocationHandler null
                    }
                    DoviBridge.convertDv7RpuToDv81(
                        blockAdditionalData,
                        mode = selectedConversionMode(profile)
                    )
                        ?.takeIf { it.isNotEmpty() }
                }

                "onHevcSample" -> {
                    if (enableRpuTap) {
                        val sampleTimeUs =
                            (invocationArgs.getOrNull(3) as? Number)?.toLong() ?: C.TIME_UNSET
                        val blockAdditionalData = invocationArgs.getOrNull(1) as? ByteArray
                        if (blockAdditionalData != null) {
                            tapPotentialRpuNal(
                                nalPayload = blockAdditionalData,
                                sampleTimeUs = sampleTimeUs,
                                source = "mkv:onHevcSample"
                            )
                        }
                    }
                    null
                }
                "onDolbyVisionCodecString" -> {
                    val codecs = invocationArgs.getOrNull(0) as? String
                    val dolbyVisionConfigBytes = invocationArgs.getOrNull(1) as? ByteArray
                    val profile = resolveDolbyVisionProfile(codecs = codecs, configBytes = dolbyVisionConfigBytes)
                    if (!shouldAllowConversion(profile)) {
                        return@InvocationHandler null
                    }
                    val normalized = normalizeDolbyVisionCodecString(codecs)
                    if (normalized != null && normalized != codecs) {
                        codecStringRewriteCount.incrementAndGet()
                    }
                    normalized
                }
                "transformHevcSample" -> {
                    val sampleLengthDelimited = invocationArgs.getOrNull(0) as? ByteArray
                        ?: return@InvocationHandler null
                    val nalUnitLengthFieldLength =
                        (invocationArgs.getOrNull(1) as? Number)?.toInt() ?: return@InvocationHandler null
                    val thirdArg = invocationArgs.getOrNull(2)
                    val fourthArg = invocationArgs.getOrNull(3)
                    val fifthArg = invocationArgs.getOrNull(4)
                    val blockAdditionalData = thirdArg as? ByteArray
                    val codecs = thirdArg as? String
                    val dolbyVisionConfigBytes = when {
                        thirdArg is ByteArray && fourthArg is ByteArray -> fourthArg
                        else -> null
                    }
                    val sampleTimeUs = when {
                        fifthArg is Number -> fifthArg.toLong()
                        fourthArg is Number -> fourthArg.toLong()
                        else -> C.TIME_UNSET
                    }
                    if (enableRpuTap) {
                        tapRpuFromLengthDelimitedSample(
                            sampleLengthDelimited = sampleLengthDelimited,
                            nalUnitLengthFieldLength = nalUnitLengthFieldLength,
                            sampleTimeUs = sampleTimeUs,
                            source = when {
                                blockAdditionalData != null -> "mkv:sample"
                                codecs != null -> "mp4:sample"
                                else -> "hevc:sample"
                            }
                        )
                        if (blockAdditionalData != null) {
                            tapPotentialRpuNal(
                                nalPayload = blockAdditionalData,
                                sampleTimeUs = sampleTimeUs,
                                source = "mkv:blockAdditional"
                            )
                        }
                    }
                    val profile = resolveDolbyVisionProfile(
                        codecs = codecs,
                        configBytes = dolbyVisionConfigBytes
                    )
                    if (!shouldAllowConversion(profile)) {
                        return@InvocationHandler null
                    }
                    val rewrittenSample =
                        rewriteMp4HevcSample(
                            sampleLengthDelimited,
                            nalUnitLengthFieldLength,
                            selectedConversionMode(profile)
                        )
                            ?: sampleLengthDelimited
                    if (blockAdditionalData == null) {
                        if (rewrittenSample !== sampleLengthDelimited) rewrittenSample else null
                    } else {
                        val convertedBlockAdditional =
                            DoviBridge.convertDv7RpuToDv81(
                                blockAdditionalData,
                                mode = selectedConversionMode(profile)
                            )
                                ?.takeIf { it.isNotEmpty() }
                                ?: blockAdditionalData
                        appendLengthDelimitedNal(
                            sampleLengthDelimited = rewrittenSample,
                            nalUnitLengthFieldLength = nalUnitLengthFieldLength,
                            nalPayload = convertedBlockAdditional
                        )
                    }
                }
                "transformDolbyVisionRpuNal" -> {
                    val nalPayload = invocationArgs.getOrNull(0) as? ByteArray
                        ?: return@InvocationHandler null
                    val codecs = invocationArgs.getOrNull(1) as? String
                    val sampleTimeUs =
                        (invocationArgs.getOrNull(2) as? Number)?.toLong() ?: C.TIME_UNSET
                    if (enableRpuTap) {
                        tapPotentialRpuNal(
                            nalPayload = nalPayload,
                            sampleTimeUs = sampleTimeUs,
                            source = "ts:rpuNal"
                        )
                    }
                    val profile = resolveDolbyVisionProfile(codecs = codecs)
                    if (!shouldAllowConversion(profile)) {
                        return@InvocationHandler null
                    }
                    maybeConvertDolbyVisionRpuNal(nalPayload, selectedConversionMode(profile))
                }
                "equals" -> proxy === invocationArgs.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NEXIODv7MatroskaTransformerProxy(host=$host)"
                else -> null
            }
        }
    }

    private fun tapRpuFromLengthDelimitedSample(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int,
        sampleTimeUs: Long,
        source: String
    ) {
        if (sampleLengthDelimited.isEmpty() || nalUnitLengthFieldLength !in 1..4) return
        var offset = 0
        while (offset + nalUnitLengthFieldLength <= sampleLengthDelimited.size) {
            val nalSize = readLengthField(sampleLengthDelimited, offset, nalUnitLengthFieldLength)
            if (nalSize <= 0) return
            offset += nalUnitLengthFieldLength
            if (offset + nalSize > sampleLengthDelimited.size) return
            val nalPayload = sampleLengthDelimited.copyOfRange(offset, offset + nalSize)
            tapPotentialRpuNal(nalPayload, sampleTimeUs, source)
            offset += nalSize
        }
    }

    private fun tapPotentialRpuNal(
        nalPayload: ByteArray,
        sampleTimeUs: Long,
        source: String
    ) {
        if (nalPayload.isEmpty()) return
        val directType = getNalUnitTypeOrMinusOne(nalPayload)
        if (directType == NAL_TYPE_UNSPEC62) {
            Dv5HardwareToneMapRpuTap.onRpuSample(sampleTimeUs, nalPayload, source)
            return
        }
        tapRpuFromAnnexBStream(
            annexBPayload = nalPayload,
            sampleTimeUs = sampleTimeUs,
            source = source
        )
    }

    private fun tapRpuFromAnnexBStream(
        annexBPayload: ByteArray,
        sampleTimeUs: Long,
        source: String
    ) {
        if (annexBPayload.size < 4) return
        var start = findAnnexBStartCodeOffset(annexBPayload, 0)
        while (start >= 0) {
            val startCodeLength = annexBStartCodeLength(annexBPayload, start)
            val nalStart = start + startCodeLength
            if (nalStart >= annexBPayload.size) return
            val nextStart = findAnnexBStartCodeOffset(annexBPayload, nalStart)
            val nalEnd = if (nextStart >= 0) nextStart else annexBPayload.size
            if (nalEnd > nalStart) {
                val nalPayload = annexBPayload.copyOfRange(nalStart, nalEnd)
                if (getNalUnitTypeOrMinusOne(nalPayload) == NAL_TYPE_UNSPEC62) {
                    Dv5HardwareToneMapRpuTap.onRpuSample(sampleTimeUs, nalPayload, source)
                }
            }
            start = nextStart
        }
    }

    private fun findAnnexBStartCodeOffset(data: ByteArray, fromIndex: Int): Int {
        var i = fromIndex.coerceAtLeast(0)
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun annexBStartCodeLength(data: ByteArray, startOffset: Int): Int {
        return if (startOffset + 3 < data.size &&
            data[startOffset] == 0.toByte() &&
            data[startOffset + 1] == 0.toByte() &&
            data[startOffset + 2] == 1.toByte()
        ) {
            3
        } else {
            4
        }
    }

    private fun rewriteMp4HevcSample(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int,
        conversionMode: Int
    ): ByteArray? {
        if (nalUnitLengthFieldLength !in 1..4) return null
        var offset = 0
        var changed = false
        val out = ByteArrayOutputStream(sampleLengthDelimited.size + 128)
        while (offset + nalUnitLengthFieldLength <= sampleLengthDelimited.size) {
            val nalSize = readLengthField(sampleLengthDelimited, offset, nalUnitLengthFieldLength)
            if (nalSize < 0) return null
            offset += nalUnitLengthFieldLength
            if (offset + nalSize > sampleLengthDelimited.size) return null
            val originalNal = sampleLengthDelimited.copyOfRange(offset, offset + nalSize)
            val convertedNal = transformNalForCompatibility(originalNal, conversionMode)
            if (convertedNal == null) {
                changed = true
                offset += nalSize
                continue
            }
            if (convertedNal !== originalNal) {
                changed = true
            }
            if (!writeLengthField(out, convertedNal.size, nalUnitLengthFieldLength)) {
                return null
            }
            out.write(convertedNal)
            offset += nalSize
        }
        if (offset != sampleLengthDelimited.size) return null
        if (!changed) return null
        if (out.size() <= 0) return null
        return out.toByteArray()
    }

    private fun transformNalForCompatibility(
        nalPayload: ByteArray,
        conversionMode: Int
    ): ByteArray? {
        if (nalPayload.isEmpty()) return nalPayload
        val nalType = getNalUnitType(nalPayload)
        val layerId = getNuhLayerId(nalPayload)
        // Drop enhancement-layer NAL units; they are not decodable on single-layer HEVC paths.
        if (layerId > 0 && nalType != NAL_TYPE_UNSPEC62) {
            return null
        }
        if (nalType != NAL_TYPE_UNSPEC62) {
            return nalPayload
        }
        val converted = DoviBridge.convertDv7RpuToDv81(nalPayload, mode = conversionMode)
            ?.takeIf { it.isNotEmpty() }
            ?: nalPayload
        return normalizeNuhLayerIdToZero(converted)
    }

    private fun maybeConvertDolbyVisionRpuNal(
        nalPayload: ByteArray,
        conversionMode: Int
    ): ByteArray {
        if (nalPayload.isEmpty()) return nalPayload
        val nalType = getNalUnitType(nalPayload)
        if (nalType != NAL_TYPE_UNSPEC62) return nalPayload
        val converted = DoviBridge.convertDv7RpuToDv81(nalPayload, mode = conversionMode)
            ?.takeIf { it.isNotEmpty() }
            ?: nalPayload
        return normalizeNuhLayerIdToZero(converted)
    }

    private fun getNalUnitType(nalPayload: ByteArray): Int {
        return (nalPayload[0].toInt() ushr 1) and 0x3F
    }

    private fun getNalUnitTypeOrMinusOne(nalPayload: ByteArray): Int {
        if (nalPayload.isEmpty()) return -1
        return getNalUnitType(nalPayload)
    }

    private fun getNuhLayerId(nalPayload: ByteArray): Int {
        if (nalPayload.size < 2) return 0
        val b0 = nalPayload[0].toInt() and 0x01
        val b1 = nalPayload[1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun normalizeNuhLayerIdToZero(nalPayload: ByteArray): ByteArray {
        if (nalPayload.size < 2) return nalPayload
        if (getNuhLayerId(nalPayload) == 0) return nalPayload
        val out = nalPayload.copyOf()
        // Keep nal_unit_type and temporal_id_plus1, force nuh_layer_id to 0.
        out[0] = (out[0].toInt() and 0xFE).toByte()
        out[1] = (out[1].toInt() and 0x07).toByte()
        return out
    }

    private fun readLengthField(data: ByteArray, offset: Int, lengthBytes: Int): Int {
        var value = 0
        for (i in 0 until lengthBytes) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return value
    }

    private fun writeLengthField(
        out: ByteArrayOutputStream,
        value: Int,
        lengthBytes: Int
    ): Boolean {
        if (value < 0) return false
        val maxNalSize = when (lengthBytes) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            4 -> Int.MAX_VALUE
            else -> return false
        }
        if (value > maxNalSize) return false
        for (shift in (lengthBytes - 1) downTo 0) {
            out.write((value ushr (shift * 8)) and 0xFF)
        }
        return true
    }

    private fun normalizeDolbyVisionCodecString(codecs: String?): String? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.').toMutableList()
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        val profileValue = parts[1].toIntOrNull() ?: return null
        if (profileValue != 5 && profileValue != 7) return null
        val width = parts[1].length.coerceAtLeast(2)
        parts[1] = "8".padStart(width, '0')
        return parts.joinToString(".")
    }

    private fun resolveDolbyVisionProfile(
        codecs: String? = null,
        configBytes: ByteArray? = null
    ): Int? {
        resolveDolbyVisionProfileFromCodecString(codecs)?.let { return it }
        if (configBytes == null || configBytes.isEmpty()) return null
        return runCatching {
            DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
        }.getOrNull()
    }

    private fun resolveDolbyVisionProfileFromCodecString(codecs: String?): Int? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.')
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        return parts[1].toIntOrNull()
    }

    private fun appendLengthDelimitedNal(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int,
        nalPayload: ByteArray
    ): ByteArray? {
        if (nalUnitLengthFieldLength !in 1..4 || nalPayload.isEmpty()) return null
        val maxNalSize = when (nalUnitLengthFieldLength) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            else -> Int.MAX_VALUE
        }
        if (nalPayload.size > maxNalSize) {
            return null
        }

        val out = ByteArray(sampleLengthDelimited.size + nalUnitLengthFieldLength + nalPayload.size)
        System.arraycopy(sampleLengthDelimited, 0, out, 0, sampleLengthDelimited.size)

        var value = nalPayload.size
        var offset = sampleLengthDelimited.size + nalUnitLengthFieldLength - 1
        repeat(nalUnitLengthFieldLength) {
            out[offset] = (value and 0xFF).toByte()
            value = value ushr 8
            offset--
        }

        System.arraycopy(
            nalPayload,
            0,
            out,
            sampleLengthDelimited.size + nalUnitLengthFieldLength,
            nalPayload.size
        )
        return out
    }

    private fun String.safeHost(): String {
        return runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
    }
}
