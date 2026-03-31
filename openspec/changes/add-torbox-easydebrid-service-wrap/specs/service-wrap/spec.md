## ADDED Requirements

### Requirement: Service Wrap supports TorBox and EasyDebrid
The Android Service Wrap runtime SHALL support TorBox and EasyDebrid as cached torrent resolution providers alongside the existing providers.

#### Scenario: TorBox cached torrent resolves to a wrapped playback stream
- **WHEN** Service Wrap is enabled and an eligible torrent hash is cached in TorBox
- **THEN** the runtime emits a TorBox wrapped stream with a direct playback URL
- **AND** uncached TorBox results are not surfaced

#### Scenario: EasyDebrid cached torrent resolves to a wrapped playback stream
- **WHEN** Service Wrap is enabled and an eligible torrent hash is cached in EasyDebrid
- **THEN** the runtime emits an EasyDebrid wrapped stream with a direct playback URL
- **AND** uncached EasyDebrid results are not surfaced

### Requirement: Provider validation stays deduplicated and parallel
The Service Wrap runtime SHALL deduplicate eligible torrents by normalized hash before provider calls and SHALL validate configured providers in parallel.

#### Scenario: Duplicate torrent hashes are checked once per provider
- **WHEN** multiple addons surface the same eligible torrent hash
- **THEN** Service Wrap triggers at most one TorBox check and one EasyDebrid check for that hash during the request
- **AND** duplicate visible wrapped rows are not emitted for the same `hash + provider + selected file`

#### Scenario: Current providers and new providers resolve in parallel
- **WHEN** multiple configured Service Wrap providers are available
- **THEN** the runtime starts their cache checks without serializing one provider behind another
- **AND** wrapped results can surface incrementally as each provider resolves

### Requirement: Portal validation is required before storing TorBox and EasyDebrid secrets
The portal SHALL validate TorBox and EasyDebrid credentials against provider-owned authenticated endpoints before saving them.

#### Scenario: TorBox key is validated before save
- **WHEN** a user submits a TorBox API key in the portal
- **THEN** the portal validates it against a TorBox authenticated endpoint
- **AND** it stores the secret only if validation succeeds

#### Scenario: EasyDebrid key is validated before save
- **WHEN** a user submits an EasyDebrid API key in the portal
- **THEN** the portal validates it against `GET /user/details`
- **AND** it stores the secret only if validation succeeds
