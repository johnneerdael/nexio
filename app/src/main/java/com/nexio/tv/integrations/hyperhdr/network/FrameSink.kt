package com.nexio.tv.integrations.hyperhdr.network

/**
 * Hand-off interface from the capture path to the HyperHDR network client. Implementations
 * MUST defensively copy the plane arrays before retaining them — callers reuse buffers per
 * frame.
 */
interface FrameSink {
    fun sendNv12(
        yPlane: ByteArray, uvPlane: ByteArray,
        width: Int, height: Int, strideY: Int, strideUv: Int,
    )

    fun sendP010(
        yPlane: ByteArray, uvPlane: ByteArray,
        width: Int, height: Int, strideY: Int, strideUv: Int,
    ) { error("P010 not implemented for this sink") }
}
