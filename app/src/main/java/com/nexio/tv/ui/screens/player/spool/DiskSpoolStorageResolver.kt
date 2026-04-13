package com.nexio.tv.ui.screens.player.spool

import android.content.Context
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

    fun builtinSpoolDirectory(context: Context): File {
        return builtinSpoolDirectory(context.cacheDir)
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
        externalCacheDirs: Array<File>,
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
        externalCacheDirs: Array<File>,
        stateOf: (File) -> String,
        removableOf: (File) -> Boolean
    ): File? {
        return externalCacheDirs
            .filter { file -> stateOf(file) == Environment.MEDIA_MOUNTED }
            .firstOrNull { file -> removableOf(file) }
            ?.resolve(DISK_SPOOL_DIR)
    }
}
