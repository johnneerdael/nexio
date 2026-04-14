package com.nexio.tv.instrumentation

import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.ui.screens.player.ass.AssSsaNativeBridge
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssSsaNativeRenderSmokeTest {
    private val supportedNativeAbis = setOf("arm64-v8a", "armeabi-v7a")
    private var nativeHandle: Long = 0L

    @After
    fun tearDown() {
        if (nativeHandle != 0L) {
            AssSsaNativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    @Test
    fun rendersSimpleAssDialogueChunk() {
        assumeTrue(
            "ASS/SSA native bridge only packages $supportedNativeAbis; device ABIs=${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.any { it in supportedNativeAbis }
        )
        assertTrue(
            "ASS/SSA native bridge should load on supported ABI; loadError=${AssSsaNativeBridge.nativeLoadError}",
            AssSsaNativeBridge.nativeAvailable
        )
        assertTrue(AssSsaNativeBridge.configureFontconfig(ApplicationProvider.getApplicationContext()))

        nativeHandle = AssSsaNativeBridge.nativeInit(width = 320, height = 180, fontScale = 1.0f)
        assertNotEquals("native init should return a non-zero handle", 0L, nativeHandle)

        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 320
            PlayResY: 180

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,sans-serif,32,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertTrue(AssSsaNativeBridge.nativeLoadHeader(nativeHandle, header) == 0)

        val chunk = "0,0,Default,,0,0,0,,Hello from libass".toByteArray(Charsets.UTF_8)
        AssSsaNativeBridge.nativeProcessChunk(nativeHandle, chunk, startMs = 0L, durationMs = 1_000L)

        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        assertTrue(AssSsaNativeBridge.nativeRender(nativeHandle, timeMs = 500L, bitmap))
    }
}
