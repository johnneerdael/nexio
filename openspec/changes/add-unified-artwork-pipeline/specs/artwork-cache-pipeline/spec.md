## ADDED Requirements

### Requirement: Metadata artwork uses a unified ownership pipeline
The system SHALL route Home, Detail, Continue Watching, Player metadata, and catalog rail metadata
artwork through a unified artwork pipeline before rendering.

#### Scenario: Metadata artwork follows the pipeline
- **GIVEN** a metadata surface needs a poster, backdrop, logo, or thumbnail
- **WHEN** final display artwork is resolved
- **THEN** the system creates artwork candidates
- **AND** `ArtworkRouter` selects the final artwork decision
- **AND** `ArtworkAssetRepository` fetches or loads the selected asset
- **AND** Coil renders a local or runtime-backed artwork reference

#### Scenario: Coil remains renderer only
- **GIVEN** metadata artwork is displayed by Android UI
- **WHEN** Coil receives an artwork model
- **THEN** Coil receives `nexio-artwork://`, `nexio-placeholder://`, or a local/content URI produced by `ArtworkAssetRepository`
- **AND** Coil does not own provider precedence, TTL, stale behavior, backoff, profile scope, or runtime trace policy

### Requirement: Raw remote artwork URLs are not final metadata display ownership
The system SHALL NOT expose raw remote artwork URLs as final UI-facing artwork for metadata
surfaces.

#### Scenario: Raw URLs remain source payload only
- **GIVEN** a provider payload includes a TMDB, TVDB, Kitsu, addon, rail preview, RPDB, or Top-Posters image URL
- **WHEN** the payload is converted into display metadata
- **THEN** the raw URL may be retained only as DTO/source payload/runtime fetch material
- **AND** the final UI-facing artwork is represented by `ArtworkDisplayRef` or a derived internal URI

#### Scenario: UI does not direct-load provider URLs
- **GIVEN** a Home, Detail, Continue Watching, Player metadata, or catalog rail UI path displays artwork
- **WHEN** that path builds a Coil request
- **THEN** the request model is not a raw `http://` or `https://` provider artwork URL
- **AND** raw provider URLs such as TMDB, TVDB, Kitsu, RPDB, Top-Posters, addon preview, and rail preview URLs are rejected by boundary tests

### Requirement: Typed artwork references are canonical with legacy string compatibility
The system SHALL make typed artwork references canonical while preserving legacy string fields only
as derived compatibility projections during migration.

#### Scenario: Display metadata has typed artwork
- **GIVEN** a resolved metadata document or display model contains artwork
- **WHEN** the model is produced after this change
- **THEN** it includes an `ArtworkBundle` with typed `ArtworkDisplayRef` values for available artwork fields

#### Scenario: Legacy strings are derived
- **GIVEN** a compatibility model still exposes `poster`, `backdrop`, `background`, `logo`, or `thumbnail` as a string
- **WHEN** that string is populated
- **THEN** it is derived from the corresponding `ArtworkDisplayRef`
- **AND** the value is `nexio-artwork://asset/{assetKey}`, `nexio-artwork://decision/{decisionKey}`, or `nexio-placeholder://{type}`
- **AND** it is not independently authored from a provider URL

#### Scenario: Placeholder projection is explicit
- **GIVEN** a selected artwork field is a placeholder
- **WHEN** legacy compatibility projection is requested
- **THEN** the projected string uses `nexio-placeholder://{type}`
- **AND** it does not route through `nexio-artwork://decision/{decisionKey}` unless there is an actual runtime asset decision

### Requirement: Runtime candidates and persisted decisions have separate safety boundaries
The system SHALL separate in-memory artwork candidates from persisted artwork decisions so raw
fetch material cannot leak into persisted records or reports.

#### Scenario: Runtime remote source may fetch but must redact
- **GIVEN** an `ArtworkCandidate` contains a remote URL source
- **WHEN** the candidate is used for runtime fetching
- **THEN** the raw URL is available only through a sensitive in-memory wrapper
- **AND** generated string output, logs, reports, serialized decisions, and cache keys do not contain the raw URL

#### Scenario: Persisted decision stores safe snapshots
- **GIVEN** `ArtworkRouter` selects a candidate
- **WHEN** the decision is persisted
- **THEN** the persisted decision stores a `PersistedArtworkCandidate`
- **AND** the persisted candidate contains only provider identity, source role, source hash, redacted source trace, safe provider template fields, and priority
- **AND** it does not contain raw remote URLs, raw provider URLs, raw credentials, raw auth headers, or raw API keys

#### Scenario: Persisted provider templates are safe
- **GIVEN** a selected candidate can be reconstructed from a provider template
- **WHEN** the template is persisted
- **THEN** it may contain non-secret media IDs, image type, provider path hash, settings hash, credential hash, image language, and policy version
- **AND** it does not contain raw provider URLs, raw auth headers, or raw credentials

