---
title: Profile Settings Scope Contract
status: active
date: 2026-04-15
---

# Profile Settings Scope Contract

This document is the source of truth for profile settings ownership in Nexio. It exists to prevent profile-specific behavior from drifting across Android, `nexio-web`, and Supabase.

## Ownership Axes

Every local setting, synced setting, and cache must be classified on two axes.

| Axis | Values | Meaning |
|------|--------|---------|
| Identity scope | `account`, `profile`, `device` | Who owns the value. |
| Persistence scope | `remote-synced`, `local-only`, `derived-cache` | Where the value is persisted and whether it is source-of-truth. |

Allowed combined ownership classes:

| Class | Identity scope | Persistence scope | Rule |
|-------|----------------|-------------------|------|
| `account-remote` | `account` | `remote-synced` | Shared by every profile on the account and synced through account settings, account addons, or account secrets. |
| `profile-remote` | `profile` | `remote-synced` | Unique per profile and synced through `profile_settings` for profiles 2-4. Profile 1 uses the legacy account/default path. |
| `profile-local` | `profile` | `local-only` | Unique per profile on one Android device and never written to Supabase. |
| `profile-derived-cache` | `profile` | `derived-cache` | Rebuildable data derived from profile state, auth state, catalog settings, language, or metadata. Must not be source-of-truth. |
| `shared-language-cache` | `device` | `derived-cache` | Shared across profiles on one device, keyed by language/provider/item so multiple profile languages can coexist. Must not contain profile auth, progress, catalog visibility, or list membership. |
| `global-device` | `device` | `local-only` or `derived-cache` | True device-wide operational facts only. User-facing preferences do not belong here. |

## Lifecycle Invariants

- Profile 1 is the legacy/default account profile.
- Profile 1 uses legacy local store names, not `_p1` suffixed store names.
- Profile 1 must not pull or push `profile_settings` blobs.
- Profile 1 user-facing synced preferences are hydrated through the account contract.
- Profiles 2-4 must not be overwritten by account settings for profile-owned preferences.
- Profiles 2-4 hydrate profile-owned synced preferences through `profile_settings` blobs.
- Profile-local settings never sync through Supabase.
- Profile-derived caches must be safe to delete and rebuild.
- Shared metadata/artwork caches may hydrate any profile, including profile 1/default, but must not carry profile-owned state.
- Profile deletion must clear profile-scoped local stores and profile-scoped caches for that profile only.

## Source Of Truth Matrix

