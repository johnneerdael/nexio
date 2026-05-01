package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBoundaryArchitectureTest {
    @Test
    fun `metadata providers use global content scopes not profile scopes`() {
        val providerFiles = listOf(
            "app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt",
            "app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt",
            "app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt"
        )

        val offenders = providerFiles.flatMap { path ->
            val text = File(path).readText()
            Regex("""IntegrationScope\.(Profile|ProfileLocal|Account)\(""")
                .findAll(text)
                .map { "$path uses profile-bound scope for global metadata: ${it.value}" }
                .toList()
        }

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `image cache keys always include english image language`() {
        val source = File("app/src/main/java/com/nexio/tv/core/image/ArtworkImageCacheKeys.kt").readText()

        assertTrue(
            "ArtworkImageCacheKeys must encode imageLang:en.",
            source.contains("imageLang:en")
        )
        assertTrue(
            "ArtworkImageCacheKeys must not accept display language as an argument.",
            !Regex("""fun\s+\w+\([^)]*language""").containsMatchIn(source)
        )
    }

    @Test
    fun `simkl and trakt account operation keys encode account boundary`() {
        val providerFiles = listOf(
            "app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt" to "SIMKL",
            "app/src/main/java/com/nexio/tv/data/integration/simkl/SimklAuthIntegrationProvider.kt" to "SIMKL",
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt" to "TRAKT"
        )

        val offenders = providerFiles.flatMap { (path, provider) ->
            val text = File(path).readText()
            listOfNotNull(
                if (!text.contains("IntegrationScope.Account(")) {
                    "$path does not use explicit account scope"
                } else {
                    null
                },
                if (!text.contains("ProfileExecutionContext(")) {
                    "$path does not attach profile execution context"
                } else {
                    null
                },
                if (!text.contains("provider:$provider")) {
                    "$path account operation key does not include provider:$provider"
                } else {
                    null
                },
                if (!text.contains("credential:\${credentialHash(session)}")) {
                    "$path account operation key does not include credential hash"
                } else {
                    null
                },
                if (!text.contains(":operation:$")) {
                    "$path account operation key does not include operation name"
                } else {
                    null
                }
            )
        }

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `production runtime scopes do not use legacy string account constructor`() {
        val productionRoot = File("app/src/main/java/com/nexio/tv")
        val offenders = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""IntegrationScope\.Account\(\s*"""")
                    .findAll(file.readText())
                    .map { "${file.path}:${file.readText().take(it.range.first).count { ch -> ch == '\n' } + 1}" }
            }
            .toList()

        assertTrue(
            "Use IntegrationScope.ProviderConfig for global provider config or explicit Account(profileId, provider, credentialHash) for profile-owned account calls:\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `simkl user settings auth runtime call is account scoped`() {
        val path = "app/src/main/java/com/nexio/tv/data/integration/simkl/SimklAuthIntegrationProvider.kt"
        val text = File(path).readText()
        val block = functionBlock(text, "getUserSettings")

        val offenders = listOfNotNull(
            if (!block.contains("session: TrackingAuthSession")) {
                "$path getUserSettings does not accept an explicit account session"
            } else {
                null
            },
            if (!block.contains("operationKey = accountOperationKey(session,")) {
                "$path getUserSettings uses a plain operationKey"
            } else {
                null
            },
            if (!block.contains("scope = accountScope(session)")) {
                "$path getUserSettings does not use accountScope(session)"
            } else {
                null
            },
            if (!block.contains("profileContext = profileContext(session)")) {
                "$path getUserSettings does not attach profileContext(session)"
            } else {
                null
            }
        )

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `trakt and simkl account scopes prefer credential hash from auth state`() {
        val serviceFiles = listOf(
            "app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt" to "TRAKT",
            "app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt" to "SIMKL"
        )
        val providerFiles = listOf(
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt",
            "app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt",
            "app/src/main/java/com/nexio/tv/data/integration/simkl/SimklAuthIntegrationProvider.kt"
        )

        val serviceOffenders = serviceFiles.flatMap { (path, provider) ->
            val block = functionBlock(File(path).readText(), "accountScopedSession")
            listOfNotNull(
                if (!block.contains("integrationCredentialHash(IntegrationProvider.$provider")) {
                    "$path accountScopedSession does not derive credential hash from auth state"
                } else {
                    null
                },
                if (!block.contains("session.copy(") || !block.contains("credentialHash =")) {
                    "$path accountScopedSession does not return a credential-scoped session"
                } else {
                    null
                }
            )
        }

        val providerOffenders = providerFiles.flatMap { path ->
            val text = File(path).readText()
            val block = functionBlock(text, "credentialHash")
            listOfNotNull(
                if (!block.contains("session.credentialHash?.takeIf")) {
                    "$path ignores TrackingAuthSession.credentialHash"
                } else {
                    null
                }
            )
        }

        assertTrue((serviceOffenders + providerOffenders).joinToString("\n"), (serviceOffenders + providerOffenders).isEmpty())
    }

    @Test
    fun `trakt authenticated runtime specs use account scope and account operation keys`() {
        val path = "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"
        val text = File(path).readText()

        val offenders = Regex("""executeAuthorizedRequestWithinRuntimeCall""")
            .findAll(text)
            .mapNotNull { match ->
                val specStart = text.lastIndexOf("IntegrationSpec(", startIndex = match.range.first)
                if (specStart < 0) return@mapNotNull null
                val loadStart = text.indexOf("load = {", startIndex = specStart)
                if (loadStart < 0 || loadStart > match.range.first) return@mapNotNull null

                val specHeader = text.substring(specStart, loadStart)
                val lineNumber = text.take(specStart).count { it == '\n' } + 1

                // Global-content specs intentionally omit profileContext to share the cache across
                // profiles and avoid ProfileBoundaryEnforcer rejections. They are validated
                // separately by TraktAuthenticatedGlobalContentBoundaryTest.
                if (specHeader.contains("scope = IntegrationScope.GlobalContent")) return@mapNotNull null

                val violations = listOfNotNull(
                    if (specHeader.contains("scope = IntegrationScope.Profile(")) {
                        "uses IntegrationScope.Profile"
                    } else {
                        null
                    },
                    if (Regex("""operationKey\s*=\s*"trakt\.""").containsMatchIn(specHeader)) {
                        "uses a plain operationKey"
                    } else {
                        null
                    },
                    if (!specHeader.contains("profileContext = profileContext(session)")) {
                        "does not attach profileContext(session)"
                    } else {
                        null
                    }
                )

                if (violations.isEmpty()) null else "$path:$lineNumber ${violations.joinToString(", ")}"
            }
            .toList()

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `trakt credential lifecycle runtime calls are account scoped after account exists`() {
        val path = "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"
        val text = File(path).readText()

        val offenders = listOf("refreshToken", "revokeToken").flatMap { functionName ->
            val block = functionBlock(text, functionName)
            listOfNotNull(
                if (!block.contains("val ownerSession = traktAuthService.accountScopedSession(session)")) {
                    "$path $functionName does not enrich the session from auth state"
                } else {
                    null
                },
                if (!block.contains("operationKey = accountOperationKey(ownerSession,")) {
                    "$path $functionName uses a plain operationKey"
                } else {
                    null
                },
                if (!block.contains("scope = accountScope(ownerSession)")) {
                    "$path $functionName does not use accountScope(ownerSession)"
                } else {
                    null
                },
                if (!block.contains("profileContext = profileContext(ownerSession)")) {
                    "$path $functionName does not attach profileContext(ownerSession)"
                } else {
                    null
                }
            )
        }

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `trakt authorized helper runtime calls are account scoped`() {
        val path = "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"
        val text = File(path).readText()

        val offenders = listOf(
            helperRegion(text, "executeAuthorizedBackgroundCall", "private suspend fun <T> executeAuthorizedResponseCall"),
            helperRegion(text, "executeAuthorizedResponseCall", "private suspend fun <T> executeRawResponseCall")
        ).flatMap { (functionName, block) ->
            listOfNotNull(
                if (!block.contains("accountScopedSession")) {
                    "$path $functionName does not enrich the session from auth state"
                } else {
                    null
                },
                if (!block.contains("operationKey = accountOperationKey(session, operationKey)") &&
                    !block.contains("operationKey = accountOperationKey(ownerSession, operationKey)")
                ) {
                    "$path $functionName does not derive account operation keys"
                } else {
                    null
                },
                if (!block.contains("scope = accountScope(session)") &&
                    !block.contains("scope = accountScope(ownerSession)")
                ) {
                    "$path $functionName does not use account scope"
                } else {
                    null
                },
                if (!block.contains("profileContext = profileContext(session)") &&
                    !block.contains("profileContext = profileContext(ownerSession)")
                ) {
                    "$path $functionName does not attach profileContext"
                } else {
                    null
                },
                if (block.contains("scope = IntegrationScope.Profile(")) {
                    "$path $functionName still uses IntegrationScope.Profile"
                } else {
                    null
                }
            )
        }

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun helperRegion(source: String, functionName: String, endMarker: String): Pair<String, String> {
        val match = Regex("""private\s+suspend\s+fun(?:\s+<[^>]+>)?\s+${Regex.escape(functionName)}\(""").find(source)
        val start = match?.range?.first ?: -1
        require(start >= 0) { "Missing helper $functionName" }
        val end = source.indexOf(endMarker, startIndex = start + functionName.length)
        require(end >= 0) { "Missing end marker for $functionName" }
        return functionName to source.substring(start, end)
    }

    private fun functionBlock(source: String, functionName: String): String {
        val match = Regex("""fun(?:\s+<[^>]+>)?\s+${Regex.escape(functionName)}\(""").find(source)
        val start = match?.range?.first ?: -1
        require(start >= 0) { "Missing function $functionName" }
        val next = source.indexOf("\n    suspend fun ", startIndex = start + 1)
        return if (next >= 0) source.substring(start, next) else source.substring(start)
    }
}
