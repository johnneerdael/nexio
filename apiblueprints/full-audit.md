According to a document from 2026-04-25, your latest audit has reached a good **control-plane** state but is not yet a full **MetadataRouter-readiness** pass: it reports 24 in-scope providers, 63 runtime-covered calls, 0 direct bypasses, 0 missing policy entries, 0 missing endpoint-shape IDs, 0 missing header policies, and 0 undocumented exemptions; however, the MetadataRouter-readiness gate still fails because 20 active-required endpoint shapes are missing runtime specs and 40 shapes are still planned-not-active.

So the next audit should not merely ask:

> “Does this go through IntegrationRuntime?”

It should ask:

> “Does this go through IntegrationRuntime **with the right provider contract, right headers, right cache policy, right runtime behavior, and right endpoint shape for the job?**”

I would turn the audit into a **five-layer conformance system**.

---

# 1. Define the five audit layers

The current report already proves much of layer 1. Now you need layers 2–5.

```text
Layer 1 — Connectivity and boundary
    Does every in-scope provider call enter IntegrationRuntime?

Layer 2 — Provider API contract
    Does the call match the provider blueprint/OpenAPI/API docs?

Layer 3 — Header contract
    Are required, optional, forbidden, auth, User-Agent, and response headers correct?

Layer 4 — Nexio cache/runtime policy
    Does the call use the correct CacheFirst / ObserveOnly / Disabled / Mutation policy?

Layer 5 — Runtime behavior
    Does the runtime actually behave as promised: fresh cache skips loader, 429 blocks, playback gates, etc.?
```

This matters because your runtime design says the runtime owns cache policy execution, cache-first reads, stale fallback, single-flight, provider-lane serialization, persisted backoff, playback/startup gating, telemetry, and debugging visibility. It also states that cache timing must come from `CacheFirst(ttlMs, staleAfterExpiryMs)`, with `freshUntil = fetchedAt + ttlMs` and `staleUntil = freshUntil + staleAfterExpiryMs`.

---

# 2. Create a single “contract registry” as the source of truth

Do not keep API-shape validation, header validation, and cache-policy validation in separate mental models. Create one checked-in registry.

Call it something like:

```text
expected_integration_contracts.yaml
```

Each `apiShapeId` should have four sections:

```yaml
tmdb.movie.core:
  provider: TMDB
  lifecycleStatus: ACTIVE_REQUIRED

  providerContract:
    source:
      type: openapi
      file: apiblueprints/tmdb.json
      operationId: movie-details
    method: GET
    path: "/3/movie/{movie_id}"
    query:
      required:
        language: present
        append_to_response:
          contains:
            - credits
            - images
            - release_dates
            - external_ids
      optional:
        append_to_response:
          mayContain:
            - videos
            - recommendations
            - reviews
            - translations
    body: none
    bulkShape: one-call multi-field enrichment

  headerContract:
    policyId: tmdb-json-v1
    stock:
      - nexio-default-user-agent
      - json-accept
    requiredHeaders:
      Authorization:
        kind: bearer
        source: tmdb.readAccessToken
        redact: true
    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - X-TVDB-ApiKey
      - api_key
    responseHeadersToCapture:
      - Retry-After

  cacheContract:
    policy: CacheFirst
    ttl: 7d
    staleAfterExpiry: 30d
    scope: Global
    codec: TmdbMovieCoreCodec
    cacheKeyIncludes:
      - provider
      - apiShapeId
      - movie_id
      - language
      - append_bundle_version
      - schema_version
    forbiddenCacheKeyParts:
      - raw_api_key
      - raw_bearer_token

  runtimeContract:
    allowedWorkClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    maxConcurrentNetworkStarts: 1
    playbackBehavior: stale-or-defer
    backoff:
      captureRetryAfter: true
      persistScope: provider
```

That one record lets your generator compare:

```text
provider blueprint → expected contract → actual adapter spec → actual runtime event → actual outgoing request
```