| Surface | Class | Profile 1 source | Profiles 2-4 source | Supabase owner | Android owner | Web owner | Delete behavior |
|---------|-------|------------------|----------------------|----------------|---------------|-----------|-----------------|
| Installed addons | `account-remote` | Account | Account | `account_addons_public` | `AddonPreferences` | `usePortalStore` account addon APIs | Not deleted with profile |
| Addon credentials | `account-remote` | Account | Account | `account_secrets` with `addon_credential` | addon secret RPCs | account secret APIs | Not deleted with profile |
| Account provider API keys | `account-remote` | Account | Account | `account_secrets` | provider settings stores and secret RPCs | account Integrations | Not deleted with profile |
| Account provider availability | `account-remote` | Account | Account | `account_settings_public` | provider settings stores | account Integrations | Not deleted with profile |
| MDBList list availability | `account-remote` | Account | Account | `account_settings_public.catalogs.mdblist` | `MDBListSettingsDataStore` | account Integrations | Not deleted with profile |
| Profile catalog order | `profile-remote` | Account settings | Profile blob | `account_settings_public` for profile 1; `profile_settings` for profiles 2-4 | `LayoutPreferenceDataStore` | `usePortalStore` for profile 1; `useProfileStore` for profiles 2-4 | Cleared for deleted secondary profile |
| Profile disabled catalog keys | `profile-remote` | Account settings | Profile blob | `account_settings_public` for profile 1; `profile_settings` for profiles 2-4 | `LayoutPreferenceDataStore` | `usePortalStore` for profile 1; `useProfileStore` for profiles 2-4 | Cleared for deleted secondary profile |
| Trakt catalog choices | `profile-remote` | Account settings | Profile blob | `account_settings_public.catalogs.trakt` for profile 1; `profile_settings.trakt_settings` for profiles 2-4 | `TraktSettingsDataStore` | account Integrations for profile 1; profile integration UI for profiles 2-4 | Cleared for deleted secondary profile |
| SIMKL catalog choices | `profile-remote` | Account settings | Profile blob | `account_settings_public.catalogs.simkl` for profile 1; `profile_settings.simkl_settings` for profiles 2-4 | `SimklSettingsDataStore` | account Integrations for profile 1; profile integration UI for profiles 2-4 | Cleared for deleted secondary profile |
| Theme | `profile-remote` | Account/default profile path | Profile blob | `account_settings_public` for profile 1 if exposed remotely; `profile_settings.theme_settings` for profiles 2-4 | `ThemeDataStore` | Profile UI when exposed | Cleared for deleted secondary profile |
| Layout UX settings | `profile-remote` | Account/default profile path | Profile blob | `account_settings_public` for profile 1 if exposed remotely; `profile_settings.layout_settings` for profiles 2-4 | `LayoutPreferenceDataStore` | Profile UI when exposed | Cleared for deleted secondary profile |
| Playback UX settings | `profile-remote` | Account/default profile path | Profile blob | `account_settings_public` for profile 1 if exposed remotely; `profile_settings.player_settings` for profiles 2-4 | `PlayerSettingsDataStore` | Profile formatter/playback surfaces when exposed | Cleared for deleted secondary profile |
| Formatter | `profile-remote` | Account/default profile path | Profile blob | `account_settings_public.formatter` for profile 1; `profile_settings.player_settings` for profiles 2-4 | `PlayerSettingsDataStore` synced formatter keys | `usePortalStore` for profile 1; profile formatter tab for profiles 2-4 | Cleared for deleted secondary profile |
| Profile Trakt auth | `profile-remote` | Account Trakt auth | Profile auth tokens | `account_secrets` for profile 1; `profile_auth_tokens` for profiles 2-4 | `TraktAuthDataStore` | account Integrations for profile 1; profile Auth tab for profiles 2-4 | Cleared for deleted secondary profile |
| Profile SIMKL auth | `profile-remote` | Account SIMKL auth | Profile auth tokens | `account_secrets` for profile 1; `profile_auth_tokens` for profiles 2-4 | `SimklAuthDataStore` | account Integrations for profile 1; profile Auth tab for profiles 2-4 | Cleared for deleted secondary profile |
| App language | `profile-local` | Legacy profile-local key | Profile-local suffixed key | none | `AppLocaleResolver` profile-aware SharedPreferences | Android settings only | Cleared for deleted secondary profile |
| Search history | `profile-local` | Legacy profile-local store | Profile-local store | none | `SearchHistoryDataStore` | Android search UI | Cleared for deleted secondary profile |
| Home catalog snapshot | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `HomeCatalogSnapshotStore` | none | Cleared for deleted secondary profile |
| Synthetic home catalogs | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `SyntheticHomeCatalogStore` | none | Cleared for deleted secondary profile |
| Continue watching snapshot | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `ContinueWatchingSnapshotStore` | none | Cleared for deleted secondary profile |
| Trakt library snapshot | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `TraktLibrarySnapshotStore` | none | Cleared for deleted secondary profile |
| Trakt discovery snapshot | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `TraktDiscoverySnapshotStore` | none | Cleared for deleted secondary profile |
| SIMKL library snapshot | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `SimklLibrarySnapshotStore` | none | Cleared for deleted secondary profile |
| SIMKL discovery snapshot | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `SimklDiscoverySnapshotStore` | none | Cleared for deleted secondary profile |
| SIMKL progress sync state | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `SimklProgressSyncStateStore` | none | Cleared for deleted secondary profile |
| Trakt mutation outbox | `profile-derived-cache` | Profile 1 cache key | Profile-suffixed cache key | none | `TraktMutationOutboxStore` | none | Cleared for deleted secondary profile |
| Metadata disk cache | `shared-language-cache` | Shared language-keyed metadata entries | Shared language-keyed metadata entries | none | `MetadataDiskCacheStore` | none | Keys use `meta::<itemKey>::<languageTag>::<providerToken>`; no profile id. |
| Artwork image cache | `global-device` | Shared device image entries | Shared device image entries | none | `ArtworkImageCacheKeys` / image loader disk cache | none | Keys include item/provider/artwork type only; no profile id and no language. |
| Catalog disk cache | `profile-derived-cache` | Profile-sensitive cache entries | Profile-sensitive cache entries | none | `CatalogDiskCacheStore` | none | Cache entries must include profile sensitivity where needed |
| TVDB identity cache | `global-device` | Device cache | Device cache | none | `TvdbIdentityCacheStore` | none | Cache policy, not profile source-of-truth |
| Addon manifest cache | `global-device` | Device cache | Device cache | none | `AddonRepositoryImpl` manifest cache | none | Cache policy, not profile source-of-truth |
| App onboarding | `global-device` | Device | Device | none | `AppOnboardingDataStore` | none | Not deleted with profile |
| Device recommendations channel metadata | `global-device` | Device | Device | none | `AndroidTvRecommendationsDataStore` | none | Not deleted with profile unless proven profile-bearing |
| Device benchmark data | `global-device` | Device | Device | none | `DebridBenchmarkStore` | none | Not deleted with profile |
| Stream link cache | `global-device` | Device cache | Device cache | none | `StreamLinkCacheDataStore` | none | Cache policy, not profile source-of-truth |
| Update preferences | `global-device` | Device | Device | none | `UpdatePreferences` | none | Not deleted with profile |

