# Trace 13 — Premium Poster Switch (RPDB / Top Posters)

**SHA under review:** `774a540f8` (`codex/integration-runtime-phase-a`)
**Date:** 2026-04-29

---

## 1. Path Summary

When a user configures an RPDB or Top Posters API key, poster URLs for every visible item are
overridden with premium artwork fetched through those services. The mechanism operates in two
distinct sub-paths that together constitute the full premium poster switch:

1. **Adapter path (new in F-C-04):** `RpdbMetadataProviderAdapter` / `TopPostersMetadataProviderAdapter`
   are `@Binds @IntoSet` members of the `MetadataProviderAdapter` set injected by Hilt. They emit
   a `MetadataCandidate` carrying `ResolvedField.POSTER` with `FieldOwner.ARTWORK` and
   `SourceRole.ARTWORK` into the `FieldResolver` merge pipeline, which selects the premium artwork
   URL as the POSTER field winner.

2. **Legacy pull-through path (pre-existing):** `PosterRatingsUrlResolver.apply(meta, activeProvider)`
   and `PosterRatingsUrlResolver.apply(metaPreview, activeProvider)` are called by
   `TmdbMetadataService`, `TvdbMetadataService`, `MetaRepositoryImpl`, and
   `HomeCatalogRefreshCoordinator` to post-process individual `Meta` / `MetaPreview` objects
   outside the `FieldResolver` pipeline.

Both paths call `PosterRatingsUrlResolver.resolvePosterUrl` which constructs a
`PosterIntegrationRequest` carrying a cache key using `stableHashHex8(apiKey)` (F-C-05).

The premium enum entries (`MetadataPrimaryProvider.RPDB` and `TOP_POSTERS`) are **artwork-only**
providers: they appear in the enum (F-C-04 closed) and in `MetadataProviderAdapterShapeRegistry`
but are explicitly guarded in `ProviderPlanExecutor.buildPlan` (throws if used as a routing
provider) and in `MetadataRouterFacade.toTvProvider()` (falls back to `TvProvider.TVDB`).

---

## 2. Caller Chain

### 2a. Adapter path (FieldResolver pipeline)

```
ProviderPlanRunner.run(plan)
  └─ adapters.firstOrNull { it.provider == step.provider && it.supports(step) }
       ├─ RpdbMetadataProviderAdapter.execute(route, step)          [step.apiShapeId = "rpdb.poster_template"]
       │    └─ posterResolver.getActiveProvider()
       │         → PosterRatingsSettingsDataStore.settings.first()
       │         → PosterRatingsProvider.RPDB   (if configured)
       │    └─ posterResolver.resolvePosterUrl(
       │         originalPosterUrl = null,
       │         contentId = route.parentId,
       │         contentType = route.mediaKind.toContentType(),
       │         activeProvider = activeProvider
       │       )
       │         └─ buildRpdbPosterUrl(apiKey, id)
       │              cacheKey = "rpdb:$idType:${id.value}:poster-default:${stableHashHex8(apiKey)}"
       │              → PosterIntegrationRequest(...).toModel()   → opaque URL string
       │    → MetadataCandidate(
       │         provider = RPDB,
       │         fields = { POSTER → FieldValue(url, ARTWORK, ARTWORK) },
       │         sourceProvider = "rpdb",
       │         sourceRole = ARTWORK
       │       )
       │
       └─ TopPostersMetadataProviderAdapter.execute(route, step)    [step.apiShapeId = "topposters.poster_template"]
            └─ [same shape as RPDB but via buildTopPostersUrl]
                 cacheKey = "topposters:${id.type.name.lowercase()}:${id.value}:${stableHashHex8(apiKey)}"
            → MetadataCandidate(
                 provider = TOP_POSTERS,
                 fields = { POSTER → FieldValue(url, ARTWORK, ARTWORK) },
                 sourceProvider = "top_posters",
                 sourceRole = ARTWORK
               )

FieldResolver.resolveWithPreview(preview, primary, secondary=[rpdbCandidate])
  └─ applyMissingCandidate(rpdbCandidate, ...)
       ├─ existingOwner == null?
       │    → selectField(POSTER, ARTWORK)   [ARTWORK adapter wins if primary produced no POSTER]
       └─ existingOwner == PRIMARY && sourceRole == RAIL_PREVIEW?
            → canReplaceRailPreview(POSTER, RAIL_PREVIEW, ARTWORK) == true
            → selectField(POSTER, ARTWORK)   [premium replaces rail-preview placeholder]
       └─ existingOwner == PRIMARY (non-preview)?
            → "field already filled"   [ARTWORK adapter is BLOCKED by primary canonical poster]

buildDocument(...)
  └─ emitFieldSelected(
       field = "POSTER",
       selectedProvider = sourceProviders["POSTER"]  → "rpdb" or "top_posters",
       sourceRole = "ARTWORK",
       ownershipRule = "secondary fills missing field" | "dedicated resolver field replaces rail preview"
     )
```

