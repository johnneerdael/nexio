---
phase: 01
slug: 01-foundation
status: verified
threats_open: 0
asvs_level: 1
created: 2026-04-14
---

# Phase 01 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| None in Phase 1 | All new code is local DataStore infrastructure — no network, no user input, no IPC | No external data crosses a trust boundary; all data originates and stays within app-private storage |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-01-01 | Tampering | ProfileDataStoreFactory file naming | accept | File names derived from hardcoded integer profile IDs (1–4) only; no user-supplied string reaches path construction. See Accepted Risks Log. | closed |
| T-01-02 | Information Disclosure | DataStore .preferences_pb files on device | accept | Files reside in app-private Context.filesDir/datastore/. Android sandbox prevents cross-app access. No PII stored in Phase 1. See Accepted Risks Log. | closed |
| T-01-03 | Tampering | ProfileDataStore JSON persistence | accept | Corruption handled via silent fallback to defaultPrimaryProfile() in parseProfiles() try/catch — no crash, no data leak. Physical device access required to tamper. See Accepted Risks Log. | closed |
| T-01-04 | Denial of Service | ProfileManager.createProfile | mitigate | `if (current.size >= 4) return false` guard enforced at ProfileManager.kt:80; ID range limited to (2..4) at line 83. No unbounded resource allocation possible. | closed |
| T-01-05 | Information Disclosure | Profile names in DataStore files | accept | Profile names stored in app-private storage only. No PII beyond user-chosen display names. Android sandbox prevents cross-app read. See Accepted Risks Log. | closed |
| T-01-06 | Tampering | ProfileManager.deleteProfile file cleanup | accept | File deletion suffix `_p{id}.preferences_pb` constructed from integer profile ID (1–4 range) only. No user input reaches path construction. No path traversal risk. See Accepted Risks Log. | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-01-01 | T-01-01 | Profile IDs are integer constants (1–4), not user input. File name construction is `if (profileId == 1) featureName else "${featureName}_p${profileId}"` — featureName is always a hardcoded literal at call sites. No injection surface exists in Phase 1. | gsd-security-auditor | 2026-04-14 |
| AR-01-02 | T-01-02 | DataStore files are written to Context.filesDir/datastore/ which is app-private by Android OS policy (mode 0700). No PII is stored in Phase 1 — only profile names (user-chosen display strings, added in Phase 3) and avatar color hex codes. | gsd-security-auditor | 2026-04-14 |
| AR-01-03 | T-01-03 | Tampered or corrupted JSON in profile_settings DataStore falls back silently to a single default Profile 1 ("Default", "#1E88E5") via try/catch in parseProfiles(). No crash, no data leak, no privilege escalation. Physical device access (rooted or ADB) is required to write the DataStore file, which is outside the Android threat model for production devices. | gsd-security-auditor | 2026-04-14 |
| AR-01-05 | T-01-05 | Profile names are user-chosen display strings with no authentication or authorization significance. They are stored only in app-private DataStore and are not transmitted in Phase 1. Android sandbox prevents cross-app read without root. | gsd-security-auditor | 2026-04-14 |
| AR-01-06 | T-01-06 | deleteProfileDataAsync constructs the deletion suffix as `"_p${profileId}.preferences_pb"` where profileId is always an integer returned by the (2..4) slot range — never a user-supplied value. The scan is limited to context.filesDir/datastore/ (app-private). No path traversal is possible with a bounded integer suffix. | gsd-security-auditor | 2026-04-14 |

---

## Unregistered Threat Flags

| Source | Flag | Maps To | Notes |
|--------|------|---------|-------|
| 01-01-SUMMARY.md | None | — | Executor reported no threat flags; consistent with plan threat model. |
| 01-02-SUMMARY.md | None | — | Executor noted T-01-03/T-01-04/T-01-06 addressed inline but raised no new unregistered flags. |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-04-14 | 6 | 6 | 0 | gsd-security-auditor (acb06d534ded7a8fa) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-04-14
