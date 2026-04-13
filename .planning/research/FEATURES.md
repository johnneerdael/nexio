# Feature Research

**Domain:** Multi-profile support in Android TV / Fire TV streaming app
**Researched:** 2026-04-14
**Confidence:** HIGH (NuvioTV reference implementation verified, cross-referenced with Netflix/Plex/Kodi/Jellyfin patterns)

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist in any multi-profile streaming app. Missing these = product feels incomplete or broken.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Profile selection screen on app launch | Every streaming app (Netflix, Plex, Disney+) shows this when multiple profiles exist | LOW | NuvioTV: `hasSelectedProfileThisSession` flag gates display — only shown once per session, only when `profiles.size > 1` or active profile has PIN |
| Per-profile avatar / visual identity | Users need to instantly recognise "their" tile at 10 feet on a TV | LOW | NuvioTV uses colour circles + optional `avatarId` from Supabase catalog; Nexio already has `UserProfile(avatarColorHex)` and `ProfileAvatarColors` |
| Per-profile display name | Minimum identity signal; "Profile 1" is acceptable fallback but named profiles are expected | LOW | Already in `UserProfile.name`; NuvioTV trims and defaults to "Profile N" |
| Profile switching from sidebar/menu | Users switch mid-session without going back to home; sidebar entry shown only when `profiles.size > 1` | MEDIUM | NuvioTV exposes a sidebar item that resets `hasSelectedProfileThisSession = false` to re-show the picker |
| Per-profile watch history (Trakt/Simkl) | Core reason households want separate profiles — "my" watch state vs "their" watch state | HIGH | Requires full per-profile Trakt OAuth + token storage; NuvioTV `LibrarySyncService` already scopes all sync calls with `activeProfileId` |
| Profile creation and deletion | Users expect self-service profile management (add/remove family members) | MEDIUM | NuvioTV enforces max 4, blocks deleting profile 1, cleans up DataStore files on delete |
| Settings that persist per profile | Language, theme, player preferences — user expects their settings to survive a profile switch | HIGH | Requires `ProfileDataStoreFactory` pattern; all DataStores must accept `profileId` at construction time |
| Zero friction for single-profile users | Single-profile users must never see a profile picker or management UI | LOW | Pure conditional gating: show picker only when `profiles.size > 1 || activeProfileHasPin` |
| Optional PIN lock per profile | Household privacy; keeps kids out of adult profiles on a shared TV | MEDIUM | NuvioTV stores PIN hash server-side via Supabase RPC (`set_profile_pin`, `verify_profile_pin`, `clear_profile_pin`); verified before granting access |

### Differentiators (Competitive Advantage)

