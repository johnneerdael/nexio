# Phase 6: TVDB Foundation and Identity - Discussion Log

**Gathered:** 2026-04-14
**Purpose:** Human-readable audit trail for the discussion that produced `06-CONTEXT.md`.

## Phase Boundary Presented

Phase 6 delivers TVDB configuration, API validation, auth token caching, remote-ID based series identity matching, and explicit fallback diagnostics. It does not replace all TMDB TV metadata yet; that starts in Phase 7.

## Gray Areas Selected

The user selected all proposed gray areas:

1. Credential model
2. Settings shape
3. Fallback diagnostics
4. Identity matching scope
5. Token/cache policy

## Questions and Decisions

### Credential Model

**Question:** Which credential modes should Phase 6 support?

**Options presented:**
1. API key only for now - simplest first pass.
2. API key + optional PIN - supports TVDB subscriber/user-supported key flows now.
3. App-level key path - users only enable TVDB, but licensing/secret-management questions are larger.
4. Hybrid: user key first, app key later - implement user API key now, keep app-level path open.

**Recommendation presented:** Option 2, because TVDB `/login` explicitly supports `apikey` and optional `pin`.

**User selected:** Option 2.

**Captured decision:** TVDB supports API key plus optional PIN in Phase 6, with secret-backed storage/sync and no public payload exposure of credentials.

### Settings Shape

**Question:** What should the Phase 6 TVDB settings shape be?

**Options presented:**
1. Simple foundation screen - enable TVDB, API key, optional PIN, validation state, and precedence copy.
2. Mirror TMDB toggles now - per-surface toggles for artwork, details, credits, trailers, etc.
3. Simple screen + advanced collapsed section - visible foundation controls plus future-facing advanced controls.
4. Agent discretion.

**Recommendation presented:** Option 1, because Phase 6 is foundation/identity and provider surface toggles belong to Phase 7+ if needed.

**User selected:** Option 1.

**Captured decision:** Phase 6 creates a simple TVDB foundation screen and defers TMDB-style per-feature toggles.

### Fallback Diagnostics

**Question:** How visible should TVDB fallback diagnostics be in Phase 6?

**Options presented:**
1. Settings status + logs - status in TVDB settings plus developer logs.
2. Logs only - minimal UI.
3. Settings status + debug diagnostics screen/state - basic status plus debug-only detail.
4. Toast/snackbar on fallback - immediate visible warnings.

**Recommendation presented:** Option 1, because it satisfies observability without making normal browsing noisy.

**User selected:** Option 1.

**Captured decision:** Phase 6 fallback observability is settings status plus logs. Deeper provider-choice diagnostics are deferred to Phase 10.

### Identity Matching Scope

**Question:** What remote-ID matching scope should Phase 6 lock in?

**Options presented:**
1. Minimum: TVDB + IMDb + TMDB remote IDs.
2. Broad: TVDB + IMDb + TMDB + TV Maze + Wikidata + official site.
3. TVDB ID only first.
4. Agent discretion.

**Recommendation presented:** Option 2, because live TVDB validation showed those remote IDs are available and useful for avoiding TMDB identity lookup.

**User selected:** Option 2.

**Captured decision:** Phase 6 supports broad TVDB identity matching: TVDB, IMDb, TMDB, TV Maze, Wikidata, and official-site IDs.

### Token and Foundation Cache Policy

**Question:** How should TVDB token and foundation cache policy work in Phase 6?

**Options presented:**
1. Persist bearer token with expiry metadata - reuse across app restarts and refresh before/after expiry.
2. Memory-only token cache - simpler, but logs in again after app restart.
3. Persist token but no explicit expiry - reuse until rejected.
4. Agent discretion.

**Recommendation presented:** Option 1, because it satisfies the requirement to avoid repeated authentication.

**User selected:** Option 1.

**Captured decision:** Persist TVDB bearer token with expiry metadata; foundation caching covers auth token state and identity lookup results.

## Deferred Ideas

- App-level TVDB key path.
- Per-surface TVDB metadata toggles.
- Full provider-choice diagnostics across all metadata paths.
- TVDB `/updates`-driven metadata invalidation.

---

*Phase: 06-tvdb-foundation-and-identity*
*Discussion logged: 2026-04-14*
