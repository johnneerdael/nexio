## Context
The current account sync contract stores and transports a wide settings payload that mixes
account-owned configuration with device-local settings. We want account sync to cover integrations,
catalog configuration/order, and addons only, but we cannot break older clients that still expect
the legacy wide payload.

## Goals / Non-Goals
- Goals:
  - Make integrations and catalog configuration the only canonical synced settings.
  - Keep addons on the existing dedicated sync path.
  - Preserve backward compatibility for legacy clients during rollout.
  - Avoid a one-time migration of existing `account_settings_public` rows.
- Non-Goals:
  - Redesign addon secret storage or addon sync transport.
  - Move local-only settings between devices after the new contract ships.
  - Rename every settings-sync symbol in the same change.

## Decisions
- Decision: Add an optional `p_contract_version` parameter to the settings push and snapshot pull
  RPCs, defaulting to `1`.
  - Rationale: keeps old callers working while allowing v2 clients to opt into the narrowed
    contract.
- Decision: Store one canonical v2 payload plus a compatibility-only legacy sidecar.
  - Rationale: canonical synced data stays narrow while older clients can still read/write the
    legacy fields they expect.
- Decision: Use `catalogs.home` for the synced home-catalog ordering and visibility keys and
  `legacyV1` for the compatibility sidecar key.
  - Rationale: both names are explicit about ownership and avoid overloading `layout`.
- Decision: Preserve the stored legacy sidecar on every v2 write.
  - Rationale: a new client updating integrations or catalog order must not erase older clients'
    compatibility state.
- Decision: Lazily normalize existing legacy rows when they are read or next written.
  - Rationale: avoids a bulk database rewrite and keeps rollout simpler.
- Decision: Narrow Android sync observation/build/apply behavior to integrations and catalog-related
  settings only.
  - Rationale: new clients should stop syncing local-only settings in practice, not just in theory.

## Alternatives Considered
- Hard cut to the narrowed contract with no compatibility path.
  - Rejected because older clients would decode or apply the wrong payload shape.
- Dual-store two independent settings rows for old and new contracts.
  - Rejected because it adds more long-lived drift risk than a canonical payload with a
    compatibility sidecar.
- Keep the current wide stored payload and simply ignore fields on new clients.
  - Rejected because that does not actually narrow the architecture or the backend ownership
    boundary.

## Risks / Trade-offs
- Risk: SQL compatibility helpers become difficult to reason about.
  - Mitigation: isolate canonical extraction and legacy synthesis in dedicated helpers.
- Risk: v2 writes overwrite legacy compatibility fields.
  - Mitigation: always merge with stored compatibility data before persistence.
- Risk: legacy clients keep exchanging local-only fields during rollout.
  - Mitigation: accept that temporary behavior as the price of backward compatibility and remove it
    later.

## Migration Plan
1. Add versioned settings RPC support in Supabase.
2. Implement canonical extraction and legacy synthesis helpers that work for both legacy-stored and
   normalized rows.
3. Update Android to push and pull contract v2 and to ignore local-only settings in sync logic.
4. Validate addon snapshot reconcile still behaves the same.
5. Remove the compatibility path in a later cleanup change once legacy clients are no longer
   required.

## Rollback Plan
- Keep the legacy contract path intact so callers can continue using v1 if v2 rollout finds issues.
- Revert Android to v1 calls if the narrowed contract causes unexpected sync regressions.
- Preserve the current addon sync path throughout so rollback does not require addon contract
  changes.