Features beyond baseline expectations that improve the household experience.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Session-scoped profile gate (not per-launch) | Show profile picker once per app session, not on every cold start — reduces friction for power users who switch infrequently | LOW | NuvioTV `hasSelectedProfileThisSession` with `rememberSaveable`; resets when user explicitly switches via sidebar |
| Avatar catalog from web companion (nexio-web) | TV remote is terrible for photo management; upload via phone/browser is dramatically better UX | MEDIUM | NuvioTV uses Supabase-hosted `AvatarRepository` with `getAvatarCatalog()` / `getAvatarImageUrl()`; Nexio already decided photo upload is nexio-web only |
| PIN with rate-limit / retry-after | Prevents brute-force PIN guessing; `retryAfterSeconds` in verify response | LOW | NuvioTV `SupabaseProfilePinVerifyResult(unlocked, retryAfterSeconds)` already models this |
| Shared addons / debrid across all profiles | Power users want one place to manage integrations; separating them would be maintenance overhead | LOW | Architectural decision — addons, debrid, TMDB, MDBList, IMDB, OMDB, RPDB, auto-translate, top-posters are all shared; only auth tokens and personal prefs are per-profile |
| Per-profile catalog ordering | Each user curates their home screen differently; different people prioritise different content sources | MEDIUM | Catalog order is a per-profile DataStore preference; addons themselves are shared but the ordering/visibility is personal |
| Per-profile Simkl in addition to Trakt | Households may mix tracker preferences — one member uses Trakt, another Simkl | MEDIUM | Analogous to Trakt; needs per-profile token storage in per-profile DataStore |
| Profile sync to Supabase | Profile metadata (name, avatar, PIN state) survives app reinstall and device change | MEDIUM | NuvioTV `ProfileSyncService.pushToRemote()` / `pullFromRemote()` via `sync_push_profiles` / `sync_pull_profiles` RPCs |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| On-device photo upload / camera access | Users want personalised profile photos | TV remote cannot drive file pickers or camera; keyboard-less input is painful; Fire TV has no reliable file picker intent | Upload via nexio-web companion, store in Supabase, sync `avatarId` to device |
| More than 4 profiles | Large households may want 5+ profiles | Each profile = one DataStore file per feature; 4 profiles × N features = manageable; 8+ quickly becomes unwieldy; also matches Netflix/Disney+ household limits | Hard cap at 4; matches NuvioTV and upstream design |
| Deleting the primary profile (profile ID 1) | Users want to "start over" or remove the default | Primary profile is the fallback when no others exist; deleting it creates an unrecoverable state | Rename it; allow deleting only profiles 2–4 |
| Per-profile addon configurations | Power users want different addon sets per profile | Addons involve OAuth, credentials, and network state that would need full duplication; high complexity for minimal gain | Addons are shared; catalog ordering and visibility are per-profile |
| Per-profile debrid accounts | Users think separate debrid = separate download queues | Debrid accounts are expensive subscriptions; households share one; separating them has no UX benefit and doubles auth complexity | Single shared debrid account for all profiles |
| Mandatory profile selection on every launch | Seems more "correct" that you always choose | Creates friction for single-user households and users who only use one profile; Netflix moved away from this | Session-scoped gate: ask once per session, not per launch |
| Profile-level parental controls / content filtering | Requested for child profiles | Requires catalogue-level content rating metadata Nexio doesn't currently have; high scope creep | Out of scope for this milestone; PIN lock is the available privacy tool |

---

## Feature Dependencies

```
[Profile Selection Screen]
    └──requires──> [ProfileManager (profiles StateFlow)]
                       └──requires──> [ProfileDataStore (list persistence)]

[Per-profile Trakt OAuth]
    └──requires──> [ProfileDataStoreFactory (per-profile token storage)]
                       └──requires──> [ProfileManager (activeProfileId)]

[Per-profile Simkl OAuth]
    └──requires──> [ProfileDataStoreFactory (per-profile token storage)]
                       └──requires──> [ProfileManager (activeProfileId)]

[Per-profile settings (language, theme, player, catalogs)]
    └──requires──> [ProfileDataStoreFactory]
                       └──requires──> [ProfileManager (activeProfileId)]

[PIN lock entry on profile selection]
    └──requires──> [Supabase PIN RPCs (set/verify/clear)]
                   [D-pad-friendly PIN entry UI (on-screen numpad)]

[Profile deletion with cleanup]
    └──requires──> [ProfileDataStoreFactory.clearProfile()]
                   [ProfileSyncService.deleteProfileData()]
                   [File system cleanup (_p{id}.preferences_pb)]

[Sidebar profile switcher]
    └──requires──> [Profile Selection Screen]
                   [Session flag reset (hasSelectedProfileThisSession = false)]

[Avatar from nexio-web]
    └──requires──> [AvatarRepository (Supabase catalog fetch)]
                   └──requires──> [Internet + auth session]

[Supabase profile sync]
    └──requires──> [ProfileSyncService]
                   └──requires──> [AuthManager (JWT refresh)]
```

### Dependency Notes

- **Per-profile DataStore is the foundation:** Every per-profile feature (Trakt, Simkl, settings, catalogs) depends on `ProfileDataStoreFactory`. This must be built first. All existing `@Singleton` DataStore injections must be refactored to use the factory.
- **ProfileManager is the coordinator:** It owns `activeProfileId` as a `StateFlow<Int>`. Everything that needs "current profile context" observes this. LibrarySyncService, WatchedItemsSyncService, and WatchProgressSyncService in NuvioTV all call `profileManager.activeProfileId.value` at execution time.
- **Trakt and Simkl are independent per-profile features:** They share the same pattern (factory-scoped DataStore for tokens) but do not depend on each other.
- **PIN verification depends on network:** Supabase RPCs are required for PIN set/verify/clear. Offline PIN verification is not supported in the NuvioTV model.
- **Profile selection screen is opt-in by profile count:** The screen is not a permanent route in the nav graph — it overlays conditionally based on `profiles.size > 1 || activeProfileHasPin`.