The endpoint index already gives you the route-shape baseline: TMDB detail calls are one-call multi-field enrichment routes, TVDB `series/{id}/extended` is the TV title enrichment shape, TVDB season episode routes are season batches, and Kitsu advanced anime detail uses routes such as top-level `/castings` plus nested staff/production/relationship routes.

---

# 3. Audit against provider blueprints in two passes

You should not expect every blueprint to be perfect or equally machine-readable. Use two passes.

## Pass A — Mechanical extraction

For OpenAPI specs such as TMDB and Top-Posters, parse:

```text
method
path
operationId
query parameters
path parameters
body schema
security schemes
response headers, where declared
content type
```

TMDB’s OpenAPI surface declares an `Authorization` security scheme in the request header, so that can be mechanically checked against your `tmdb-json-v1` header policy.

Top-Posters’ OpenAPI text says poster generation uses `/{api_key}/{id_type}/poster/{media_id}.jpg`, supports IDs such as IMDb, TMDB, TVDB, Trakt, MAL, Kitsu, AniList, and AniDB, and documents tiered limits where exceeding limits returns `429 Too Many Requests`; it also documents episode thumbnail routes and optional customization parameters.

## Pass B — Human-reviewed overlay

For API Blueprint files or dynamic transports, add a reviewed overlay:

```yaml
trakt-json-v2:
  provenance:
    source: trakt.apib
    reviewedAt: 2026-04-25
    reviewer: integration-platform
  requiredHeaders:
    X-Trakt-API-Key: trakt.clientId
    X-Trakt-API-Version: "2"
  optionalHeaders:
    Authorization: trakt.accessToken
```

Use overlays for:

```text
Trakt
Simkl
MDBList
Kitsu
RPDB
OMDb
custom IMDb
debrid providers
dynamic addon routes
subtitle providers
```

The rule should be:

> If the provider blueprint is incomplete, the overlay is allowed — but it must have provenance, review date, and a reason.

---

# 4. Add a “best-practice conformance” section to the audit

Right now your report can say `PASS` for endpoint/header/policy structure. Add a new section:

```text
Section H — Best-Practice Conformance
```

This should score each call on practical integration quality, not just structural correctness.

Example:

| Dimension               | What it checks                                                              |
| ----------------------- | --------------------------------------------------------------------------- |
| Runtime coverage        | Call enters runtime before network.                                         |
| Endpoint shape          | Method/path/query/body match provider contract.                             |
| Bulk efficiency         | Uses append/batch/list route where provider supports it.                    |
| Header policy           | Required headers present, forbidden headers absent.                         |
| Auth location           | Credential in correct header/path/query/body location.                      |
| User-Agent policy       | Default UA used unless explicit approved override.                          |
| Response header capture | `Retry-After` and rate-limit headers captured where expected.               |
| Cache policy            | `CacheFirst`, `ObserveOnly`, `Disabled`, or `Mutation` matches your policy. |
| Cache key correctness   | Cache key includes all response-varying inputs and excludes secrets.        |
| Codec correctness       | `CacheFirst` uses a typed codec.                                            |
| Fresh-hit behavior      | Fresh cache prevents loader/network.                                        |
| Backoff behavior        | `429` persists and blocks new starts.                                       |
| Playback behavior       | Work class obeys playback gate.                                             |
| Redaction               | URLs, keys, tokens, path API keys, and query keys redacted in audit output. |

Then give each call:

```text
PASS
PASS_WITH_WARNINGS
FAIL
```

This gives you a richer answer than “endpoint shape matches.”

---

# 5. Validate header usage with actual outgoing requests, not only the matrix

Your header policy matrix is a strong start. It already shows, for example, TMDB shapes requiring `Authorization`, forbidding Trakt/Simkl/TVDB/API-key leakage, using the default Nexio User-Agent, and capturing `Retry-After`; Trakt shapes require `X-Trakt-API-Key` and `X-Trakt-API-Version`, allow `Authorization`, forbid cross-provider headers, and capture `Retry-After`.

