## ADDED Requirements

### Requirement: AIO-Compatible Uniform Formatting Contract

The system SHALL provide an AIO-compatible parser and formatter contract for uniform stream
formatting.

#### Scenario: Uniform formatting exposes AIO-compatible parsed fields
- **WHEN** Nexio prepares a stream for uniform formatting
- **THEN** it derives the stream, service, addon, and metadata fields required by the built-in
  AIO-compatible templates
- **AND** those fields include parsed values such as title, year, resolution, quality, visual tags,
  audio tags, audio channels, languages, season/episode data, release group, cache state, and
  formatter-facing derived values

#### Scenario: Formatter grammar supports AIO-compatible template features
- **WHEN** Nexio evaluates a built-in AIO-compatible formatter template
- **THEN** it supports nested variable expansion, conditional checks, chained modifiers, comparator
  chains, and formatter post-processing directives used by the shipped AIO-compatible templates

### Requirement: Uniform Formatting Uses Template Output as Source of Truth

When uniform stream formatting is enabled, the system SHALL render stream card text exclusively from
the AIO-compatible template output.

#### Scenario: Uniform path renders title from formatter output
- **WHEN** `uniformStreamFormattingEnabled` is true for a stream card
- **THEN** the rendered stream card title is produced from the active AIO-compatible name template
- **AND** the title is not synthesized by legacy hardcoded Kotlin uniform title builders

#### Scenario: Uniform path renders detail lines from formatter output
- **WHEN** `uniformStreamFormattingEnabled` is true for a stream card
- **THEN** the rendered stream card detail lines are produced from the active AIO-compatible
  description template
- **AND** legacy hardcoded Kotlin uniform detail-line builders do not contribute visible text

#### Scenario: Non-uniform path remains unchanged
- **WHEN** `uniformStreamFormattingEnabled` is false
- **THEN** Nexio continues to use the existing non-uniform stream presentation behavior

### Requirement: Built-In AIO Template Registry

The system SHALL ship built-in AIO-compatible formatter templates in code so future template swaps
do not require presentation logic rewrites.

#### Scenario: Built-in universal template is available in code
- **WHEN** Nexio initializes its built-in uniform formatting templates
- **THEN** the built-in registry includes the approved universal AIO-compatible template definition

#### Scenario: Multiple built-in templates can coexist without UI exposure
- **WHEN** Nexio loads built-in AIO-compatible formatter definitions
- **THEN** it can resolve templates by internal identifier
- **AND** template selection does not require immediate Android UI exposure in the same change
