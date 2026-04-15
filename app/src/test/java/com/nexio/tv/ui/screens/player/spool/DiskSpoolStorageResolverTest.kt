package com.nexio.tv.ui.screens.player.spool

import android.os.Environment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskSpoolStorageResolverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `builtin directory is app cache player disk spool`() {
        val cacheDir = temp.newFolder("cache")

        assertEquals(
            File(cacheDir, "player_disk_spool"),
            DiskSpoolStorageResolver.builtinSpoolDirectory(cacheDir)
        )
    }

    @Test
    fun `builtin directory prefers device protected cache over install location cache`() {
        val installCacheDir = temp.newFolder("install-cache")
        val deviceCacheDir = temp.newFolder("device-cache")

        assertEquals(
            File(deviceCacheDir, "player_disk_spool"),
            DiskSpoolStorageResolver.builtinSpoolDirectory(
                cacheDir = installCacheDir,
                deviceProtectedCacheDir = deviceCacheDir
            )
        )
    }

    @Test
    fun `external directory uses first mounted removable app cache directory`() {
        val primary = temp.newFolder("primary")
        val usb = temp.newFolder("usb")

        val result = DiskSpoolStorageResolver.externalSpoolDirectoryFromCandidates(
            externalCacheDirs = arrayOf(primary, usb),
            stateOf = { Environment.MEDIA_MOUNTED },
            removableOf = { file -> file == usb }
        )

        assertEquals(File(usb, "player_disk_spool"), result)
    }

    @Test
    fun `external directory uses mounted secondary adopted app cache directory`() {
        val primary = temp.newFolder("primary")
        val adopted = temp.newFolder("adopted")

        val result = DiskSpoolStorageResolver.externalSpoolDirectoryFromCandidates(
            externalCacheDirs = arrayOf(primary, adopted),
            stateOf = { Environment.MEDIA_MOUNTED },
            removableOf = { false }
        )

        assertEquals(File(adopted, "player_disk_spool"), result)
    }

    @Test
    fun `external directory ignores single mounted primary app cache directory`() {
        val primary = temp.newFolder("primary")

        val result = DiskSpoolStorageResolver.externalSpoolDirectoryFromCandidates(
            externalCacheDirs = arrayOf(primary),
            stateOf = { Environment.MEDIA_MOUNTED },
            removableOf = { false }
        )

        assertNull(result)
    }

    @Test
    fun `external directory is unavailable when no removable mounted app cache dir exists`() {
        val primary = temp.newFolder("primary")
        val usb = temp.newFolder("usb")

        val result = DiskSpoolStorageResolver.externalSpoolDirectoryFromCandidates(
            externalCacheDirs = arrayOf(primary, usb),
            stateOf = { file -> if (file == usb) Environment.MEDIA_UNMOUNTED else Environment.MEDIA_MOUNTED },
            removableOf = { file -> file == usb }
        )

        assertNull(result)
    }

    @Test
    fun `external directory ignores null app cache candidates`() {
        val primary = temp.newFolder("primary")
        val usb = temp.newFolder("usb")

        val result = DiskSpoolStorageResolver.externalSpoolDirectoryFromCandidates(
            externalCacheDirs = arrayOf(primary, null, usb),
            stateOf = { Environment.MEDIA_MOUNTED },
            removableOf = { file -> file == usb }
        )

        assertEquals(File(usb, "player_disk_spool"), result)
    }

    @Test
    fun `external request returns null when external storage is unavailable`() {
        val cacheDir = temp.newFolder("cache")

        val result = DiskSpoolStorageResolver.resolveSpoolDirectory(
            cacheDir = cacheDir,
            externalCacheDirs = emptyArray(),
            location = DiskSpoolStorageLocation.EXTERNAL,
            stateOf = { Environment.MEDIA_MOUNTED },
            removableOf = { true }
        )

        assertNull(result)
    }

    @Test
    fun `spool usable space falls back to nearest existing parent`() {
        val cacheDir = temp.newFolder("cache")
        val missingSpoolDir = File(cacheDir, "missing-cache/player_disk_spool")

        assertEquals(
            cacheDir.usableSpace,
            DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(missingSpoolDir)
        )
    }
}
