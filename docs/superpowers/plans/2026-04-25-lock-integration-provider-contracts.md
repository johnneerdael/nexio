# Integration Provider Contract Lockdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lock every IntegrationRuntime provider contract to a reviewed endpoint, header, cache, runtime, and best-practice policy so the final audit can validate real provider conformance before MetadataRouter work continues.

**Architecture:** Add a single contract registry that sits above `expected_api_shapes.yaml` and captures provider contract provenance, header rules, cache policy, runtime policy, cache-key vary rules, and best-practice constraints. Extend the existing Gradle audit generator to compare provider blueprint guidance, registry rows, runtime specs, generated headers, runtime samples, and outgoing-request evidence. Keep the current control-plane gate separate from MetadataRouter readiness so a runtime boundary pass cannot hide missing primary-authority endpoint coverage.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, JUnit4 architecture tests, OpenSpec, YAML-like test resources, IntegrationRuntime audit reports.

---

## Working Directory

Run implementation from the IntegrationRuntime worktree:

```bash
cd /Users/jneerdael/Scripts/nexio/.worktrees/integration-runtime-phase-a
```

Use the blueprint guidance from the main checkout:

```text
/Users/jneerdael/Scripts/nexio/apiblueprints/tmdb-contract.md
/Users/jneerdael/Scripts/nexio/apiblueprints/tvdb-contract.md
/Users/jneerdael/Scripts/nexio/apiblueprints/kitsu-contract.md
/Users/jneerdael/Scripts/nexio/apiblueprints/trakt-simkl.md
/Users/jneerdael/Scripts/nexio/apiblueprints/rpdb-topposters.md
/Users/jneerdael/Scripts/nexio/apiblueprints/tidb-mdblist.md
/Users/jneerdael/Scripts/nexio/apiblueprints/full-audit.md
```

## File Structure

- Create: `openspec/changes/lock-integration-provider-contracts/proposal.md`
- Create: `openspec/changes/lock-integration-provider-contracts/tasks.md`
- Create: `openspec/changes/lock-integration-provider-contracts/specs/integration-runtime-audit/spec.md`
- Create: `app/src/test/resources/integration/expected_integration_contracts.yaml`
- Modify: `app/src/test/resources/integration/expected_api_shapes.yaml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHeaderPolicies.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractConformanceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationRuntimeAuditArtifactTest.kt`

The new registry owns the reviewed contracts. `expected_api_shapes.yaml` remains the transitional endpoint index consumed by existing scanner code until the audit generator reads all shape data directly from `expected_integration_contracts.yaml`.

## Contract Registry Shape

Use this top-level structure in `app/src/test/resources/integration/expected_integration_contracts.yaml`:

```yaml
schemaVersion: 1
reviewedAt: "2026-04-25"
contractSources:
  TMDB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tmdb-contract.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  TVDB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tvdb-contract.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  KITSU:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/kitsu-contract.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  TRAKT:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/trakt-simkl.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  SIMKL:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/trakt-simkl.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  RPDB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/rpdb-topposters.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  TOP_POSTERS:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/rpdb-topposters.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  THEINTRODB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tidb-mdblist.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  MDBLIST:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tidb-mdblist.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
headerPolicies: {}
cacheContracts: {}
apiShapes: {}
```

Every `apiShapes` row must contain these fields:

```yaml
  provider: TMDB
  lifecycleStatus: ACTIVE_REQUIRED
  metadataRouterRequired: true
  method: GET
  path: "/movie/{movie_id}"
  headerPolicy: tmdb-json-v1
  cacheContract: tmdb-movie-core-v1
  runtimePolicy:
    workClasses: [USER_VISIBLE, BACKGROUND_HYDRATION]
    defaultCachePolicy: CacheFirst
    scope: Global
    providerConcurrency: 1
    playbackBehavior: blocked during playback unless fresh or stale cache is available
  requestContract:
    requiredQuery: {}
    optionalQuery: {}
    bodyCredentialFields: []
  cacheKeyContract:
    include: []
    forbidden: [raw Authorization token, raw API key, apiKey.hashCode]
  responseHeaders:
    capture: [Retry-After]
  bestPracticeRules: []
```

Allowed `lifecycleStatus` values:

```text
ACTIVE_REQUIRED
ACTIVE_RUNTIME_COVERED
PLANNED_NOT_ACTIVE
DORMANT_PROVIDER
EXEMPT
RETIRED
```

## Task 1: Scaffold OpenSpec Change

**Files:**
- Create: `openspec/changes/lock-integration-provider-contracts/proposal.md`
- Create: `openspec/changes/lock-integration-provider-contracts/tasks.md`
- Create: `openspec/changes/lock-integration-provider-contracts/specs/integration-runtime-audit/spec.md`

- [ ] **Step 1: Create the proposal**

Write `openspec/changes/lock-integration-provider-contracts/proposal.md`:

```markdown
# Lock Integration Provider Contracts

## Why

The IntegrationRuntime control-plane audit can pass while provider contract conformance remains under-specified. MetadataRouter depends on provider-specific endpoint shapes, header policies, cache behavior, runtime policy, and efficiency rules being locked against the reviewed blueprint guidance before routing authority decisions are made.

## What Changes

- Add a reviewed IntegrationRuntime contract registry for provider endpoint, header, cache, runtime, provenance, and best-practice rules.
- Extend the generated audit with provider contract provenance, cache policy conformance, cache-key vary conformance, and provider best-practice conformance.
- Add CI tests that fail when active provider shapes lack contract rows, header policy, cache contract, runtime policy, or MetadataRouter readiness classification.
- Preserve separate gates for control-plane boundary compliance and MetadataRouter readiness.

## Impact

- The existing `:app:generateIntegrationRuntimeAudit` task remains the reviewer entry point.
- Existing `expected_api_shapes.yaml` remains supported during transition.
- MetadataRouter readiness remains failed until active-required primary-authority shapes are runtime-covered or explicitly reclassified.
```

- [ ] **Step 2: Create the OpenSpec task list**

Write `openspec/changes/lock-integration-provider-contracts/tasks.md`:

```markdown
# Tasks

- [ ] Add `expected_integration_contracts.yaml` with provider source provenance, header policies, cache contracts, and API shape contracts.
- [ ] Add registry tests for required top-level sections, provider coverage, lifecycle statuses, and contract references.
- [ ] Lock TMDB and TVDB contracts from the reviewed blueprint briefs.
- [ ] Lock Kitsu, Trakt, Simkl, RPDB, Top-Posters, TheIntroDB, and MDBList contracts from the reviewed blueprint briefs.
- [ ] Extend `:app:generateIntegrationRuntimeAudit` with provider contract provenance, cache policy, cache-key vary, and best-practice matrices.
- [ ] Add conformance tests for header policy references, cache contract references, active-required shape coverage, forbidden cache-key material, and audit output files.
- [ ] Run OpenSpec validation, architecture tests, audit generation, KSP release checks, and release assembly.
```

- [ ] **Step 3: Create the spec delta**

Write `openspec/changes/lock-integration-provider-contracts/specs/integration-runtime-audit/spec.md`:

```markdown
# IntegrationRuntime Audit Contract Lockdown

## ADDED Requirements

### Requirement: Provider contracts are registry-backed

The audit SHALL load a checked-in provider contract registry that records provider provenance, endpoint shape, header policy, cache contract, runtime policy, cache-key vary rules, and best-practice rules for every in-scope IntegrationRuntime API shape.

#### Scenario: Active provider shape lacks registry row

- **GIVEN** an active runtime spec or active-required endpoint shape
- **WHEN** `:app:generateIntegrationRuntimeAudit` runs
- **THEN** the audit fails the provider contract gate and reports the missing shape id.

#### Scenario: Provider contract has source provenance

- **GIVEN** a provider included in `IntegrationProvider`
- **WHEN** the provider appears in the contract registry
- **THEN** the registry records source file, source type, reviewer, and reviewed date.

### Requirement: Header contracts are validated against runtime specs

Every active API shape SHALL declare a header policy, credential location, forbidden cross-provider headers, default User-Agent behavior, response headers to capture, and cache-vary implications.

#### Scenario: Header policy missing

- **GIVEN** an active API shape
- **WHEN** its contract omits `headerPolicy`
- **THEN** the audit fails header conformance.

### Requirement: Cache contracts are explicit

Every active API shape SHALL reference a cache contract that defines cache policy mode, TTL, stale-after-expiry, scope, typed codec requirement, cache-key include fields, and forbidden secret material.

#### Scenario: CacheFirst shape lacks typed codec policy

- **GIVEN** a shape with `cachePolicy: CacheFirst`
- **WHEN** the cache contract does not require a typed codec
- **THEN** the audit fails cache conformance.

### Requirement: MetadataRouter readiness remains separate

The audit SHALL expose a control-plane gate and a MetadataRouter readiness gate. Active-required shapes missing runtime specs SHALL fail MetadataRouter readiness without changing the control-plane verdict.

#### Scenario: Active-required primary-authority endpoint is missing runtime coverage

- **GIVEN** an API shape with `lifecycleStatus: ACTIVE_REQUIRED`
- **WHEN** no runtime spec uses that `apiShapeId`
- **THEN** MetadataRouter readiness is `FAIL` and the missing shape appears in `metadata-router-readiness.csv`.
```

- [ ] **Step 4: Validate the OpenSpec change**

Run:

```bash
openspec validate lock-integration-provider-contracts --strict
```

Expected:

```text
Change 'lock-integration-provider-contracts' is valid
```

## Task 2: Add Contract Registry Skeleton and Registry Tests

**Files:**
- Create: `app/src/test/resources/integration/expected_integration_contracts.yaml`
- Create: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`

- [ ] **Step 1: Add the registry skeleton**

Write `app/src/test/resources/integration/expected_integration_contracts.yaml` with the structure shown in “Contract Registry Shape.” Keep `headerPolicies`, `cacheContracts`, and `apiShapes` as empty maps for this first failing test step:

```yaml
schemaVersion: 1
reviewedAt: "2026-04-25"
contractSources:
  TMDB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tmdb-contract.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  TVDB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tvdb-contract.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  KITSU:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/kitsu-contract.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  TRAKT:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/trakt-simkl.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  SIMKL:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/trakt-simkl.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  RPDB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/rpdb-topposters.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  TOP_POSTERS:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/rpdb-topposters.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  THEINTRODB:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tidb-mdblist.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
  MDBLIST:
    sourceFile: "/Users/jneerdael/Scripts/nexio/apiblueprints/tidb-mdblist.md"
    sourceType: "human-reviewed blueprint brief"
    reviewer: "IntegrationRuntime audit"
headerPolicies: {}
cacheContracts: {}
apiShapes: {}
```

- [ ] **Step 2: Add a failing registry test**

Write `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`:

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegrationProviderContractRegistryTest {
    private val registry = File("app/src/test/resources/integration/expected_integration_contracts.yaml")

    @Test
    fun `contract registry exists and has required sections`() {
        val source = registry.readText()

        listOf(
            "schemaVersion: 1",
            "reviewedAt: \"2026-04-25\"",
            "contractSources:",
            "headerPolicies:",
            "cacheContracts:",
            "apiShapes:"
        ).forEach { token ->
            assertTrue("Registry missing $token", source.contains(token))
        }
    }

    @Test
    fun `contract registry declares provenance for reviewed providers`() {
        val source = registry.readText()
        val providers = setOf("TMDB", "TVDB", "KITSU", "TRAKT", "SIMKL", "RPDB", "TOP_POSTERS", "THEINTRODB", "MDBLIST")

        providers.forEach { provider ->
            assertTrue("Missing provider provenance for $provider", source.contains("  $provider:"))
        }

        val sourceFiles = listOf(
            "tmdb-contract.md",
            "tvdb-contract.md",
            "kitsu-contract.md",
            "trakt-simkl.md",
            "rpdb-topposters.md",
            "tidb-mdblist.md"
        )
        sourceFiles.forEach { filename ->
            assertTrue("Missing blueprint source $filename", source.contains(filename))
        }
    }

    @Test
    fun `contract registry uses only approved lifecycle statuses`() {
        val approved = setOf(
            "ACTIVE_REQUIRED",
            "ACTIVE_RUNTIME_COVERED",
            "PLANNED_NOT_ACTIVE",
            "DORMANT_PROVIDER",
            "EXEMPT",
            "RETIRED"
        )
        val statuses = Regex("""lifecycleStatus:\s*([A-Z_]+)""")
            .findAll(registry.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue("Expected at least one lifecycleStatus entry after provider tasks land.", statuses.isNotEmpty())
        assertEquals(emptySet<String>(), statuses - approved)
    }
}
```

- [ ] **Step 3: Run the failing registry test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationProviderContractRegistryTest"
```

Expected now:

```text
FAILED
Expected at least one lifecycleStatus entry after provider tasks land.
```

## Task 3: Add Shared Header and Cache Contracts

**Files:**
- Modify: `app/src/test/resources/integration/expected_integration_contracts.yaml`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHeaderPolicies.kt`

- [ ] **Step 1: Add header policy constants for policies currently returned as string literals**

Modify `IntegrationHeaderPolicies.kt` so every policy returned by `defaultFor` is a named constant:

```kotlin
package com.nexio.tv.core.integration

object IntegrationHeaderPolicies {
    const val JSON_BODY_NO_AUTH_V1 = "json-body-no-auth-v1"
    const val PUBLIC_JSON_V1 = "public-json-v1"
    const val GRAPHQL_JSON_V1 = "graphql-json-v1"
    const val TMDB_JSON_V1 = "tmdb-json-v1"
    const val TVDB_JSON_BEARER_V1 = "tvdb-json-bearer-v1"
    const val TRAKT_JSON_V2 = "trakt-json-v2"
    const val SIMKL_JSON_V1 = "simkl-json-v1"
    const val IMAGE_FETCH_DEFAULT_V1 = "image-fetch-default-v1"
    const val TOP_POSTERS_THUMBNAIL_V1 = "topposters-thumbnail-v1"
    const val INTRODB_JSON_OPTIONAL_BEARER_V1 = "introdb-json-optional-bearer-v1"
    const val MDBLIST_API_KEY_V1 = "mdblist-api-key-v1"
    const val OMDB_QUERY_API_KEY_V1 = "omdb-query-api-key-v1"
    const val CUSTOM_IMDB_JSON_V1 = "custom-imdb-json-v1"
    const val ADDON_JSON_V1 = "addon-json-v1"
    const val COLLECTOR_JSON_V1 = "collector-json-v1"
    const val GITHUB_JSON_V1 = "github-json-v1"
    const val YOUTUBE_HTML_V1 = "youtube-html-v1"
    const val SUBTITLE_PROVIDER_V1 = "subtitle-provider-v1"

    fun tokenPolicyFor(provider: IntegrationProvider): String =
        "${provider.name.lowercase()}-json-token-v1"

    fun defaultFor(provider: IntegrationProvider, apiShapeId: String): String =
        when (provider) {
            IntegrationProvider.TMDB -> TMDB_JSON_V1
            IntegrationProvider.TVDB -> if (apiShapeId == TvdbApiShapes.LOGIN) JSON_BODY_NO_AUTH_V1 else TVDB_JSON_BEARER_V1
            IntegrationProvider.TRAKT -> TRAKT_JSON_V2
            IntegrationProvider.SIMKL -> SIMKL_JSON_V1
            IntegrationProvider.KITSU,
            IntegrationProvider.ANISKIP,
            IntegrationProvider.ARM -> PUBLIC_JSON_V1
            IntegrationProvider.ANIMESKIP -> GRAPHQL_JSON_V1
            IntegrationProvider.RPDB,
            IntegrationProvider.TOP_POSTERS -> if (apiShapeId == PosterApiShapes.TOP_POSTERS_THUMBNAIL) TOP_POSTERS_THUMBNAIL_V1 else IMAGE_FETCH_DEFAULT_V1
            IntegrationProvider.THEINTRODB -> INTRODB_JSON_OPTIONAL_BEARER_V1
            IntegrationProvider.MDBLIST -> MDBLIST_API_KEY_V1
            IntegrationProvider.OMDB -> OMDB_QUERY_API_KEY_V1
            IntegrationProvider.CUSTOM_IMDB -> CUSTOM_IMDB_JSON_V1
            IntegrationProvider.REAL_DEBRID,
            IntegrationProvider.PREMIUMIZE,
            IntegrationProvider.TORBOX,
            IntegrationProvider.EASY_DEBRID -> tokenPolicyFor(provider)
            IntegrationProvider.ADDON -> ADDON_JSON_V1
            IntegrationProvider.SHADOW_COLLECTOR -> COLLECTOR_JSON_V1
            IntegrationProvider.GITHUB -> GITHUB_JSON_V1
            IntegrationProvider.YOUTUBE_TRAILER -> YOUTUBE_HTML_V1
            IntegrationProvider.SUBTITLE_SOURCE_DOWNLOAD,
            IntegrationProvider.SUBTITLE_TRANSLATION -> SUBTITLE_PROVIDER_V1
        }
}
```