But a matrix proves the contract. It does **not** prove the actual outgoing request complied.

Add an enforcement interceptor:

```kotlin
class IntegrationHeaderContractInterceptor(
    private val contracts: IntegrationContractRegistry,
    private val mode: Mode // AUDIT_ONLY or ENFORCE
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val permit = request.tag(IntegrationNetworkPermit::class.java)
            ?: return chain.proceed(request)

        val contract = contracts.get(permit.apiShapeId)

        val result = HeaderContractValidator.validate(
            contract = contract.headerContract,
            actualHeaders = request.headers,
            actualUrl = request.url,
            method = request.method
        )

        if (result.hasFailure) {
            IntegrationAuditSink.recordHeaderViolation(
                traceId = permit.traceId,
                apiShapeId = permit.apiShapeId,
                provider = permit.provider,
                redactedHeaders = redactHeaders(request.headers),
                violations = result.violations
            )

            if (mode == Mode.ENFORCE) {
                error("Header contract violation for ${permit.apiShapeId}: ${result.summary}")
            }
        }

        val response = chain.proceed(request)

        RateLimitHeaderCapture.capture(
            provider = permit.provider,
            apiShapeId = permit.apiShapeId,
            headers = response.headers
        )

        return response
    }
}
```

Then add runtime evidence:

```text
header-policy-matrix.csv
    what should happen

header-runtime-sample.jsonl
    what actually happened

header-violations.csv
    what failed
```

Best-practice rule:

> Static header policy can pass only if sampled runtime requests also demonstrate required headers, forbidden-header absence, redaction, and response-header capture.

---

# 6. Make caching policy auditable as its own contract

Create a second registry file or a section inside the main contract:

```text
cache_policy_contracts.yaml
```

Example:

```yaml
policies:
  primary_metadata_core:
    cachePolicy: CacheFirst
    ttl: 7d
    staleAfterExpiry: 30d
    retention: RecentlyViewedOrRailScoped
    negativeCache:
      enabled: true
      ttl: 30m
    requiresTypedCodec: true
    requiresFreshHitNoLoaderTest: true

  ratings_dynamic:
    cachePolicy: CacheFirst
    ttl: 12h
    staleAfterExpiry: 3d
    retention: RailScoped
    requiresCredentialDiscriminator: true

  skip_segments_episode_immutable:
    cachePolicy: CacheFirst
    ttl: 30d
    staleAfterExpiry: 90d
    retention: EpisodeImmutable
    requiresLanguageOrSourceDiscriminator: true

  poster_generated:
    cachePolicy: CacheFirst
    ttl: 24h
    staleAfterExpiry: 7d
    retention: RailScoped
    requiresStyleAndBadgeVary: true

  account_state:
    cachePolicy: ObserveOnlyOrMutation
    remoteCache: forbidden
    retention: UserScoped

  mutation:
    cachePolicy: Mutation
    cacheReadWrite: forbidden
```

Then each API shape references one:

```yaml
kitsu.anime.core:
  cacheContractRef: primary_metadata_core

mdblist.rating.batch:
  cacheContractRef: ratings_dynamic

theintrodb.media:
  cacheContractRef: skip_segments_episode_immutable

topposters.poster_template:
  cacheContractRef: poster_generated

trakt.user.lists:
  cacheContractRef: account_state
```

Your runtime requirements already say `CacheFirst` requests must return fresh cached values without invoking the provider loader or starting network, persist/enforce `Retry-After` backoff, use work classes for playback/maintenance behavior, compute fresh/stale windows from the cache policy, and use typed codecs for disk caching.

The audit should test those requirements directly.

---

# 7. Add cache-key conformance tests

This is where subtle bugs will happen.

The audit should fail if a `CacheFirst` call’s key omits anything that can change the response.

For example:

## TMDB movie core

Should include:

```text
provider = TMDB
apiShapeId = tmdb.movie.core
movie_id
language
region if used
append_bundle_version
schema_version
```

Should not include:

```text
raw Authorization token
raw API key
```

## TVDB series translation

Should include:

```text
tvdb series id
language
schema version
```

## Kitsu episodes

Should include:

```text
kitsu id
page/offset/limit
language policy if relevant
schema version
```

## Top-Posters poster

Should include:

```text
provider = TOP_POSTERS
id_type
media_id
poster_type
style
language
badge sources
trend
season suffix where applicable
fallback_url hash if it changes output
user_agent_profile_id if provider behavior varies by User-Agent/device
schema version
credential discriminator if entitlement/tier changes output
```

Top-Posters explicitly supports multiple ID types, styles, languages, badge customization, trend indicators, and episode thumbnails, and its changelog notes seasonal anime posters and cache-key season suffixes; those facts mean cache keys must include the response-varying poster inputs.

## MDBList / OMDb / RPDB / credentialed paths

Replace this kind of key material:

```text
apiKey.hashCode()
```

with a deliberate discriminator:

```text
credentialHash = sha256(provider + normalizedCredential)
```

`hashCode()` is not stable enough as a long-term audit/security primitive, and it reads badly in the audit. The audit should flag raw `hashCode()` in cache keys as a warning first, then a failure.

---

# 8. Use “active-required” versus “planned-not-active” honestly

Your latest report is already moving in the right direction by separating the control-plane gate from the MetadataRouter-readiness gate. The report says the control-plane gate passes but MetadataRouter readiness fails, with 20 active-required endpoint shapes missing runtime specs and 40 planned-not-active shapes.

That is exactly how to prevent false confidence.

For every endpoint shape, require one of:

```text
ACTIVE_REQUIRED
ACTIVE_RUNTIME_COVERED
PLANNED_NOT_ACTIVE
DORMANT_PROVIDER
EXEMPT
RETIRED
```

Then the audit rules become:

```text
ACTIVE_REQUIRED missing runtime spec → FAIL MetadataRouter gate
PLANNED_NOT_ACTIVE missing runtime spec → INFO or WARNING
EXEMPT without owner/expiry → FAIL
DORMANT_PROVIDER with runtime calls → FAIL unless status updated
RETIRED still called → FAIL
```

This lets your control plane pass while still telling the truth about router readiness.

---

# 9. Add blueprint drift detection

The provider contracts should be treated as versioned inputs. The audit should show:

```text
provider
contract file
contract version/hash
last reviewed date
operation count
operations used by Nexio
operations missing from contract
manual overlays used
```

Example output:

| Provider    | Contract source   | Contract hash | Shapes used | Manual overlays | Verdict            |
| ----------- | ----------------- | ------------: | ----------: | --------------: | ------------------ |
| TMDB        | `tmdb.json`       |   `sha256...` |          17 |               0 | PASS               |
| Top-Posters | `topposters.json` |   `sha256...` |           4 |               1 | PASS_WITH_WARNINGS |
| Trakt       | `trakt.apib`      |   `sha256...` |          10 |               2 | PASS_WITH_WARNINGS |
| MDBList     | `mdblist.apib`    |   `sha256...` |           5 |               3 | PASS_WITH_WARNINGS |

If a blueprint changes, the audit should say:

```text
Contract hash changed since last review.
Re-run endpoint/header extraction.
Manual overlay may need review.
```

This is how you audit “against the various API blueprints” over time, not only once.

---

# 10. Include “provider efficiency” rules

Best practices are not only correctness. They also include using the provider’s best API shape.

Add rules like:

```yaml
bestPracticeRules:
  tmdb.detail:
    preferAppendToResponse: true
    requiredAppendForCore:
      - credits
      - images
      - release_dates
      - external_ids
    warnIfSeparateCallsFor:
      - credits
      - images
      - release_dates

  tvdb.episodes:
    preferSeasonBatch: true
    failIfPerEpisodeTranslationsWithoutReason: true

  kitsu.advanced:
    preferListBatchCastings:
      path: "/castings"
      requiredQuery:
        filter[mediaId]: present
        include: "person,character"

  ratings:
    preferBatchWhereAvailable: true
    warnIfOneIdRequestUsesBatchEndpointWithoutReason: true
```

