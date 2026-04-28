# Path 13 — Premium poster switch

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** B (FieldResolver) + C (provider contracts: posters) + I (trace: SecondaryDoesNotOverwritePrimary)
- **Contract:** Premium artwork providers (TopPosters, RPDB) override POSTER but NEVER overwrite TITLE/OVERVIEW/EPISODE_LIST.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | Settings: enable Top-Posters/RPDB | `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt:74,121,126` | persists `topPostersEnabled` / `rpdbEnabled` + key in `PosterRatingsSettingsDataStore` | OK — `dataStore.setTopPostersEnabled(...)`, `setTopPostersApiKey(...)`, `setRpdbApiKey(...)`. UI is mutually exclusive (`rpdbRowEnabled = !topPostersEnabled || rpdbEnabled`). |
| 2 | API key validation | `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt:62-87`, `RpdbIntegrationProvider.kt:59-84` | `runtime.get(spec)` with `IntegrationCachePolicy.Disabled`, `apiShapeId=*_KEY_VALIDATION` | OK — both providers go through `IntegrationRuntime`, with `credentialHash(...)` used in cache key. |
| 3 | Active-provider lookup at metadata-build time | `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt:24-69` (`getActiveProvider`), called from `MetaRepositoryImpl.kt:54,131,314`, `TmdbMetadataService.kt:65,246,420`, `TvdbMetadataService.kt:44,91`, `HomeCatalogRefreshCoordinator.kt:105,191` | reads `PosterRatingsSettingsDataStore.settings.first()` and returns `ActiveProvider(provider, apiKey)` or null | OK — single resolver consulted from every meta build site. |
| 4 | Poster URL rewrite | `PosterRatingsUrlResolver.kt:29-55` (`apply(meta,...)` / `apply(metaPreview,...)`) and `:71-97` (`resolvePosterUrl`) | replaces `meta.poster` with `PosterIntegrationRequest(...).toModel()` synthetic URL pointing at RPDB/TopPosters; sets `posterProviderTag` | OK — `meta.copy(poster = resolvePosterUrl(...), posterProviderTag = providerTag)`. Idempotent: `isAlreadyProviderUrl` short-circuits if URL is already a premium model. |
| 5 | Coil fetcher resolves synthetic URL → premium-provider bytes | `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt:23-37,45-63` | `Fetcher.create(data)` parses `PosterIntegrationRequest.fromModel(data)`; routes to `RpdbIntegrationProvider.fetchPoster` or `TopPostersIntegrationProvider.fetchPoster`, which call `runtime.get(spec)` with `cachePolicy=CacheFirst`, `apiShapeId=*_POSTER_TEMPLATE`, `headerPolicyId=*_IMAGE_PATH_KEY_V1` | OK — bytes returned via `IntegrationRuntime`; cached per-poster. |
| 6 | `FieldResolver.resolve(primary, secondary)` | `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:19-99` | If POSTER candidate from premium provider was passed as a secondary `MetadataCandidate(provider=…, fields={POSTER → FieldValue(value, owner=ARTWORK)})`, then primary POSTER would be REJECTED in favor of premium and emitted as `metadata.field_selected` with `selectedProvider=…`, `ownershipRule=…`, `rejectedCandidates=[primary]` | **NOT EXERCISED** — see Findings. Premium URL is rewritten upstream of any FieldResolver merge; no `MetadataCandidate` is ever constructed for the premium provider, and `MetadataPrimaryProvider` enum (`MetadataModels.kt:6`) only contains TMDB/TVDB/KITSU. |
| 7 | `metadata.field_selected` emit for POSTER (premium) | `FieldResolver.kt:74-82` | per spec: `selectedProvider=TOP_POSTERS` or `RPDB`, `ownershipRule="premium artwork can override poster"`, rejected[primary]=TMDB | **NOT EMITTED** — premium override happens outside FieldResolver. |
| 8 | `metadata.field_selected` emit for TITLE/OVERVIEW | `FieldResolver.kt:62-83` | when invoked at all, owner is FieldOwner.PRIMARY → `ownershipRule="primary always wins"`, `selectedProvider=primary.provider.name` (TMDB/TVDB/KITSU) | OK as written, but `FieldResolver` itself is only constructed in two call sites (`HomeProviderLocalizedMetadataOverlay.kt:76`, `MetaDetailsViewModel.kt:112`) — and both via the no-arg test constructor that wires a `NoopRuntimeTraceSink`. |
| 9 | UI tile renders premium poster + primary title | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt:758` (poster image), title sourced from `Meta.name` / `MetaPreview.name` | image URL is the synthetic premium model; title/overview come from primary `Meta` fields untouched | OK — title field is never touched by `PosterRatingsUrlResolver.apply`. |

## Validator rule check

- `SecondaryDoesNotOverwritePrimary` — `app/src/main/java/com/nexio/tv/core/trace/TraceValidationRules.kt:171-185`. `protectedFields = {"TITLE", "OVERVIEW", "EPISODE_LIST"}`. POSTER is NOT in the set, matching the spec.
- ✅ Rule design: if a `metadata.field_selected` event for TITLE/OVERVIEW/EPISODE_LIST has any `rejectedCandidates`, it fails.
- ⚠️ Note: spec uses `EPISODE_LIST`, but `ResolvedField` (`MetadataModels.kt:34-54`) uses `EPISODES` — the validator key never matches a real `field_selected` field. The protection is effectively only TITLE/OVERVIEW for emitted events.
- ⚠️ Rule cannot fire for the premium poster path because no `metadata.field_selected` event is ever emitted with a premium provider as `selectedProvider` — the premium override happens at URL-rewrite time, not via `FieldResolver`.

## What does NOT happen on this path (verified)

- ❌ NO premium artwork provider sets/overwrites TITLE — confirmed: `PosterRatingsUrlResolver.apply` only rewrites `poster` and sets `posterProviderTag`.
- ❌ NO premium artwork provider sets/overwrites OVERVIEW — confirmed.
- ❌ NO direct OkHttp call to TopPosters/RPDB outside `data/integration/posters` — confirmed: only `RpdbIntegrationProvider`, `TopPostersIntegrationProvider`, and the shared `PosterTransport` reach the API hosts; both go through `runtime.get(spec)`.
- ❌ NO `metadata.field_selected` event emitted with `selectedProvider=TOP_POSTERS` or `RPDB` — premium override is upstream of FieldResolver.

## Trace event coverage

| Event | Emitted? | Notes |
|---|---|---|
| `runtime.operation_start` (poster lookup) | ✅ | Via `IntegrationRuntime.get(spec)` in `RpdbIntegrationProvider.fetchPoster` / `TopPostersIntegrationProvider.fetchPoster`. |
| `runtime.cache_decision` (poster cache) | ✅ | Same path; `CacheFirst(ttlMs)` policy, HIT on repeated views. |
| `metadata.field_selected` (POSTER) | ❌ | Premium URL never participates in FieldResolver merge. |
| `metadata.field_selected` (TITLE) | ⚠️ | Only when `FieldResolver.resolve` is actually invoked. Production wiring uses the no-arg constructor (`NoopRuntimeTraceSink`) at `MetaDetailsViewModel.kt:112` and `HomeProviderLocalizedMetadataOverlay.kt:76` — events are silently dropped. |
| `http.request` / `http.response` (poster) | ⚠️ | Goes through `PosterTransport` (single shared OkHttp client used by both poster providers); whether `runtimeOperationId` propagates to interceptor depends on Lane H trace-OkHttp wiring (Path 03/04 already flagged interceptor coverage gaps). |

## Verdict

❌ — Specified contract enforcement does not exist in production code. The premium poster switch works functionally (correct URL is rendered) but the field-ownership story the spec relies on is bypassed entirely.

## Findings

- **F-50 (HIGH):** `PosterRatingsUrlResolver.apply` rewrites `Meta.poster` directly; the premium provider never participates in `FieldResolver.resolve`. As a result, the `SecondaryDoesNotOverwritePrimary` validator can never observe a premium-poster override (no `metadata.field_selected` event with `selectedProvider=TOP_POSTERS|RPDB` is ever emitted), and the audit trail for "what overrode the poster" is absent. To honour the spec, premium artwork should be expressed as a `MetadataCandidate` (with a new `MetadataPrimaryProvider` value or a dedicated artwork-provider channel) so `FieldResolver` emits `field_selected` with `ownershipRule="premium artwork can override poster"`.
- **F-51 (MEDIUM):** `FieldResolver` is only injected via the no-arg fallback constructor at the two production callers (`MetaDetailsViewModel.kt:112`, `HomeProviderLocalizedMetadataOverlay.kt:76`); that constructor wires `NoopRuntimeTraceSink`, so every `emitFieldSelected` call is silently discarded in production. The Hilt binding for `TraceMetadataEvents` exists but is not used by either caller. Result: even for the cases where FieldResolver IS exercised (TMDB↔TVDB merges), the spec's `metadata.field_selected` events do not reach the runtime trace sink.
- **F-52 (MEDIUM):** `SecondaryDoesNotOverwritePrimary.protectedFields` set uses the literal string `"EPISODE_LIST"` (`TraceValidationRules.kt:173`), but `ResolvedField` enum exposes `EPISODES`. The mismatch means the rule cannot fire for episode-list overrides even if such an event is emitted. Either rename the enum constant or update the rule's set.
- **F-53 (LOW):** `PosterRatingsUrlResolver.buildRpdbPosterUrl` and `buildTopPostersUrl` use `apiKey.hashCode()` in the cache key (`PosterRatingsUrlResolver.kt:127,151`). `kotlin.String.hashCode()` is 32-bit and not collision-resistant; reuse the existing `credentialHash(IntegrationProvider, apiKey)` helper for consistency with the API-key-validation cache keys (and to avoid two different keys identifying the same credential).

## Cross-references

- Path 03 (Detail core) — same FieldResolver wiring
- Boundary map (FieldResolver ownership)
- Earlier work: commit `e67fe167e` (FieldResolver field_selected emission with rejectedCandidates), commit `742047f51` (SecondaryDoesNotOverwritePrimary rule)
