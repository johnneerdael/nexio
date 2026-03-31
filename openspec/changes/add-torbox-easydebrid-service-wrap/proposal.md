# Change: Add TorBox and EasyDebrid service wrap support

## Why
Nexio's Service Wrap runtime currently supports only Real-Debrid and Premiumize. The approved expansion adds TorBox and EasyDebrid as first-class providers across Android and the portal, while keeping other requested providers in a documented discovery-only state until their public contracts are confirmed.

## What Changes
- Add TorBox and EasyDebrid to the account-config sync contract, secret sync allowlists, Android settings, and portal integrations.
- Refactor Android Service Wrap provider handling so TorBox and EasyDebrid can validate cache status and resolve direct playback links in parallel with Real-Debrid and Premiumize.
- Add portal-side credential validation for TorBox and EasyDebrid before storing secrets.
- Document the confirmed and deferred provider contracts in the same change set.

## Impact
- Affected apps: `app`, `nexio-web`
- Affected sync contract: account-config v4
- Affected specs: `account-config-sync`, `service-wrap`
- Affected data model: Supabase secret allowlist and account secret refs
