# Remote informational notices — design spec

**Date:** 2026-05-12
**Author:** John Neerdael (collaborator: Codex)
**Status:** Awaiting user review before plan-writing

---

## Goal

NEXIO already checks GitHub Releases on startup and shows
`UpdatePromptDialog` when a newer APK is available. That flow is for app
upgrades. The new feature adds a separate remote informational notice
mechanism so important messages can be shown to users without shipping a
new app build.

The notice system must:

1. Read a small manifest from the GitHub repo.
2. Fetch and render a Markdown notice body, including embedded remote
   images.
3. Show at most one notice on startup.
4. Show only the newest active eligible notice.
5. Never show notices that already existed when a fresh install first
   establishes its notice baseline.
6. Show each eligible notice only once after the user closes it.

Out of scope: push notifications, in-session polling, profile-scoped
notices, notice queues, rich actions, user targeting beyond app version
ranges, and server-side state.

---

## Current Context

Relevant existing code:

- `app/src/main/java/com/nexio/tv/updater/UpdateViewModel.kt` runs a
  lightweight update check on app start.
- `app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt` fetches
  the latest GitHub Release through `GitHubReleaseIntegrationProvider`.
- `app/src/main/java/com/nexio/tv/updater/UpdatePreferences.kt` stores
  updater state in app-wide DataStore.
- `app/src/main/java/com/nexio/tv/updater/ui/UpdatePromptDialog.kt`
  already renders release notes through
  `com.mikepenz.markdown.m3.Markdown`.
- `app/src/main/java/com/nexio/tv/MainActivity.kt` renders the update
  dialog above the main navigation shell.
- Coil is already available for Compose image loading, and the Markdown
  renderer dependency is already present.

The notice feature should reuse the existing visual language and
networking stack, but remain separate from update availability checks so
release notes and operator announcements do not become entangled.

---

## Publishing Contract

Notices live in the GitHub repo as ordinary files:

```text
notices/
  manifest.json
  2026-05-important-service-change.md
  images/
    service-change.png
```

Example manifest:

```json
{
  "schemaVersion": 1,
  "notices": [
    {
      "id": "2026-05-important-service-change",
      "title": "Important service change",
      "publishedAt": "2026-05-12T10:00:00Z",
      "markdownUrl": "https://raw.githubusercontent.com/<owner>/<repo>/<branch>/notices/2026-05-important-service-change.md",
      "minVersion": "1.4.0",
      "maxVersion": "1.8.99",
      "expiresAt": "2026-06-12T00:00:00Z"
    }
  ]
}
```

Required notice fields:

- `id`: stable unique identifier. If a notice changes materially, publish
  a new `id`.
- `title`: title displayed in the dialog.
- `publishedAt`: ISO-8601 instant used for freshness, baseline
  suppression, and newest-only selection.
- `markdownUrl`: HTTPS URL for the Markdown body.

Optional fields:

- `minVersion`: minimum app version that may see the notice.
- `maxVersion`: maximum app version that may see the notice.
- `expiresAt`: ISO-8601 instant after which the notice is ignored.

If version fields are absent, the notice applies to every app version.
If `expiresAt` is absent, the notice remains eligible until removed from
the manifest or suppressed locally.

Markdown bodies may include normal remote images:

```md
![Example](https://raw.githubusercontent.com/<owner>/<repo>/<branch>/notices/images/example.png)
```

---

## Eligibility Rules

The app fetches the manifest on startup and computes an eligible notice
set. A notice is eligible only when all conditions are true:

1. Manifest `schemaVersion` is supported.
2. `id`, `title`, `publishedAt`, and `markdownUrl` are present and valid.
3. `markdownUrl` uses HTTPS.
4. `publishedAt` is not in the future.
5. `publishedAt` is after the local notice baseline.
6. `expiresAt` is absent or in the future.
7. `minVersion` is absent or `BuildConfig.VERSION_NAME >= minVersion`.
8. `maxVersion` is absent or `BuildConfig.VERSION_NAME <= maxVersion`.
9. `id` is not in the local seen-notice set.

If multiple notices are eligible, the app selects only the newest notice
by `publishedAt`. Ties are resolved deterministically by `id` so behavior
is stable across runs.

Version comparison should use the existing updater version logic rather
than adding a second parser.

---

## Fresh Install Baseline

Fresh installs must not surface notices that already exist in the first
successfully fetched manifest. This avoids installing the app and
immediately seeing old operational announcements.

Baseline behavior:

1. Store a local `noticeBaselineAt` in app-wide DataStore.
2. If `noticeBaselineAt` is absent, the first successful manifest fetch
   and parse establishes it as the current app clock time before
   eligibility filtering for that manifest.
3. On that first successful fetch, notices with
   `publishedAt <= noticeBaselineAt` are treated as pre-existing and are
   not shown.
4. Only notices with `publishedAt > noticeBaselineAt` can surface.
5. If the first manifest fetch fails or cannot be parsed, do not set the
   baseline yet.

Seen behavior:

1. Store seen notice IDs in the same app-wide DataStore.
2. Mark a notice as seen when the user closes the notice dialog.
3. Do not profile-scope this state; notices are app-level announcements.
4. If an already-seen notice changes materially, publish a new `id` and
   new `publishedAt`.

---

## Architecture

Add a dedicated remote-notices feature beside `updater`, not inside it.

Proposed package:

```text
app/src/main/java/com/nexio/tv/notices/
  RemoteNoticeRepository.kt
  RemoteNoticePreferences.kt
  RemoteNoticeViewModel.kt
  VersionNoticeFilter.kt
  model/RemoteNotice.kt
  model/RemoteNoticeManifest.kt
  ui/RemoteNoticeDialog.kt
```

### Repository

`RemoteNoticeRepository` is responsible for network and parsing:

1. Fetch `notices/manifest.json` from GitHub raw content.
2. Parse it with Moshi.
3. Validate notice fields.
4. Apply time, version, baseline, and seen-ID filters.
5. Select the newest eligible notice.
6. Fetch that notice's Markdown body.
7. Return a display-ready notice model.

Repository failures return no notice. They do not throw into startup UI
and do not affect update checks.

### Preferences

`RemoteNoticePreferences` stores:

- `noticeBaselineAt`: ISO instant or epoch milliseconds.
- `seenNoticeIds`: string set of acknowledged IDs.
- `lastCheckAtMs`: optional diagnostic field for debugging.

This DataStore is app-wide, matching `UpdatePreferences`, and must not
participate in profile/account settings sync.

### View Model

`RemoteNoticeViewModel` runs a lightweight startup check and exposes:

```kotlin
data class RemoteNoticeUiState(
    val isChecking: Boolean = false,
    val notice: RemoteNoticeDisplay? = null,
    val showDialog: Boolean = false
)
```

It provides:

- `checkForNotice()` for startup.
- `dismissNotice()` to mark the current notice as seen and hide the
  dialog.

The view model should not poll while the user is using the app.

### UI

`RemoteNoticeDialog` uses the same modal language as
`UpdatePromptDialog`:

- Title from manifest.
- Scrollable Markdown body.
- One `Close` action.
- TV focus starts on `Close`.
- Dialog max height prevents long notices from occupying the full screen.

The Markdown renderer should render basic Markdown and remote images. If
embedded image support needs explicit configuration, use the existing
Coil dependency rather than adding another image stack.

---

## Startup And Precedence Rules

The notice is startup-only. It must not interrupt active use.

Show the notice only when:

1. The startup splash is no longer visible.
2. The app is not in playback.
3. A fullscreen trailer is not active.
4. The idle screensaver is not visible.
5. `UpdatePromptDialog` is not visible.

If an update dialog is visible, the update dialog wins and the notice is
suppressed for that startup. The notice remains unseen and may appear on
a future startup if it is still active and eligible.

The notice check may run during startup, but rendering is gated by the
conditions above. The implementation can either keep the fetched notice
pending until gates open during startup, or suppress it for the startup
once a higher-priority dialog appears. It must not pop up in the middle
of normal use after the user has started interacting with the app.

---

## Error Handling

Fail closed:

| Trigger | Behavior |
|---|---|
| Manifest request fails | Show nothing; keep app startup normal. |
| Manifest JSON is malformed | Show nothing; do not set baseline. |
| Unsupported `schemaVersion` | Show nothing; do not set baseline. |
| Notice has invalid required fields | Ignore that notice. |
| No eligible notices remain | Show nothing. |
| Markdown request fails | Show nothing; do not mark the notice seen. |
| Markdown body is blank | Show nothing; do not mark the notice seen. |
| Image inside Markdown fails | Render the rest of the notice if the renderer allows it. |

Only debug logging is needed. User-facing error UI is not part of this
feature.

---

## Testing

Unit tests should cover:

- First successful manifest fetch establishes the baseline and suppresses
  notices already present in that manifest.
- A notice with `publishedAt > noticeBaselineAt` is eligible.
- A seen notice is not shown again.
- `minVersion`, `maxVersion`, and `expiresAt` filtering.
- Newest-only selection when multiple notices are eligible.
- Invalid notice entries are ignored without failing the whole manifest.
- Malformed manifest returns no notice and does not set the baseline.
- Markdown fetch failure returns no notice and does not mark it seen.
- The update dialog takes precedence over notice rendering.

UI tests can stay focused: one Compose-level test for rendering title,
Markdown body, and close behavior is enough unless the implementation
touches shared dialog infrastructure.

---

## Open Implementation Decisions

- Exact GitHub raw URL source: derive from existing
  `BuildConfig.GITHUB_OWNER` / `BuildConfig.GITHUB_REPO` plus a fixed
  branch, or add a `BuildConfig.NOTICES_MANIFEST_URL`.
- Whether to implement raw-content fetching through Retrofit or a small
  OkHttp transport under the existing integration runtime.
- Whether startup gating lives entirely in `MainActivity` composition or
  partly in `RemoteNoticeViewModel`.

These are implementation-plan decisions, not product behavior
questions.
