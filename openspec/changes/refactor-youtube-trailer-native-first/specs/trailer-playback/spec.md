## MODIFIED Requirements

### Requirement: Signed-in YouTube trailers must prefer native resolution and fall back to the helper
When YouTube trailer auth is available, Nexio MUST first reuse any valid cached YouTube playback source, MUST then attempt the native internal YouTube resolver, and MUST invoke the bundled helper only when native resolution does not produce playable media.

#### Scenario: Signed-in YouTube trailer uses native playback first
- **GIVEN** the user has an active `YouTube Trailer Login` session
- **AND** the trailer source is YouTube-backed
- **AND** the native internal YouTube resolver can produce playable trailer media for that trailer
- **WHEN** Nexio resolves playback for that trailer
- **THEN** Nexio uses the native internal YouTube resolver result
- **AND** Nexio does not invoke the bundled helper for that trailer

#### Scenario: Signed-in YouTube trailer falls back to helper after native miss
- **GIVEN** the user has an active `YouTube Trailer Login` session
- **AND** the trailer source is YouTube-backed
- **AND** the native internal YouTube resolver returns no playable media for that trailer
- **WHEN** Nexio resolves playback for that trailer
- **THEN** Nexio invokes the bundled helper as a fallback
- **AND** Nexio uses the helper-produced media URLs when the helper resolves playback successfully

#### Scenario: Signed-in playback can reuse cached native YouTube playback
- **GIVEN** Nexio has a valid cached native YouTube playback source for a trailer within the YouTube cache TTL
- **AND** the user has an active `YouTube Trailer Login` session
- **WHEN** Nexio resolves playback for that same YouTube trailer again
- **THEN** Nexio reuses the cached playback source
- **AND** Nexio does not discard the cached source only because it is not helper-backed

#### Scenario: Expired YouTube playback cache is discarded before resolution
- **GIVEN** Nexio has a cached YouTube playback source older than the YouTube cache TTL
- **WHEN** Nexio resolves playback for that YouTube trailer again
- **THEN** Nexio discards the expired cached source
- **AND** Nexio reruns the normal cache, native, helper, and backend resolution order
