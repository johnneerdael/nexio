package com.nexio.tv.integrations.hyperhdr.capture

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import com.nexio.tv.integrations.hyperhdr.network.FrameSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Production HDR capture shader. Runs as a passthrough in Media3's effect pipeline
 * (input texture is presented unchanged downstream), with a side-tap that downscales to
 * 192×108 P010 and reads back via PBO async, then hands the bytes to a [FrameSink] for
 * network transport.
 *
 * GL-program construction uses [GlProgram] + [GlProgram.setBufferAttribute] +
 * [GlProgram.bindAttributesAndUniforms], following the same idiom as
 * HyperHdrLoggingShaderProgram (Task 7 spike). [GlUtil.createProgram] does not exist in
 * Nexio's Media3 fork.
 *
 * The side-tap binds auxiliary FBOs; before returning from [drawFrame] we restore the output
 * FBO that [BaseGlShaderProgram] bound before calling [drawFrame]. We do this by reading the
 * currently-bound FBO ID just before the side-tap begins and re-binding it afterward.
 *
 * PBO ring-buffer semantics (NUM_PBOS = 3):
 *   Frame N  → glReadPixels into pboIds[pboNext=0], then pboNext advances to 1.
 *              Read from pboIds[1] — but it hasn't been written yet, so skip read on frame 0.
 *   Frame 1  → write into pboIds[1], advance to 2. Read from pboIds[2] — skip (not yet written).
 *   Frame 2  → write into pboIds[2], advance to 0. Read from pboIds[0] — written 3 frames ago; ready.
 *   Frame 3+ → normal: write pboIds[pboNext], advance, read from new pboNext (oldest ready buffer).
 * We skip the map on the first NUM_PBOS-1 frames via the [framesCapturing] counter.
 */
