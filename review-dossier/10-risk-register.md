# Risk Register

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 6
- **Owner task:** 36

## Future risks (not yet realized)

| ID | Risk | Likelihood | Impact | Lane | Mitigation |
|---|---|---|---|---|---|
| R-A-1 | Future runtime spec construction sites bypass `validateRequest` (e.g. test helpers, fixtures) | Low | High | A | Architecture test scanning every `IntegrationCallSpec(...)` literal for missing init invariants |
| R-A-2 | Single-flight regression — concurrent identical ops both fire network — coverage gap per F-A-02/F-TM-02 | Medium | Medium | A | Add a single-flight test that fires N concurrent `runtime.get(samespec)` calls and asserts only one network call |
| R-B-1 | Future emission site for a metadata event uses a different payload key name than the validator expects | Medium | Medium | B + I | Keep `RuntimeTraceValidatorRealEmissionTest` running on every emission addition; expand to cover all 14 rule scenarios |
| R-B-2 | New facade-bypass pattern lands (e.g. yet another `*SecondaryRepository.fetchX` direct adapter call) | High | Medium | B + J | Architecture test that scans for direct provider-adapter / repository calls outside the canonical chain |
| R-C-1 | New provider added that uses raw OkHttp instead of IntegrationRuntime | Medium | High | C | Architecture test scans `data/integration/<provider>/` for OkHttp/Retrofit usage outside an `IntegrationRuntime` invocation |
| R-C-2 | Provider adapter parses a new ID prefix incorrectly (e.g. `kitsu-anime:`) | Low | Medium | C | Add a test matrix of all known prefixes against `MetadataProviderTargetIds` |
| R-D-1 | Cache write atomicity broken on a different store (e.g. encrypted blob, future remote cache) | Low | High | D | Tmp+rename pattern enforced via `IntegrationCacheStore` interface contract |
| R-D-2 | INVALIDATED/EVICTED enum values (currently dead per F-D-03) get half-wired but the validator can't see them | Medium | Low | D | Either delete the enum values or wire them with at least one production emit + one test |
| R-E-1 | New TVDB/Kitsu localization helper added that does cross-provider fallback (e.g. via Kitsu fallback to TMDB) | Medium | High | E | Architecture test that bans `TmdbMetadataService` calls from any TVDB/Kitsu localization site |
| R-E-2 | Per-episode translation cap raised silently (currently 8) — large episode counts could create network storm | Low | Medium | E | Add a test that asserts the cap stays ≤ 8 and emits a warning if changed |
| R-F-1 | Profile delete during active playback leaves orphan `PlaybackOwnerContext` | Low | High | F + H | Add `ProfileManager.deleteProfile(id)` guard that calls `playbackSessionRegistry.activeOwner()` and refuses if matching profileId |
| R-F-2 | Reactive `dataStore.activeProfileId.collect` divergence (per F-F-04) — DataStore advances but StateFlow lags during playback | Medium | Medium | F | Add a test that mutates DataStore during a registered playback session and asserts no `_profileSwitched` emit |
| R-F-3 | Third / fourth profile (rather than profile 1 vs 2) exposes leak | Medium | Medium | F | Add cross-profile tests parameterized over profileIds 1..4 |
| R-G-1 | New caller of CW reads bypasses `observeContinueWatching(profileId)` and uses `observeSnapshot()` directly | High | Medium | G | Architecture test bans `observeSnapshot()` callers outside `ContinueWatchingSnapshotService` |
| R-G-2 | CW snapshot file size grows unbounded over a long session (no rotation) | Low | Low | G | Add a sanity bound test |
| R-H-1 | New scrobble caller (e.g. checkin-from-detail) doesn't pass `PlaybackOwnerContext` and silently uses default | Medium | High | H + I | Architecture test bans `null` owner-arg literal at scrobble call sites; OR — better — change `checkin()` signature to require owner |
| R-H-2 | Single-slot `PlaybackSessionRegistry` (per F-H-02) under nested playback (e.g. Picture-in-Picture) | Low | High | H | Convert to multi-slot or document the nested-playback restriction |
| R-I-1 | Future contributor uses static install-slot pattern in a less-justified place | Medium | Low | I | Document the pattern's bounded use in `07-on-device-trace-design.md` and require code-review label for any new install-slot |
| R-I-2 | Trace mode `Noop` performance regression (someone makes the no-op sink do work) | Low | Medium | I | Add a microbenchmark test asserting `NoopRuntimeTraceSink.emit` allocates 0 bytes |
| R-J-1 | New `@Deprecated(level = ERROR)` markers added without `ReplaceWith` (per F-J-04) | Medium | Low | J | Architecture test enforcing every `@Deprecated` has either `ReplaceWith` or a TODO with date / issue ref |
| R-J-2 | Architecture test whitelist (per F-J-01) gets expanded silently to mask new bypass | Medium | High | J | Pin the whitelist size — adding a row requires explicit code-owner approval |

## Future-vector summary

- **Most-cited risk category:** facade-bypass / canonical-chain erosion (R-B-2, R-C-1, R-G-1) — 3 lanes, all medium-high likelihood. Mitigation: architecture-test coverage of the canonical-chain contract.
- **Most-cited mitigation type:** "architecture test enforcing X" — applies to 9 of 21 risks. Suggests Phase 5+ should invest in a generic `architecture/CanonicalChainBoundaryTest.kt` suite that enumerates the bypass patterns surfaced in this audit.
