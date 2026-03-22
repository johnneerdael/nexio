# Account Config Sync Scope Design

## Context

Nexio currently treats the Supabase account settings blob as a broad cross-device profile. Android
pushes and pulls `appearance`, `layout`, `integrations`, `playback`, `trakt`, and `debug`, while
addons are synced through a separate addon RPC. That model is too wide for the desired ownership
boundary. Many of those settings are device-local choices and should not move between TVs or
between the app and the portal.

The target architecture is narrower:

- synced account-owned data:
  - integrations configuration
  - catalog configuration and ordering
  - addons
- local device-owned data:
  - appearance
  - non-catalog layout presentation settings
  - playback
  - non-catalog Trakt behavior settings
  - debug flags

There is an additional rollout constraint: older released clients still expect the legacy wide
payload and should remain functional during the transition.

The portal is being updated in parallel and is not the primary implementation scope for this plan.
This design focuses on Android and the Supabase contract.

## Goals

- Narrow the canonical synced account settings contract to integrations plus catalog configuration.
- Keep addon syncing on the existing separate addon channel.
- Preserve backward compatibility for older clients that still read and write the legacy wide
  payload.
- Ensure new clients never apply or overwrite local-only settings from remote state.
- Avoid a one-time destructive database migration for existing `account_settings_public` rows.

## Non-Goals

- Redesign the addon sync transport or secret storage flow.
- Migrate local-only settings from one device to another after the new contract ships.
- Force every older client to upgrade before the contract change can be deployed.
- Rename every "settings sync" symbol in the codebase in the same change.

## Canonical Payload

The canonical stored settings payload should move to a contract-v2 shape centered on account-owned
configuration:

```json
{
  "schemaVersion": 2,
  "integrations": {
    "...": "existing synced integration state"
  },
  "catalogs": {
    "home": {
      "heroCatalogKeys": [],
      "homeCatalogOrderKeys": [],
      "disabledHomeCatalogKeys": []
    },
    "trakt": {
      "catalogEnabledSet": [],
      "catalogOrder": [],
      "selectedPopularListKeys": []
    },
    "mdblist": {
      "hiddenPersonalListKeys": [],
      "selectedTopListKeys": [],
      "catalogOrder": []
    }
  },
  "legacyV1": {
    "...": "compatibility-only local-only fields for older clients"
  }
}
```

`legacyV1` is not part of the canonical v2 contract. It exists only so legacy clients can keep
reading and writing the dropped fields during rollout without forcing those fields back into new
clients.

## Decisions

### 1. Use a versioned RPC contract with defaults that preserve old callers

`sync_push_account_settings` and `sync_pull_account_snapshot` should gain an optional
`p_contract_version` parameter defaulting to `1`.

- v1 callers:
  - keep using the legacy wide payload shape
  - continue to read and write compatibility fields
- v2 callers:
  - read and write only the narrow canonical contract

This keeps the existing RPC names stable while allowing the new contract to roll out client by
client.

### 2. Store canonical synced data separately from legacy compatibility data

The server should normalize writes into one canonical v2 payload and preserve compatibility-only
fields in a `legacyV1` sidecar.

That gives the system one source of truth for:

- integrations
- home catalog ordering/visibility keys
- Trakt catalog preferences
- MDBList catalog preferences

Old clients can still exchange local-only fields among themselves during rollout, but those fields
stop being part of the canonical synced account model.

### 3. Preserve legacy compatibility data on v2 writes

New clients must not wipe older clients' compatibility state. A v2 write should update only the
canonical synced slices and preserve the existing `legacyV1` sidecar from storage.

That allows:

- a new client to update integrations or catalog order
- an old client to keep seeing its existing appearance/playback/debug state
- older clients to continue functioning until they are upgraded

### 4. Support lazy normalization of existing legacy rows

Existing `account_settings_public.settings_payload` rows are already stored in the legacy wide
shape. The rollout should not require a one-off migration.

The Supabase layer should:

- detect whether a stored row is legacy-shaped or canonical-v2-shaped
- derive canonical v2 data from a legacy-shaped row on reads
- normalize the row into canonical-v2-plus-legacy-sidecar on the next write

This keeps deployment simpler and avoids a bulk rewrite step.

### 5. New Android clients should sync only account-owned stores

