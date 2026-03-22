## 1. Supabase Contract
- [ ] 1.1 Add `p_contract_version` support to the settings push and snapshot pull RPCs.
- [ ] 1.2 Normalize legacy and v2 payloads into one canonical stored account-config shape with a
      preserved legacy compatibility sidecar.
- [ ] 1.3 Synthesize contract-v1 snapshot responses from canonical plus compatibility data and
      reject unknown contract versions.

## 2. Android Contract-v2 Client
- [ ] 2.1 Introduce contract-v2 synced settings models for integrations and catalog configuration.
- [ ] 2.2 Narrow `AccountSettingsSyncService` observation, payload building, and remote apply logic
      so local-only settings are no longer synced.
- [ ] 2.3 Update Android settings push/pull calls to request `contract_version = 2` while keeping
      addon sync behavior unchanged.

## 3. Validation
- [ ] 3.1 Add targeted coverage for Supabase normalization and compatibility behavior.
- [ ] 3.2 Add Android tests for v2 payload building, remote apply filtering, and reduced automatic
      push observation scope.
- [ ] 3.3 Verify startup snapshot pull still reconciles addons correctly after the settings contract
      change.
