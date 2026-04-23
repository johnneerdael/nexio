## Context
Today the TV QR approval flow records a `linked_devices` row but returns a normal Supabase owner session to the Android TV client. The TV then behaves like any other Supabase client that happens to have received a refresh token. This couples long-term TV trust to normal Supabase session persistence rather than to a first-class device credential. When startup hydration fails, local storage is corrupted, refresh-token rotation trips, or a device is upgraded into an empty auth store, Nexio can lose the session even though the device was previously approved. The current linked-device model also gives the portal a weak revoke story because the row is metadata rather than the true authority for future session minting.

## Goals / Non-Goals
- Goals:
  - Let an approved Android TV or Android device silently regain a real Supabase owner session on cold start without user reauthentication.
  - Make device approval durable across normal upgrades and local session-store loss.
  - Preserve backward compatibility for already approved TVs as far as a still-live legacy session allows, instead of silently orphaning them at rollout.
  - Make portal device revocation the authoritative control that blocks future session issuance for that TV.
  - Keep Supabase as the issuer of normal short-lived access and refresh tokens used by the app after recovery.
  - Preserve per-device visibility and revocation in `nexio-web`.
  - Persist a stable recognizable display name for each approved device so users can identify old TVs later.
- Non-Goals:
  - Replacing Supabase Auth with a custom identity provider.
  - Guaranteeing immediate invalidation of already-issued access JWTs beyond normal JWT expiry semantics.
  - Supporting unlimited offline authorization without any server contact.
  - Building a generic OAuth client registry or third-party device-auth platform.

## Decisions
- Decision: Add a Nexio-owned durable device credential separate from the Supabase refresh token.
  - Rationale: Supabase sessions are designed as rotating refresh-token chains, not as the permanent source of truth for “this TV stays trusted forever.”

- Decision: Keep Supabase as the session issuer after durable credential validation.
  - Rationale: the app already depends on Supabase JWT/RLS semantics, so the durable layer should restore that normal session rather than bypass it.

- Decision: Store only a hash of the durable credential server-side and a single retrievable copy on the approved device.
  - Rationale: this keeps the durable credential revocable and minimizes blast radius if application tables are exposed.

- Decision: Treat portal revoke as revoking the durable credential, not just deleting linkage metadata.
  - Rationale: the credential must be the real authority for future TV session issuance or the revoke surface remains weak.

- Decision: Keep `linked_devices` as user-facing/device-metadata inventory, but back it with a new credential authority model.
  - Rationale: existing UI and ownership concepts remain useful even if credential issuance moves elsewhere.

- Decision: Startup recovery should distinguish identity continuity from sync/session continuity.
  - Rationale: the app may show the previously approved account identity while restore is in progress, but sync must resume only after a real Supabase session is successfully minted.

- Decision: Persist a stable display name chosen at approval time and treat it as the primary human-readable device label.
  - Rationale: model/platform strings alone are weak identifiers in households with multiple similar TVs or sticks, and revocation UX depends on the user recognizing the correct device.

- Decision: Do not support automatic metadata-only legacy durable-credential backfill in this rollout.
  - Rationale: owner session plus legacy device metadata does not prove that the current TV is the previously approved device, so silently minting durable authority across that boundary is unsafe.

## Architecture
- `device_credentials` table
  - Stores `id`, `owner_id`, `device_user_id` or stable device public id, hashed durable secret, status, issued/rotated/revoked timestamps, and metadata such as stable display name plus device model/platform.
  - Serves as the authority for whether the TV may mint a fresh Supabase session.

- TV approval exchange
  - Current QR approval stays the product entry point.
  - On successful approval, the approving user can confirm or edit a stable device display name.
  - Server creates or rotates a durable device credential and returns it once to the TV.
  - The TV stores the credential in secure device storage and stores non-sensitive metadata for recovery.

- Device-session exchange endpoint
  - Android calls this at startup when no live Supabase session is present.
  - Server validates device identifier + durable secret against the hashed credential store.
  - If valid and active, server mints a fresh owner Supabase session and returns access/refresh tokens.
  - If revoked or invalid, server denies the exchange and the app clears local device credential state.

- Android auth recovery
  - Attempt normal Supabase hydration/refresh first.
  - If absent, attempt durable device-session exchange immediately.
  - If exchange succeeds, import the returned Supabase session and resume sync.
  - If exchange fails authoritatively, clear local credential and surface reconnect/sign-in.

- Portal device management
  - Device inventory reads both linkage metadata and durable-credential status.
  - Device inventory uses the stable approval-time display name as the primary title and model/platform as supporting detail.
  - Revoke action marks the durable credential revoked and optionally removes linkage metadata.
  - Portal copy should explain that future logins are blocked immediately, while already-issued access tokens expire on normal JWT timelines.

## Security and Session Semantics
- The durable credential should be random, opaque, and high entropy.
- Server should store only a hash, not the raw durable secret.
- Android should store the raw durable secret in the strongest available secure storage path.
- Device-session exchange should update `last_seen_at` and may rotate or roll forward the durable credential if desired, but rotation must tolerate interrupted responses.
- Revocation blocks future session issuance, but a previously issued access token may remain valid until `exp`, which is consistent with Supabase session behavior.
- JWT lifetime should remain short enough that revoke latency is acceptable for the product's risk profile.

## Migration Plan
1. Add the durable credential model and server exchange endpoint while preserving the current TV login path.
2. Update QR approval to issue a durable device credential to newly approved devices.
3. Update Android startup recovery to use durable exchange before reconnect UI.
4. Expose durable device status and revoke in the portal.
5. Define how legacy already-linked TVs migrate:
   - A legacy TV that already holds a durable credential continues using it normally.
   - A legacy TV that only has a legacy owner session and metadata does not receive silent durable promotion.
   - A legacy TV without a pre-existing durable credential must reconnect once to receive one.
6. After rollout, make durable credential presence the expected path for every approved TV.

## Migration Note
- Legacy approved TVs are not silently backfilled into durable authority from owner session plus metadata alone.
- Legacy TVs without a pre-existing durable credential must reconnect once to receive a durable credential in this rollout.
- Existing legacy `device_name` values become the initial stable display name when no approval-time custom name exists yet.

## Risks / Trade-offs
- Device-specific revoke remains bounded by JWT expiry for already-issued access tokens.
  - Mitigation: keep JWT expiry reasonably short and treat durable credential revoke as the control for future session minting.

- Durable credential loss on the TV still requires server contact to recover.
  - Mitigation: store credential securely and keep startup exchange eager and automatic.

- Credential rotation can cause false revoke if interrupted carelessly.
  - Mitigation: prefer explicit versioning or overlap windows instead of destructive immediate replacement.

- Legacy-device migration may still require one-time user action if no trustworthy bootstrap path exists.
  - Mitigation: keep that migration path explicit rather than pretending legacy sessions are durable.

## Open Questions
- Whether the durable credential should be bound to `device_user_id`, a new stable device public id, or both.
- Whether exchange should rotate the durable credential each time or keep a stable long-lived secret with optional manual rotate.
- Whether revoked devices should keep a visible history row in the portal or be removed from the primary list by default.
