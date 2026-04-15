---
phase: 06-tvdb-foundation-and-identity
phase_number: 06
phase_name: tvdb-foundation-and-identity
status: secured
asvs_level: 1
block_on: open_threats
threats_total: 21
threats_closed: 21
threats_open: 0
audited_at: "2026-04-15T04:55:44+02:00"
auditor: gsd-security-auditor
---

# Phase 06 Security Verification

## Scope

Verified only the STRIDE threats declared in the Phase 06 PLAN.md threat models and the threat register supplied for this audit. No unrelated vulnerability scan was performed. Implementation files were read-only for this audit; only this SECURITY.md file was created.

## Result

| Metric | Count |
|--------|-------|
| Registered threats | 21 |
| Closed threats | 21 |
| Open threats | 0 |
| Accepted risks | 1 |
| Transfer risks | 0 |
| Unregistered threat flags | 0 |

## Threat Verification

| Threat ID | Category | Component | Disposition | Status | Evidence |
|-----------|----------|-----------|-------------|--------|----------|
| T-06-01-01 | Information Disclosure | TvdbAuthServiceTest | mitigate | CLOSED | `TvdbAuthServiceTest.kt:22`, `TvdbAuthServiceTest.kt:39`, `AccountConfigSyncContractTest.kt:164`, `AccountConfigSyncContractTest.kt:165`, `AccountConfigSyncContractTest.kt:166` verify blank PIN omission and public JSON credential omission. Test credentials are placeholders such as `tvdb-key` and `subscriber-pin`. |
| T-06-01-02 | Denial of Service | TvdbIdentityServiceTest | mitigate | CLOSED | `TvdbIdentityServiceTest.kt:32` starts concurrent lookup against the same deferred remote call; `TvdbIdentityServiceTest.kt:51` verifies exactly one `searchByRemoteId` call. |
| T-06-01-03 | Tampering | TvdbIdentityServiceTest | mitigate | CLOSED | `TvdbIdentityServiceTest.kt:56`, `TvdbIdentityServiceTest.kt:75`, `TvdbIdentityServiceTest.kt:76`, `TvdbIdentityServiceTest.kt:79`, `TvdbIdentityServiceTest.kt:81`, and `TvdbIdentityServiceTest.kt:87` require broad remote-ID preservation using a series record. |
| T-06-01-04 | Information Disclosure | AccountConfigSyncContractTest | mitigate | CLOSED | `AccountConfigSyncContractTest.kt:237`, `AccountConfigSyncContractTest.kt:269`, `AccountConfigSyncContractTest.kt:270`, and `AccountConfigSyncContractTest.kt:271` assert TVDB public sync omits `apiKey`, `pin`, and `token`. |
| T-06-01-05 | Tampering | TvdbSecretAllowlistStaticTest | mitigate | CLOSED | `TvdbSecretAllowlistStaticTest.kt:26`, `TvdbSecretAllowlistStaticTest.kt:29`, `TvdbSecretAllowlistStaticTest.kt:38`, and `TvdbSecretAllowlistStaticTest.kt:42` enforce at least eight `tvdb_api_key` allowlist entries and require every checked `secret_type in` block to include it. |
| T-06-02-01 | Information Disclosure | TvdbAuthService | mitigate | CLOSED | `TvdbAuthService.kt:187` to `TvdbAuthService.kt:189` sends blank PIN as null. Negative grep for `Log.[dwe].*(apiKey|pin|token|Authorization|request body)` in TVDB auth/fallback/identity files returned no matches. |
| T-06-02-02 | Denial of Service | TvdbAuthService | mitigate | CLOSED | `TvdbAuthService.kt:47` defines the refresh `Mutex`; `TvdbAuthService.kt:84` wraps token refresh in `withLock`. |
| T-06-02-03 | Spoofing | TvdbAuthService | mitigate | CLOSED | `TvdbAuthService.kt:103` clears stale token state, `TvdbAuthService.kt:105` saves `INVALID`, and `TvdbAuthService.kt:205` treats HTTP 401 as invalid credentials. |
| T-06-02-04 | Information Disclosure | TvdbTokenStore | accept | CLOSED | Accepted risk documented below. `TvdbTokenStore.kt:18` to `TvdbTokenStore.kt:22` stores token as local app state; public sync tests at `AccountConfigSyncContractTest.kt:166` and `AccountConfigSyncContractTest.kt:271` omit `token`. |
| T-06-03-01 | Tampering | TvdbIdentityService | mitigate | CLOSED | `TvdbIdentityService.kt:97` filters remote-ID search results to `result.series != null` before accepting a match. |
| T-06-03-02 | Spoofing | TvdbRemoteIdNormalizer | mitigate | CLOSED | `TvdbRemoteIdNormalizer.kt:21` to `TvdbRemoteIdNormalizer.kt:29` normalize known source names and preserve unknown sources as `OTHER`. |
| T-06-03-03 | Denial of Service | TvdbIdentityService | mitigate | CLOSED | `TvdbIdentityService.kt:25` uses an in-flight `ConcurrentHashMap<String, CompletableDeferred<...>>`; `TvdbIdentityService.kt:30`, `TvdbIdentityService.kt:49`, `TvdbIdentityService.kt:38`, and `TvdbIdentityService.kt:71` read/write persisted cache around network calls. |
| T-06-03-04 | Information Disclosure | TvdbProviderFallback | mitigate | CLOSED | `TvdbProviderFallback.kt:33`, `TvdbProviderFallback.kt:38`, and `TvdbProviderFallback.kt:41` to `TvdbProviderFallback.kt:47` sanitize to reason codes before logging. Negative grep for credential names, bearer tokens, Authorization, and URL patterns in `TvdbProviderFallback.kt` returned no matches. |
| T-06-04-01 | Information Disclosure | AccountSyncModels | mitigate | CLOSED | `AccountSyncModels.kt:270` to `AccountSyncModels.kt:275` define public `TvdbSyncSettings` without credential fields; `AccountConfigSyncContractTest.kt:269` to `AccountConfigSyncContractTest.kt:271` assert absence of `apiKey`, `pin`, and `token`. |
| T-06-04-02 | Elevation of Privilege | AccountSettingsSyncService | mitigate | CLOSED | `AccountSettingsSyncService.kt:116` and `AccountSettingsSyncService.kt:117` define fixed `tvdb_api_key` and `integration:tvdb`; `AccountSettingsSyncService.kt:910` to `AccountSettingsSyncService.kt:920` use existing authenticated secret RPC set path; `AccountSettingsSyncService.kt:1165` to `AccountSettingsSyncService.kt:1171` use the resolve path. |
| T-06-04-03 | Tampering | supabase/account_settings_sync.sql | mitigate | CLOSED | `supabase/account_settings_sync.sql:17`, `supabase/account_settings_sync.sql:41`, `supabase/account_settings_sync.sql:302`, `supabase/account_settings_sync.sql:348`, `supabase/account_settings_sync.sql:391`, `supabase/account_settings_sync.sql:438`, `supabase/account_settings_sync.sql:484`, and `supabase/account_settings_sync.sql:527` include `tvdb_api_key` in checked allowlists. |
| T-06-04-04 | Information Disclosure | docs/settings/settings-sync.schema.json | mitigate | CLOSED | `settings-sync.schema.json:111` to `settings-sync.schema.json:123` expose only public TVDB fields. Negative grep for `apiKey|pin|token` in the schema returned no matches. |
| T-06-05-01 | Information Disclosure | TvdbSettingsScreen | mitigate | CLOSED | `TvdbSettingsViewModel.kt:176` to `TvdbSettingsViewModel.kt:181` masks the API key display and omits PIN from UI state; `TvdbSettingsScreen.kt:200` keeps PIN dialog state local. |
| T-06-05-02 | Spoofing | TvdbSettingsViewModel | mitigate | CLOSED | `TvdbSettingsViewModel.kt:116` validates credentials through `TvdbAuthService`; `TvdbSettingsViewModel.kt:121` records valid status; `TvdbSettingsViewModel.kt:61` to `TvdbSettingsViewModel.kt:62` enables TVDB only when validation status is `VALID`. |
| T-06-05-03 | Denial of Service | TvdbSettingsViewModel | mitigate | CLOSED | `TvdbSettingsViewModel.kt:83` to `TvdbSettingsViewModel.kt:85` returns early while validation status is `VALIDATING`, ignoring duplicate save actions. |
| T-06-05-04 | Information Disclosure | Settings feedback | mitigate | CLOSED | `TvdbSettingsScreen.kt:152` and `TvdbSettingsScreen.kt:157` to `TvdbSettingsScreen.kt:163` render settings-local validation/status feedback. Negative grep for `Toast|Snackbar` in TVDB settings/fallback files returned no matches. |