@UnstableApi
class HyperHdrCaptureShaderProgram(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val useHdr: Boolean,
    private val sink: FrameSink,
    private val mode: CaptureMode,
    private val outputWidth: Int = 192,
    private val outputHeight: Int = 108,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    // Passthrough program: full-resolution passthrough draw into the output FBO.
    private var passthroughProgram: GlProgram? = null

    // Side-tap programs: downscale + PQ-encode into integer FBOs.
    private var yProgram: GlProgram? = null
    private var uvProgram: GlProgram? = null

    private var yTextureId = 0
    private var uvTextureId = 0
    private var yFbo = 0
    private var uvFbo = 0

    private val pboYIds = IntArray(NUM_PBOS)
    private val pboUvIds = IntArray(NUM_PBOS)
    private var pboNext = 0
    private var framesCapturing = 0  // counts frames in the capture path; skip map until >= NUM_PBOS

    private var ySize: Int = 0
    private var uvSize: Int = 0
    // Reusable destination byte arrays (callers of FrameSink must copy before retaining)
    private lateinit var yBytes: ByteArray
    private lateinit var uvBytes: ByteArray

    private var initialized = false
    private var lastDispatchTimeNanos = 0L
    private val intervalNanos = 1_000_000_000L / 30   // 30 fps target

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (!initialized) initGl()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // Pass A: passthrough. BaseGlShaderProgram has already bound the output FBO and set the
        // viewport to [outputWidth × outputHeight] of the *output texture* (same as input size).
        // We render the input texture into it unchanged.
        try {
            val prog = passthroughProgram
                ?: throw VideoFrameProcessingException("passthrough program not initialized")
            prog.use()
            prog.setSamplerTexIdUniform("uTex", inputTexId, /* texUnitIndex= */ 0)
            prog.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(
                "HyperHdrCaptureShaderProgram passthrough draw failed", e)
        }

        // Pass B: rate-gated side-tap for HyperHDR.
        val now = System.nanoTime()
        if (now - lastDispatchTimeNanos < intervalNanos) return
        lastDispatchTimeNanos = now

        try {
            renderSideTap(inputTexId)
        } catch (t: Throwable) {
            Log.w(TAG, "Side-tap render failed; skipping frame", t)
        }
    }

    private fun renderSideTap(inputTexId: Int) {
        // Save the FBO that BaseGlShaderProgram focused for us (the output texture FBO).
        // We must restore it before returning so the parent's output texture remains intact.
        val savedFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFbo, 0)
        val savedViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0)

        try {
            // Y pass — render to GL_R16UI FBO at outputWidth × outputHeight
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, yFbo)
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            val yProg = yProgram ?: return
            yProg.use()
            yProg.setSamplerTexIdUniform("uTex", inputTexId, /* texUnitIndex= */ 0)
            yProg.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // UV pass — half resolution, renders Cb/Cr into GL_RG16UI FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, uvFbo)
            GLES20.glViewport(0, 0, outputWidth / 2, outputHeight / 2)
            val uvProg = uvProgram ?: return
            uvProg.use()
            uvProg.setSamplerTexIdUniform("uTex", inputTexId, /* texUnitIndex= */ 0)
            uvProg.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // PBO ring-buffer async readback.
            // Write glReadPixels into pboIds[pboNext] (asynchronous with PBO bound).
            val writeY = pboYIds[pboNext]
            val writeUv = pboUvIds[pboNext]

            val yReadPair: Pair<Int, Int> = when (mode) {
                CaptureMode.HDR_P010 -> GLES30.GL_RED_INTEGER to GLES30.GL_UNSIGNED_SHORT
                CaptureMode.SDR_NV12 -> GLES30.GL_RED to GLES20.GL_UNSIGNED_BYTE
            }
            val yReadFormat = yReadPair.first
            val yReadType = yReadPair.second
            val uvReadPair: Pair<Int, Int> = when (mode) {
                CaptureMode.HDR_P010 -> GLES30.GL_RG_INTEGER to GLES30.GL_UNSIGNED_SHORT
                CaptureMode.SDR_NV12 -> GLES30.GL_RG to GLES20.GL_UNSIGNED_BYTE
            }
            val uvReadFormat = uvReadPair.first
            val uvReadType = uvReadPair.second

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, yFbo)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, writeY)
            GLES30.glReadPixels(
                0, 0, outputWidth, outputHeight,
                yReadFormat, yReadType, 0)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, uvFbo)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, writeUv)
            GLES30.glReadPixels(
                0, 0, outputWidth / 2, outputHeight / 2,
                uvReadFormat, uvReadType, 0)

            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

            // Advance ring index. After advancement, pboNext points to the oldest buffer
            // (the one written NUM_PBOS frames ago), which is now ready to be mapped.
            pboNext = (pboNext + 1) % NUM_PBOS
            framesCapturing++

            // Only map after the ring has been primed (NUM_PBOS write cycles).
            if (framesCapturing >= NUM_PBOS) {
                val readY = pboYIds[pboNext]
                val readUv = pboUvIds[pboNext]
                readPboInto(readY, ySize, yBytes)
                readPboInto(readUv, uvSize, uvBytes)
                when (mode) {
                    CaptureMode.HDR_P010 -> sink.sendP010(
                        yBytes, uvBytes,
                        outputWidth, outputHeight,
                        /* strideY= */ outputWidth * 2,
                        /* strideUv= */ outputWidth * 2,
                    )
                    CaptureMode.SDR_NV12 -> sink.sendNv12(
                        yBytes, uvBytes,
                        outputWidth, outputHeight,
                        /* strideY= */ outputWidth,
                        /* strideUv= */ outputWidth,
                    )
                }
            }
        } finally {
            // Always restore the output FBO and viewport so the parent's output texture is intact.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFbo[0])
            GLES20.glViewport(
                savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
        }
    }

    private fun readPboInto(pboId: Int, size: Int, dest: ByteArray) {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboId)
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, size, GLES30.GL_MAP_READ_BIT
        )
        if (mapped is ByteBuffer) {
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            mapped.get(dest)
        } else {
            Log.w(TAG, "glMapBufferRange returned non-ByteBuffer for PBO $pboId")
        }
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    @Throws(GlUtil.GlException::class)
    private fun initGl() {
        // Passthrough program: vertex shader uses aPos (homogeneous vec4) + aTex (homogeneous vec4)
        // from GlUtil's coordinate bounds. Fragment simply samples and outputs the colour.
        val passthroughFs = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vTex;
out vec4 oColor;
void main() { oColor = texture(uTex, vTex); }
"""
        passthroughProgram = GlProgram(PqDownscaleShaders.VERTEX, passthroughFs).also { prog ->
            // Full-screen NDC quad — 4-component homogeneous coords (matches GlUtil helpers)
            prog.setBufferAttribute(
                "aPos",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            prog.setBufferAttribute(
                "aTex",
                GlUtil.getTextureCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }

        // Branch on capture mode: pick shader source + compute sizes
        when (mode) {
            CaptureMode.HDR_P010 -> {
                ySize = outputWidth * outputHeight * 2
                uvSize = (outputWidth / 2) * (outputHeight / 2) * 4
                yProgram = GlProgram(PqDownscaleShaders.VERTEX, PqDownscaleShaders.Y_FRAGMENT).also { prog ->
                    prog.setBufferAttribute(
                        "aPos",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                    prog.setBufferAttribute(
                        "aTex",
                        GlUtil.getTextureCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
                uvProgram = GlProgram(PqDownscaleShaders.VERTEX, PqDownscaleShaders.UV_FRAGMENT).also { prog ->
                    prog.setBufferAttribute(
                        "aPos",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                    prog.setBufferAttribute(
                        "aTex",
                        GlUtil.getTextureCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
            }
            CaptureMode.SDR_NV12 -> {
                ySize = outputWidth * outputHeight
                uvSize = (outputWidth / 2) * (outputHeight / 2) * 2
                yProgram = GlProgram(NvDownscaleShaders.VERTEX, NvDownscaleShaders.Y_FRAGMENT).also { prog ->
                    prog.setBufferAttribute(
                        "aPos",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                    prog.setBufferAttribute(
                        "aTex",
                        GlUtil.getTextureCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
                uvProgram = GlProgram(NvDownscaleShaders.VERTEX, NvDownscaleShaders.UV_FRAGMENT).also { prog ->
                    prog.setBufferAttribute(
                        "aPos",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                    prog.setBufferAttribute(
                        "aTex",
                        GlUtil.getTextureCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
            }
        }

        // Allocate reusable byte arrays now that sizes are known
        yBytes = ByteArray(ySize)
        uvBytes = ByteArray(uvSize)

        // Y-plane texture + FBO — format depends on mode
        val texIds = IntArray(2)
        GLES20.glGenTextures(2, texIds, 0)
        yTextureId = texIds[0]
        uvTextureId = texIds[1]

        when (mode) {
            CaptureMode.HDR_P010 -> {
                // GL_R16UI — integer format; MUST use GL_NEAREST (linear undefined on integer textures)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId)
                GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16UI,
                    outputWidth, outputHeight, 0,
                    GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

                // UV-plane: GL_RG16UI texture + FBO at half resolution
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uvTextureId)
                GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG16UI,
                    outputWidth / 2, outputHeight / 2, 0,
                    GLES30.GL_RG_INTEGER, GLES30.GL_UNSIGNED_SHORT, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            }
            CaptureMode.SDR_NV12 -> {
                // GL_R8 — normalized format; GL_LINEAR is valid (GL_NEAREST also fine)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId)
                GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8,
                    outputWidth, outputHeight, 0,
                    GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

                // UV-plane: GL_RG8 texture + FBO at half resolution
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uvTextureId)
                GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG8,
                    outputWidth / 2, outputHeight / 2, 0,
                    GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            }
        }

        // Framebuffers
        val fboIds = IntArray(2)
        GLES20.glGenFramebuffers(2, fboIds, 0)
        yFbo = fboIds[0]
        uvFbo = fboIds[1]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, yFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, yTextureId, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Y FBO incomplete"
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, uvFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, uvTextureId, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "UV FBO incomplete"
        }

        // PBO ring buffers — 3 sets for async readback pipeline
        GLES30.glGenBuffers(NUM_PBOS, pboYIds, 0)
        GLES30.glGenBuffers(NUM_PBOS, pboUvIds, 0)
        for (i in 0 until NUM_PBOS) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboYIds[i])
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, ySize, null, GLES30.GL_STREAM_READ)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboUvIds[i])
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, uvSize, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

        initialized = true
    }

    override fun release() {
        super.release()
        try { passthroughProgram?.delete() } catch (_: GlUtil.GlException) { /* best-effort */ }
        try { yProgram?.delete() } catch (_: GlUtil.GlException) { /* best-effort */ }
        try { uvProgram?.delete() } catch (_: GlUtil.GlException) { /* best-effort */ }
        passthroughProgram = null
        yProgram = null
        uvProgram = null

        if (pboYIds[0] != 0) GLES30.glDeleteBuffers(NUM_PBOS, pboYIds, 0)
        if (pboUvIds[0] != 0) GLES30.glDeleteBuffers(NUM_PBOS, pboUvIds, 0)
        if (yFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(yFbo), 0)
        if (uvFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(uvFbo), 0)
        if (yTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(yTextureId), 0)
        if (uvTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(uvTextureId), 0)
    }

    companion object {
        private const val TAG = "HyperHdrCapture"
        private const val NUM_PBOS = 3
    }
}
