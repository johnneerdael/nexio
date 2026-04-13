# Requirements: Nexio

**Defined:** 2026-04-14
**Core Value:** Reliable, high-quality streaming playback with smart source selection and seamless library tracking across debrid providers.

## v1.0 Requirements

Requirements for multi-profile support milestone. Each maps to roadmap phases.

### Profile Infrastructure

- [ ] **INFRA-01**: App isolates DataStore preferences per profile via ProfileDataStoreFactory
- [ ] **INFRA-02**: User can create up to 4 named profiles
- [ ] **INFRA-03**: User can edit profile name and avatar color
- [ ] **INFRA-04**: User can delete non-primary profiles (profile 1 protected)
- [ ] **INFRA-05**: Profile 1 uses bare DataStore filenames for zero-migration from single-profile
- [ ] **INFRA-06**: 7 DataStores converted from singleton delegate to factory pattern with flatMapLatest
- [ ] **INFRA-07**: UserProfile model extended with avatarId and pinEnabled fields

### Per-Profile Auth

- [ ] **AUTH-01**: User can link a unique Trakt account per profile
- [ ] **AUTH-02**: Trakt scrobbles, library sync, and watch progress are scoped to the active profile
- [ ] **AUTH-03**: User can link a unique Simkl account per profile
- [ ] **AUTH-04**: Simkl sync is scoped to the active profile
- [ ] **AUTH-05**: Shared settings (addons, debrid, TMDB, MDBList, IMDB, OMDB, auto-translate, top-posters, RPDB) are configurable only from default profile
- [ ] **AUTH-06**: Per-profile settings (language, theme, player, catalog order) persist across profile switches

### Profile UI

- [ ] **UI-01**: User sees profile selection screen only when 2+ profiles exist
- [ ] **UI-02**: Profile selection is session-scoped (shown once per session, not per launch)
- [ ] **UI-03**: Profile selection screen is fully D-pad navigable on Android TV
- [ ] **UI-04**: User can optionally set a PIN on their profile (server-side hash)
- [ ] **UI-05**: PIN entry uses a D-pad-friendly on-screen numpad
- [ ] **UI-06**: PIN verification respects server rate limiting (retryAfterSeconds)
- [ ] **UI-07**: User can switch profiles from the sidebar menu
- [ ] **UI-08**: Active profile name/avatar is visible in settings header

### Sync & Cleanup

- [ ] **SYNC-01**: Profile metadata (name, avatar, PIN state) syncs to Supabase
- [ ] **SYNC-02**: Per-profile settings sync via independent blob push/pull (not v7 contract)
- [ ] **SYNC-03**: Profile deletion removes all DataStore files, SharedPreferences, and Supabase remote data
- [ ] **SYNC-04**: Snapshot stores (TraktLibrary, ContinueWatching) are classified and scoped per-profile where applicable

### nexio-web Integration

- [ ] **WEB-01**: Master account can create, edit, and delete profiles via nexio-web
- [ ] **WEB-02**: Non-default profiles can manage Trakt/Simkl auth via nexio-web
- [ ] **WEB-03**: Non-default profiles can configure catalog ordering via nexio-web
- [ ] **WEB-04**: Non-default profiles can configure formatter settings via nexio-web
- [ ] **WEB-05**: Profile photo upload via nexio-web stored in Supabase

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Parental Controls

- **PARENT-01**: User can set content maturity ratings per profile
- **PARENT-02**: Content filtered based on profile maturity settings

### Advanced Profiles

- **ADV-01**: Guest profile (temporary, no sync, auto-delete on session end)
- **ADV-02**: Profile import/restore from Supabase backup
- **ADV-03**: PIN complexity options (longer PIN, alphanumeric)

## Out of Scope

| Feature | Reason |
|---------|--------|
| On-device photo upload | TV remote cannot drive file pickers; use nexio-web instead |
| More than 4 profiles | Keeps DataStore file count manageable; matches Netflix/Disney+ household limits |
| Per-profile addon configurations | Addons involve OAuth and network state; high complexity for minimal gain |
| Per-profile debrid accounts | Debrid subscriptions are shared per household; separating has no UX benefit |
| Deleting the primary profile | Profile 1 is the fallback; deleting creates unrecoverable state |
| Per-profile parental controls | Requires catalogue content rating metadata Nexio doesn't have |
| Mandatory profile selection on every launch | Creates friction for single-user households |
| Profile-specific notification preferences | Nexio doesn't have push notifications yet |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01 | — | Pending |
| INFRA-02 | — | Pending |
| INFRA-03 | — | Pending |
| INFRA-04 | — | Pending |
| INFRA-05 | — | Pending |
| INFRA-06 | — | Pending |
| INFRA-07 | — | Pending |
| AUTH-01 | — | Pending |
| AUTH-02 | — | Pending |
| AUTH-03 | — | Pending |
| AUTH-04 | — | Pending |
| AUTH-05 | — | Pending |
| AUTH-06 | — | Pending |
| UI-01 | — | Pending |
| UI-02 | — | Pending |
| UI-03 | — | Pending |
| UI-04 | — | Pending |
| UI-05 | — | Pending |
| UI-06 | — | Pending |
| UI-07 | — | Pending |
| UI-08 | — | Pending |
| SYNC-01 | — | Pending |
| SYNC-02 | — | Pending |
| SYNC-03 | — | Pending |
| SYNC-04 | — | Pending |
| WEB-01 | — | Pending |
| WEB-02 | — | Pending |
| WEB-03 | — | Pending |
| WEB-04 | — | Pending |
| WEB-05 | — | Pending |

**Coverage:**
- v1.0 requirements: 30 total
- Mapped to phases: 0
- Unmapped: 30

---
*Requirements defined: 2026-04-14*
*Last updated: 2026-04-14 after initial definition*