### Requirement: Persisted safe decisions are materialized through an explicit source materializer
The system SHALL obtain fetch material for persisted decisions through an explicit materialization
step instead of persisting raw remote URLs.

#### Scenario: Provider template decision is reconstructed
- **GIVEN** a persisted artwork decision has a safe provider template
- **WHEN** `ArtworkAssetRepository` needs to fetch the asset after process restart
- **THEN** `ArtworkSourceMaterializer` reconstructs the provider request from provider identity, media ID, settings hash, credential hash, image type, and policy version

#### Scenario: Remote preview decision recovers source payload
- **GIVEN** a persisted artwork decision references a remote preview source by owner key and source hash
- **WHEN** the asset repository needs fetch material
- **THEN** `ArtworkSourceMaterializer` recovers the raw URL from the owning source payload record
- **AND** if the payload cannot be recovered, `ArtworkRouter` recomputes candidates or falls back according to artwork policy

### Requirement: Artwork owner keys support first paint before canonical identity
The system SHALL support artwork decisions for preview items before canonical content identity is
known and SHALL supersede those decisions after stable identity resolution.

#### Scenario: Preview item renders before canonical identity
- **GIVEN** a rail or addon preview item has no canonical content ID yet
- **WHEN** first-paint artwork is resolved
- **THEN** the artwork decision key may use `ArtworkOwnerKey.PreviewItem`
- **AND** first paint is not blocked waiting for canonical identity

#### Scenario: Canonical decision supersedes preview decision
- **GIVEN** a preview item later resolves to canonical identity
- **WHEN** canonical artwork is resolved
- **THEN** the canonical `ArtworkOwnerKey.CanonicalContent` decision becomes the primary artwork decision
- **AND** the prior preview artwork remains available as a fallback candidate

### Requirement: Artwork provider identity does not drift from runtime provider identity
The system SHALL map artwork provider identity losslessly to existing runtime provider identity
where a runtime provider exists.

#### Scenario: Known providers reuse ProviderId
- **GIVEN** artwork originates from TMDB, TVDB, Kitsu, RPDB, Top-Posters, addon, MDBList, SIMKL, or Trakt-backed sources
- **WHEN** provider identity is recorded for artwork routing, cache keys, or reports
- **THEN** the identity uses the existing `ProviderId` spelling for known runtime providers
- **AND** pseudo-providers such as rail preview, addon preview, and placeholder cannot collide with runtime providers

### Requirement: ArtworkRouter owns provider precedence and fallback
The system SHALL make `ArtworkRouter` the owner of artwork provider precedence and fallback
selection.

#### Scenario: Poster precedence is source-aware
- **GIVEN** a poster has premium, primary, preview, and placeholder candidates
- **WHEN** `ArtworkRouter` selects the poster
- **THEN** it evaluates active supported premium provider first
- **AND** primary provider artwork second
- **AND** current first-paint source preview artwork third
- **AND** other preview artwork fourth
- **AND** placeholder last

#### Scenario: Asset repository does not choose provider winner
- **GIVEN** multiple artwork candidates exist
- **WHEN** `ArtworkAssetRepository` fetches an asset
- **THEN** it fetches the selected candidate from an `ArtworkDecision`
- **AND** it does not choose between TMDB, TVDB, Kitsu, RPDB, Top-Posters, addon preview, rail preview, or placeholder candidates

#### Scenario: FieldResolver consumes artwork decision
- **GIVEN** providers emit metadata and artwork candidates
- **WHEN** final display fields are resolved
- **THEN** artwork fields are selected from `ArtworkRouter` decisions
- **AND** provider adapters do not directly overwrite final poster, backdrop, logo, or thumbnail fields

### Requirement: Premium artwork uses explicit capability checks
The system SHALL evaluate provider capabilities before selecting or attempting premium artwork.

#### Scenario: Unsupported premium ID is rejected
- **GIVEN** an active premium provider requires IMDb, TMDB, or TVDB identifiers
- **WHEN** the current item only has an unsupported ID type such as a raw Kitsu ID
- **THEN** the premium candidate is rejected or omitted with trace reason `UNSUPPORTED_ID_TYPE`
- **AND** the system falls back to the next candidate according to artwork policy

#### Scenario: Supported premium provider wins poster
- **GIVEN** Top-Posters or RPDB is active
- **AND** the item has a supported identifier for the active provider
- **WHEN** poster artwork is selected
- **THEN** the active premium provider wins over primary provider poster artwork
- **AND** title, canonical identity, overview, episode metadata, ratings, tracking, and routing are unchanged

### Requirement: Artwork assets are fetched through IntegrationRuntime and app-owned cache
The system SHALL fetch metadata artwork bytes through `ArtworkAssetRepository`,
`IntegrationRuntime`, and `ArtworkAssetDiskCache` rather than direct Coil remote URL loading.

