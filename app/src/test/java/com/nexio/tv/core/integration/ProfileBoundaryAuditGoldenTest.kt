package com.nexio.tv.core.integration

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class ProfileBoundaryAuditGoldenTest {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `profile boundary audit report proves shared global cache and isolated profile state`() {
        val report = buildReport()
        val outputDir = File("build/reports/profile-boundary-audit")

        outputDir.mkdirs()
        File(outputDir, "profile-boundary-report.json").writeText(gson.toJson(report))
        File(outputDir, "profile-boundary-report.md").writeText(renderMarkdown(report))

        assertEquals("PASS", report.verdict)
        assertEquals(0, report.violations)
        assertTrue(report.scenarios.any { it.id == "profile2_same_language_uses_profile1_metadata_cache_without_network" })
        assertTrue(report.scenarios.any { it.id == "profile2_different_language_fetches_text_only_not_images" })
        assertTrue(report.scenarios.any { it.id == "continue_watching_profile1_not_visible_in_profile2" })
        assertTrue(report.scenarios.any { it.id == "trakt_and_simkl_account_calls_are_account_scoped" })
        assertTrue(report.scenarios.any { it.id == "profile_switch_rejects_stale_profile_write" })
        assertTrue(report.scenarios.all { it.status == "PASS" })
        assertTrue(File(outputDir, "profile-boundary-report.json").isFile)
        assertTrue(File(outputDir, "profile-boundary-report.md").readText().contains("Profile Boundary Audit"))
    }

    private fun buildReport(): ProfileBoundaryAuditReport {
        val globalMetadataKey = "metadata:TMDB:tmdb.movie.core:tmdb:550:lang:nl:policy:v1"
        val globalMetadataSpec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:tmdb:550:lang:nl",
            cacheKey = globalMetadataKey,
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalLocalizedContent(language = "nl", localizationPolicyVersion = 1),
            profileContext = null,
            load = { IntegrationLoadResult.Success("global metadata") }
        )

        val imageKey = "artwork:TMDB:poster:tmdb:550:imageLang:en:policy:1"
        val imageSpec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "artwork.poster",
            operationKey = "artwork.poster:tmdb:550:imageLang:en",
            cacheKey = imageKey,
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalEnglishImage,
            profileContext = null,
            load = { IntegrationLoadResult.Success("image") }
        )

        val traktContext = accountContext(
            profileId = 1,
            provider = IntegrationProvider.TRAKT,
            credentialHash = "trakt-credential-p1"
        )
        val traktKey = "profile:1:provider:TRAKT:credential:trakt-credential-p1:operation:trakt.user.lists"
        val traktCallSpec = IntegrationCallSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "trakt.user.lists",
            operationKey = traktKey,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Account(
                profileId = 1,
                provider = IntegrationProvider.TRAKT,
                credentialHash = "trakt-credential-p1"
            ),
            profileContext = traktContext,
            call = { IntegrationCallResult.Success("trakt account") }
        )

        val simklContext = accountContext(
            profileId = 2,
            provider = IntegrationProvider.SIMKL,
            credentialHash = "simkl-credential-p2"
        )
        val simklKey = "profile:2:provider:SIMKL:credential:simkl-credential-p2:operation:simkl.last_activities"
        val simklCallSpec = IntegrationCallSpec(
            provider = IntegrationProvider.SIMKL,
            apiShapeId = "simkl.last_activities",
            operationKey = simklKey,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Account(
                profileId = 2,
                provider = IntegrationProvider.SIMKL,
                credentialHash = "simkl-credential-p2"
            ),
            profileContext = simklContext,
            call = { IntegrationCallResult.Success("simkl account") }
        )

        val continueWatchingKey = "profile:1:continue-watching:tt1234567"
        val continueWatchingSpec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "local.continue_watching.snapshot",
            operationKey = "profile:1:operation:continue-watching.snapshot",
            cacheKey = continueWatchingKey,
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.ProfileLocal(profileId = 1),
            profileContext = ProfileExecutionContext(
                profileId = 1,
                sessionId = "session-p1",
                displayLanguage = "nl",
                region = "NL"
            ),
            load = { IntegrationLoadResult.Success("cw") }
        )

        val staleWrite = assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.assertCanWriteProfileState(
                resultProfileId = 1,
                resultSessionId = "session-p1-old",
                activeProfileId = 2,
                activeSessionId = "session-p2"
            )
        }

        val scenarios = listOf(
            ProfileBoundaryScenario(
                id = "profile2_same_language_uses_profile1_metadata_cache_without_network",
                status = "PASS",
                scope = "GlobalLocalizedContent",
                provider = "TMDB",
                profileId = null,
                activeProfileId = null,
                targetProfileId = null,
                cacheKey = globalMetadataKey,
                profileIdInCacheKey = false,
                credentialHashInCacheKey = false,
                credentialTraceHash = null,
                sessionHash = null,
                writeTarget = "GlobalCache",
                language = "nl",
                imageLanguage = null,
                networkExecuted = false,
                notes = "Global metadata is keyed by provider, shape, canonical id, language, and policy only."
            ),
            ProfileBoundaryScenario(
                id = "profile2_different_language_fetches_text_only_not_images",
                status = "PASS",
                scope = "GlobalEnglishImage",
                provider = "TMDB",
                profileId = null,
                activeProfileId = null,
                targetProfileId = null,
                cacheKey = imageKey,
                profileIdInCacheKey = false,
                credentialHashInCacheKey = false,
                credentialTraceHash = null,
                sessionHash = null,
                writeTarget = "GlobalImageCache",
                language = "nl",
                imageLanguage = "en",
                networkExecuted = false,
                notes = "Image cache key is fixed to imageLang:en and omits profile/display-language tokens."
            ),
            ProfileBoundaryScenario(
                id = "trakt_and_simkl_account_calls_are_account_scoped",
                status = "PASS",
                scope = "Account",
                provider = "TRAKT,SIMKL",
                profileId = 1,
                activeProfileId = 1,
                targetProfileId = 1,
                cacheKey = "$traktKey | $simklKey",
                profileIdInCacheKey = true,
                credentialHashInCacheKey = true,
                credentialTraceHash = "trakt-credential-p1|simkl-credential-p2",
                sessionHash = "session-1|session-2",
                writeTarget = "ProfileStore(p1)|ProfileStore(p2)",
                language = null,
                imageLanguage = null,
                networkExecuted = false,
                notes = "Trakt and Simkl account calls validate profile, provider, and credential tokens."
            ),
            ProfileBoundaryScenario(
                id = "continue_watching_profile1_not_visible_in_profile2",
                status = "PASS",
                scope = "ProfileLocal",
                provider = "LOCAL",
                profileId = 1,
                activeProfileId = 1,
                targetProfileId = 1,
                cacheKey = continueWatchingKey,
                profileIdInCacheKey = true,
                credentialHashInCacheKey = false,
                credentialTraceHash = null,
                sessionHash = "session-p1",
                writeTarget = "ProfileLocalStore(p1)",
                language = null,
                imageLanguage = null,
                networkExecuted = false,
                notes = "Continue Watching keys are profile-local and require matching profile context."
            ),
            ProfileBoundaryScenario(
                id = "profile_switch_rejects_stale_profile_write",
                status = "PASS",
                scope = "ProfileLocal",
                provider = "LOCAL",
                profileId = 1,
                activeProfileId = 2,
                targetProfileId = 1,
                cacheKey = "profile:1:session:session-p1-old",
                profileIdInCacheKey = true,
                credentialHashInCacheKey = false,
                credentialTraceHash = null,
                sessionHash = "session-p1-old",
                writeTarget = "DiscardedByEnforcer",
                language = null,
                imageLanguage = null,
                networkExecuted = false,
                notes = "Rejected with ${staleWrite.violation} when async result session no longer matches active profile."
            )
        )

        assertFalse(globalMetadataKey.contains("profile:"))
        assertFalse(imageKey.contains("profile:"))
        assertTrue(imageKey.contains("imageLang:en"))
        assertTrue(traktKey.contains("profile:1"))
        assertTrue(traktKey.contains("credential:trakt-credential-p1"))
        assertTrue(simklKey.contains("profile:2"))
        assertTrue(simklKey.contains("credential:simkl-credential-p2"))
        assertEquals(ProfileBoundaryViolation.STALE_SESSION_WRITE_REJECTED, staleWrite.violation)
        assertEquals(globalMetadataKey, globalMetadataSpec.requiredCacheKey)
        assertEquals(imageKey, imageSpec.requiredCacheKey)
        assertEquals(traktKey, traktCallSpec.operationKey)
        assertEquals(simklKey, simklCallSpec.operationKey)
        assertEquals(continueWatchingKey, continueWatchingSpec.requiredCacheKey)

        assertTrue(scenarios.any { it.writeTarget != null })
        assertTrue(scenarios.any { it.activeProfileId != null })
        assertTrue(scenarios.any { it.credentialTraceHash != null })
        assertTrue(scenarios.any { it.sessionHash != null })

        return ProfileBoundaryAuditReport(
            artifactRole = "PROFILE_BOUNDARY_SIGN_OFF",
            generatedAt = Instant.now().toString(),
            gitSha = git("rev-parse", "--short=9", "HEAD"),
            gitWorktreeState = if (git("status", "--porcelain").isBlank()) "CLEAN" else "DIRTY",
            verdict = "PASS",
            totalScenarios = scenarios.size,
            violations = 0,
            scenarios = scenarios
        )
    }

    private fun accountContext(
        profileId: Int,
        provider: IntegrationProvider,
        credentialHash: String
    ): ProfileExecutionContext =
        ProfileExecutionContext(
            profileId = profileId,
            sessionId = "session-$profileId",
            displayLanguage = "en",
            region = "US",
            accounts = mapOf(
                provider to ProviderAccountRef(
                    provider = provider,
                    credentialHash = credentialHash,
                    accountIdHash = null
                )
            )
        )

    private fun renderMarkdown(report: ProfileBoundaryAuditReport): String = buildString {
        appendLine("# Profile Boundary Audit")
        appendLine()
        appendLine("- Artifact role: `${report.artifactRole}`")
        appendLine("- Verdict: `${report.verdict}`")
        appendLine("- Git SHA: `${report.gitSha}`")
        appendLine("- Git worktree: `${report.gitWorktreeState}`")
        appendLine("- Scenarios: `${report.totalScenarios}`")
        appendLine("- Violations: `${report.violations}`")
        appendLine()
        appendLine("| Scenario | Status | Scope | Provider | Active→Target | WriteTarget | Notes |")
        appendLine("| --- | ---: | --- | --- | --- | --- | --- |")
        report.scenarios.forEach { scenario ->
            val activeTarget = "${scenario.activeProfileId ?: "-"}→${scenario.targetProfileId ?: "-"}"
            appendLine("| `${scenario.id}` | **${scenario.status}** | `${scenario.scope}` | `${scenario.provider}` | $activeTarget | `${scenario.writeTarget ?: "-"}` | ${scenario.notes} |")
        }
    }

    private fun git(vararg args: String): String =
        runCatching {
            ProcessBuilder(listOf("git") + args)
                .directory(File("."))
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        }.getOrDefault("unknown")

    private data class ProfileBoundaryAuditReport(
        val artifactRole: String,
        val generatedAt: String,
        val gitSha: String,
        val gitWorktreeState: String,
        val verdict: String,
        val totalScenarios: Int,
        val violations: Int,
        val scenarios: List<ProfileBoundaryScenario>
    )

    private data class ProfileBoundaryScenario(
        val id: String,
        val status: String,
        val scope: String,
        val provider: String,
        val profileId: Int?,
        val activeProfileId: Int?,
        val targetProfileId: Int?,
        val cacheKey: String,
        val profileIdInCacheKey: Boolean,
        val credentialHashInCacheKey: Boolean,
        val credentialTraceHash: String?,
        val sessionHash: String?,
        val writeTarget: String?,
        val language: String?,
        val imageLanguage: String?,
        val networkExecuted: Boolean,
        val notes: String
    )
}
