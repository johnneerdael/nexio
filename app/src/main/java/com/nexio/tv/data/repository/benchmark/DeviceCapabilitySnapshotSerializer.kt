package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonObject

internal object DeviceCapabilitySnapshotSerializer {
    fun toJson(snapshot: DeviceCapabilitySnapshot): JsonObject = snapshot.toJsonObject()
}