#### Scenario: Primary provider artwork uses runtime cache
- **GIVEN** TMDB, TVDB, or Kitsu artwork is selected
- **WHEN** its bytes are requested
- **THEN** the request enters `IntegrationRuntime` with an artwork image `apiShapeId`
- **AND** the result is stored or read through `ArtworkAssetDiskCache`

#### Scenario: Premium artwork uses runtime cache
- **GIVEN** RPDB or Top-Posters artwork is selected
- **WHEN** its bytes are requested
- **THEN** the request enters `IntegrationRuntime` with `rpdb.poster_template` or `topposters.poster_template`
- **AND** a fresh cache hit suppresses provider network execution

#### Scenario: Preview artwork uses runtime cache
- **GIVEN** addon or rail preview artwork is selected
- **WHEN** its bytes are requested
- **THEN** the request enters `IntegrationRuntime` through a generic or source-specific artwork fetch shape
- **AND** Coil does not direct-fetch the preview URL

### Requirement: Artwork cache keys are explicit and privacy-safe
The system SHALL build artwork decision and asset cache keys from explicit, privacy-safe inputs.

#### Scenario: Decision key includes semantic ownership inputs
- **GIVEN** an artwork decision key is generated
- **WHEN** key parts are assembled
- **THEN** the key includes owner key, artwork type, active artwork provider policy, premium enabled state, relevant settings hash, relevant credential hash, `imageLanguage=en`, and policy version
- **AND** it does not include raw remote URLs or raw credentials

#### Scenario: Asset key includes byte identity inputs
- **GIVEN** an artwork asset key is generated
- **WHEN** key parts are assembled
- **THEN** the key includes provider/source, artwork type, canonical content ID or provider template identity or normalized source URL hash, image variant/size when relevant, relevant settings hash, relevant credential hash, `imageLanguage=en`, and policy version
- **AND** it does not include raw remote URLs or raw credentials

#### Scenario: Profile display language does not affect artwork keys
- **GIVEN** the user changes profile display language
- **WHEN** artwork decision and asset keys are generated for the same content and artwork settings
- **THEN** metadata artwork keys still use `imageLanguage=en`
- **AND** the keys do not vary by profile display language

### Requirement: Artwork failure handling separates fallback from UI continuity
The system SHALL distinguish artwork routing fallback from UI display continuity.

#### Scenario: Runtime failure falls back by policy
- **GIVEN** selected artwork cannot be fetched fresh
- **WHEN** an artwork asset is requested
- **THEN** the repository may serve a stale selected asset when policy allows
- **AND** otherwise `ArtworkRouter` may select the next fallback candidate
- **AND** if no candidate is available, `FieldResolver` returns a placeholder

#### Scenario: Previously rendered image is continuity only
- **GIVEN** the UI has already rendered an image
- **WHEN** the current artwork decision is replacing or refetching that image
- **THEN** the UI may keep the previously rendered image until a replacement is ready
- **AND** this continuity behavior is trace-labeled
- **AND** it is not recorded as a new artwork decision

### Requirement: Artwork settings invalidate artwork caches without invalidating primary metadata
The system SHALL invalidate affected artwork decisions and assets when artwork policy inputs change
without invalidating primary metadata caches.

#### Scenario: Premium settings invalidate artwork only
- **GIVEN** TMDB, TVDB, or Kitsu metadata cache entries exist for an item
- **WHEN** active premium provider, premium settings, badge style, credential hash, artwork policy version, or provider capability changes
- **THEN** affected artwork decisions and affected asset keys are invalidated
- **AND** TMDB, TVDB, and Kitsu metadata caches are not invalidated

#### Scenario: Provider switch updates posters without primary metadata refetch
- **GIVEN** an item has cached primary metadata and artwork decisions
- **WHEN** the user switches from Top-Posters to RPDB or disables premium artwork
- **THEN** the poster decision is recomputed using the new artwork policy
- **AND** primary title, overview, identity, episode metadata, ratings, and routing data are not refetched solely because of the artwork switch

### Requirement: Artwork audit reports prove decision, cache, and UI boundaries
The system SHALL emit artwork audit/report data that proves selected provider, rejected candidates,
asset cache behavior, runtime shape, and UI boundary status.

#### Scenario: Premium poster report shows rejected primary candidate
- **GIVEN** premium poster artwork wins over a primary provider poster
- **WHEN** metadata execution reporting is generated
- **THEN** the report includes field, selected provider, source role, decision key, asset key, asset cache decision, runtime `apiShapeId`, network execution flag, Coil model, and raw remote URL boundary status
- **AND** the report includes the rejected primary provider candidate and reason

#### Scenario: Cache hit report proves network suppression
- **GIVEN** an artwork asset has a fresh cache entry
- **WHEN** the artwork is rendered
- **THEN** report output shows cache decision `HIT`
- **AND** network execution is false
- **AND** the runtime operation identity is present for the artwork fetch path
