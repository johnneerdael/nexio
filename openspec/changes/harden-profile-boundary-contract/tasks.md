# Tasks

## 1. P0-A: PlaybackOwnerContext

- [x] Add `PlaybackOwnerContext(ownerProfileId, ownerSessionId, traktAccount, simklAccount, startedAtEpochMs)` data class.
- [x] Replace nullable `ownerProfileId: Int?` parameters on `TrackingScrobbleService.scrobbleStart/Stop/Pause` with a required `PlaybackOwnerContext`.
- [x] Construct `PlaybackOwnerContext` once at playback start; thread it through all scrobble/progress writes for the session.
- [x] Codify the "no profile switch during active playback" invariant: `ProfileManager.switchProfile()` rejects switches while a playback session is registered as active.
- [x] Reject scrobble/progress writes whose `ownerProfileId`/`ownerSessionId` no longer matches the active session and record `ProfileBoundaryViolation.STALE_SESSION_WRITE_REJECTED`.
- [x] Add tests:
  - [x] `trakt_scrobble_uses_playback_owner_profile_not_current_profile`
  - [x] `simkl_scrobble_uses_playback_owner_profile_not_current_profile`
  - [x] `profile_switch_during_active_playback_is_rejected`
  - [x] `scrobble_after_playback_stops_and_profile_switch_is_discarded`
  - [x] `scrobble_without_playback_owner_context_fails_to_compile_or_run`

## 2. P0-B: Continue Watching explicit profile API

- [x] Add `observeContinueWatching(profileId: Int): Flow<List<ContinueWatchingRecord>>`.
- [x] Add `ContinueWatchingRecord(profileId, parentId, contentId, provider, routingVersion, positionMs, durationMs, episodeContext, clickTimeDisplayMetadata, source, updatedAt)`.
- [x] Add explicit-profileId snapshot/read APIs and migrate production callers.
- [x] Reduce parameterless `observeContinueWatchingNextUp()` to a thin wrapper over the explicit API that re-subscribes on `ProfileManager.profileSwitched`, OR remove it. (Determined by callsite audit during implementation — see proposal §Out Of Scope.)
- [x] Expose `(profileId, parentId, episodeKey?)` as the audit/storage identity even if internal storage remains bucket-style.
- [x] Add tests:
  - [x] `continue_watching_profile1_not_visible_in_profile2`
  - [x] `continue_watching_query_requires_profile_id`
  - [x] `profile_switch_resubscribes_continue_watching_to_new_profile`
  - [x] `old_continue_watching_subscription_does_not_emit_previous_profile_after_switch`

## 3. P0-C: Account and outbox behavioral isolation

- [x] Add tests proving Profile 2 cannot resolve, read, or use Profile 1 credentials:
  - [x] `profile2_never_uses_profile1_trakt_token`
  - [x] `profile2_never_uses_profile1_simkl_token`
- [x] Add tests proving outbox drain filters by `profileId` and `credentialHash`:
  - [x] `profile2_never_drains_profile1_outbox`
  - [x] `trakt_outbox_drain_filters_by_profile_and_credential_hash`
  - [x] `simkl_outbox_drain_filters_by_profile_and_credential_hash`

## 4. P1-A: Typed metadata composition

- [x] Add `GlobalMetadataDocument(contentId, provider, language, title?, overview?, runtime?, episodeMetadata, artworkCandidates, fieldTrace)`.
- [x] Add `ProfileMetadataOverlay(profileId, watched?, progress?, listMembership?, scrobbleState?, userRating?, continueWatching?)`.
- [x] Add `ProfileResolvedDisplayDocument(profileId, global, overlay, artworkDecision, trace)`.
- [ ] Refactor metadata composition: global cache stores `GlobalMetadataDocument`; UI consumers receive `ProfileResolvedDisplayDocument` composed at read time. (DEFERRED — typed shapes landed; integration into the live composition path is a follow-on change.)
- [ ] Cache `ProfileResolvedDisplayDocument` only under `profile:{profileId}:resolved-display:{contentId}:{language}:{policyVersion}`. (DEFERRED — depends on the resolver refactor above.)
- [x] Add tests:
  - [x] `global_metadata_document_has_no_profile_overlay_fields` (architecture test on the data class shape)
  - [ ] `profile_overlay_not_written_to_global_metadata_cache` (DEFERRED with the resolver refactor.)
  - [ ] `profile_switch_clears_profile_overlays_not_global_cache` (DEFERRED with the resolver refactor.)

## 5. P1-B: ProfileExecutionContext cleanup

- [x] Add typed accessors `traktAccount`, `simklAccount`, `mdblistAccount` derived from the existing `accounts` map.
- [x] Add `settings: ProfileSettingsSnapshot` field.
- [x] Update audit/log sites to read accounts via the named accessors.

## 6. P1-C: Runtime provider-scope enforcement

- [x] In `ProfileBoundaryEnforcer`, add construction-time check: `Trakt`/`Simkl` authenticated calls require `IntegrationScope.Account`. `Profile` scope remains valid for device-auth/login flows. `Global*` scopes are rejected.
- [x] Add tests:
  - [x] `trakt_authenticated_call_with_global_scope_fails`
  - [x] `simkl_authenticated_call_with_global_scope_fails`
  - [x] `trakt_device_auth_profile_scope_allowed`
  - [x] `simkl_device_auth_profile_scope_allowed`

## 7. P2: Audit observability

- [x] Extend profile-boundary audit events with: `activeProfileId`, `writeTarget`, `targetProfileId`, `credentialTraceHash`, `sessionHash`.
- [x] Update `ProfileBoundaryAuditGoldenTest` fixtures to assert the new fields.

## 8. Sign-off

- [x] Run `./gradlew :app:generateProfileBoundaryAudit :app:generateIntegrationRuntimeAudit :app:generateMetadataExecutionAudit --rerun-tasks`.
- [x] Confirm all three reports show `PASS` with `0` violations and clean worktree after commit.
- [x] Confirm callsite audit for parameterless CW observation either landed the resubscription wrapper or removed the API. (Completed in Task 10: API deprecated, single internal caller suppressed; see commit `d623575d5`.)
- [x] Confirm rails tests from the original Profile Boundary spec are either covered or explicitly deferred to a follow-on change with a written rationale. (Rails tests are EXPLICITLY DEFERRED to a follow-on change; the metadata-composition resolver refactor and its associated cache/clear tests are also deferred — see Section 4 above.)