### 2b. Legacy pull-through path (outside FieldResolver pipeline)

```
TvdbMetadataService.fetchSeriesCore(...)
  └─ posterRatingsUrlResolver.resolvePosterUrl(
       originalPosterUrl = tvdbPoster,
       contentId = contentId,
       contentType = contentType,
       activeProvider = activeProvider   (passed by caller)
     )

HomeCatalogRefreshCoordinator / MetaRepositoryImpl
  └─ posterRatingsUrlResolver.apply(meta, activeProvider)
       └─ meta.copy(poster = resolvePosterUrl(...), posterProviderTag = "rpdb"/"top_posters")
```

**Key types at each stage:**

| Stage | Type |
|---|---|
| Entry (adapter path) | `ProviderPlanStep(apiShapeId = "rpdb.poster_template")` |
| Adapter output | `MetadataCandidate(provider = RPDB, fields = {POSTER → FieldValue(…, ARTWORK)})` |
| Field resolution input | `ProviderPlanRunResult.secondaryCandidates` |
| Field ownership decision | `FieldOwner.ARTWORK` / `SourceRole.ARTWORK` |
| Trace event | `metadata.field_selected(field="POSTER", sourceRole="ARTWORK", selectedProvider="rpdb")` |
| Legacy path output | `Meta.copy(poster = premiumUrl, posterProviderTag = "rpdb"/"top_posters")` |

---

## 3. Trace Events Expected

When an RPDB or Top Posters poster switch occurs inside a trace session with `sessionId() != null`:

| Sequence | Event | Emitter | Key payload fields |
|---|---|---|---|
| 1 | `metadata.resolver_schedule` | `ResolverOrchestrator.schedule` | `scheduled` includes `"ARTWORK"` |
| 2 | `metadata.route_decision` | `MetadataRouter.route` | `provider = "TMDB"/"TVDB"/"KITSU"` (not `"RPDB"`) |
| 3 | `metadata.provider_plan` | `ProviderPlanRunner.run` | step list includes `{apiShapeId="rpdb.poster_template"}` (if RPDB active) |
| 4…N | `metadata.field_selected` × per-field | `FieldResolver.buildDocument` | For POSTER: `selectedProvider = "rpdb"` or `"top_posters"`, `sourceRole = "ARTWORK"`, `ownershipRule = "secondary fills missing field"` or `"dedicated resolver field replaces rail preview"` |

Note: `selectedProvider` in the `field_selected` event is populated from `sourceProviders[field]`,
which is set to `candidate.sourceProvider` in `selectField()`. For `RpdbMetadataProviderAdapter`
this is the literal string `"rpdb"` (lowercase); for `TopPostersMetadataProviderAdapter` it is
`"top_posters"`. The `MetadataCandidate.provider` enum value is `RPDB` / `TOP_POSTERS` (stored
separately in `providers[field]` but not the field used as the trace payload).

---

## 4. Verification

### F-C-04 closure: enum entries, adapter classes, and Hilt bindings

**Enum entries:**
```
grep -n "RPDB\|TOP_POSTERS" \
  app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt
```
Result: line 7 — `enum class MetadataPrimaryProvider { TMDB, TVDB, KITSU, IMDB, TRAKT, SIMKL, RPDB, TOP_POSTERS }`. Both entries present.

**Adapter classes:**
- `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt` — `@Singleton`, implements `MetadataProviderAdapter`, `provider = MetadataPrimaryProvider.RPDB`.
- `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt` — identical structure with `TOP_POSTERS`.

