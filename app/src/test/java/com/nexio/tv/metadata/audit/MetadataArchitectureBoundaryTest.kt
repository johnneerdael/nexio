package com.nexio.tv.metadata.audit

import java.io.File
import org.junit.Test

class MetadataArchitectureBoundaryTest {
    private val forbiddenUiImports = listOf(
        "ProviderMetadataRouter",
        "TvMetadataRouter",
        "TmdbMetadataService",
        "KitsuMetadataService",
        "TvdbMetadataService",
        "TmdbApi",
        "TvdbApi",
        "KitsuApi",
        "OkHttpClient",
        "Retrofit"
    )

    private val uiMetadataPaths = listOf(
        "app/src/main/java/com/nexio/tv/ui/",
        "app/src/main/java/com/nexio/tv/workers/",
        "app/src/main/java/com/nexio/tv/data/repository/ContinueWatching"
    )

    @Test
    fun `ui metadata paths do not import legacy provider metadata execution`() {
        val root = File(".")
        val offenders = uiMetadataPaths.flatMap { path ->
            File(root, path).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val text = file.readLines()
                        .filter { line -> line.trimStart().startsWith("import ") }
                        .joinToString(separator = "\n")
                    forbiddenUiImports
                        .filter { symbol -> text.contains(symbol) }
                        .map { symbol -> "${file.path}: forbidden symbol $symbol" }
                }
        }

        check(offenders.isEmpty()) {
            offenders.joinToString(separator = "\n")
        }
    }
}
