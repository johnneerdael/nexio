## ADDED Requirements

### Requirement: Anime stream requests use only anime-specific addons when available
When a stream request is classified as anime and at least one compatible installed stream addon is marked `isAnime`, NEXIO SHALL query only the compatible anime-marked addons for that request.

#### Scenario: Anime content with anime addons excludes generic addons
- **WHEN** the requested content is classified as anime
- **AND** at least one compatible installed stream addon is marked `isAnime`
- **AND** at least one compatible installed stream addon is not marked `isAnime`
- **THEN** only the anime-marked compatible addons are queried
- **AND** generic compatible addons are not queried for that request

#### Scenario: Anime addon returning no streams does not fall back to generic addons
- **WHEN** the requested content is classified as anime
- **AND** a compatible anime-marked addon returns zero streams
- **THEN** NEXIO emits the anime addon bucket with zero streams
- **AND** does not query generic compatible addons as fallback during that request

#### Scenario: Non-anime content keeps querying all compatible addons
- **WHEN** the requested content is not classified as anime
- **AND** compatible anime-marked and generic addons are installed
- **THEN** NEXIO queries all compatible stream addons

#### Scenario: Unknown anime classification keeps querying all compatible addons
- **WHEN** the requested content id cannot be parsed or anime classification fails non-fatally
- **AND** compatible anime-marked and generic addons are installed
- **THEN** NEXIO queries all compatible stream addons

### Requirement: Torii is preferred over Nagare for grouped stream ranking
When grouped stream presentation compares Nexio built-in provider streams with equal addon-priority, cache, and resolution signals, NEXIO SHALL rank Torii streams ahead of Nagare streams before applying size ordering.

#### Scenario: Torii outranks Nagare before size comparison
- **WHEN** a Torii stream and a Nagare stream have the same cache state and resolution
- **AND** the Nagare stream has a larger parsed size
- **THEN** the Torii stream is presented before the Nagare stream

#### Scenario: Cache and resolution remain stronger than provider preference
- **WHEN** Torii and Nagare streams differ in cache state or resolution
- **THEN** grouped presentation applies cache and resolution ordering before the Torii-over-Nagare preference