**Hilt bindings:**
```
grep -n "@IntoSet" \
  app/src/main/java/com/nexio/tv/core/di/MetadataExecutionModule.kt
```
Lines 68–73:
```kotlin
@Binds
@IntoSet
abstract fun bindRpdbPosterAdapter(impl: RpdbMetadataProviderAdapter): MetadataProviderAdapter

@Binds
@IntoSet
abstract fun bindTopPostersPosterAdapter(impl: TopPostersMetadataProviderAdapter): MetadataProviderAdapter
```
Both poster adapters are bound into the same `Set<MetadataProviderAdapter>` as all other adapters. F-C-04 is confirmed closed.

**Shape registry:**
`MetadataProviderAdapterShapeRegistry.all` (lines 39–40) contains both `PosterApiShapes.RPDB_POSTER_TEMPLATE` (`"rpdb.poster_template"`) and `PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE` (`"topposters.poster_template"`).

### F-C-05 closure: `stableHashHex8` cache keys

```
grep -rn "stableHashHex8\|apiKey.hashCode" \
  app/src/main/java/com/nexio/tv/core/poster/
```

`PosterRatingsUrlResolver.kt` lines 127 and 151 use `stableHashHex8(apiKey)` in both cache key
builders. The function (lines 209–218) is SHA-256 of the UTF-8 key, truncated to the first 4
bytes, formatted as an 8-character lowercase hex string. This is deterministic across JVM
restarts and identical for the same API key regardless of JVM identity hash. No `hashCode()` call
exists in the poster package. F-C-05 is confirmed closed.

### `MetadataCandidate` carries `ResolvedField.POSTER` with `FieldOwner.ARTWORK`

`RpdbMetadataProviderAdapter.execute()` (lines 61–73):
```kotlin
MetadataCandidate(
    provider = MetadataPrimaryProvider.RPDB,
    fields = mapOf(
        ResolvedField.POSTER to FieldValue(
            value = posterUrl,
            owner = FieldOwner.ARTWORK,
            sourceRole = SourceRole.ARTWORK
        )
    ),
    sourceProvider = "rpdb",
    sourceRole = SourceRole.ARTWORK
)
```

`TopPostersMetadataProviderAdapter.execute()` has the identical structure with `TOP_POSTERS` /
`"top_posters"`. Both candidates satisfy the `FieldResolver` merge precondition. Confirmed.

### `metadata.field_selected` fires for POSTER when a switch happens

`FieldResolver.buildDocument()` (lines 215–248) iterates every field in the resolved map and
calls `traceEvents.emitFieldSelected(...)` unconditionally per field. If the RPDB or Top Posters
adapter wins the POSTER slot (via `applyMissingCandidate` or `canReplaceRailPreview`), the
resulting `field_selected` event carries:

- `field = "POSTER"`
- `selectedProvider = "rpdb"` or `"top_posters"` (from `candidate.sourceProvider`, set in `selectField()`)
- `sourceRole = "ARTWORK"`
- `ownershipRule`: one of `"secondary fills missing field"` or `"dedicated resolver field replaces rail preview"`

The audit golden test (`MetadataExecutionAuditGoldenTest.kt` lines 434–437) asserts:
```kotlin
assertEquals("TOP_POSTERS", topposters.selectedFields.single { it.field == "poster" }.selectedProvider)
assertEquals("RPDB", rpdb.selectedFields.single { it.field == "poster" }.selectedProvider)
assertTrue(topposters.runtimeCalls.any { it.apiShapeId == "topposters.poster_template" })
assertTrue(rpdb.runtimeCalls.any { it.apiShapeId == "rpdb.poster_template" })
```

However, note that the audit runner (`MetadataAuditRunner.kt` lines 205–222) builds `selectedFields`
by checking `scenario.premiumArtworkProvider != null` rather than reading from the real
`FieldResolver` output — it synthesises the `FieldSelectedEvent` directly. This means the golden
test validates the *audit harness's own model* of the switch, not the real `FieldResolver`
emission. See Finding 13-A below.

### `SecondaryDoesNotOverwritePrimary` can now see POSTER events

`SecondaryDoesNotOverwritePrimary` (`TraceValidationRules.kt` lines 171–185) uses
`protectedFields = setOf("TITLE", "OVERVIEW", "EPISODE_LIST")`. `"POSTER"` is not in the
protected set, so the rule never fires for any `field_selected` event on POSTER — including the
ARTWORK-wins scenario. The rule is effectively silent for the entire premium poster switch path.