Android contract-v2 behavior should:

- stop observing local-only stores for automatic remote pushes
- stop building local-only sections into the pushed payload
- stop applying local-only sections from remote pulls
- continue syncing addons separately as today

`AccountSettingsSyncService` remains the orchestration point, but its responsibility narrows to
account-owned config rather than general app settings.

## Component Changes

### Supabase

- Add `p_contract_version integer default 1` to the settings push and snapshot pull RPCs.
- Add JSON normalization helpers that can:
  - extract canonical v2 data from a legacy or v2-shaped row
  - extract legacy compatibility data from a legacy or v2-shaped row
  - synthesize a v1 response payload from canonical plus compatibility data
  - synthesize a v2 response payload from canonical data only
- Keep `account_settings_public.settings_payload` as `jsonb`; no table redesign is required.

### Android

- Introduce a contract-v2 payload model for synced settings.
- Narrow `AccountSettingsSyncService.observeLocalChanges()` to integrations and catalog-related
  flows only.
- Narrow local payload building and remote apply logic to the canonical synced slices.
- Pass `p_contract_version = 2` on push and pull.
- Leave `AddonSyncService`, addon reconciliation, and addon secret handling unchanged.

### Web Portal

- The portal is already being updated in parallel to use the narrowed synced model.
- This design assumes the portal will also use `p_contract_version = 2` and will not depend on the
  legacy compatibility envelope.

## Data Flow

### v1 write

1. Legacy client pushes the wide payload with no explicit contract version.
2. Server treats it as contract v1.
3. Server extracts canonical synced slices into the v2 storage shape.
4. Server extracts local-only compatibility fields into `legacyV1`.
5. Server stores the normalized row and publishes the sync event.

### v2 write

1. New client pushes only integrations plus catalog config with `p_contract_version = 2`.
2. Server loads any existing row and preserves the current `legacyV1` sidecar.
3. Server replaces only the canonical synced slices.
4. Server stores the merged canonical row and publishes the sync event.

### v1 read

1. Legacy client pulls the snapshot with no explicit contract version.
2. Server reads the stored row.
3. Server derives canonical synced slices and compatibility-only fields.
4. Server synthesizes the legacy wide payload shape expected by the old client.
5. Snapshot addons remain sourced from the existing addon table and addon RPC flow.

### v2 read

1. New client pulls the snapshot with `p_contract_version = 2`.
2. Server reads the stored row, regardless of whether it is legacy-shaped or normalized.
3. Server derives the canonical synced slices.
4. Server returns only the narrowed v2 payload.

## Error Handling

- Unknown contract versions should fail fast with a clear RPC error rather than silently falling
  back to another shape.
- Missing sections in a legacy or canonical payload should normalize to empty/default synced
  sections rather than failing snapshot reads.
- Malformed legacy-only compatibility data should not block canonical v2 reads; the server should
  prefer a usable canonical response for new clients.

## Testing

- Supabase contract tests or validation queries for:
  - legacy row -> v2 read normalization
  - legacy row -> v1 read synthesis
  - v1 write normalization into canonical plus compatibility sidecar
  - v2 write preserving `legacyV1`
  - invalid contract version rejection
- Android unit tests for:
  - v2 payload building only includes integrations and catalog config
  - remote apply logic leaves appearance/playback/debug/local-only Trakt settings untouched
  - automatic push observation no longer reacts to local-only datastore changes
  - startup pull still reconciles addons correctly

## Rollout Plan

1. Deploy the Supabase RPC compatibility layer first.
2. Ship the narrowed portal and Android clients on contract v2.
3. Leave legacy compatibility in place until older clients are sufficiently phased out.
4. Remove `legacyV1` and the v1 synthesis path in a later cleanup change once compatibility is no
   longer required.

## Risks / Trade-offs

- Risk: legacy compatibility logic increases SQL complexity.
  - Mitigation: keep one canonical stored shape and isolate compatibility in dedicated helpers.
- Risk: a v2 write could accidentally erase legacy compatibility state.
  - Mitigation: explicitly merge with stored `legacyV1` on every v2 write.
- Risk: old clients continue syncing local-only settings among themselves longer than desired.
  - Mitigation: accept that as the cost of backward compatibility and remove the path later.
