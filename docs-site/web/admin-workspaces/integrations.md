# Integrations: Service Graph and Validation Guide

## Audience
This page is for users who want enterprise-grade reliability from connected services and need a clear validation sequence.

## What this page covers
- Integration topology and dependency order
- Trakt device flow behavior
- Runtime verification strategy per service class
- Failure isolation and recovery patterns

## Source of truth
Integration behavior is represented in:
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktScreen.kt`

## Integration topology
Nexio integration stack is layered:
1. **Identity and state sync layer**: Trakt
2. **Stream availability layer**: Debrid providers
3. **Metadata enrichment layer**: TMDB and MDBList
4. **Optional enhancement layer**: Anime Skip, Gemini, poster ratings

This order is intentional. Each upper layer benefits from a stable lower layer.

## Settings integration hub architecture
Integrations are split into explicit sub-sections to keep state transitions isolated:
- Hub
- Debrid
- Trakt
- TMDB
- MDBList
- Anime Skip
- Gemini
- Poster ratings

Use this to enable and validate one service category at a time.

## Trakt device flow model
Trakt uses a TV-safe device authorization pattern with:
- Device user code
- QR activation path
- Waiting state with countdown
- Connected state with account context and token refresh timing

This enables secure sign-in without on-TV credential entry.

## Recommended bring-up order

### 1. Connect Trakt
Establish account identity and sync baseline first.

**Expected result:** Connected state with username and account indicators.

### 2. Connect Debrid provider
Enable stream availability improvements after identity baseline is stable.

**Expected result:** Improved stream option quality and cache hit likelihood.

### 3. Enable metadata enrichers
Configure TMDB and MDBList for richer detail and ranking context.

**Expected result:** Detail and browsing surfaces show enhanced metadata depth.

### 4. Add optional enhancements
Enable Anime Skip, Gemini, or poster rating overlays last.

**Expected result:** Optional features are additive and easy to disable if noisy.

## Validation matrix
After each integration change, validate one runtime signal:
- **Trakt:** watched or progress state reflects expected account behavior
- **Debrid:** stream list quality and availability improve
- **Metadata enrichers:** detail context and ranking metadata become richer
- **Optional features:** only the targeted enhancement appears

## Troubleshooting

### Symptom
Integration appears connected but no visible runtime impact.

### Likely root causes
- Connected with wrong account identity
- Token state stale or expired
- Dependency layer below it not configured yet

### Recovery
1. Disconnect the affected integration.
2. Reconnect with the correct account.
3. Re-validate lower layer dependencies first.
4. Re-test one runtime signal.

### Verification
A specific, expected in-app behavior now matches the connected integration.

## Operational guidance
Avoid enabling every integration in one pass.
Use incremental rollout with verification checkpoints.
This is the fastest path to a stable high-end setup.

## Next page
Continue with [Custom Formatter Getting Started](./formatter-getting-started.md) to present stream intelligence cleanly once integration data is stable.