The test `TraceValidationRulesTest.kt` line 162–176 explicitly verifies this: same-shape event
for `"POSTER"` with a non-empty `rejectedCandidates` list is `assertSilent`. POSTER is
intentionally excluded from the protected field set.

---

## 5. Path-Specific Findings

### Finding 13-A (P2) — Audit golden test synthesises premium POSTER `FieldSelectedEvent` from harness model, not from real `FieldResolver` output

**Location:** `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt` lines 204–228 and 537–543.

The `MetadataAuditRunner` does not feed the `RpdbMetadataProviderAdapter` or
`TopPostersMetadataProviderAdapter` as real Hilt-injected adapters. Instead, when
`scenario.premiumArtworkProvider != null`, the runner synthesises:
1. A `RuntimeCallEvent` with the poster apiShapeId (line 1085–1095).
2. A `FieldSelectedEvent` (lines 205–227) constructed directly with `selectedProvider = scenario.premiumArtworkProvider.orEmpty()` and `sourceRole = "ARTWORK"`.

The real `ProviderPlanRunner.run()` path — which would look up the adapter from the Hilt-injected
set, call `execute()`, and return a real `MetadataCandidate` — is bypassed entirely by the audit
harness. The golden test (`MetadataExecutionAuditGoldenTest.kt` lines 434–437) therefore does not
prove that the live `FieldResolver.buildDocument` emits a `field_selected` event with
`selectedProvider = "RPDB"` or `"TOP_POSTERS"` when the real adapter runs. It only proves the
harness correctly models that assertion.

**Impact:** The integration between the poster adapters and the `FieldResolver` trace emission is
untested by the golden test. A regression in `RpdbMetadataProviderAdapter.execute()` (e.g., wrong
`sourceProvider` string) or in `FieldResolver.selectField()` for `FieldOwner.ARTWORK` candidates
would not be caught by this test.

**Required fix:** Add a `FieldSelectedTraceTest` or extend the existing
`FieldSelectedTraceTest.kt` with a scenario that injects a real `RpdbMetadataProviderAdapter` (or
a synthetic `MetadataCandidate` with `provider = RPDB`, `fields = {POSTER → FieldValue(…,
ARTWORK)}`) into a `FieldResolver` instance backed by a `CapturingRuntimeTraceSink`, and asserts
the emitted `metadata.field_selected` event has `field = "POSTER"`, `sourceRole = "ARTWORK"`, and
`selectedProvider = "rpdb"`.

---

### Finding 13-B (P2) — ARTWORK adapter is silently rejected when primary canonical poster arrives first (no user-observable signal)

**Location:** `FieldResolver.applyMissingCandidate()` lines 301–358.

When `ProviderPlanRunner.run()` processes a plan where the primary provider (TMDB/TVDB) returns a
POSTER field in its candidate **and** the RPDB/Top Posters adapter also runs as a secondary step
in the same plan, the secondary candidate is processed by `applyMissingCandidate`. Since
`existingOwner` is already `PRIMARY` (non-preview), and
`canReplaceRailPreview(POSTER, PRIMARY, ARTWORK)` returns `false` (the existing `sourceRole` is
`PRIMARY`, not `RAIL_PREVIEW`), the primary poster wins and the ARTWORK candidate is added to
`ignoredOverwrites` + `rejectedByField` with reason `"field already filled"`.

The `emitFieldSelected` call for POSTER will then carry `selectedProvider = <primary provider>`
and `ownershipRule = "primary always wins"` — NOT `"rpdb"` or `"top_posters"`. This means a user
with RPDB configured who has a primary provider poster available will see the primary provider's
poster retained, and the premium switch is silently dropped with no trace event distinguishing it
from a case where RPDB returned no result.

This is by design per the KDoc in both adapter classes (`"respecting the standard 'field already
filled' rule against primary canonical poster values"`), but the consequence is that:
- The canonical ownership path **does not** allow the RPDB/Top Posters adapter to override a
  primary-canonical poster via `FieldResolver`. The actual poster replacement relies on the
  legacy `PosterRatingsUrlResolver.apply()` call sites in `TmdbMetadataService` and
  `TvdbMetadataService`, which run **before** the `FieldResolver` pipeline and rewrite the poster
  URL in the raw data that eventually becomes the primary candidate's `POSTER` field.