- [ ] **Step 2: Add shared header policies to the registry**

Replace `headerPolicies: {}` with:

```yaml
headerPolicies:
  tmdb-json-v1:
    stock: [nexio-default-user-agent, json-accept]
    requiredHeaders:
      Authorization:
        kind: bearer
        source: tmdb.readAccessToken
        redact: true
    optionalHeaders: {}
    forbiddenHeaders: [X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey, api_key]
    credentialLocation: header
    responseHeaders: [Retry-After]
    userAgentPolicy: nexio-default-user-agent
  tvdb-json-bearer-v1:
    stock: [nexio-default-user-agent, json-accept]
    requiredHeaders:
      Authorization:
        kind: bearer
        source: tvdb.jwt
        redact: true
    optionalHeaders: {}
    forbiddenHeaders: [X-Trakt-API-Key, simkl-api-key, api_key]
    credentialLocation: header
    responseHeaders: [Retry-After]
    userAgentPolicy: nexio-default-user-agent
  json-body-no-auth-v1:
    stock: [nexio-default-user-agent, json-accept, json-content-type]
    requiredHeaders: {}
    optionalHeaders: {}
    forbiddenHeaders: [Authorization, X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey]
    credentialLocation: body
    responseHeaders: []
    userAgentPolicy: nexio-default-user-agent
  public-json-v1:
    stock: [nexio-default-user-agent, json-accept]
    requiredHeaders: {}
    optionalHeaders: {}
    forbiddenHeaders: [Authorization, X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey, api_key]
    credentialLocation: none
    responseHeaders: [Retry-After]
    userAgentPolicy: nexio-default-user-agent
  trakt-json-v2:
    stock: [nexio-default-user-agent, json-accept, json-content-type]
    requiredHeaders:
      X-Trakt-API-Key:
        source: trakt.clientId
        redact: true
      X-Trakt-API-Version:
        value: "2"
        redact: false
    optionalHeaders:
      Authorization:
        kind: bearer
        source: trakt.accessToken
        redact: true
    forbiddenHeaders: [simkl-api-key, X-TVDB-ApiKey, api_key]
    credentialLocation: header
    responseHeaders: [Retry-After, X-Ratelimit, X-Account-Locked, X-Account-Deactivated]
    userAgentPolicy: nexio-default-user-agent
  simkl-json-v1:
    stock: [nexio-default-user-agent, json-accept, json-content-type]
    requiredHeaders:
      simkl-api-key:
        source: simkl.clientId
        redact: true
    optionalHeaders:
      Authorization:
        kind: bearer
        source: simkl.accessToken
        redact: true
    forbiddenHeaders: [X-Trakt-API-Key, X-TVDB-ApiKey, api_key]
    credentialLocation: header
    responseHeaders: [Retry-After]
    userAgentPolicy: nexio-default-user-agent
  mdblist-api-key-v1:
    stock: [nexio-default-user-agent, json-accept]
    requiredHeaders: {}
    optionalHeaders: {}
    forbiddenHeaders: [Authorization, X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey]
    credentialLocation: query.apikey
    responseHeaders: [Retry-After]
    userAgentPolicy: nexio-default-user-agent
  introdb-json-optional-bearer-v1:
    stock: [nexio-default-user-agent, json-accept]
    requiredHeaders: {}
    optionalHeaders:
      Authorization:
        kind: optionalBearer
        source: theintrodb.apiKey
        redact: true
    forbiddenHeaders: [X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey, api_key]
    credentialLocation: optional-header
    responseHeaders: [X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset, X-UsageLimit-Limit, X-UsageLimit-Remaining, X-UsageLimit-Reset, Retry-After]
    userAgentPolicy: nexio-default-user-agent
  image-fetch-default-v1:
    stock: [nexio-default-user-agent, image-accept]
    requiredHeaders: {}
    optionalHeaders: {}
    forbiddenHeaders: [Authorization, X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey]
    credentialLocation: provider-specific
    responseHeaders: [Retry-After]
    userAgentPolicy: nexio-default-user-agent
  topposters-thumbnail-v1:
    stock: [nexio-default-user-agent, image-accept]
    requiredHeaders: {}
    optionalHeaders:
      user_agent:
        transport: query
        source: userAgentProfile.browserLikeOrDefault
        redact: false
    forbiddenHeaders: [Authorization, X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey]
    credentialLocation: path.api_key
    responseHeaders: [Retry-After]
    userAgentPolicy: default-or-approved-browser-profile
  graphql-json-v1:
    stock: [nexio-default-user-agent, json-accept, json-content-type]
    requiredHeaders: {}
    optionalHeaders: {}
    forbiddenHeaders: [X-Trakt-API-Key, simkl-api-key, X-TVDB-ApiKey, api_key]
    credentialLocation: none
    responseHeaders: [Retry-After]
    userAgentPolicy: nexio-default-user-agent
```

- [ ] **Step 3: Add shared cache contracts to the registry**

Replace `cacheContracts: {}` with:

```yaml
cacheContracts:
  primary-identity-alias-v1:
    cachePolicy: CacheFirst
    ttl: 30d
    staleAfterExpiry: 180d
    requiresTypedCodec: true
    retention: durable-identity
  primary-metadata-core-v1:
    cachePolicy: CacheFirst
    ttl: 7d
    staleAfterExpiry: 30d
    requiresTypedCodec: true
    retention: recently-viewed-or-rail-scoped
  season-batch-v1:
    cachePolicy: CacheFirst
    ttl: 24h
    staleAfterExpiry: 7d
    requiresTypedCodec: true
    retention: season-scoped
  dynamic-list-v1:
    cachePolicy: CacheFirst
    ttl: 12h
    staleAfterExpiry: 7d
    requiresTypedCodec: true
    retention: rail-scoped
  discovery-rail-v1:
    cachePolicy: CacheFirst
    ttl: 6h
    staleAfterExpiry: 24h
    requiresTypedCodec: true
    retention: rail-scoped
  ratings-dynamic-v1:
    cachePolicy: CacheFirst
    ttl: 12h
    staleAfterExpiry: 3d
    requiresTypedCodec: true
    retention: rail-scoped
  skip-segments-episode-v1:
    cachePolicy: CacheFirst
    ttl: 30d
    staleAfterExpiry: 90d
    requiresTypedCodec: true
    retention: episode-immutable
  poster-generated-v1:
    cachePolicy: CacheFirst
    ttl: 24h
    staleAfterExpiry: 7d
    requiresTypedCodec: true
    retention: artwork-scoped
  account-state-observe-v1:
    cachePolicy: ObserveOnly
    ttl: none
    staleAfterExpiry: none
    requiresTypedCodec: false
    retention: user-scoped
  mutation-v1:
    cachePolicy: Mutation
    ttl: none
    staleAfterExpiry: none
    requiresTypedCodec: false
    retention: mutation-outbox-or-none
  disabled-no-cache-v1:
    cachePolicy: Disabled
    ttl: none
    staleAfterExpiry: none
    requiresTypedCodec: false
    retention: none
```

- [ ] **Step 4: Run header policy compilation and registry test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationProviderContractRegistryTest"
```

Expected:

```text
FAILED
Expected at least one lifecycleStatus entry after provider tasks land.
```

The failure remains expected until Task 4 adds `apiShapes`.

## Task 4: Lock TMDB and TVDB Contracts

**Files:**
- Modify: `app/src/test/resources/integration/expected_integration_contracts.yaml`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`

- [ ] **Step 1: Add TMDB shape contracts**

Under `apiShapes:`, add these TMDB rows:

```yaml
apiShapes:
  tmdb.find.external_id:
    provider: TMDB
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    metadataRouterRequired: true
    method: GET
    path: "/find/{external_id}"
    headerPolicy: tmdb-json-v1
    cacheContract: disabled-no-cache-v1
    runtimePolicy:
      workClasses: [USER_VISIBLE, BACKGROUND_HYDRATION]
      defaultCachePolicy: Disabled
      scope: Global
      providerConcurrency: 1
      playbackBehavior: defer background work during playback
    requestContract:
      requiredQuery:
        external_source: present
      optionalQuery:
        language: present
    cacheKeyContract:
      include: [provider, apiShapeId, external_id, external_source, language, schema_version]
      forbidden: [raw Authorization token, raw API key, apiKey.hashCode]
    responseHeaders:
      capture: [Retry-After]
    bestPracticeRules: [identity-lookup-cacheable-alias]
  tmdb.movie.core:
    provider: TMDB
    lifecycleStatus: ACTIVE_REQUIRED
    metadataRouterRequired: true
    method: GET
    path: "/movie/{movie_id}"
    headerPolicy: tmdb-json-v1
    cacheContract: primary-metadata-core-v1
    runtimePolicy:
      workClasses: [USER_VISIBLE, BACKGROUND_HYDRATION]
      defaultCachePolicy: CacheFirst
      scope: Global
      providerConcurrency: 1
      playbackBehavior: blocked during playback unless fresh or stale cache is available
    requestContract:
      requiredQuery:
        append_to_response: [credits, images, release_dates, external_ids]
      optionalQuery:
        language: present
        region: present
    cacheKeyContract:
      include: [provider, apiShapeId, movie_id, language, region, append_bundle_version, schema_version]
      forbidden: [raw Authorization token, raw API key, apiKey.hashCode]
    responseHeaders:
      capture: [Retry-After]
    bestPracticeRules: [tmdb-detail-append-bundle]
  tmdb.tv.core:
    provider: TMDB
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    metadataRouterRequired: true
    method: GET
    path: "/tv/{tv_id}"
    headerPolicy: tmdb-json-v1
    cacheContract: primary-metadata-core-v1
    runtimePolicy:
      workClasses: [USER_VISIBLE, BACKGROUND_HYDRATION]
      defaultCachePolicy: CacheFirst
      scope: Global
      providerConcurrency: 1
      playbackBehavior: blocked during playback unless fresh or stale cache is available
    requestContract:
      requiredQuery:
        append_to_response: [credits, images, content_ratings, external_ids]
      optionalQuery:
        language: present
    cacheKeyContract:
      include: [provider, apiShapeId, tv_id, language, append_bundle_version, schema_version]
      forbidden: [raw Authorization token, raw API key, apiKey.hashCode]
    responseHeaders:
      capture: [Retry-After]
    bestPracticeRules: [tmdb-detail-append-bundle]
  tmdb.season.episodes:
    provider: TMDB
    lifecycleStatus: ACTIVE_REQUIRED
    metadataRouterRequired: true
    method: GET
    path: "/tv/{series_id}/season/{season_number}"
    headerPolicy: tmdb-json-v1
    cacheContract: season-batch-v1
    runtimePolicy:
      workClasses: [USER_VISIBLE, BACKGROUND_HYDRATION]
      defaultCachePolicy: CacheFirst
      scope: Global
      providerConcurrency: 1
      playbackBehavior: blocked during playback unless fresh or stale cache is available
    requestContract:
      requiredQuery: {}
      optionalQuery:
        language: present
    cacheKeyContract:
      include: [provider, apiShapeId, series_id, season_number, language, schema_version]
      forbidden: [raw Authorization token, raw API key, apiKey.hashCode]
    responseHeaders:
      capture: [Retry-After]
    bestPracticeRules: [tmdb-season-batch]
  tmdb.movie.videos:
    provider: TMDB
    lifecycleStatus: ACTIVE_REQUIRED
    metadataRouterRequired: true
    method: GET
    path: "/movie/{movie_id}/videos"
    headerPolicy: tmdb-json-v1
    cacheContract: dynamic-list-v1
    runtimePolicy:
      workClasses: [USER_VISIBLE]
      defaultCachePolicy: CacheFirst
      scope: Global
      providerConcurrency: 1
      playbackBehavior: user-visible allowed unless provider backoff is active
    requestContract:
      requiredQuery: {}
      optionalQuery:
        language: present
    cacheKeyContract:
      include: [provider, apiShapeId, movie_id, language, schema_version]
      forbidden: [raw Authorization token, raw API key, apiKey.hashCode]
    responseHeaders:
      capture: [Retry-After]
    bestPracticeRules: [lazy-secondary-fetch]
```

For the remaining TMDB shapes in `expected_api_shapes.yaml`, add registry rows with the same field set and these classifications:

```text
tmdb.tv.videos                         ACTIVE_REQUIRED      dynamic-list-v1
tmdb.season.videos                     PLANNED_NOT_ACTIVE   dynamic-list-v1
tmdb.movie.recommendations             ACTIVE_REQUIRED      dynamic-list-v1
tmdb.tv.recommendations                ACTIVE_REQUIRED      dynamic-list-v1
tmdb.movie.reviews                     ACTIVE_REQUIRED      dynamic-list-v1
tmdb.tv.reviews                        ACTIVE_REQUIRED      dynamic-list-v1
tmdb.collection                        PLANNED_NOT_ACTIVE   primary-metadata-core-v1
tmdb.person.detail                     PLANNED_NOT_ACTIVE   primary-metadata-core-v1
tmdb.person.combined_credits           PLANNED_NOT_ACTIVE   dynamic-list-v1
tmdb.company.detail                    PLANNED_NOT_ACTIVE   primary-metadata-core-v1
tmdb.network.detail                    PLANNED_NOT_ACTIVE   primary-metadata-core-v1
tmdb.discover.movie.by_company         PLANNED_NOT_ACTIVE   discovery-rail-v1
tmdb.discover.tv.by_company_or_network PLANNED_NOT_ACTIVE   discovery-rail-v1
```