---

## MVP Definition

This is an additive milestone on an existing app. "MVP" here means the minimum feature set that delivers a usable multi-profile household experience.

### Launch With (v1 — this milestone)

- [x] `ProfileDataStoreFactory` — without this, nothing else is isolated
- [x] `ProfileManager` CRUD (create, update, delete, max 4, protect profile 1)
- [x] Profile selection screen (opt-in, session-scoped, D-pad navigable)
- [x] Per-profile Trakt OAuth + token storage + scrobble routing
- [x] Per-profile Simkl OAuth + token storage
- [x] Per-profile settings (language, theme, player prefs, catalog order/visibility)
- [x] Optional PIN lock per profile (Supabase RPCs, D-pad numpad entry)
- [x] Profile deletion with full cleanup (DataStore files, remote Supabase data)
- [x] Supabase profile sync (push/pull profile metadata)
- [x] Sidebar profile switcher (shown only when profiles.size > 1)
- [x] Settings cascade classification (shared vs per-profile clearly defined)

### Add After Validation (v1.x)

- [ ] Avatar catalog expansion — more avatar choices via nexio-web; trigger: user feedback requests more identity options
- [ ] Profile import/restore — if user reinstalls and wants to recover profile setup; trigger: support requests
- [ ] PIN complexity options (longer PIN) — trigger: security-conscious user requests

### Future Consideration (v2+)

- [ ] Per-profile parental controls / content filtering — requires catalogue content rating metadata
- [ ] Guest profile (temporary, no sync) — niche use case, adds complexity to state machine
- [ ] Profile-specific notification preferences — if/when Nexio adds push notifications

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| ProfileDataStoreFactory | HIGH | MEDIUM | P1 — foundation for all other features |
| ProfileManager CRUD | HIGH | LOW | P1 — prerequisite for profile selection screen |
| Profile selection screen (D-pad UI) | HIGH | MEDIUM | P1 — first visible feature users interact with |
| Per-profile Trakt OAuth | HIGH | MEDIUM | P1 — primary reason households want profiles |
| Per-profile settings isolation | HIGH | HIGH | P1 — without this, switching profiles changes nothing |
| Optional PIN lock | MEDIUM | MEDIUM | P1 — household privacy without it is incomplete |
| Profile deletion + cleanup | HIGH | MEDIUM | P1 — without cleanup, deleted profile data leaks |
| Supabase profile sync | MEDIUM | LOW | P1 — already have infrastructure; needed for PIN state |
| Per-profile Simkl OAuth | MEDIUM | LOW | P2 — same pattern as Trakt, smaller user base |
| Sidebar profile switcher | MEDIUM | LOW | P2 — convenience; session gate handles switching already |
| Per-profile catalog ordering | MEDIUM | MEDIUM | P2 — personalisation; catalog addons are shared anyway |
| Avatar catalog (nexio-web) | LOW | LOW | P2 — polish; colour circles are functional fallback |

**Priority key:**
- P1: Must have for milestone launch
- P2: Should have, include if time allows
- P3: Nice to have, future consideration

---

## Competitor Feature Analysis