- In the pure adapter path, ARTWORK only wins when the primary provider's poster is absent (the
  `existingOwner == null` branch) or when the existing value came from a rail-preview placeholder
  (`canReplaceRailPreview`).

**Impact:** The claim in the trace brief that RPDB/Top Posters are handled "via the canonical
ownership path" is partially misleading. The canonical merge path only applies when the primary
poster is absent. The actual premium switch for items that already have a primary poster is
performed by the legacy `PosterRatingsUrlResolver.apply()` sites, not by the adapter pipeline.
This is a documentation/understanding gap rather than a production defect, but it means the
canonical F-C-04 mechanism only delivers premium artwork when the upstream provider did not
return a poster.

---

### Finding 13-C (P1) — `selectedProvider` in `metadata.field_selected` for POSTER will be `"rpdb"` or `"top_posters"` (lowercase strings), not the enum name `"RPDB"` / `"TOP_POSTERS"`

**Location:** `RpdbMetadataProviderAdapter.kt:72` (`sourceProvider = "rpdb"`),
`TopPostersMetadataProviderAdapter.kt:72` (`sourceProvider = "top_posters"`),
`FieldResolver.selectField()` line 394 (`sourceProviders[field] = candidate.sourceProvider`),
`FieldResolver.buildDocument()` line 217 (`selectedProvider = sourceProviders[field] ?: fallbackSourceProvider`).

The `selectedProvider` payload key in `metadata.field_selected` is drawn from
`MetadataCandidate.sourceProvider`, which is the **string** `"rpdb"` or `"top_posters"` — not the
enum name `"RPDB"` / `"TOP_POSTERS"`. The audit golden test at line 434 asserts
`selectedProvider == "TOP_POSTERS"` (uppercase enum name), but it builds the `FieldSelectedEvent`
from the harness's own model (`scenario.premiumArtworkProvider.orEmpty()`) rather than from the
real adapter output — so the discrepancy is invisible in the test.

Any downstream validator rule, dashboard query, or analytics pipeline that expects
`selectedProvider = "RPDB"` (uppercase) for the poster field will not match the actual trace event
payload, which contains `"rpdb"` (lowercase). The `MetadataLocalizationFieldTrace.selectedProvider`
type is `MetadataPrimaryProvider` (an enum) and uses the enum name; but `field_selected`
`selectedProvider` is a raw string from `sourceProvider`, which is a separate string field on the
candidate.

**Impact:** Potential validator / analytics mismatch. If `ScheduledResolversAreDispatched` or any
future rule does a case-sensitive string comparison on `selectedProvider` for POSTER events, it
will fail to match `"rpdb"` when expecting `"RPDB"`.

**Required fix:** Either normalise `sourceProvider` to uppercase in `selectField()`, or document
that `selectedProvider` in `field_selected` events uses lowercase provider identifiers for
artwork-only providers. Update the audit golden test to assert `selectedProvider == "rpdb"` (as
produced by the real adapter) rather than `"RPDB"`.

---

### Finding 13-D (P3) — `SecondaryDoesNotOverwritePrimary` rule has inverted semantics and cannot validate POSTER override semantics (cross-lane I-08)

**Location:** `TraceValidationRules.SecondaryDoesNotOverwritePrimary` (`TraceValidationRules.kt`
lines 171–185). `protectedFields = setOf("TITLE", "OVERVIEW", "EPISODE_LIST")`.

`"POSTER"` is not in `protectedFields`. The original audit (Lane I, Finding I-08) identified that
the rule has inverted semantics: it fires on any `field_selected` event for a protected field with
a non-empty `rejectedCandidates` list — including when the PRIMARY provider won with secondary
candidates rejected. It does NOT distinguish "secondary overwrote primary" from "primary won with
competition."

For the POSTER field specifically, two consequences follow:

