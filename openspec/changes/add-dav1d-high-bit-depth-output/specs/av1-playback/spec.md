## ADDED Requirements

### Requirement: High-Bit-Depth dav1d output compatibility

The system SHALL present high-bit-depth dav1d-decoded AV1 frames through the existing AV1 renderer
outputs without failing solely because dav1d produced 10-bit or 12-bit planar output.

#### Scenario: 10-bit AV1 frame is rendered through YUV output
- **WHEN** dav1d decodes an AV1 frame with `bpc` greater than 8
- **AND** the renderer is using YUV output buffers
- **THEN** the JNI bridge downconverts the decoded frame to 8-bit YUV output
- **AND** playback does not fail with a high-bit-depth unsupported error

#### Scenario: 10-bit AV1 frame is rendered through surface output
- **WHEN** dav1d decodes an AV1 frame with `bpc` greater than 8
- **AND** the renderer is using surface-YUV output
- **THEN** the JNI bridge downconverts the decoded frame while copying it into the surface buffer
- **AND** playback does not fail with a high-bit-depth unsupported error

#### Scenario: 8-bit AV1 behavior is preserved
- **WHEN** dav1d decodes an AV1 frame with `bpc` equal to 8
- **THEN** the existing 8-bit copy/render path is used
- **AND** no extra downconversion is applied

### Requirement: Default dav1d renderer tuning favors playback stability

The default `Libdav1dVideoRenderer` configuration SHALL prefer a lower-overhead dav1d setup for
software AV1 playback when instantiated through the default renderer factory path.

#### Scenario: Default renderer uses playback-friendly dav1d defaults
- **WHEN** `Libdav1dVideoRenderer` is created through its default constructor
- **THEN** it configures dav1d with the experimental reduced thread-count mode
- **AND** it uses a max frame delay of 1