- [ ] **Step 2: Add TVDB shape contracts**

Add TVDB rows with these classifications:

```text
tvdb.login                         ACTIVE_RUNTIME_COVERED json-body-no-auth-v1      disabled-no-cache-v1
tvdb.remoteid.lookup               ACTIVE_REQUIRED        tvdb-json-bearer-v1       primary-identity-alias-v1
tvdb.search                        PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       dynamic-list-v1
tvdb.series.base                   PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.series.extended               ACTIVE_REQUIRED        tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.series.translation            ACTIVE_REQUIRED        tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.series.episodes.season_type   ACTIVE_REQUIRED        tvdb-json-bearer-v1       season-batch-v1
tvdb.series.episodes.language      ACTIVE_REQUIRED        tvdb-json-bearer-v1       season-batch-v1
tvdb.episode.translation           PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.updates                       ACTIVE_REQUIRED        tvdb-json-bearer-v1       account-state-observe-v1
tvdb.reference.artwork_types       PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.reference.genres              PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.reference.languages           PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.reference.content_ratings     PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.reference.season_types        PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
tvdb.person.extended               PLANNED_NOT_ACTIVE     tvdb-json-bearer-v1       primary-metadata-core-v1
```

Use exact paths from `apiblueprints/tvdb-contract.md`:

```text
tvdb.login                       POST /login
tvdb.remoteid.lookup             GET /search/remoteid/{remoteId}
tvdb.search                      GET /search
tvdb.series.base                 GET /series/{id}
tvdb.series.extended             GET /series/{id}/extended
tvdb.series.translation          GET /series/{id}/translations/{language}
tvdb.series.episodes.season_type GET /series/{id}/episodes/{seasonType}
tvdb.series.episodes.language    GET /series/{id}/episodes/{seasonType}/{language}
tvdb.episode.translation         GET /episodes/{id}/translations/{language}
tvdb.updates                     GET /updates
tvdb.person.extended             GET /people/{id}/extended
```

- [ ] **Step 3: Add required-shape assertions**

Append to `IntegrationProviderContractRegistryTest.kt`:

```kotlin
    @Test
    fun `contract registry includes TMDB and TVDB primary authority shapes`() {
        val source = registry.readText()
        val required = setOf(
            "tmdb.find.external_id",
            "tmdb.movie.core",
            "tmdb.tv.core",
            "tmdb.season.episodes",
            "tmdb.movie.videos",
            "tmdb.tv.videos",
            "tmdb.movie.recommendations",
            "tmdb.tv.recommendations",
            "tmdb.movie.reviews",
            "tmdb.tv.reviews",
            "tvdb.login",
            "tvdb.remoteid.lookup",
            "tvdb.series.extended",
            "tvdb.series.translation",
            "tvdb.series.episodes.season_type",
            "tvdb.series.episodes.language",
            "tvdb.updates"
        )

        required.forEach { shapeId ->
            assertTrue("Missing contract for $shapeId", source.contains("  $shapeId:"))
        }
    }
```

- [ ] **Step 4: Run the registry tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationProviderContractRegistryTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

## Task 5: Lock Kitsu, Trakt, and Simkl Contracts

**Files:**
- Modify: `app/src/test/resources/integration/expected_integration_contracts.yaml`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`

- [ ] **Step 1: Add Kitsu shape contracts**

Add these Kitsu rows:

```text
kitsu.discovery.trending   ACTIVE_RUNTIME_COVERED public-json-v1 discovery-rail-v1       GET /trending/anime
kitsu.discovery.anime      ACTIVE_REQUIRED        public-json-v1 discovery-rail-v1       GET /anime
kitsu.search.text          ACTIVE_REQUIRED        public-json-v1 dynamic-list-v1         GET /anime
kitsu.anime.core           ACTIVE_RUNTIME_COVERED public-json-v1 primary-metadata-core-v1 GET /anime/{id}
kitsu.anime.episodes       ACTIVE_RUNTIME_COVERED public-json-v1 season-batch-v1         GET /anime/{id}/episodes
kitsu.castings             ACTIVE_REQUIRED        public-json-v1 primary-metadata-core-v1 GET /castings
kitsu.anime_staff          ACTIVE_REQUIRED        public-json-v1 primary-metadata-core-v1 GET /anime/{id}/anime-staff
kitsu.anime_productions    ACTIVE_REQUIRED        public-json-v1 primary-metadata-core-v1 GET /anime/{id}/anime-productions
kitsu.media_relationships  ACTIVE_REQUIRED        public-json-v1 primary-metadata-core-v1 GET /anime/{id}/media-relationships
```

For `kitsu.castings`, set:

```yaml
requestContract:
  requiredQuery:
    "filter[mediaId]": present
    include: [person, character]
  optionalQuery: {}
bestPracticeRules: [kitsu-top-level-castings-include-graph]
```

For `kitsu.anime.episodes`, set:

```yaml
cacheKeyContract:
  include: [provider, apiShapeId, kitsu_id, limit, offset, schema_version]
  forbidden: [Authorization, raw API key, apiKey.hashCode]
bestPracticeRules: [kitsu-paginated-episode-cache]
```

- [ ] **Step 2: Add Trakt shape contracts**

Add these Trakt rows:

```text
trakt.oauth.device_code       ACTIVE_RUNTIME_COVERED trakt-json-v2 disabled-no-cache-v1
trakt.oauth.device_token      ACTIVE_RUNTIME_COVERED trakt-json-v2 mutation-v1
trakt.calendar.my_shows       PLANNED_NOT_ACTIVE     trakt-json-v2 dynamic-list-v1
trakt.discovery.trending      ACTIVE_RUNTIME_COVERED trakt-json-v2 discovery-rail-v1
trakt.discovery.popular       ACTIVE_RUNTIME_COVERED trakt-json-v2 discovery-rail-v1
trakt.recommendations.movies  ACTIVE_RUNTIME_COVERED trakt-json-v2 discovery-rail-v1
trakt.recommendations.shows   ACTIVE_RUNTIME_COVERED trakt-json-v2 discovery-rail-v1
trakt.user.lists              ACTIVE_RUNTIME_COVERED trakt-json-v2 account-state-observe-v1
trakt.playback.progress       ACTIVE_RUNTIME_COVERED trakt-json-v2 account-state-observe-v1
trakt.watched.show            ACTIVE_RUNTIME_COVERED trakt-json-v2 account-state-observe-v1
trakt.history.add             ACTIVE_RUNTIME_COVERED trakt-json-v2 mutation-v1
trakt.history.remove          ACTIVE_RUNTIME_COVERED trakt-json-v2 mutation-v1
trakt.scrobble.start          ACTIVE_RUNTIME_COVERED trakt-json-v2 mutation-v1
trakt.scrobble.pause          ACTIVE_RUNTIME_COVERED trakt-json-v2 mutation-v1
trakt.scrobble.stop           ACTIVE_RUNTIME_COVERED trakt-json-v2 mutation-v1
trakt.checkin                 ACTIVE_RUNTIME_COVERED trakt-json-v2 mutation-v1
trakt.movie.comments          ACTIVE_REQUIRED        trakt-json-v2 dynamic-list-v1
trakt.show.comments           ACTIVE_RUNTIME_COVERED trakt-json-v2 dynamic-list-v1
```

For scrobble and checkin rows set `runtimePolicy.workClasses: [SCROBBLE]`, `scope: Account`, and `playbackBehavior: allowed during playback`.

- [ ] **Step 3: Add Simkl shape contracts**

Add these Simkl rows:

```text
simkl.oauth.pin              ACTIVE_RUNTIME_COVERED simkl-json-v1 disabled-no-cache-v1
simkl.oauth.pin_status       ACTIVE_RUNTIME_COVERED simkl-json-v1 disabled-no-cache-v1
simkl.user.settings          ACTIVE_RUNTIME_COVERED simkl-json-v1 account-state-observe-v1
simkl.last_activities        ACTIVE_RUNTIME_COVERED simkl-json-v1 account-state-observe-v1
simkl.all_items              ACTIVE_RUNTIME_COVERED simkl-json-v1 account-state-observe-v1
simkl.playback               ACTIVE_RUNTIME_COVERED simkl-json-v1 account-state-observe-v1
simkl.playback.delete        ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.scrobble.start         ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.scrobble.pause         ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.scrobble.stop          ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.checkin                ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.history.add            ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.history.remove         ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.list.add               ACTIVE_RUNTIME_COVERED simkl-json-v1 mutation-v1
simkl.discovery.trending     ACTIVE_RUNTIME_COVERED simkl-json-v1 discovery-rail-v1
simkl.discovery.popular      ACTIVE_RUNTIME_COVERED simkl-json-v1 discovery-rail-v1
simkl.summary                ACTIVE_RUNTIME_COVERED simkl-json-v1 dynamic-list-v1
```

For all account-scoped Simkl rows set:

```yaml
cacheKeyContract:
  include: [provider, apiShapeId, accountHash, operation_inputs, schema_version]
  forbidden: [raw Authorization token, simkl-api-key raw value, apiKey.hashCode]