## Android Store Inventory

Every store in this table must appear exactly once. Additions to `app/src/main/java/com/nexio/tv/data/local` or new profile-sensitive utility stores must update this table and `ProfileSettingsScopeContractTest`.

| Store | Class | Identity scope | Persistence scope | Supabase owner | Notes |
|-------|-------|----------------|-------------------|----------------|-------|
| `AddonPreferences` | `account-remote` | `account` | `remote-synced` | `account_addons_public` | Installed addon availability is shared across profiles. |
| `AddonRepositoryManifestCache` | `global-device` | `device` | `derived-cache` | none | Device cache of account-shared addon manifest metadata, not source-of-truth. |
| `AndroidTvRecommendationsDataStore` | `global-device` | `device` | `local-only` | none | TV recommendation channel state is device/platform state unless later proven profile-bearing. |
| `AnimeSkipSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.animeSkip` | Provider availability/config is account-owned. |
| `AppLocaleResolver` | `profile-local` | `profile` | `local-only` | none | App language must be profile-unique and local-only. |
| `AppOnboardingDataStore` | `global-device` | `device` | `local-only` | none | One-time device/app onboarding state. |
| `CatalogDiskCacheStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Catalog cache depends on profile-visible catalogs. |
| `ContinueWatchingSnapshotStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Snapshot depends on active profile history/progress and language epoch. |
| `DebridBenchmarkStore` | `global-device` | `device` | `derived-cache` | none | Device benchmark/capability data is hardware/device state. |
| `DebugSettingsDataStore` | `global-device` | `device` | `local-only` | none | Debug flags are device/operator state unless promoted into profile settings later. |
| `EasyDebridSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.debrid.easyDebrid`, `account_secrets` | Provider credential/config is account-owned. |
| `HomeCatalogSnapshotStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Home rows derive from profile catalog visibility/order. |
| `ImdbSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.imdb`, `account_secrets` | Provider credential/config is account-owned. |
| `LayoutPreferenceDataStore` | `profile-remote` | `profile` | `remote-synced` | `account_settings_public` for profile 1; `profile_settings.layout_settings` for profiles 2-4 | User-facing layout/catalog UX settings. |
| `LibraryPreferences` | `profile-local` | `profile` | `local-only` | none | Library display/state preference must not bleed across profiles. |
| `MDBListDiscoverySnapshotStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Discovery list cache depends on account availability and profile visibility. |
| `MDBListSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.mdblist`, `account_settings_public.catalogs.mdblist` | MDBList availability/list source is account-owned; profile visibility is handled through profile catalog settings. |
| `MetadataDiskCacheStore` | `shared-language-cache` | `device` | `derived-cache` | none | Shared text metadata cache keyed by item, language, and provider token; profile id must not be part of the key. |
| `OmdbSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.omdb`, `account_secrets` | Provider credential/config is account-owned. |
| `PlayerSettingsDataStore` | `profile-remote` | `profile` | `remote-synced` | `account_settings_public` for profile 1; `profile_settings.player_settings` for profiles 2-4 | User-facing playback, formatter, audio, subtitle, and stream-selection settings. |
| `PosterRatingsSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.posterRatings`, `account_secrets` | Provider credential/config is account-owned. |
| `PremiumizeSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.debrid.premiumize`, `account_secrets` | Provider credential/config is account-owned. |
| `ProfileDataStore` | `global-device` | `device` | `local-only` | `profiles` metadata sync is handled separately | Stores profile list and active profile id for the device. Not a user preference store. |
| `ProfileDataStoreFactory` | `global-device` | `device` | `local-only` | none | Factory/locator for profile-scoped DataStores, not a settings source. |
| `RealDebridAuthDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.debrid.realDebrid`, `account_secrets` | Account Real-Debrid auth is shared. |
| `SearchHistoryDataStore` | `profile-local` | `profile` | `local-only` | none | Search history must be isolated per profile. |
| `SimklAuthDataStore` | `profile-remote` | `profile` | `remote-synced` | `account_secrets` for profile 1; `profile_auth_tokens` for profiles 2-4 | Profile-specific SIMKL auth for secondary profiles. |
| `SimklDiscoverySnapshotStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Discovery cache depends on profile SIMKL settings/language. |
| `SimklLibrarySnapshotStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Library cache depends on active profile SIMKL auth/state. |
| `SimklProgressSyncStateStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Sync progress state must not bleed between profile tokens. |
| `SimklSettingsDataStore` | `profile-remote` | `profile` | `remote-synced` | `account_settings_public.catalogs.simkl` for profile 1; `profile_settings.simkl_settings` for profiles 2-4 | User-facing SIMKL catalog choices. |
| `StreamLinkCacheDataStore` | `global-device` | `device` | `derived-cache` | none | Stream URL cache is device cache; not source-of-truth. |
| `SubtitleTranslationSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.subtitleTranslation`, `account_secrets` | Provider credential/config is account-owned. |
| `SyntheticHomeCatalogStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Synthetic rows depend on profile catalog settings and language epoch. |
| `TheIntroDbSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.theIntroDb` | Segment provider availability/buttons are account-owned product settings. |
| `ThemeDataStore` | `profile-remote` | `profile` | `remote-synced` | `profile_settings.theme_settings` for profiles 2-4; account/default path for profile 1 | User-facing visual preference owned by the active profile. |
| `TmdbSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.tmdb`, `account_secrets` | Provider credential/config is account-owned. |
| `TorBoxSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.debrid.torBox`, `account_secrets` | Provider credential/config is account-owned. |
| `TrailerSettingsDataStore` | `global-device` | `device` | `local-only` | none | Trailer helper/device behavior; classify as profile-local if later made user-facing per profile. |
| `TraktAuthDataStore` | `profile-remote` | `profile` | `remote-synced` | `account_secrets` for profile 1; `profile_auth_tokens` for profiles 2-4 | Profile-specific Trakt auth for secondary profiles. |
| `TraktDiscoverySnapshotStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Discovery cache depends on profile Trakt settings/auth. |
| `TraktLibrarySnapshotStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Library cache depends on active profile Trakt auth/state. |
| `TraktMutationOutboxStore` | `profile-derived-cache` | `profile` | `derived-cache` | none | Mutation outbox must not bleed between profile Trakt accounts. |
| `TraktSettingsDataStore` | `profile-remote` | `profile` | `remote-synced` | `account_settings_public.catalogs.trakt` for profile 1; `profile_settings.trakt_settings` for profiles 2-4 | User-facing Trakt catalog choices and continue-watching window. |
| `TvdbSettingsDataStore` | `account-remote` | `account` | `remote-synced` | `account_settings_public.integrations.tvdb`, `account_secrets` | Provider credential/config is account-owned. |
| `TvdbIdentityCacheStore` | `global-device` | `device` | `derived-cache` | none | TVDB identity lookup cache, not source-of-truth. |
| `TvdbTokenStore` | `account-remote` | `account` | `remote-synced` | `account_secrets` or token-refresh service state | TVDB token is derived from account-owned TVDB credentials. |
| `WatchProgressPreferences` | `profile-local` | `profile` | `local-only` | none | Local watch progress preferences must not bleed across profiles. |
| `WatchedItemsPreferences` | `profile-local` | `profile` | `local-only` | none | Local watched item state must not bleed across profiles. |
| `YouTubeTrailerAuthDataStore` | `global-device` | `device` | `local-only` | none | Device helper auth. Reclassify if product requires per-profile YouTube state. |
| `YouTubeTrailerTokenStore` | `global-device` | `device` | `local-only` | none | Device helper token cache. Reclassify if product requires per-profile YouTube state. |
| `UpdatePreferences` | `global-device` | `device` | `local-only` | none | App update preference is device-wide operational state. |
| `profile_cleanup_state` | `global-device` | `device` | `local-only` | none | Pending remote profile cleanup retry state, not user preference. |

## Classification Rules

- User-facing preference + should roam across devices: `profile-remote`.
- User-facing preference + device-specific or privacy-sensitive local behavior: `profile-local`.
- Derived data from profile settings, auth, language, or catalog inventory: `profile-derived-cache`.
- Shared text metadata keyed by item/language/provider and reusable by all profiles: `shared-language-cache`.
- Provider availability, account API key, addon install, or account service credentials: `account-remote`.
- Device capability, app bootstrapping, or non-user preference operational state: `global-device`.

## Required Guards

- `ProfileSettingsSyncService.syncedFeatures` may include only `profile-remote` stores.
- `AccountSettingsSyncService` may write profile-owned UX values only for profile 1.
- `nexio-web` profile 1 surfaces must not call profile settings APIs for profile-owned UX values.
- `nexio-web` profile 2-4 surfaces must not mutate account settings for profile-owned UX values.
- Supabase `sync_pull_profile_settings_blob` and `sync_push_profile_settings_blob` reject profile 1; profile 1/default uses account settings RPCs only.
- No `profile-local`, `profile-derived-cache`, `shared-language-cache`, or `global-device` value may be serialized to `profile_settings`.