## Accepted Risks Log

| Threat ID | Accepted Risk | Justification | Scope | Status |
|-----------|---------------|---------------|-------|--------|
| T-06-02-04 | TVDB bearer token is stored in local app state. | The plan explicitly accepts this because the token follows the existing local DataStore credential pattern, while remote account sync remains secret-backed and public sync omits `token`. The token is not serialized into public sync JSON or schema. | Local app storage only; remote sync uses `tvdb_api_key` secret channel. | CLOSED |

## Unregistered Flags

None. `06-05-SUMMARY.md` contains `## Threat Flags` with `None`; the other Phase 06 summaries contain no `## Threat Flags` entries.

## Audit Trail

| Time | Action | Result |
|------|--------|--------|
| 2026-04-15T04:55:44+02:00 | Loaded all files listed in the audit prompt, including PLAN/SUMMARY/REVIEW/VERIFICATION artifacts, tests, implementation files, schema, SQL, UI, and strings. | Complete |
| 2026-04-15T04:55:44+02:00 | Extracted threat models from `06-01-PLAN.md` through `06-05-PLAN.md` and compared them to the supplied threat register. | 21 registered threats |
| 2026-04-15T04:55:44+02:00 | Classified dispositions. | 20 mitigations, 1 accepted risk, 0 transfers |
| 2026-04-15T04:55:44+02:00 | Verified mitigation patterns only in declared files and components using targeted reads and greps. | 21 closed, 0 open |
| 2026-04-15T04:55:44+02:00 | Incorporated SUMMARY threat flags. | No unregistered flags |
| 2026-04-15T04:55:44+02:00 | Wrote Phase 06 security register. | `threats_open: 0` |

## Notes

- `06-REVIEW.md` contains three warning-level implementation findings. They are verification debt in `06-VERIFICATION.md`, but they are not new `## Threat Flags` from SUMMARY.md and were not added to this threat register under the audit scope.
- No implementation files were modified during this security audit.