```

- [ ] **Step 4: Add registry assertions for Kitsu, Trakt, and Simkl**

Append to `IntegrationProviderContractRegistryTest.kt`:

```kotlin
    @Test
    fun `contract registry includes anime and tracking provider shapes`() {
        val source = registry.readText()
        val required = setOf(
            "kitsu.discovery.anime",
            "kitsu.search.text",
            "kitsu.anime.core",
            "kitsu.anime.episodes",
            "kitsu.castings",
            "kitsu.anime_staff",
            "kitsu.anime_productions",
            "kitsu.media_relationships",
            "trakt.movie.comments",
            "trakt.show.comments",
            "trakt.scrobble.start",
            "trakt.scrobble.pause",
            "trakt.scrobble.stop",
            "simkl.last_activities",
            "simkl.all_items",
            "simkl.scrobble.start",
            "simkl.discovery.trending"
        )

        required.forEach { shapeId ->
            assertTrue("Missing contract for $shapeId", source.contains("  $shapeId:"))
        }
    }
```

- [ ] **Step 5: Run the registry tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationProviderContractRegistryTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

## Task 6: Lock Artwork, Ratings, and Skip Provider Contracts

**Files:**
- Modify: `app/src/test/resources/integration/expected_integration_contracts.yaml`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`

- [ ] **Step 1: Add RPDB and Top-Posters contracts**

Add these rows:

```text
rpdb.key_validation             ACTIVE_RUNTIME_COVERED image-fetch-default-v1       disabled-no-cache-v1 GET /validate
rpdb.poster_template            ACTIVE_RUNTIME_COVERED image-fetch-default-v1       poster-generated-v1  GET /{api_key}/imdb/poster/{media_id}.jpg
topposters.key_validation       ACTIVE_RUNTIME_COVERED image-fetch-default-v1       disabled-no-cache-v1 GET /validate
topposters.poster_template      ACTIVE_RUNTIME_COVERED image-fetch-default-v1       poster-generated-v1  GET /{api_key}/{id_type}/poster/{media_id}.jpg
topposters.logo                 PLANNED_NOT_ACTIVE     image-fetch-default-v1       poster-generated-v1  GET /{api_key}/{id_type}/logo/{media_id}.png
topposters.thumbnail            ACTIVE_REQUIRED        topposters-thumbnail-v1     poster-generated-v1  GET /{api_key}/{id_type}/thumbnail/{media_id}/S{season}E{episode}.jpg
```

For `topposters.poster_template`, set cache key includes:

```text
provider, apiShapeId, credentialHash, id_type, media_id, poster_type, style, language, trend, badge_sources, fallback_url_hash, schema_version
```

For `topposters.thumbnail`, set cache key includes:

```text
provider, apiShapeId, credentialHash, id_type, media_id, season, episode, badge_position, badge_size, blur, user_agent_profile_id, schema_version
```

Set `bestPracticeRules`:

```text
premium-poster-provider-mutual-exclusion
poster-cache-key-all-output-varying-inputs
browser-user-agent-explicit-profile
```

- [ ] **Step 2: Add MDBList contracts**

Add these rows:

```text
mdblist.key_validation       ACTIVE_RUNTIME_COVERED mdblist-api-key-v1 disabled-no-cache-v1 GET /user
mdblist.user                 ACTIVE_RUNTIME_COVERED mdblist-api-key-v1 account-state-observe-v1 GET /user
mdblist.rating.batch         ACTIVE_RUNTIME_COVERED mdblist-api-key-v1 ratings-dynamic-v1 GET /ratings
mdblist.episode_ratings      ACTIVE_REQUIRED        mdblist-api-key-v1 ratings-dynamic-v1 GET /ratings/episode
mdblist.raw_url.list         ACTIVE_RUNTIME_COVERED mdblist-api-key-v1 account-state-observe-v1 GET dynamic-list-url
mdblist.list.items           PLANNED_NOT_ACTIVE     mdblist-api-key-v1 dynamic-list-v1 GET /lists/{list_id}/items
mdblist.catalog.movie        PLANNED_NOT_ACTIVE     mdblist-api-key-v1 discovery-rail-v1 GET /lists/{list_id}/items
mdblist.catalog.show         PLANNED_NOT_ACTIVE     mdblist-api-key-v1 discovery-rail-v1 GET /lists/{list_id}/items
```

For all MDBList rows set forbidden cache-key fields:

```text
raw apikey query value, raw API key, apiKey.hashCode
```

- [ ] **Step 3: Add TheIntroDB contracts**

Add these rows:

```text
theintrodb.media               ACTIVE_RUNTIME_COVERED introdb-json-optional-bearer-v1 skip-segments-episode-v1 GET /media
theintrodb.authenticated_media ACTIVE_REQUIRED        introdb-json-optional-bearer-v1 skip-segments-episode-v1 GET /media
theintrodb.submit              PLANNED_NOT_ACTIVE     introdb-json-optional-bearer-v1 mutation-v1 POST /submit
```

For `theintrodb.media`, set:

```yaml
runtimePolicy:
  workClasses: [PLAYBACK_RESOLUTION]
  defaultCachePolicy: CacheFirst
  scope: Global
  providerConcurrency: 1
  playbackBehavior: allowed for playback resolution
cacheKeyContract:
  include: [provider, apiShapeId, authMode, credentialHashWhenAuthenticated, external_id_type, external_id, season, episode, schema_version]
  forbidden: [raw Authorization token, raw API key, apiKey.hashCode]
responseHeaders:
  capture: [X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset, X-UsageLimit-Limit, X-UsageLimit-Remaining, X-UsageLimit-Reset, Retry-After]
```

- [ ] **Step 4: Add assertion for artwork, ratings, and skip shapes**

Append to `IntegrationProviderContractRegistryTest.kt`:

```kotlin
    @Test
    fun `contract registry includes artwork ratings and skip provider shapes`() {
        val source = registry.readText()
        val required = setOf(
            "rpdb.key_validation",
            "rpdb.poster_template",
            "topposters.key_validation",
            "topposters.poster_template",
            "topposters.thumbnail",
            "mdblist.key_validation",
            "mdblist.user",
            "mdblist.rating.batch",
            "mdblist.episode_ratings",
            "mdblist.raw_url.list",
            "theintrodb.media",
            "theintrodb.authenticated_media",
            "theintrodb.submit"
        )

        required.forEach { shapeId ->
            assertTrue("Missing contract for $shapeId", source.contains("  $shapeId:"))
        }
    }
```

- [ ] **Step 5: Run the registry tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationProviderContractRegistryTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

## Task 7: Add Contract Conformance Tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractConformanceTest.kt`

- [ ] **Step 1: Add conformance test helpers**

Write `IntegrationProviderContractConformanceTest.kt`:

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegrationProviderContractConformanceTest {
    private val contracts = File("app/src/test/resources/integration/expected_integration_contracts.yaml").readText()
    private val expectedShapes = File("app/src/test/resources/integration/expected_api_shapes.yaml").readText()
    private val integrationSources = File("app/src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.readText().contains("apiShapeId") }
        .associate { it.path.replace(File.separatorChar, '/') to it.readText() }

    private fun contractShapeIds(): Set<String> =
        Regex("""(?m)^  ([a-z0-9][a-z0-9_.-]+):\s*$""")
            .findAll(contracts.substringAfter("apiShapes:"))
            .map { it.groupValues[1] }
            .toSet()

    private fun expectedShapeIds(): Set<String> =
        Regex("""(?m)^([a-z0-9][a-z0-9_.-]+):\s*$""")
            .findAll(expectedShapes)
            .map { it.groupValues[1] }
            .toSet()

    private fun runtimeShapeIds(): Set<String> =
        integrationSources.values.flatMap { source ->
            Regex("""apiShapeId\s*=\s*([A-Za-z0-9_.]+ApiShapes\.[A-Z0-9_]+|"[a-z0-9_.-]+")""")
                .findAll(source)
                .map { it.groupValues[1].trim('"') }
                .toList()
        }.toSet()
}
```

- [ ] **Step 2: Add endpoint registry conformance tests**

Append:

```kotlin
    @Test
    fun `every expected endpoint shape has a provider contract row`() {
        val missing = expectedShapeIds() - contractShapeIds()
        assertTrue("Expected endpoint shapes missing provider contract rows: $missing", missing.isEmpty())
    }

    @Test
    fun `runtime shape literal ids are registry backed`() {
        val literalRuntimeIds = runtimeShapeIds().filter { it.contains('.') }.toSet()
        val missing = literalRuntimeIds - contractShapeIds()
        assertTrue("Runtime apiShapeIds missing provider contract rows: $missing", missing.isEmpty())
    }
```

- [ ] **Step 3: Add policy reference conformance tests**

Append:

```kotlin
    @Test
    fun `every active shape declares header policy cache contract and runtime policy`() {
        val activeBlocks = Regex("""(?ms)^  ([a-z0-9][a-z0-9_.-]+):\n(.*?)(?=^  [a-z0-9][a-z0-9_.-]+:|\z)""")
            .findAll(contracts.substringAfter("apiShapes:"))
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (_, block) ->
                block.contains("lifecycleStatus: ACTIVE_REQUIRED") || block.contains("lifecycleStatus: ACTIVE_RUNTIME_COVERED")
            }
            .toList()

        val missing = activeBlocks.filter { (_, block) ->
            !block.contains("headerPolicy:") ||
                !block.contains("cacheContract:") ||
                !block.contains("runtimePolicy:") ||
                !block.contains("cacheKeyContract:")
        }.map { it.first }

        assertTrue("Active shapes missing contract sections: $missing", missing.isEmpty())
    }

    @Test
    fun `cache key contracts forbid raw secret material`() {
        val blocks = Regex("""(?ms)^  ([a-z0-9][a-z0-9_.-]+):\n(.*?)(?=^  [a-z0-9][a-z0-9_.-]+:|\z)""")
            .findAll(contracts.substringAfter("apiShapes:"))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        val missing = blocks.filter { (_, block) ->
            block.contains("lifecycleStatus: ACTIVE") &&
                (!block.contains("raw API key") || !block.contains("apiKey.hashCode"))
        }.map { it.first }

        assertTrue("Active shapes must forbid raw credentials and hashCode cache keys: $missing", missing.isEmpty())
    }
```

- [ ] **Step 4: Run conformance tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationProviderContractConformanceTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

## Task 8: Extend the Audit Generator Outputs

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationRuntimeAuditArtifactTest.kt`

- [ ] **Step 1: Add new audit row models**

In `app/build.gradle.kts`, near the existing `IntegrationAuditHeaderPolicyRow`, add:

```kotlin
data class IntegrationProviderContractProvenanceRow(
    val provider: String,
    val sourceFile: String,
    val sourceType: String,
    val reviewedAt: String,
    val reviewer: String,
    val usedShapes: Int,
    val activeRequiredShapes: Int,
    val verdict: String
)

data class IntegrationCachePolicyContractRow(
    val apiShapeId: String,
    val provider: String,
    val expectedCacheContract: String,
    val expectedCachePolicy: String,
    val actualCachePolicy: String?,
    val ttl: String,
    val staleAfterExpiry: String,
    val scope: String,
    val codec: String?,
    val cacheKeyTemplate: String?,
    val verdict: String,
    val issues: List<String>
)

data class IntegrationCacheKeyVaryRow(
    val apiShapeId: String,
    val include: String,
    val forbidden: String,
    val actualTemplate: String?,
    val verdict: String,
    val issues: List<String>
)

data class IntegrationBestPracticeRow(
    val apiShapeId: String,
    val provider: String,
    val rule: String,
    val verdict: String,
    val evidence: String
)
```

- [ ] **Step 2: Load the new registry**

Near existing loading of `expected_api_shapes.yaml`, add `expected_integration_contracts.yaml` as an input and parse it with the same lightweight block parser used for shapes:

```kotlin
fun parseIntegrationContractShapes(file: File): Map<String, Map<String, String>> {
    val source = file.readText()
    val apiSection = source.substringAfter("apiShapes:", missingDelimiterValue = "")
    return Regex("""(?ms)^  ([a-z0-9][a-z0-9_.-]+):\n(.*?)(?=^  [a-z0-9][a-z0-9_.-]+:|\z)""")
        .findAll(apiSection)
        .associate { match ->
            val id = match.groupValues[1]
            val block = match.groupValues[2]
            id to mapOf(
                "provider" to Regex("""(?m)^    provider:\s*(\S+)""").find(block)?.groupValues?.get(1).orEmpty(),
                "lifecycleStatus" to Regex("""(?m)^    lifecycleStatus:\s*(\S+)""").find(block)?.groupValues?.get(1).orEmpty(),
                "metadataRouterRequired" to Regex("""(?m)^    metadataRouterRequired:\s*(\S+)""").find(block)?.groupValues?.get(1).orEmpty(),
                "headerPolicy" to Regex("""(?m)^    headerPolicy:\s*(\S+)""").find(block)?.groupValues?.get(1).orEmpty(),
                "cacheContract" to Regex("""(?m)^    cacheContract:\s*(\S+)""").find(block)?.groupValues?.get(1).orEmpty(),
                "method" to Regex("""(?m)^    method:\s*(\S+)""").find(block)?.groupValues?.get(1).orEmpty(),
                "path" to Regex("""(?m)^    path:\s*"?([^"\n]+)"?""").find(block)?.groupValues?.get(1).orEmpty(),
                "bestPracticeRules" to Regex("""(?m)^    bestPracticeRules:\s*\[([^\]]*)]""").find(block)?.groupValues?.get(1).orEmpty(),
                "include" to Regex("""(?m)^      include:\s*\[([^\]]*)]""").find(block)?.groupValues?.get(1).orEmpty(),
                "forbidden" to Regex("""(?m)^      forbidden:\s*\[([^\]]*)]""").find(block)?.groupValues?.get(1).orEmpty()
            )
        }
}
```

