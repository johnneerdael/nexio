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
}
