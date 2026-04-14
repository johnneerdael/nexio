# Roadmap: Nexio

## Overview

v1.0 Multi-Profile Support transforms Nexio from a single-account app into a household-ready streaming client. The work proceeds in strict dependency order: first the DataStore factory foundation that everything builds on, then per-profile auth and settings isolation, then the profile selection and switching UI, then Supabase sync and cleanup infrastructure, and finally nexio-web management for the companion web surface. Each phase delivers a coherent capability; nothing is partially built across phase boundaries.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Foundation** - ProfileDataStoreFactory, ProfileManager, and UserProfile model extension — the base layer every subsequent phase depends on
- [ ] **Phase 2: Per-Profile Auth and Settings** - Migrate 7 DataStores from singleton delegate to factory pattern, making Trakt/Simkl and all per-profile settings automatically profile-scoped
- [ ] **Phase 3: Profile UI** - Profile selection screen, PIN entry, sidebar switcher, and settings header — all D-pad navigable and gated by profile count
- [ ] **Phase 4: Sync and Cleanup** - Supabase sync for profile metadata and per-profile settings blobs, plus full cleanup on profile deletion
- [ ] **Phase 5: nexio-web Integration** - Master account profile CRUD, per-profile Trakt/Simkl auth, catalog and formatter config, and photo upload from the companion web app

## Phase Details

### Phase 1: Foundation
**Goal**: The ProfileDataStoreFactory, ProfileManager, and extended UserProfile model exist and all downstream code can depend on them
**Depends on**: Nothing (first phase)
**Requirements**: INFRA-01, INFRA-02, INFRA-03, INFRA-04, INFRA-05, INFRA-06, INFRA-07
**Success Criteria** (what must be TRUE):
  1. A developer can call `ProfileDataStoreFactory.get(profileId, featureName)` and receive a distinct DataStore instance per profile, with profile 1 using bare filenames
  2. User can create up to 4 named profiles, edit name and avatar color, and delete any non-primary profile
  3. Profile 1 cannot be deleted and its DataStore files use no suffix, preserving existing single-profile user data
  4. UserProfile model carries `avatarId` and `pinEnabled` fields without breaking existing serialization
  5. Hilt module provides ProfileDataStoreFactory and ProfileManager as singletons across the app
**Plans:** 2 plans
Plans:
- [ ] 01-01-PLAN.md — UserProfile model extension and ProfileDataStoreFactory
- [ ] 01-02-PLAN.md — ProfileDataStore, ProfileManager, and ProfileModule (Hilt DI)

### Phase 2: Per-Profile Auth and Settings
**Goal**: Users have isolated Trakt and Simkl accounts per profile, and per-profile settings persist independently across profile switches
**Depends on**: Phase 1
**Requirements**: AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, AUTH-06
**Success Criteria** (what must be TRUE):
  1. User can authenticate a distinct Trakt account on each profile; scrobbles and library sync are scoped to the active profile
  2. User can authenticate a distinct Simkl account on each profile; Simkl sync is scoped to the active profile
  3. Switching profiles instantly reflects that profile's Trakt and Simkl tokens without re-authentication
  4. Per-profile settings (language, theme, player preferences, catalog order) persist independently when switching between profiles
  5. Shared settings (addons, debrid, TMDB, MDBList, IMDB, OMDB, auto-translate, top-posters, RPDB) are only configurable from the default profile
**Plans**: TBD

### Phase 3: Profile UI
**Goal**: Users can select, switch, and manage profiles through a fully D-pad navigable interface that stays invisible for single-profile households
**Depends on**: Phase 2
**Requirements**: UI-01, UI-02, UI-03, UI-04, UI-05, UI-06, UI-07, UI-08
**Success Criteria** (what must be TRUE):
  1. Profile selection screen appears exactly once per session when 2 or more profiles exist, and never appears for single-profile users
  2. All profile selection interactions (navigate, choose, PIN entry) are completable using only the D-pad on an Android TV remote
  3. User can set an optional PIN on a profile; a locked profile requires correct PIN entry before switching into it, with server-enforced rate limiting displayed when exceeded
  4. User can switch to any profile from the sidebar menu without returning to the home screen
  5. Active profile name and avatar are visible in the settings header at all times
**Plans**: TBD
**UI hint**: yes

### Phase 4: Sync and Cleanup
**Goal**: Profile metadata and per-profile settings sync to Supabase, and deleting a profile leaves no orphaned data anywhere on-device or in the cloud
**Depends on**: Phase 2
**Requirements**: SYNC-01, SYNC-02, SYNC-03, SYNC-04
**Success Criteria** (what must be TRUE):
  1. Profile metadata (name, avatar, PIN state) syncs to Supabase and is restored on a fresh install or new device
  2. Per-profile settings push and pull via independent blob RPCs, not the shared v7 contract, so Profile 2 changes never overwrite Profile 1 data
  3. Deleting a profile removes all associated DataStore files, SharedPreferences files, and Supabase remote data with no orphans remaining
  4. TraktLibrary and ContinueWatching snapshot stores are classified and scoped per-profile where applicable, with shared stores remaining shared
**Plans**: TBD

### Phase 5: nexio-web Integration
**Goal**: The master account holder can manage all profiles from nexio-web, and non-default profiles can self-manage auth, catalogs, and formatter config without touching the TV
**Depends on**: Phase 4
**Requirements**: WEB-01, WEB-02, WEB-03, WEB-04, WEB-05
**Success Criteria** (what must be TRUE):
  1. Master account holder can create, rename, and delete profiles from nexio-web with changes reflected on-device after next sync
  2. A non-default profile can link and unlink its Trakt and Simkl accounts from nexio-web without requiring access to the TV
  3. A non-default profile can reorder its catalog list from nexio-web with the order persisting on-device
  4. A non-default profile can adjust formatter settings from nexio-web with changes applying on next sync
  5. Profile photo uploaded via nexio-web is stored in Supabase Storage and displayed as the profile avatar in the TV app
**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

Note: Phase 3 (UI) and Phase 4 (Sync) both depend on Phase 2 and can be developed in parallel, but Phase 5 requires Phase 4 complete.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation | 0/2 | Planned | - |
| 2. Per-Profile Auth and Settings | 0/? | Not started | - |
| 3. Profile UI | 0/? | Not started | - |
| 4. Sync and Cleanup | 0/? | Not started | - |
| 5. nexio-web Integration | 0/? | Not started | - |
