package com.nexio.tv.ui.screens.player.spool

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

enum class DiskSpoolStorageLocation {
    BUILTIN,
    EXTERNAL
}

internal object DiskSpoolStorageResolver {
    private const val DISK_SPOOL_DIR = "player_disk_spool"

    fun builtinSpoolDirectory(cacheDir: File): File {
        return File(cacheDir, DISK_SPOOL_DIR)
    }

    internal fun builtinSpoolDirectory(cacheDir: File, deviceProtectedCacheDir: File?): File {
        return builtinSpoolDirectory(deviceProtectedCacheDir ?: cacheDir)
    }

    fun builtinSpoolDirectory(context: Context): File {
        val deviceCacheDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                context.createDeviceProtectedStorageContext()?.cacheDir
            }.getOrNull()
        } else {
            null
        }
        return builtinSpoolDirectory(context.cacheDir, deviceCacheDir)
    }

    fun externalSpoolDirectoryOrNull(context: Context): File? {
        return externalSpoolDirectoryFromCandidates(
            externalCacheDirs = context.externalCacheDirs,
            stateOf = { file -> Environment.getExternalStorageState(file) },
            removableOf = { file -> Environment.isExternalStorageRemovable(file) }
        )
    }

    fun resolveSpoolDirectory(
        context: Context,
        location: DiskSpoolStorageLocation
    ): File? {
        return when (location) {
            DiskSpoolStorageLocation.BUILTIN -> builtinSpoolDirectory(context)
            DiskSpoolStorageLocation.EXTERNAL -> externalSpoolDirectoryOrNull(context)
        }
    }

    internal fun resolveSpoolDirectory(
        cacheDir: File,
        externalCacheDirs: Array<File?>,
        location: DiskSpoolStorageLocation,
        stateOf: (File) -> String,
        removableOf: (File) -> Boolean
    ): File? {
        return when (location) {
            DiskSpoolStorageLocation.BUILTIN -> builtinSpoolDirectory(cacheDir)
            DiskSpoolStorageLocation.EXTERNAL -> externalSpoolDirectoryFromCandidates(
                externalCacheDirs = externalCacheDirs,
                stateOf = stateOf,
                removableOf = removableOf
            )
        }
    }

    internal fun externalSpoolDirectoryFromCandidates(
        externalCacheDirs: Array<File?>,
        stateOf: (File) -> String,
        removableOf: (File) -> Boolean
    ): File? {
        val mountedCandidates = externalCacheDirs
            .filterNotNull()
            .filter { file -> stateOf(file) == Environment.MEDIA_MOUNTED }
        val selectedDirectory = mountedCandidates.firstOrNull { file -> removableOf(file) }
            ?: mountedCandidates.drop(1).firstOrNull()

        return selectedDirectory?.resolve(DISK_SPOOL_DIR)
    }

    internal fun usableSpaceForSpoolDirectory(spoolDirectory: File): Long {
        val existingPath = generateSequence(spoolDirectory) { file -> file.parentFile }
            .firstOrNull { file -> file.exists() }
        return (existingPath ?: spoolDirectory).usableSpace
    }
}