| Feature | Netflix / Disney+ | Plex | Kodi | Jellyfin | Nexio approach |
|---------|-------------------|------|------|----------|----------------|
| Profile count cap | Netflix: 5, Disney+: 4 | Unlimited (Plex Home) | Unlimited | Unlimited | 4 (matches upstream NuvioTV; manageable DataStore count) |
| Profile selection timing | Every launch (Netflix), configurable (Plex) | Per-session or always | Startup screen (optional) | Per-logout (no fast switch) | Session-scoped: once per session, not per launch |
| PIN / lock | Yes (Netflix Kids lock, profile PIN) | Yes (Plex managed user PINs) | Yes (per-profile lock) | Partial (LAN PIN removed, feature requested) | Yes, optional per profile; PIN hash stored in Supabase |
| Watch history isolation | Full per-profile | Full per-user | Full per-profile | Full per-user | Via per-profile Trakt/Simkl accounts |
| Settings isolation | Theme, language, maturity ratings | Limited (server-side mostly) | Full (separate settings DB per profile) | Per-user server settings | Language, theme, player prefs, catalog order |
| Shared vs per-profile split | Shared: payment/subscription; Per-profile: everything else | Shared: server libraries; Per-profile: watch state, ratings | Configurable per setting category | Shared: server config; Per-profile: client prefs | Shared: addons, debrid, TMDB/MDBList/etc; Per-profile: Trakt/Simkl, language, theme, player, catalogs |
| Avatar/photo | Full photo upload (mobile app) | Full photo upload | Colour/icon picker | Text avatar | Colour picker (on-device) + photo upload via nexio-web |
| D-pad profile picker | Grid of large avatar tiles, minimal text | Full TV remote support | Menu-based, less visual | No native TV profile switcher | Full Compose TV grid; `FocusRequester` managed; animated transitions |
| Profile creation flow | Simple name + photo | Web/mobile app | Settings-based | Admin dashboard | On-device name + colour + optional avatar; max 4 |

---

## TV-Specific UX Considerations

These apply specifically to the profile selection and switching experience on Android TV / Fire TV with D-pad remote control.

**Profile picker grid layout:** Profiles should be displayed as a horizontal row or 2x2 grid of large focusable tiles. Grid layout makes D-pad navigation predictable. NuvioTV uses a full-screen overlay with animated slide transitions between the picker and creation/edit panels.

**Focus management on PIN entry:** The standard Android TV soft keyboard is unreliable for PIN entry. NuvioTV renders a custom on-screen number pad as a Compose grid of focusable buttons, navigable entirely with D-pad. `FocusRequester` is used to auto-focus the first digit on entry. `onPreviewKeyEvent` handles hardware remote numeric key presses directly as a fallback.

**Back button behaviour:** On the profile selection screen, `BackHandler` must be handled carefully — pressing Back should not exit the app before a profile is selected if a PIN is required. NuvioTV wraps the overlay in a `BackHandler` that either cancels PIN entry or returns to profile list.

**Visual distinction at 10 feet:** Profile tiles must be large (min 120dp), with clear focus ring (2dp border with accent colour), and name text large enough to read from a sofa. Avatar colour circles provide instant identity cues without needing to read the name.

**Session flag prevents double-gating:** `hasSelectedProfileThisSession` stored with `rememberSaveable` means navigating away and back within the app does not re-show the picker. It resets only when the user explicitly selects "Switch Profile" from the sidebar.

**Sidebar profile entry:** Only appears when `profiles.size > 1`. Shows active profile name and avatar colour. D-pad focus on it triggers profile switch flow by resetting the session flag.

---

## Sources

- NuvioTV reference implementation at `~/Scripts/NuvioTV` — `ProfileManager`, `ProfileDataStoreFactory`, `ProfileSelectionViewModel`, `ProfileSelectionScreen`, `ProfileSyncService`, `LibrarySyncService`, `MainActivity` (verified directly, HIGH confidence)
- Netflix TV UI patterns: https://mlangendijk.medium.com/breaking-down-the-new-netflix-tv-ui-d651aff8bbee
- Android TV navigation guide: https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv
- Plex Fast User Switching: https://support.plex.tv/articles/204232453-fast-user-switching/
- Plex profile support: https://support.plex.tv/articles/profile/
- Jellyfin PIN protection issue: https://github.com/jellyfin/jellyfin-androidtv/issues/3509
- Jellyfin multi-user profile switching discussion: https://github.com/jellyfin/jellyfin-web/discussions/7059
- Kodi profiles wiki: https://kodi.wiki/view/Profiles
- TV UX best practices: https://spyro-soft.com/blog/media-and-entertainment/8-ux-ui-best-practices-for-designing-user-friendly-tv-apps
- Amazon Fire TV design guidelines: https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html

---
*Feature research for: multi-profile support in Android TV streaming app*
*Researched: 2026-04-14*
