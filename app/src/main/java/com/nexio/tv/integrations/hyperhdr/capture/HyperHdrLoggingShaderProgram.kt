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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnostic spike: logs the input texture's properties for every Nth frame so we can find out
 * what Media3's effect pipeline actually delivers to a GlShaderProgram for HDR sources. Throw
 * away after we've collected the information; replaced by HyperHdrCaptureShaderProgram in Task 8.
 *
 * Behaviour: passthrough — input texture is presented as the output texture unchanged, so the
 * player's display is undisturbed.
 *
 * API adaptation vs plan:
 *   - Plan calls `GlUtil.createProgram(vs, fs)` which does not exist in Nexio's media3 fork.
 *     We use `GlProgram(vertexShaderGlsl, fragmentShaderGlsl)` (same fork's GlProgram class) and
 *     set up vertex buffers the same way AlphaScaleShaderProgram does.
 */
@UnstableApi
class HyperHdrLoggingShaderProgram(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private var frameCounter = 0
    private val logEveryN = 60

    private var glProgram: GlProgram? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        Log.i(TAG, "configure inputWidth=$inputWidth inputHeight=$inputHeight useHdr=$useHdr")
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        frameCounter++
        if (frameCounter % logEveryN == 0) {
            // Read one pixel from the input texture via a temporary FBO. We bind the input tex
            // directly to a throwaway FBO (does not disturb the output FBO that BaseGlShaderProgram
            // already focuses), read the centre pixel as UNSIGNED_BYTE RGBA8 (always supported) and
            // again as FLOAT (valid for fp16/fp32 textures, errors otherwise — we capture the error).
            val inspectFbo = IntArray(1)
            GLES20.glGenFramebuffers(1, inspectFbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, inspectFbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, inputTexId, 0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                val readback = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
                // Read as RGBA UNSIGNED_BYTE — always supported
                GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
                val rgba8 = "${readback.get(0).toInt() and 0xFF},${readback.get(1).toInt() and 0xFF}," +
                    "${readback.get(2).toInt() and 0xFF},${readback.get(3).toInt() and 0xFF}"
                // Read as float — only succeeds if texture is fp16/fp32
                readback.rewind()
                GLES20.glGetError() // clear any pending error
                GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES30.GL_FLOAT, readback)
                val floatErr = GLES20.glGetError()
                val asFloat = if (floatErr == GLES20.GL_NO_ERROR) {
                    "${readback.getFloat(0)},${readback.getFloat(4)},${readback.getFloat(8)}"
                } else {
                    "(float read err=0x${floatErr.toString(16)})"
                }
                Log.i(TAG, "frame=$frameCounter pts=${presentationTimeUs}us pixel0(rgba8)=$rgba8 pixel0(float)=$asFloat")
            } else {
                Log.w(TAG, "FBO incomplete during inspection: 0x${status.toString(16)}")
            }
            // Restore: unbind the inspection FBO so the output FBO we received is active again.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, inspectFbo, 0)
            // BaseGlShaderProgram's queueInputFrame re-focuses the output FBO after drawFrame
            // returns, so we just need to not corrupt the framebuffer state. Rebind the default
            // (0) above achieves that; the parent class will re-focus the real output FBO.
        }

        // Passthrough draw: copy inputTexId → output FBO (already focused by BaseGlShaderProgram).
        try {
            val prog = getOrCreateGlProgram()
            prog.use()
            prog.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            prog.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException("HyperHdrLoggingShaderProgram passthrough draw failed", e)
        }
    }

    @Throws(GlUtil.GlException::class)
    private fun getOrCreateGlProgram(): GlProgram {
        glProgram?.let { return it }
        // Vertex shader: full-screen triangle strip from -1..+1 NDC with UV 0..1
        val vs = """
            #version 100
            attribute vec4 aFramePosition;
            attribute vec4 aTexSamplerCoord;
            varying vec2 vTexSamplerCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplerCoord = vec2(aTexSamplerCoord.x, aTexSamplerCoord.y);
            }
        """.trimIndent()
        // Fragment shader: simple passthrough sampler2D
        val fs = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplerCoord;
            void main() {
                gl_FragColor = texture2D(uTexSampler, vTexSamplerCoord);
            }
        """.trimIndent()
        val prog = GlProgram(vs, fs)
        // Full-screen NDC quad in homogeneous coords (matches GlUtil.getNormalizedCoordinateBounds)
        prog.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        // UV coords 0..1 for the same four vertices
        prog.setBufferAttribute(
            "aTexSamplerCoord",
            GlUtil.getTextureCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram = prog
        return prog
    }

    override fun release() {
        super.release()
        try {
            glProgram?.delete()
        } catch (_: GlUtil.GlException) { /* best-effort */ }
        glProgram = null
    }

    companion object {
        private const val TAG = "HyperHdrSpike"
    }
}
