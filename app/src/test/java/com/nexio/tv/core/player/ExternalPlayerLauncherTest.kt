package com.nexio.tv.core.player

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExternalPlayerLauncherTest {

    @Test
    fun `discover returns installed video handlers excluding current app`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = shadowOf(context.packageManager)
        val probeIntent = ExternalPlayerLauncher.buildViewIntent(
            url = "https://example.test/movie.mkv"
        )

        packageManager.addResolveInfoForIntent(
            probeIntent,
            resolveInfo(packageName = "org.videolan.vlc", label = "VLC")
        )
        packageManager.addResolveInfoForIntent(
            probeIntent,
            resolveInfo(packageName = "com.mxtech.videoplayer.ad", label = "MX Player")
        )
        packageManager.addResolveInfoForIntent(
            probeIntent,
            resolveInfo(packageName = context.packageName, label = "NEXIO")
        )
        packageManager.addResolveInfoForIntent(
            probeIntent,
            resolveInfo(packageName = "com.android.tv.frameworkpackagestubs", label = "Media")
        )

        val candidates = ExternalPlayerDiscovery.discover(
            context,
            url = "https://example.test/movie.mkv"
        )

        assertEquals(
            listOf("com.mxtech.videoplayer.ad", "org.videolan.vlc"),
            candidates.map { it.packageName }
        )
        assertEquals(listOf("MX Player", "VLC"), candidates.map { it.label })
    }

    @Test
    fun `launch targets selected package when it can resolve stream intent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).addResolveInfoForIntent(
            ExternalPlayerLauncher.buildViewIntent(
                url = "https://example.test/movie.mkv",
                preferredPackageName = "org.videolan.vlc"
            ),
            resolveInfo(packageName = "org.videolan.vlc", label = "VLC")
        )

        val launched = ExternalPlayerLauncher.launch(
            context = context,
            url = "https://example.test/movie.mkv",
            title = "Movie",
            headers = mapOf("User-Agent" to "NEXIO"),
            preferredPackageName = "org.videolan.vlc"
        )

        val startedIntent = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(launched)
        assertEquals("org.videolan.vlc", startedIntent.`package`)
        assertEquals(Uri.parse("https://example.test/movie.mkv"), startedIntent.data)
        assertEquals("video/*", startedIntent.type)
        assertEquals("Movie", startedIntent.getStringExtra("title"))
        assertEquals(
            listOf("User-Agent: NEXIO"),
            startedIntent.getStringArrayExtra("headers")?.toList()
        )
    }

    @Test
    fun `launch falls back to system resolver when saved package no longer resolves`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val launched = ExternalPlayerLauncher.launch(
            context = context,
            url = "https://example.test/movie.mkv",
            preferredPackageName = "missing.player"
        )

        val startedIntent = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(launched)
        assertNull(startedIntent.`package`)
    }

    private fun resolveInfo(packageName: String, label: String): ResolveInfo {
        return ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.PlayerActivity"
                nonLocalizedLabel = label
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    nonLocalizedLabel = label
                }
            }
            isDefault = true
        }
    }
}
