# Project Context

## Purpose
Nexio is an Android TV/Fire OS media client built around the Stremio addon ecosystem, with synced account settings and a tuned playback stack for premium TV-device performance.

Primary goals:
- Fast, stable, disk-first home/startup experience on TV hardware.
- High-quality playback (Media3-based, with custom native audio/video work).
- Cross-device account sync for addons/settings via Supabase.
- Strong integrations (Trakt, MDBList, TMDB) without compromising startup smoothness.

## Tech Stack
- Kotlin + Jetpack Compose (Android TV app in `app/`)
- Android SDK (minSdk 26, target/compile 36), Gradle Kotlin DSL
- Dagger Hilt + KSP for DI/codegen
- Coroutines + Flow for async/reactive pipelines
- Retrofit + OkHttp + Moshi for networking
- Coil for image loading/caching
- AndroidX Media3 playback stack, with local fork in `media/` submodule
- JNI/C++ for custom playback/audio sink integrations
- Nuxt server app for web portal (`nexio-web/`)
- Supabase (PostgREST + SQL/functions) for account/settings/addon sync

## Project Conventions

### Code Style
- Kotlin-first codebase with clear feature/module split (`ui/`, `data/`, `core/`, `domain/`).
- Keep UI-thread work minimal; performance-sensitive code should avoid startup fan-out and excessive allocations.
- Use explicit, descriptive naming (`...Service`, `...Repository`, `...DataStore`, `...Pipeline`).
- Prefer immutable UI state models (`HomeUiState`, etc.) and controlled state updates.
- Log tags should be specific and consistent for profiling/debug correlation.

### Architecture Patterns
- Layered architecture:
  - `domain/`: models + repository interfaces
  - `data/`: repository implementations, local stores, remote APIs
  - `ui/`: Compose screens + ViewModels
  - `core/`: cross-cutting services (sync, playback, locale, recommendations)
- ViewModel pipeline split by concern (e.g. `HomeViewModel...Pipeline.kt` partials).
- Cached-first rendering strategy for home/discovery snapshots, with deferred background refresh.
- Repository pattern for addon catalogs, metadata, and integrations.
- Event-driven refresh via notifiers/services (e.g. account sync refresh notifier).

### Testing Strategy
- Unit tests exist under `app/src/test` for core logic and repositories (network safety, stream selection/presentation, caching behavior).
- Focus tests on deterministic business logic and regressions in data/selection pipelines.
- For performance changes, validate with device profiling/log capture (startup frame timing, jank, CPU/memory).
- Prefer adding targeted tests when touching repository/cache/state reconciliation logic.

### Git Workflow
- Main app repo branch is `main`.
- `media/` is a separate git repository tracked as a submodule; commit/push submodule changes first, then commit updated submodule pointer in main repo.
- Use small, focused commits with imperative commit messages.
- Avoid bundling generated artifacts/log dumps/build outputs in commits.

## Domain Context
- Nexio is a client app; it does not host/distribute media content.
- Content catalogs come from user-installed addons (Stremio ecosystem) and integrated providers.
- Discovery rails are enriched by Trakt/MDBList/TMDB and user account settings.
- TV UX quality depends heavily on startup/frame-time behavior, cache strategy, and background refresh timing.
- Android TV recommendations/channels and browsable request flows are part of launch/home behavior.

## Important Constraints
- Startup performance is a top priority: defer non-visual work and avoid heavy concurrent refresh on first render path.
- Disk-first content presentation is preferred over immediate network refresh fan-out.
- Metadata/image refresh should be controlled to avoid jank and unnecessary network churn.
- Locale/language changes must be respected for metadata correctness.
- Media playback changes in `media/libraries/cpp_audiosink` are tightly constrained (JNI bridge-focused and aligned with Kodi AudioEngine references).
- Keep legal boundary clear: user-authorized sources only, no hosted catalog/media service in-app.

## External Dependencies
- Supabase backend (account snapshot/settings sync, secrets/config transport)
- Trakt APIs (auth, scrobble/check-in, discovery)
- MDBList APIs (list discovery/ratings catalogs)
- TMDB APIs (metadata enrichment/artwork)
- Stremio-compatible addon endpoints (catalog/meta/stream APIs)
- Real-Debrid/Premiumize integrations (where configured)
- GitHub Releases (in-app update metadata/source)