The endpoint index explicitly classifies TMDB detail as one-call multi-field enrichment, TVDB season episodes as season batch, and Kitsu castings/staff/productions/relationships as list-batch or related-resource routes.

These best-practice checks are what prevent you from “technically matching an API route” while still using it inefficiently.

---

# 11. What the final audit report should contain

Add these sections to the report.

```text
A. Executive verdict
B. Provider coverage
C. Call-site coverage
D. Endpoint-shape conformance
E. Header-policy conformance
F. Cache-policy conformance
G. Runtime behavior conformance
H. Blueprint drift / provenance
I. Best-practice provider-efficiency conformance
J. Boundary and exemptions
K. MetadataRouter readiness
```

The key new sections are **F, G, H, and I**.

## F — Cache-policy conformance

Example table:

| API shape                    | Expected cache policy | Actual       | TTL | Stale | Codec                 | Key vary                | Verdict      |
| ---------------------------- | --------------------- | ------------ | --: | ----: | --------------------- | ----------------------- | ------------ |
| `kitsu.anime.core`           | `CacheFirst`          | `CacheFirst` |  7d |   30d | `KitsuAnimeCoreCodec` | id, lang, schema        | PASS         |
| `trakt.user.lists`           | `ObserveOnly/Account` | `CacheFirst` |   ? |     ? | ?                     | profile                 | WARNING/FAIL |
| `topposters.poster_template` | `CacheFirst`          | `CacheFirst` | 24h |    7d | `ImageBlobCodec`      | style, id, lang, badges | PASS         |

## G — Runtime behavior conformance

Example table:

| Scenario                  | Evidence source             | Required                        | Observed | Verdict |
| ------------------------- | --------------------------- | ------------------------------- | -------- | ------- |
| Fresh cache skips loader  | runtime event sample        | `loaderInvoked=false`           | yes      | PASS    |
| Fresh cache skips network | runtime event sample        | `networkStarted=false`          | yes      | PASS    |
| 429 persists backoff      | runtime event sample / test | blocked later call              | yes      | PASS    |
| Playback blocks prefetch  | runtime event sample / test | no network                      | yes      | PASS    |
| Cache write timestamps    | DB row / test               | `freshUntil + staleAfterExpiry` | yes      | PASS    |

## H — Blueprint drift

Example table:

| Provider    | Blueprint         | Hash  | Manual overlay | Last reviewed | Verdict            |
| ----------- | ----------------- | ----- | -------------: | ------------- | ------------------ |
| TMDB        | `tmdb.json`       | `...` |             no | 2026-04-25    | PASS               |
| Top-Posters | `topposters.json` | `...` |            yes | 2026-04-25    | PASS_WITH_WARNINGS |

## I — Best-practice provider-efficiency

Example table:

| API shape                       | Rule                                        | Verdict |
| ------------------------------- | ------------------------------------------- | ------- |
| `tmdb.movie.core`               | uses append bundle                          | PASS    |
| `tvdb.series.episodes.language` | season batch                                | PASS    |
| `tvdb.episode.translation`      | per-episode fanout allowed only with reason | WARNING |
| `kitsu.castings`                | top-level filtered list with include graph  | PASS    |

---

# 12. Recommended acceptance gates

Use separate gates so the report stays honest.

## Gate 1 — Runtime control plane

Already largely passing.

```text
0 direct bypasses
0 missing policy entries
0 missing endpoint-shape IDs
0 missing header policies
0 undocumented exemptions
```

The new report satisfies this at the top level.

## Gate 2 — Header conformance

```text
0 header violations
every active apiShapeId has headerPolicyId
actual runtime samples cover every header-policy family
no cross-provider auth leakage
browser-like UA requires explicit approved profile
all credential values redacted
all expected rate-limit headers captured
```

