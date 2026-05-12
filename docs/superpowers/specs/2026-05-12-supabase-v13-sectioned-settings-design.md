# Supabase Contract v13 Sectioned Account Settings Design

Date: 2026-05-12
Status: approved design, pending implementation plan

## Purpose

Contract v12 protects account sync surfaces with timestamp watermarks, removes synced Wyzie, TheIntroDb, and TVDB settings, removes user-entered TMDB/TVDB secrets, keeps TMDB settings with build-time credentials, and keeps Kitsu auth without an `enabled` field. Account settings are still stored and written as one large JSONB document in `account_settings_public.settings_payload`. That leaves Android and `nexio-web` sharing one source-of-truth blob even though each client owns and understands only part of the document.

Contract v13 splits account settings into sectioned JSONB rows. Each section becomes its own source-of-truth boundary with its own payload, revision, update timestamp, and conflict behavior. Addons, secrets, profile settings blobs, and profile auth tokens keep their v12 surfaces unless a later contract changes them.

## Goals

- Remove the single account-settings blob as the authoritative remote source.
- Let clients pull, decode, apply, and push only the setting sections they understand.
- Prevent unknown future web or Android fields from aborting unrelated setting application.
- Make conflict detection section-scoped instead of whole-account-settings scoped.
- Keep v12 clients working during rollout through adapter RPCs.
- Avoid changing profile ownership semantics in this contract.

## Non-Goals

- Do not move profile 1/default-profile settings out of the account-settings compatibility shape in v13.
- Do not normalize every setting into scalar SQL columns.
- Do not change account addons, account secrets, profile settings blobs, or profile auth token contracts beyond including them in the v13 snapshot envelope.
- Do not remove v12 RPCs during initial rollout.

## V12 Baseline

V13 starts from the already-bumped v12 contract. Do not reintroduce surfaces removed in v12:

- `integrations.theIntroDb` is device-local only and is not synced.
- `integrations.tvdb` is not synced; TVDB uses `BuildConfig.TVDB_API_KEY`.
- TMDB user secrets are gone; `integrations.tmdb` settings stay synced and `enabled` is forced true by clients.
- `integrations.kitsuAuth.enabled` is gone; login/auth status stays synced.
- Wyzie remains absent from settings and secrets.

## Current Problem

Today the v12 account snapshot has separate surfaces for settings, addons, and secrets, but the settings surface is still one JSONB payload:

```text
account_settings_public
- user_id
- settings_payload jsonb
- sync_revision
- updated_at
- updated_from
```

This creates fragile client coupling:

- Web can write fields Android does not know.
- Android can fail strict typed decoding before applying known fields.
- A client can overwrite unrelated settings when it sends a full payload.
- v7 path-level conflict tracking must infer ownership from JSON paths inside one document.
- Debugging a stale or dropped setting requires inspecting one large payload instead of a bounded section.

The immediate model-sync bug came from this pattern: web wrote a richer account settings payload, Android decoded the entire settings payload too strictly, and the account pull aborted before applying `integrations.subtitleTranslation.model`.

## Recommended Approach

Use an authoritative section table plus backward-compatible v12 adapter RPCs.

Alternative approaches considered:

- Keep one blob and improve path tracking. This is lower churn, but preserves the core source-of-truth problem.
- Fully normalize into one table per setting type. This gives strong SQL contracts, but adds much more migration and client churn for settings that naturally evolve as JSON objects.

The section table is the middle ground: one row per functional owner, JSONB inside each row.

## V13 Schema

Create `public.account_settings_sections`:

```sql
create table public.account_settings_sections (
  user_id uuid not null references auth.users(id) on delete cascade,
  section_key text not null,
  payload jsonb not null default '{}'::jsonb,
  schema_version integer not null default 1,
  sync_revision bigint not null default 0,
  updated_at timestamptz not null default now(),
  updated_from text not null default 'app',
  primary key (user_id, section_key)
);
```

Enable RLS and owner policies equivalent to the existing account settings table. RPCs should use `security definer` with `search_path = public, pg_temp`, matching the current sync contract pattern.

Add a server-side allowlist/check function for valid section keys. Unknown section keys are rejected on write.

## Section Keys

Use section-per-functional-owner granularity:

```text
integrations.subtitleTranslation
integrations.imdb
integrations.gemini
integrations.tmdb
integrations.omdb
integrations.posterRatings
integrations.animeSkip
integrations.mdblist
integrations.kitsu
integrations.traktAuth
integrations.simklAuth
integrations.kitsuAuth
integrations.debrid.premiumize
integrations.debrid.realDebrid
integrations.debrid.torBox
integrations.debrid.easyDebrid
catalogs.mdblist
catalogs.trakt
catalogs.simkl
catalogs.tmdb
catalogs.kitsu
catalogs.home
playback.streamSelection
formatter
```

Each section row owns only its subtree payload. For example, `integrations.subtitleTranslation` stores:

```json
{
  "enabled": true,
  "provider": "OPENAI",
  "model": "openai/gpt-5.5",
  "baseUrl": "https://openrouter.ai/api/v1"
}
```