- [ ] **Step 3: Generate new output files**

Add these output names to the task’s expected report set:

```text
provider-contract-provenance.csv
cache-policy-matrix.csv
cache-key-vary-matrix.csv
cache-policy-violations.csv
provider-best-practice-matrix.csv
```

Write each file under `build/reports/integration-runtime-audit/`. The CSV headers must be:

```text
provider-contract-provenance.csv:
provider,sourceFile,sourceType,reviewedAt,reviewer,usedShapes,activeRequiredShapes,verdict

cache-policy-matrix.csv:
apiShapeId,provider,expectedCacheContract,expectedCachePolicy,actualCachePolicy,ttl,staleAfterExpiry,scope,codec,cacheKeyTemplate,verdict,issues

cache-key-vary-matrix.csv:
apiShapeId,include,forbidden,actualTemplate,verdict,issues

cache-policy-violations.csv:
apiShapeId,provider,issue,requiredFix

provider-best-practice-matrix.csv:
apiShapeId,provider,rule,verdict,evidence
```

- [ ] **Step 4: Add Markdown sections**

Append these sections to `integration-runtime-audit.md` after the header-policy section:

```text
## Cache-Policy Conformance
## Cache-Key Vary Conformance
## Provider Contract Provenance
## Best-Practice Provider-Efficiency Conformance
```

The cache-policy section fails rows when:

```text
CacheFirst expected but actual is missing
CacheFirst row lacks typed codec
TTL or staleAfterExpiry missing from cache contract
cacheKeyTemplate contains apiKey.hashCode
cacheKeyTemplate contains raw token/key wording
scope is missing for account-scoped providers
```

- [ ] **Step 5: Update audit artifact test**

Add these filenames to `IntegrationRuntimeAuditArtifactTest.kt` in `audit generator task and output paths are declared`:

```kotlin
"provider-contract-provenance.csv",
"cache-policy-matrix.csv",
"cache-key-vary-matrix.csv",
"cache-policy-violations.csv",
"provider-best-practice-matrix.csv"
```

- [ ] **Step 6: Run artifact tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationRuntimeAuditArtifactTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

## Task 9: Generate Full Audit and Enforce Final Gate Semantics

**Files:**
- Modify: `app/build.gradle.kts`
- Verify output: `app/build/reports/integration-runtime-audit/`

- [ ] **Step 1: Run the audit generator**

Run:

```bash
./gradlew :app:generateIntegrationRuntimeAudit --rerun-tasks
```

Expected:

```text
BUILD SUCCESSFUL
```

Generated folder:

```text
/Users/jneerdael/Scripts/nexio/.worktrees/integration-runtime-phase-a/app/build/reports/integration-runtime-audit/
```

- [ ] **Step 2: Verify the full output set exists**

Run:

```bash
ls app/build/reports/integration-runtime-audit
```

Expected output includes:

```text
boundary-exemptions.csv
boundary-violations.csv
cache-key-vary-matrix.csv
cache-policy-matrix.csv
cache-policy-violations.csv
endpoint-shape-matrix.csv
header-policy-matrix.csv
header-runtime-sample.jsonl
header-violations.csv
integration-runtime-audit.json
integration-runtime-audit.md
metadata-router-readiness.csv
provider-best-practice-matrix.csv
provider-contract-provenance.csv
provider-policy-matrix.csv
runtime-event-sample.jsonl
```

- [ ] **Step 3: Verify control-plane and MetadataRouter gates remain separate**

Run:

```bash
grep -E '"controlPlane"|"metadataRouterReadiness"' app/build/reports/integration-runtime-audit/integration-runtime-audit.json
```

Expected:

```text
"controlPlane"
"metadataRouterReadiness"
```

The control-plane gate may be `PASS`. MetadataRouter readiness remains `FAIL` until all `ACTIVE_REQUIRED` shapes are runtime-covered or reclassified.

- [ ] **Step 4: Verify contract conformance failures are actionable**

Run:

```bash
grep -E 'FAIL|WARNING' app/build/reports/integration-runtime-audit/cache-policy-violations.csv
```

Expected after this contract-lockdown phase:

```text
No missing header policy, missing cache contract, missing provider provenance, or undocumented exemption rows.
```

If the command prints rows, each row must name one `apiShapeId`, one issue, and one required fix.

## Task 10: Run Final Verification Commands

**Files:**
- Verify all changed files

- [ ] **Step 1: Run OpenSpec validation**

Run:

```bash
openspec validate lock-integration-provider-contracts --strict
```

Expected:

```text
Change 'lock-integration-provider-contracts' is valid
```

- [ ] **Step 2: Run architecture tests for the audit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationProviderContractRegistryTest" --tests "com.nexio.tv.architecture.IntegrationProviderContractConformanceTest" --tests "com.nexio.tv.architecture.IntegrationRuntimeAuditArtifactTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Run the audit**

Run:

```bash
./gradlew :app:generateIntegrationRuntimeAudit --rerun-tasks
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Run release KSP checks**

Run:

```bash
./gradlew :app:kspArm64ReleaseKotlin :app:kspArmv7ReleaseKotlin :app:kspUniversalReleaseKotlin
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Run release assembly**

Run:

```bash
./gradlew :app:assembleRelease
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Run diff whitespace check**

Run:

```bash
git diff --check
```

Expected:

```text
no output
```

## Acceptance Criteria

- `expected_integration_contracts.yaml` contains reviewed provenance for TMDB, TVDB, Kitsu, Trakt, Simkl, RPDB, Top-Posters, TheIntroDB, and MDBList.
- Every shape in `expected_api_shapes.yaml` has a contract row in `expected_integration_contracts.yaml`.
- Every active runtime-covered shape has header policy, cache contract, runtime policy, cache-key contract, and provider provenance.
- Header policy IDs are named constants in `IntegrationHeaderPolicies.kt`; no provider policy is returned only as an inline string literal except token policy names generated by `tokenPolicyFor`.
- The audit generator emits provider provenance, cache policy, cache-key vary, cache violation, and best-practice matrices.
- `CacheFirst` rows require a typed codec, TTL, stale-after-expiry, and cache-key vary contract.
- Cache-key contracts forbid raw tokens, raw API keys, and `apiKey.hashCode`.
- MetadataRouter readiness remains a separate gate from the control-plane verdict.
- The final audit folder is `/Users/jneerdael/Scripts/nexio/.worktrees/integration-runtime-phase-a/app/build/reports/integration-runtime-audit/`.
- All verification commands in Task 10 pass, except MetadataRouter readiness may continue to report `FAIL` until active-required missing runtime specs are implemented or explicitly reclassified.

## Commit Guidance

Use small commits after passing task-level tests:

```bash
git add openspec/changes/lock-integration-provider-contracts
git commit -m "spec: define integration provider contract lockdown"
```

```bash
git add app/src/test/resources/integration/expected_integration_contracts.yaml app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt app/src/main/java/com/nexio/tv/core/integration/IntegrationHeaderPolicies.kt
git commit -m "test: add integration provider contract registry"
```

```bash
git add app/build.gradle.kts app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractConformanceTest.kt app/src/test/java/com/nexio/tv/architecture/IntegrationRuntimeAuditArtifactTest.kt
git commit -m "feat: audit integration provider contract conformance"
```
