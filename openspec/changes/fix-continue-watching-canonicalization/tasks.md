# Tasks

- [ ] Add canonical Continue Watching row normalization before snapshot persistence.
- [ ] Resolve non-anime series next-up/resume coordinates to TVDB when TVDB identity is available.
- [ ] Preserve existing anime/Kitsu coordinate projection behavior.
- [ ] Add watched-anchor suppression across local, Trakt, SIMKL, MDBList, and retained snapshot rows.
- [ ] Tighten next-up air-date gating so unknown dates are dropped and concrete future dates are scheduled only.
- [ ] Ensure stream-fetch identities use the same canonical coordinates shown in Continue Watching.
- [ ] Sanitize existing persisted snapshots on read or next refresh.
- [ ] Add regression tests for completed rows, unaired rows, Australian Survivor TVDB coordinates, retention, and anime.
- [ ] Run focused unit tests and a rooted-device smoke check against `com.nexio.tv`.
