package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object DeviceCapabilitySnapshotSerializer {
    fun toJson(snapshot: DeviceCapabilitySnapshot): JsonObject = JsonObject().apply {
        snapshot.model?.let { addProperty("model", it) }
        snapshot.manufacturer?.let { addProperty("manufacturer", it) }
        addProperty("sdkInt", snapshot.sdkInt)
        addProperty("capturedAtMs", snapshot.capturedAtMs)
        add("displayHdrTypes", JsonArray().also { array ->
            snapshot.displayHdrTypes
                .map { it.wireKey }
                .sorted()
                .forEach { array.add(it) }
        })
        add("videoDecode", videoDecodeJson(snapshot.videoDecode))
        add("audioOutput", audioOutputJson(snapshot.audioOutput))
    }

    private fun videoDecodeJson(video: DeviceVideoDecodeCapabilities): JsonObject = JsonObject().apply {
        video.h264?.let { add("h264", codecSupportJson(it)) }
        video.hevc?.let { add("hevc", codecSupportJson(it)) }
        video.av1?.let { add("av1", codecSupportJson(it)) }
        video.dolbyVision?.let { add("dolbyVision", codecSupportJson(it)) }
    }

    private fun codecSupportJson(c: CodecSupport): JsonObject = JsonObject().apply {
        addProperty("hardwareAccelerated", c.hardwareAccelerated)
        addProperty("softwareOnlyAvailable", c.softwareOnlyAvailable)
        addProperty("secureSupported", c.secureSupported)
    }

    private fun audioOutputJson(audio: DeviceAudioOutputCapabilities): JsonObject = JsonObject().apply {
        add("ac3", audioEncodingJson(audio.ac3))
        add("eac3", audioEncodingJson(audio.eac3))
        add("atmos", audioEncodingJson(audio.atmos))
        add("truehd", audioEncodingJson(audio.truehd))
        add("dts", audioEncodingJson(audio.dts))
        add("dtshd", audioEncodingJson(audio.dtshd))
        add("dtsx", audioEncodingJson(audio.dtsx))
    }

    private fun audioEncodingJson(e: AudioEncodingSupport): JsonObject = JsonObject().apply {
        addProperty("supported", e.supported)
        addProperty("passthroughLikely", e.passthroughLikely)
    }
}