It does not store the outer `{ "integrations": { "subtitleTranslation": ... } }` wrapper.

## V13 Pull Contract

Add:

```text
sync_pull_account_snapshot_v13()
sync_pull_account_settings_sections_v13()
```

`sync_pull_account_snapshot_v13()` returns the v12 addons and secrets envelope, but sectioned account settings:

```json
{
  "contract_version": 13,
  "settings": {
    "sections": [
      {
        "section_key": "integrations.subtitleTranslation",
        "payload": {
          "enabled": true,
          "provider": "OPENAI",
          "model": "openai/gpt-5.5"
        },
        "schema_version": 1,
        "sync_revision": 123,
        "updated_at_ms": 1747000000000
      }
    ],
    "updated_at_ms": 1747000000000
  },
  "addons": {
    "items": [],
    "updated_at_ms": 0
  },
  "secrets": {
    "items": [],
    "updated_at_ms": 0
  }
}
```

`settings.updated_at_ms` is the max `updated_at_ms` over the user's section rows, or `0` if no rows exist. Clients should use per-section watermarks for writes; the aggregate value is informational and useful for UI/status.

Absent sections mean "use local/default value". The server should not materialize default section rows during pull.

## V13 Push Contract

Add:

```text
sync_push_account_settings_section_v13(
  p_section_key text,
  p_payload jsonb,
  p_base_updated_at_ms bigint,
  p_source text default 'app'
)

sync_push_account_settings_sections_v13(
  p_sections jsonb,
  p_source text default 'app'
)
```

Single-section push returns the existing push-outcome shape plus section metadata:

```json
{
  "applied": true,
  "section_key": "integrations.subtitleTranslation",
  "sync_revision": 124,
  "current_updated_at_ms": 1747000001000
}
```

Batch push accepts an array of section entries, each with `section_key`, `payload`, and `base_updated_at_ms`. It applies sections independently and returns per-section outcomes:

```json
{
  "applied": false,
  "sections": [
    {
      "section_key": "formatter",
      "applied": true,
      "sync_revision": 124,
      "current_updated_at_ms": 1747000001000
    },
    {
      "section_key": "integrations.tmdb",
      "applied": false,
      "reason": "stale_base",
      "current_updated_at_ms": 1747000002000
    }
  ]
}
```

The top-level `applied` is true only if every submitted section applied. Partial success is allowed and expected.

## Validation Rules

Server-side validation:

- Reject unauthenticated calls.
- Reject unknown `section_key` values.
- Reject payloads that are not JSON objects.
- Reject stale writes when `p_base_updated_at_ms < current section updated_at_ms`.
- Trim/normalize `p_source`; default to `app`.

Per-section schema validation can be added incrementally for high-risk sections. Start with:

- `integrations.subtitleTranslation`
- `playback.streamSelection`

Validation must not require clients to know fields outside the section they are pushing.

## Conflict Handling

Conflict detection is section-scoped:

1. Client pulls section rows and stores per-section watermarks.
2. Client edits one or more sections locally.
3. Client pushes only dirty sections with their own base watermarks.
4. Server rejects only stale sections.
5. Client updates watermarks for applied sections.
6. Client pulls/reconciles stale sections only.

Unrelated sections must not block each other. A stale `formatter` write must not prevent `integrations.subtitleTranslation` from applying.

For a stale section, clients should:

- Pull the latest remote section.
- Apply it to local state.
- If local pending edits remain, either prompt the user for user-edited UI state or re-apply a deterministic transform when safe.

## Backward Compatibility

V12 RPCs remain available during rollout and become adapters over `account_settings_sections`.

### V12 Pull Adapter

The current full-payload pull RPC reads `account_settings_sections`, merges known section rows into the legacy settings object, and returns the normal v12 envelope:

```json
{
  "settings": {
    "payload": {
      "integrations": {
        "subtitleTranslation": {}
      },
      "catalogs": {},
      "playback": {},
      "formatter": {}
    },
    "sync_revision": 123,
    "updated_at_ms": 1747000000000
  }
}
```

The adapter should preserve v12 behavior for addons and secrets.

### V12 Push Adapter

The current full-payload push RPC accepts the legacy full settings payload and explodes known subtrees into section rows. The old `p_changed_paths` can be mapped to affected section keys.

Behavior:

- If a v12 payload contains multiple known sections, each extracted section is written separately.
- If a v12 push has a stale base relative to any target section, return a stale/conflict outcome compatible with existing v12 clients.
- Unknown legacy fields are written to `legacy.unmapped` only if that section is explicitly allowed. Otherwise they remain unsupported compatibility data and must not block known section writes.
- V13 clients never write `account_settings_public.settings_payload`.

During rollout, `account_settings_public` can remain for inspection and rollback, but section rows are authoritative after backfill.

## Migration And Backfill

Migration is additive:

1. Create `account_settings_sections`.
2. Create section-key allowlist/check helpers.
3. Backfill from `account_settings_public.settings_payload`.
4. Add v13 pull/push RPCs.
5. Update v12 pull/push RPCs to adapter behavior.
6. Keep `account_settings_public` for rollback and legacy inspection.