The current report has a header policy matrix and reports no boundary violations; it also says runtime event samples exist and include redaction metadata, but the next step is to ensure the samples cover every policy family, not just selected examples.

## Gate 3 — Cache conformance

```text
every CacheFirst call has:
    cacheKey
    typed codec
    TTL
    staleAfterExpiry
    scope
    vary list
    redaction policy

runtime tests prove:
    fresh hit does not call loader
    stale fallback behaves correctly
    429 blocks later calls
```

This aligns directly with the runtime spec’s cache-first, backoff, work-class, stale-window, and codec requirements.

## Gate 4 — Provider blueprint conformance

```text
all ACTIVE_REQUIRED shapes match blueprint or approved overlay
all required query/path/body/header elements are present
all known provider-specific efficiency rules pass
all manual deviations have reviewer + reason
```

## Gate 5 — MetadataRouter readiness

```text
active-required endpoint shapes missing runtime spec = 0
primary provider shapes complete:
    TMDB movie core
    TVDB series extended / translations / episodes
    Kitsu anime core / episodes / advanced detail
secondary resolver shapes classified:
    ratings
    posters
    trailers
    skip segments
    reviews
```

Your latest report explicitly says MetadataRouter-readiness still fails, so this gate is the one that remains open.

---

# 13. Immediate follow-up tasks

I would give engineers this sequence.

## Task 1 — Add cache-policy conformance report

Generate:

```text
cache-policy-matrix.csv
cache-key-vary-matrix.csv
cache-policy-violations.csv
```

Each row should include:

```text
apiShapeId
provider
actual cachePolicy
expected cachePolicy
ttl
staleAfterExpiry
codec
scope
cacheKeyTemplate
cacheKeyIncludes
cacheKeyForbiddenParts
retention class
fresh-hit test coverage
verdict
```

## Task 2 — Add provider-contract provenance

Generate:

```text
provider-contract-provenance.csv
```

Each row:

```text
provider
contract file
contract type
contract hash
manual overlay file
reviewedAt
reviewer
used shapes
missing shapes
verdict
```

## Task 3 — Add best-practice rule evaluation

Generate:

```text
provider-best-practice-matrix.csv
```

Rules:

```text
TMDB append bundle
TVDB season batch
TVDB avoid N per-episode translation unless reason
Kitsu castings include graph
Kitsu episodes paginated and cache-keyed by page/offset
poster provider cache key includes all style/rating/language/season/user-agent-vary inputs
ratings providers avoid duplicate one-ID fanout where batch exists
```

## Task 4 — Expand runtime/header samples

Add deterministic tests that produce events for:

```text
TMDB CacheFirst fresh hit
TVDB CacheFirst stale fallback
Kitsu cache miss → network → cache write
Trakt account-scoped header policy
Simkl header policy
Top-Posters path-key image fetch
TheIntroDB optional bearer and rate-limit header capture
429 Retry-After and persisted backoff
playback-active background block
```

## Task 5 — Fix MetadataRouter-readiness blockers

For the 20 active-required missing runtime specs:

```text
either implement the runtime spec
or downgrade to PLANNED_NOT_ACTIVE with a reason
or mark EXEMPT with owner + expiry
```

Do not leave them as active-required missing.

---

# Bottom line

You audit this properly by turning the current report into a **contract conformance system**.

The final structure should be:

```text
Provider blueprints / OpenAPI / API Blueprint
        ↓
Nexio expected integration contract registry
        ↓
Static code audit
        ↓
Runtime permit/header interceptor audit
        ↓
Runtime behavior tests
        ↓
Generated conformance report
```

And the report should answer four separate questions:

```text
1. Did every call go through IntegrationRuntime?
2. Did it use the provider API shape correctly?
3. Did it use the correct headers and credential locations?
4. Did it obey Nexio's own cache/runtime policy?
```

Your current report has mostly answered question 1. It has started answering questions 2 and 3. The next material improvement is to make question 4 — **cache-policy conformance** — as explicit and testable as the header matrix.
