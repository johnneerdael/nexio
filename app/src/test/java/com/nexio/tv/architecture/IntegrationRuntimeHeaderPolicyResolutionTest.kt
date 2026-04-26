package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegrationRuntimeHeaderPolicyResolutionTest {
    private val auditJson = File("app/build/reports/integration-runtime-audit/integration-runtime-audit.json")
    private val sourceRoot = File("app/src/main/java")

    @Test
    fun `runtime specs do not rely on scanner fallback for header policy`() {
        assertTrue(
            "Run ./gradlew :app:generateIntegrationRuntimeAudit before this assertion.",
            auditJson.isFile
        )
        val text = auditJson.readText()

        assertTrue(
            "Audit must expose expectedHeaderPolicyId for runtime comparison.",
            text.contains("\"expectedHeaderPolicyId\"")
        )
        assertTrue(
            "Audit must not contain runtime-header-policy mismatch rows.",
            !text.contains("header policy differs from endpoint contract")
        )
    }

    @Test
    fun `poster providers declare endpoint specific header policies explicitly`() {
        val rpdb = File(sourceRoot, "com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt").readText()
        val topPosters = File(sourceRoot, "com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt").readText()

        assertTrue(
            "RPDB poster fetch must use rpdb-image-path-key-v1 explicitly.",
            rpdb.contains("headerPolicyId = IntegrationHeaderPolicies.RPDB_IMAGE_PATH_KEY_V1")
        )
        assertTrue(
            "Top-Posters poster fetch must use topposters-image-path-key-v1 explicitly.",
            topPosters.contains("headerPolicyId = IntegrationHeaderPolicies.TOP_POSTERS_IMAGE_PATH_KEY_V1")
        )
    }
}