Backfill mapping:

```text
settings_payload.integrations.subtitleTranslation -> integrations.subtitleTranslation
settings_payload.integrations.imdb -> integrations.imdb
settings_payload.integrations.gemini -> integrations.gemini
settings_payload.integrations.tmdb -> integrations.tmdb
settings_payload.integrations.omdb -> integrations.omdb
settings_payload.integrations.posterRatings -> integrations.posterRatings
settings_payload.integrations.animeSkip -> integrations.animeSkip
settings_payload.integrations.mdblist -> integrations.mdblist
settings_payload.integrations.kitsu -> integrations.kitsu
settings_payload.integrations.traktAuth -> integrations.traktAuth
settings_payload.integrations.simklAuth -> integrations.simklAuth
settings_payload.integrations.kitsuAuth -> integrations.kitsuAuth
settings_payload.integrations.debrid.premiumize -> integrations.debrid.premiumize
settings_payload.integrations.debrid.realDebrid -> integrations.debrid.realDebrid
settings_payload.integrations.debrid.torBox -> integrations.debrid.torBox
settings_payload.integrations.debrid.easyDebrid -> integrations.debrid.easyDebrid
settings_payload.catalogs.mdblist -> catalogs.mdblist
settings_payload.catalogs.trakt -> catalogs.trakt
settings_payload.catalogs.simkl -> catalogs.simkl
settings_payload.catalogs.tmdb -> catalogs.tmdb
settings_payload.catalogs.kitsu -> catalogs.kitsu
settings_payload.catalogs.home -> catalogs.home
settings_payload.playback.streamSelection -> playback.streamSelection
settings_payload.formatter -> formatter
```

Do not create rows for absent sections during backfill. Clients merge defaults locally.

For backfilled rows, preserve `sync_revision`, `updated_at`, and `updated_from` from `account_settings_public` where possible.

## Client Design

Clients can keep a composed settings object for UI convenience, but remote sync state must be section-aware:

```text
section_key -> payload
section_key -> updated_at_ms watermark
section_key -> dirty flag
```

### Android

- Add account-settings section watermarks, keyed by section key.
- Decode only known section payloads.
- Apply known sections to the owning DataStores.
- Ignore unknown sections without failing snapshot application.
- Push only dirty sections.
- Keep Settings "Sync Now" pulling the account snapshot and applying known account settings sections.

### Web

- Keep `PortalSettings` as composed UI state if useful.
- Track dirty section keys instead of changed JSON paths.
- Persist only dirty sections through v13 RPCs.
- Bootstrap from section rows, compose `PortalSettings`, and retain unknown remote sections in sync state.
- Stop using full settings-object writes once v13 is enabled.

The v13 client invariant is: no client sends a complete account settings object as a remote write.

## Testing

Supabase tests:

- Backfill splits a legacy payload into expected section rows.
- V13 pull returns sections with independent `updated_at_ms`.
- V13 single-section push updates only that section.
- V13 batch push can partially apply sections.
- Stale base on `formatter` does not block `integrations.subtitleTranslation`.
- V12 pull adapter reconstructs the legacy payload.
- V12 push adapter explodes a legacy payload into section rows.

Android tests:

- V13 snapshot with unknown section does not fail.
- `integrations.subtitleTranslation` model update applies from a section row.
- Dirty section push sends only changed section.
- Stale section pull does not overwrite unrelated local sections.
- Settings "Sync Now" pulls account sections and applies known rows.

Web tests:

- Bootstrap composes `PortalSettings` from sections.
- Persist sends only dirty sections.
- Unknown sections survive bootstrap/persist.
- V12 full-payload fallback is not used once v13 is enabled.

## Observability

Add temporary rollout visibility:

- Source labels: `android-v13`, `web-v13`, `v12-adapter`.
- Per-section stale-conflict logging.
- SQL smoke queries for writes by `section_key`, writes by `updated_from`, and stale conflict frequency.
- A migration verification query comparing legacy payload section count to backfilled row count.

## Rollout

1. Deploy additive Supabase migration and v13 RPCs.
2. Backfill section rows.
3. Switch current v12 full-payload RPCs to adapter behavior.
4. Update Android to read/write v13 sections.
5. Update `nexio-web` to read/write v13 sections.
6. Monitor source labels and stale conflicts.
7. After both clients are stable on v13, plan a later contract to retire v12 adapters and decide the future of `account_settings_public`.

## Open Decisions For Implementation Planning

- Whether to allow a `legacy.unmapped` section. Default recommendation: do not add it unless current production payloads contain meaningful unmapped data after section extraction.
- Whether batch push should execute in one transaction with partial results or process each section independently. Default recommendation: one transaction for the RPC call, but per-section outcomes, with only valid non-stale sections updated.
- Whether v12 adapter push should keep path-level `field_conflict` behavior or collapse to stale-base on affected section conflicts. Default recommendation: preserve compatible `field_conflict` responses where existing clients already branch on them.
