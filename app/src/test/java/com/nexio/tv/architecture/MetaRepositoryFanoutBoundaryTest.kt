package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaRepositoryFanoutBoundaryTest {

    @Test
    fun `production callers must be in the allow-list at meta_repository_fanout_allowlist_txt`() {
        val productionRoots = listOf(File("app/src/main/java/com/nexio/tv"))
        val allowlist = File("app/src/test/resources/architecture/meta_repository_fanout_allowlist.txt")
            .readLines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val forbidden = Regex("""\b(getMetaFromAllAddons|getCachedMetaFromAllAddons|hydrateAddonOriginItem)\b""")
        val offenders = mutableListOf<String>()
        productionRoots.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val pathSuffix = file.path.substringAfter("/com/nexio/tv").let { "/com/nexio/tv$it" }
                if (allowlist.any { pathSuffix.endsWith(it) }) return@forEach
                if (forbidden.containsMatchIn(file.readText())) {
                    offenders.add(pathSuffix)
                }
            }
        }
        assertEquals(
            "Production callers of MetaRepository fan-out APIs must be in the allow-list " +
                "at app/src/test/resources/architecture/meta_repository_fanout_allowlist.txt. " +
                "Use MetadataRouterFacade.resolveRequest() instead unless the caller has a " +
                "verified addon origin.",
            emptyList<String>(),
            offenders
        )
    }
}
