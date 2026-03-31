# Deferred Provider Contract Memo

## Implemented In This Change
- TorBox
- EasyDebrid

## Discovery Only

### AllDebrid
- Auth model: API key appears supported in public docs.
- Active validation endpoint: to be confirmed from official docs before implementation.
- Instant-cache probe: not yet confirmed from the official public docs reviewed so far.
- Add magnet / torrent: magnet management appears documented.
- File listing / direct playback: partially documented.
- Library/list/history: appears plausible.
- Decision: do not implement in this change.

### Debrid-Link
- Auth model: bearer token / OAuth-backed API is documented.
- Active validation endpoint: appears available.
- Instant-cache probe: not yet confirmed from the official public docs reviewed so far.
- Add magnet / torrent: appears documented.
- File listing / direct playback: appears plausible.
- Library/list/history: appears plausible.
- Decision: do not implement in this change.

### Offcloud
- Auth model: API key and account credentials are referenced in AIOStreams presets.
- Active validation endpoint: to be confirmed from official docs before implementation.
- Instant-cache probe: not yet confirmed from the official public docs reviewed so far.
- Add magnet / torrent: to be confirmed.
- File listing / direct playback: to be confirmed.
- Library/list/history: to be confirmed.
- Decision: do not implement in this change.

### Debrider
- Auth model: API key is referenced by AIOStreams.
- Active validation endpoint: to be confirmed from official docs before implementation.
- Instant-cache probe: not yet confirmed from the official public docs reviewed so far.
- Add magnet / torrent: to be confirmed.
- File listing / direct playback: to be confirmed.
- Library/list/history: to be confirmed.
- Decision: do not implement in this change.

### PikPak
- Auth model: email/password is referenced by AIOStreams.
- Active validation endpoint: to be confirmed from official docs before implementation.
- Instant-cache probe: not yet confirmed from the official public docs reviewed so far.
- Add magnet / torrent: to be confirmed.
- File listing / direct playback: to be confirmed.
- Library/list/history: to be confirmed.
- Decision: do not implement in this change.

## Explicitly Excluded

### put.io
- Official docs show transfer and file workflows, but no confirmed instant-cache probe.
- Decision: exclude from Service Wrap in this change.

### Seedr
- Official docs show cloud/file workflows, but no confirmed instant-cache probe.
- Decision: exclude from Service Wrap in this change.