1. The rule cannot validate the intended POSTER invariant ("premium artwork does not replace a
   canonical primary poster that was explicitly returned by the primary provider"). POSTER is
   excluded from `protectedFields` entirely, so the rule is silent regardless of what happens.

2. Even if POSTER were added to `protectedFields`, the inverted semantics (fires when any
   candidate is rejected, not when a secondary wins) would generate false positives whenever a
   primary TMDB/TVDB poster exists and the RPDB candidate is correctly rejected — exactly the
   normal case.

**Impact:** No production defect (the rule is silent for POSTER). Clarifies why the rule "can now
actually validate POSTER overrides" claim in the trace brief is inaccurate: the rule cannot do so
because (a) POSTER is not in `protectedFields`, and (b) even if it were, the semantics are
inverted. The Lane I-08 fix (gate on `sourceRole != "PRIMARY"`) would be required before POSTER
could be added to the protected set meaningfully.

---

### Finding 13-E (Nit) — Both adapter classes contain a private `MetadataMediaKind.toContentType()` extension — duplicated code

**Location:** `RpdbMetadataProviderAdapter.kt` line 82–86 and
`TopPostersMetadataProviderAdapter.kt` line 82–86.

The private `fun MetadataMediaKind.toContentType()` extension is copy-pasted identically in both
files. It should be extracted to a shared internal file (e.g.,
`com.nexio.tv.data.integration.posters.PosterAdapterUtils.kt`) or promoted to an extension on
`MetadataMediaKind` in the domain model layer.

**Impact:** Cosmetic. No functional duplication risk for current logic, but future divergence in
`MetadataMediaKind` values (e.g., a new `DOCUMENTARY` kind) would require the update to be
applied in two places.

---

## 6. Cross-References

| Reference | Lane | Status | Relevance to this trace |
|---|---|---|---|
| F-C-04 (RPDB/Top Posters adapters and enum entries) | C | CLOSED | Enum entries, adapter classes, `@Binds @IntoSet` all verified present at SHA `774a540f8`. |
| F-C-05 (`stableHashHex8` stable cache key) | C | CLOSED | Applied in both `buildRpdbPosterUrl` and `buildTopPostersUrl` with no `hashCode()` calls remaining. |
| C-NN adapter boundary audit | C | — | RPDB and Top Posters adapters inject only `PosterRatingsUrlResolver`; no cross-provider field reads (confirmed in Lane C §6). |
| I-08 (`SecondaryDoesNotOverwritePrimary` inverted semantics) | I | P2 OPEN | Directly constrains whether the rule can validate POSTER override; confirmed it cannot (Finding 13-D). |
| B-NN (`metadata.field_selected` contentId threading) | B | CLOSED (F-B-05) | `requestContentId` is threaded into `buildDocument`; POSTER events carry the correct `contentId`. |
| Audit golden test (premium-artwork scenarios) | C | — | Golden test synthesises POSTER field_selected from harness model, not from real adapter; see Finding 13-A. |
| `FieldResolverTest.kt` line 69 | — | — | Asserts `fieldOwners[POSTER] == ARTWORK` for the secondary-ARTWORK path; confirms the ownership logic is unit-tested, though not the trace payload. |

---

## 7. Summary

F-C-04 is fully closed: `MetadataPrimaryProvider.RPDB` and `TOP_POSTERS` exist, both adapter
classes exist and implement `MetadataProviderAdapter`, and both are bound via `@Binds @IntoSet`
in `MetadataExecutionModule`. F-C-05 is fully closed: `stableHashHex8(apiKey)` (SHA-256 first 4
bytes, 8-char hex) replaces any `hashCode()` call in both RPDB and Top Posters cache key
builders. Both adapters emit a `MetadataCandidate` with `ResolvedField.POSTER` /
`FieldOwner.ARTWORK` / `SourceRole.ARTWORK`; `FieldResolver.buildDocument` emits
`metadata.field_selected` for POSTER with `sourceRole = "ARTWORK"` when the premium adapter wins
the field. `SecondaryDoesNotOverwritePrimary` is silent for POSTER by design (POSTER not in
`protectedFields`), and the Lane I-08 finding confirms the rule would require semantics correction
before POSTER could meaningfully be added.

Five path-specific findings were identified: two P2 (golden test validates harness model not real
adapter output; ARTWORK adapter is silently dropped when primary canonical poster exists,
revealing the adapter path only activates for poster-absent items), one P1 (case mismatch between
`selectedProvider = "rpdb"` in real trace events vs `"RPDB"` expected by audit model and
downstream consumers), one P3 (cross-reference to I-08 rule semantics blocking POSTER validation),
and one Nit (duplicated private extension in both adapter classes).
