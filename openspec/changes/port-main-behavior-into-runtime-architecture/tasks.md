## 1. Main Parity Ledger
- [ ] 1.1 Generate a machine-readable ledger for all `HEAD..main` commits with touched files and domain classification.
- [ ] 1.2 Mark each main-only commit as `PORT`, `ALREADY_COVERED`, `OBSOLETE`, or `REDESIGN_FOR_SHARED_ARCHITECTURE`.
- [ ] 1.3 Add branch-local verification notes for the critical commits already identified: `bacd1e39b`, `5723e649c`, `a2357b29c`, `46d9b3bd8`, `aac4096d4`, `f44c71332`, `1419bb608`, `14917f00b`, `2306180d1`, and playback proxy recovery commits.

## 2. Continue Watching Route Parity
- [ ] 2.1 Add tests proving Continue Watching route construction preserves addon context, stable IDs, content ID, video ID, season, episode, runtime, resume state, and content name.
- [ ] 2.2 Port main’s addon-context preservation into the branch’s `ContinueWatchingSnapshotService`, home CW models, stream route creation, and player route creation.
- [ ] 2.3 Route CW identity preparation through `StableIdBundleResolver` and existing metadata/router components.
- [ ] 2.4 Revalidate localized episode release-time gating through `TvdbContinueWatchingTimingEnricher`.

## 3. Playback And Autoplay Parity
- [ ] 3.1 Add tests for deterministic title guard, diacritic folding, original-language guard, placeholder stream rejection, resolver wait budgets, and final-pass failure diagnostics.
- [ ] 3.2 Port main’s deterministic autoplay fixes into the existing stream presentation and scoring path.
- [ ] 3.3 Port proxy-resolution, auth-recovery, transient 5xx, and placeholder-skip behavior into the existing player and transport components.
- [ ] 3.4 Add logcat/trace events proving source counts, candidate counts, rejection reasons, selected stream, cache-link decision, and fallback candidates.

## 4. Canonical Detail Hydration Parity
- [ ] 4.1 Add failing tests for TMDB movie detail cast/companies, TMDB TV route-to-TVDB detail, Trakt movie/series detail hydration, and addon series detail routing.
- [ ] 4.2 Port main’s canonical detail fallback through `MetadataRouterFacade`, `StableIdBundleResolver`, `ProviderPlanExecutor`, `ProviderPlanRunner`, and `FieldResolver`.
- [ ] 4.3 Prevent detail screens from executing provider-native calls unless the shared router and stable ID bundle have selected the canonical provider and target ID.

## 5. Provider Metadata Completeness
- [ ] 5.1 Add tests for TVDB translated episode titles/overviews, English fallback translations, episode image fields, TVDB organization/person navigation, TMDB cast/companies/networks, Kitsu character/person/production/review/related data, and IMDb rating enrichment.
- [ ] 5.2 Port main’s TVDB localization behavior into the IntegrationRuntime-backed TVDB provider and adapter path.
- [ ] 5.3 Port TMDB, Kitsu, and IMDb rating completeness into shared provider adapters and runtime operations.
- [ ] 5.4 Verify every provider call has an endpoint shape, operation key, cache policy, header policy, and trace event.

## 6. Modern Home Mutation And Hydration Parity
- [ ] 6.1 Add tests proving addon catalog enable/disable, Trakt settings row changes, Simkl settings row changes, and built-in rail changes refresh Modern Home rows.
- [ ] 6.2 Ensure row changes publish through the catalog rail repository and preview-first stream.
- [ ] 6.3 Ensure hydration results apply through `HydratedHomeOverlayStore` and item-level overlay patching.
- [ ] 6.4 Verify row order and focus are stable during hydration updates.

## 7. Device Validation
- [ ] 7.1 Install the profileable build on rooted `192.168.50.98`.
- [ ] 7.2 Capture focused logcat traces for CW Survivor S05E10, TMDB movie detail, TMDB TV detail, Trakt series detail, Kitsu One Piece detail, addon movie detail, addon series detail, and catalog enable/disable.
- [ ] 7.3 Prove second-open fresh cache paths emit no provider metadata network calls while TTL remains valid.
- [ ] 7.4 Update the metadata execution report with main-parity scenarios and no-parallel-path assertions.

## 8. Final Verification
- [ ] 8.1 Run focused unit tests for each migrated domain.
- [ ] 8.2 Run architecture/audit tests for no direct provider bypasses and no parallel renderer/hydrator paths.
- [ ] 8.3 Run `openspec validate port-main-behavior-into-runtime-architecture --strict`.
- [ ] 8.4 Commit each phase separately with domain-scoped commit messages.
