package com.nexio.tv.data.local.integration

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationBlobStore internal constructor(
    private val root: File
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(File(context.filesDir, "integration-cache"))

    fun fileFor(path: String): File = File(root, path).apply {
        root.mkdirs()
        parentFile?.mkdirs()
    }

    fun delete(path: String) {
        val file = File(root, path)
        if (file.exists()) {
            file.delete()
        }
        pruneEmptyParents(file.parentFile)
    }

    private fun pruneEmptyParents(start: File?) {
        var current = start
        while (current != null && current != root && current.exists() && current.isDirectory) {
            val children = current.list()
            if (children.isNullOrEmpty()) {
                current.delete()
                current = current.parentFile
            } else {
                return
            }
        }
    }
}
