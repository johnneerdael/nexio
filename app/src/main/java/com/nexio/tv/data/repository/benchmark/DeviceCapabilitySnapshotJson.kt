package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun parseDeviceCapabilitySnapshotJson(raw: String): DeviceCapabilitySnapshot? {
    val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
    return parseDeviceCapabilitySnapshotBestEffort(root)
}

internal fun parseDeviceCapabilitySnapshotBestEffort(deviceJson: JsonObject): DeviceCapabilitySnapshot? {
    return runCatching { parseDeviceCapabilitySnapshot(deviceJson) }
        .recoverCatching { parseLegacyDeviceCapabilitySnapshot(deviceJson) }
        .getOrNull()
}

private fun parseDeviceCapabilitySnapshot(deviceJson: JsonObject): DeviceCapabilitySnapshot {
    return DeviceCapabilitySnapshot(
        model = deviceJson.stringOrNull("model"),
        manufacturer = deviceJson.stringOrNull("manufacturer"),
        sdkInt = deviceJson.strictIntegralIntOrNull("sdkInt")?.takeIf { it > 0 }
            ?: throw InvalidDeviceCapabilityPayload,
        displayHdrTypes = deviceJson.arrayOrEmpty("displayHdrTypes").map { hdrType ->
            hdrType.asStringOrThrow().let(DeviceHdrType::fromWireKey)
                ?: throw InvalidDeviceCapabilityPayload
        }.toSet(),
        videoDecode = deviceJson.requiredObject("videoDecode").let(::parseVideoDecode),
        audioOutput = deviceJson.requiredObject("audioOutput").let(::parseAudioOutput),
        evidence = deviceJson.optionalObject("evidence")?.let(::parseDeviceCapabilityEvidence),
        capturedAtMs = deviceJson.strictIntegralLongOrNull("capturedAtMs")?.takeIf { it > 0L }
            ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseLegacyDeviceCapabilitySnapshot(deviceJson: JsonObject): DeviceCapabilitySnapshot {
    return DeviceCapabilitySnapshot(
        model = deviceJson.stringOrNull("model"),
        manufacturer = deviceJson.stringOrNull("manufacturer"),
        sdkInt = deviceJson.strictIntegralIntOrNull("sdkInt")?.takeIf { it > 0 }
            ?: throw InvalidDeviceCapabilityPayload,
        displayHdrTypes = deviceJson.arrayOrEmpty("displayHdrTypes").map { hdrType ->
            hdrType.asStringOrThrow().let(DeviceHdrType::fromWireKey)
                ?: throw InvalidDeviceCapabilityPayload
        }.toSet(),
        videoDecode = deviceJson.requiredObject("videoDecode").let(::parseVideoDecode),
        audioOutput = deviceJson.requiredObject("audioOutput").let(::parseAudioOutput),
        evidence = null,
        capturedAtMs = deviceJson.strictIntegralLongOrNull("capturedAtMs")?.takeIf { it > 0L }
            ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseDeviceCapabilityEvidence(evidenceJson: JsonObject): DeviceCapabilityEvidence {
    return DeviceCapabilityEvidence(
        hdr = evidenceJson.optionalObject("hdr")?.let(::parseHdrEvidence),
        audio = evidenceJson.optionalObject("audio")?.let(::parseAudioEvidence),
        video = evidenceJson.optionalObject("video")?.let(::parseVideoEvidence)
    )
}

private fun parseHdrEvidence(hdrJson: JsonObject): DeviceHdrCapabilityEvidence {
    return DeviceHdrCapabilityEvidence(
        displayId = hdrJson.optionalStrictIntegralIntOrNull("displayId"),
        rawSupportedHdrTypes = hdrJson.arrayOrEmpty("rawSupportedHdrTypes").map { it.asStringOrThrow() }
    )
}

private fun parseAudioEvidence(audioJson: JsonObject): DeviceAudioCapabilityEvidence {
    return DeviceAudioCapabilityEvidence(
        discoveryMode = audioJson.stringOrNull("discoveryMode"),
        routedDeviceTypes = audioJson.arrayOrEmpty("routedDeviceTypes").map { it.asStringOrThrow() },
        outputDevices = audioJson.arrayOrEmpty("outputDevices").map { device ->
            parseAudioOutputDeviceEvidence(device.asJsonObjectOrThrow())
        },
        directProfiles = audioJson.arrayOrEmpty("directProfiles").map { profile ->
            parseAudioDirectProfileEvidence(profile.asJsonObjectOrThrow())
        },
        directPlaybackProbes = audioJson.arrayOrEmpty("directPlaybackProbes").map { probe ->
            parseAudioPlaybackProbeEvidence(probe.asJsonObjectOrThrow())
        }
    )
}

private fun parseAudioOutputDeviceEvidence(deviceJson: JsonObject): AudioOutputDeviceEvidence {
    return AudioOutputDeviceEvidence(
        id = deviceJson.optionalStrictIntegralIntOrNull("id"),
        type = deviceJson.stringOrNull("type") ?: throw InvalidDeviceCapabilityPayload,
        productName = deviceJson.stringOrNull("productName"),
        encodings = deviceJson.arrayOrEmpty("encodings").map { it.asStringOrThrow() }
    )
}

private fun parseAudioDirectProfileEvidence(profileJson: JsonObject): AudioDirectProfileEvidence {
    return AudioDirectProfileEvidence(
        format = profileJson.stringOrNull("format") ?: throw InvalidDeviceCapabilityPayload,
        channelMasks = profileJson.arrayOrEmpty("channelMasks").map {
            it.asIntegralIntOrThrow()
        },
        sampleRates = profileJson.arrayOrEmpty("sampleRates").map {
            it.asIntegralIntOrThrow()
        }
    )
}

private fun parseAudioPlaybackProbeEvidence(probeJson: JsonObject): AudioPlaybackProbeEvidence {
    return AudioPlaybackProbeEvidence(
        bucket = probeJson.stringOrNull("bucket") ?: throw InvalidDeviceCapabilityPayload,
        format = probeJson.stringOrNull("format") ?: throw InvalidDeviceCapabilityPayload,
        channelMask = probeJson.strictIntegralIntOrNull("channelMask") ?: throw InvalidDeviceCapabilityPayload,
        sampleRateHz = probeJson.strictIntegralIntOrNull("sampleRateHz") ?: throw InvalidDeviceCapabilityPayload,
        supportMode = probeJson.stringOrNull("supportMode") ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseVideoEvidence(videoJson: JsonObject): DeviceVideoDecoderEvidence {
    return DeviceVideoDecoderEvidence(
        scannedDecoderCount = videoJson.optionalStrictIntegralIntOrNull("scannedDecoderCount") ?: 0,
        decoders = videoJson.arrayOrEmpty("decoders").map { decoder ->
            parseVideoDecoderEvidence(decoder.asJsonObjectOrThrow())
        }
    )
}

private fun parseVideoDecoderEvidence(decoderJson: JsonObject): VideoDecoderEvidence {
    return VideoDecoderEvidence(
        codecName = decoderJson.stringOrNull("codecName") ?: throw InvalidDeviceCapabilityPayload,
        mimeType = decoderJson.stringOrNull("mimeType") ?: throw InvalidDeviceCapabilityPayload,
        hardwareAccelerated = decoderJson.strictBooleanOrNull("hardwareAccelerated")
            ?: throw InvalidDeviceCapabilityPayload,
        softwareOnly = decoderJson.strictBooleanOrNull("softwareOnly")
            ?: throw InvalidDeviceCapabilityPayload,
        secureSupported = decoderJson.strictBooleanOrNull("secureSupported")
            ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseVideoDecode(videoDecodeJson: JsonObject): DeviceVideoDecodeCapabilities {
    return DeviceVideoDecodeCapabilities(
        h264 = videoDecodeJson.optionalObject("h264")?.let(::parseCodecSupport),
        hevc = videoDecodeJson.optionalObject("hevc")?.let(::parseCodecSupport),
        av1 = videoDecodeJson.optionalObject("av1")?.let(::parseCodecSupport),
        dolbyVision = videoDecodeJson.optionalObject("dolbyVision")?.let(::parseCodecSupport)
    )
}

private fun parseCodecSupport(codecJson: JsonObject): CodecSupport {
    return CodecSupport(
        hardwareAccelerated = codecJson.strictBooleanOrNull("hardwareAccelerated")
            ?: throw InvalidDeviceCapabilityPayload,
        softwareOnlyAvailable = codecJson.strictBooleanOrNull("softwareOnlyAvailable")
            ?: throw InvalidDeviceCapabilityPayload,
        secureSupported = codecJson.strictBooleanOrNull("secureSupported")
            ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseAudioOutput(audioOutputJson: JsonObject): DeviceAudioOutputCapabilities {
    return DeviceAudioOutputCapabilities(
        ac3 = audioOutputJson.requiredObject("ac3").let(::parseAudioEncodingSupport),
        eac3 = audioOutputJson.requiredObject("eac3").let(::parseAudioEncodingSupport),
        atmos = audioOutputJson.optionalObject("atmos")?.let(::parseAudioEncodingSupport)
            ?: audioOutputJson.requiredObject("eac3").let(::parseAudioEncodingSupport),
        truehd = audioOutputJson.requiredObject("truehd").let(::parseAudioEncodingSupport),
        dts = audioOutputJson.requiredObject("dts").let(::parseAudioEncodingSupport),
        dtshd = audioOutputJson.requiredObject("dtshd").let(::parseAudioEncodingSupport),
        dtsx = audioOutputJson.optionalObject("dtsx")?.let(::parseAudioEncodingSupport)
            ?: audioOutputJson.requiredObject("dtshd").let(::parseAudioEncodingSupport)
    )
}

private fun parseAudioEncodingSupport(audioEncodingJson: JsonObject): AudioEncodingSupport {
    return AudioEncodingSupport(
        supported = audioEncodingJson.strictBooleanOrNull("supported") ?: throw InvalidDeviceCapabilityPayload,
        passthroughLikely = audioEncodingJson.strictBooleanOrNull("passthroughLikely")
            ?: throw InvalidDeviceCapabilityPayload
    )
}

private object InvalidDeviceCapabilityPayload : RuntimeException()

private fun JsonObject.stringOrNull(key: String): String? {
    return runCatching {
        get(key)?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()
}

private fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = get(key) ?: return null
    if (value.isJsonNull) return null
    return value.takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidDeviceCapabilityPayload
}

private fun JsonObject.requiredObject(key: String): JsonObject {
    return optionalObject(key) ?: throw InvalidDeviceCapabilityPayload
}

private fun JsonObject.arrayOrEmpty(key: String) =
    get(key)?.let { value ->
        if (!value.isJsonArray) throw InvalidDeviceCapabilityPayload
        value.asJsonArray.asList()
    } ?: emptyList()

private fun JsonObject.strictIntegralLongOrNull(key: String): Long? {
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isNumber) return null
    val text = primitive.asString.trim()
    if (!text.matches(INTEGRAL_NUMBER_REGEX)) return null
    return text.toLongOrNull()
}

private fun JsonObject.strictIntegralIntOrNull(key: String): Int? {
    return strictIntegralLongOrNull(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

private fun JsonObject.optionalStrictIntegralIntOrNull(key: String): Int? {
    if (!has(key) || get(key)?.isJsonNull == true) return null
    return strictIntegralIntOrNull(key)?.takeIf { it >= 0 } ?: throw InvalidDeviceCapabilityPayload
}

private fun JsonObject.strictBooleanOrNull(key: String): Boolean? {
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isBoolean) return null
    return primitive.asBoolean
}

private fun com.google.gson.JsonElement.asJsonObjectOrThrow(): JsonObject {
    return takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidDeviceCapabilityPayload
}

private fun com.google.gson.JsonElement.asStringOrThrow(): String {
    return takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw InvalidDeviceCapabilityPayload
}

private fun com.google.gson.JsonElement.asIntegralIntOrThrow(): Int {
    val primitive = takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw InvalidDeviceCapabilityPayload
    if (!primitive.isNumber) throw InvalidDeviceCapabilityPayload
    val text = primitive.asString.trim()
    if (!text.matches(INTEGRAL_NUMBER_REGEX)) throw InvalidDeviceCapabilityPayload
    return text.toIntOrNull() ?: throw InvalidDeviceCapabilityPayload
}

private val INTEGRAL_NUMBER_REGEX = Regex("^-?\\d+$")
