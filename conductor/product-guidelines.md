# Product Guidelines

> How we build, name, write, and present Nexio. Keep this aligned with `product.md`, `tech-stack.md`, and the per-category docs under `conductor/docs/`.

## Quick Reference

The 10 rules that apply to every change. Violating any of these should be justified in the PR / change description.

1. **Preserve layering.** `ui → domain → data`, with cross-cutting infra in `core/`. Domain must stay Android-free.
2. **Follow suffix conventions.** `*ViewModel`, `*UiState`, `*Repository`, `*DataStore`, `*Screen`, `*Contract`, `*Policy`. Match the class name to the file name.
3. **Retrofit services are `suspend fun ...(): Response<T>`**. Wrap calls with `safeApiCall { ... }` returning `NetworkResult<T>` (`Success` / `Error(message, code?)` / `Loading`). No `Call<T>`, no `Flow<T>` at the transport layer.
4. **Use the right serializer.** Moshi + `@JsonClass(generateAdapter = true)` for external APIs; `kotlinx.serialization` + `@Serializable` for Supabase sync models.
5. **Rate-limit through coroutine gates**, not `Thread.sleep`. Use `TraktRequestGate` / `SimklRequestGate`-style `Mutex` + `delay` (≥ 500 ms).
6. **State = `MutableStateFlow` → exposed `StateFlow`.** Repositories emit `Flow<NetworkResult<T>>`. DataStore-backed preferences plug into `AccountConfigSyncContract` so they sync to the portal.
7. **Account-config sync is versioned.** `AccountConfigSyncContract` (v7 today) — keep backward compatible, don't regress delta-path push to full-payload, and never inline addon secrets in the payload (use `secretRef`).
8. **Playback invariants.** Deterministic Autoplay keeps DV-aware fallback wired; ASS/SSA goes through the protected translation pipeline only (generic Media3 cue translation stays disabled); TrueHD stays gated (not production-stable).
9. **Test like the repo does.** JUnit4 + MockK (`relaxed = true`, `coEvery`, `coVerify(exactly = N)`) + `runTest(dispatcher)` + `StandardTestDispatcher`. Test method names are **backticked descriptive sentences**; shared fakes live in `app/src/test/java/com/nexio/tv/testutil/`. Add `*ForTesting` seams on production classes rather than bending DI.
10. **Build `arm64` locally.** `./gradlew assembleArm64Debug` for iteration, `./gradlew testArm64DebugUnitTest` for tests, `./gradlew bundlePlayRelease` for Play bundles.

Full details live in `conductor/docs/`: `naming-conventions.md`, `architecture.md`, `api-conventions.md`, `testing-patterns.md`.

## Project Structure

```
nexio/
├── app/                          # Android TV app (com.nexio.tv)
│   └── src/main/java/com/nexio/tv/
│       ├── core/                 # Cross-cutting infrastructure
│       │   ├── auth/             # Trakt/Simkl/Kitsu/RD device-code + OAuth
│       │   ├── di/               # Hilt modules (RepositoryModule, NetworkModule, SupabaseModule…)
│       │   ├── network/          # OkHttp/Retrofit wiring, NetworkResult, safeApiCall
│       │   ├── player/           # Deterministic autoplay, DV gates/policies
│       │   ├── stream/           # Stream resolution + presentation models
│       │   ├── sync/             # AccountConfigSyncContract (v7), sync services
│       │   ├── metadata/, tmdb/, tvdb/, poster/, recommendations/
│       │   ├── anime/, scheduler/, search/, server/, qr/, logging/, image/, locale/, util/, ui/
│       ├── data/
│       │   ├── local/            # DataStore-backed settings
│       │   ├── remote/           # Retrofit services + DTOs, Supabase RPC models
│       │   ├── mapper/           # DTO ↔ domain mapping (toDomain() extensions)
│       │   ├── repository/       # Repository implementations (incl. benchmark/, servicewrap/)
│       │   └── trakt/, trailer/  # Feature-scoped data sources
│       ├── domain/
│       │   ├── model/            # Pure-Kotlin domain models (Stream, Meta, etc.)
│       │   └── repository/       # Repository interfaces
│       ├── ui/
│       │   ├── screens/          # Compose screens (*Screen + *ViewModel + *UiState)
│       │   ├── components/, navigation/, screensaver/, theme/, util/
│       ├── workers/              # Hilt WorkManager jobs
│       └── updater/              # GitHub-Releases in-app updater
├── media/                        # Forked Media3 / ExoPlayer source (USE_MEDIA3_SOURCE)
├── nexio-web/                    # Nuxt 3 + Vue 3 companion portal (shares sync contract)
├── supabase/                     # Supabase SQL migrations (source of truth)
├── docs/, docs-site/             # Engineering & public docs (docs-site published to GitHub Pages)
├── shadow-collector/             # Opt-in telemetry dashboard
├── stremio-nuvio-importer/       # Import helper
├── tools/, scripts/              # Build + dev tooling
├── openspec/                     # Spec-driven change proposals
└── conductor/                    # Conductor context (this directory)
```

Code owners should keep `media/` and third-party AARs under `app/libs/` isolated from feature work — touch them only when a playback fix genuinely requires it.

## Voice, Naming, Terminology

### Product voice (user-facing)
- **Lean-back, confident, not breathless.** README sets the tone: concrete capabilities over marketing adjectives. ("Deterministic Autoplay", "Service Wrap", "benchmark-driven" — specific, loaded terms we own.)
- **Respect enthusiast vocabulary.** Use industry-correct terms: HDR10, Dolby Vision (DV / DV7 / DV8.1), AC3 / E-AC3 / DTS-HD / DTS:X / Atmos, passthrough, tunneled playback, frame-rate matching. Don't soften them.
- **Name the caveats.** TrueHD is not production-stable; ASS/SSA has a protected pipeline. Users trust us more when we explicitly scope what works and what doesn't.
- **No hype words for features that aren't earned.** Call them "early" / "experimental" when they are. "Best-in-class" is reserved for things we can actually measure (benchmarks, autoplay hit-rate).

### Product terminology (canonical spellings)
- **Nexio** (product). **NEXIO** in all-caps headers only when quoting existing assets. The Android package is **`com.nexio.tv`** (release) / **`com.nexiodebug.tv`** (debug).
- **Deterministic Autoplay**, **Service Wrap**, **Config Benchmark**, **Direct vs Optimized Benchmark** — proper nouns; capitalize.
- **AccountConfigSyncContract** (the Kotlin symbol) / **account-config sync contract v7** (prose).
- **Trakt / Simkl / Kitsu / Real-Debrid / Premiumize / TorBox / EasyDebrid / TMDB / TheTVDB / MDBList / OMDb / IMDb / RPDB / TOP Posters** — use official spellings and hyphenation.
- **Android TV / Fire TV** (not "AndroidTV" / "FireTV").

### Code naming (summary — full list in `conductor/docs/naming-conventions.md`)
- **Kotlin:** PascalCase files + classes; camelCase functions + properties; `UPPER_SNAKE_CASE` constants (with unit suffixes: `_MS`, `_FRACTION`, `_BYTES`).
- **Suffix vocabulary:** `*ViewModel`, `*UiState`, `*Repository`, `*DataStore`, `*Screen`, `*Contract`, `*Policy`, `*Gate`, `*Selector`, `*Service`, `*Worker`, `*Resolver`.
- **DI qualifiers:** `@Named("trakt" | "simkl" | "playback" | "benchmark" | "addonCatalog" | …)`.
- **Supabase RPC params:** snake_case with `p_` prefix (`p_contract_version`, `p_base_revision`, `p_changed_paths`).
- **Test methods:** backticked descriptive sentences (e.g. `` fun `bingeGroup-first selects matching stream before first stream mode`() ``). Test class = `<SourceClass>Test`.
- **TypeScript / web:** kebab-case files; PascalCase types; camelCase functions; `use*` composables; `*.get.ts` / `*.post.ts` server routes.

## Visual Identity

### Compose theme
- TV-first UI using `androidx.tv:tv-material` plus Compose Material3; theming centralized under `ui/theme/`.
- Dark-first palette tuned for living-room viewing; focus states must be visible at 2 m (Android TV D-pad navigation).
- Focus assertions are part of the contract: test via `onNodeWithTag().assertIsFocused()` and `performKeyInput { pressKey(Key.DirectionDown) }`.
- Compose stability is tuned in `compose_stability_config.conf`. Compose compiler metrics land in `app/build/compose_metrics` + `compose_reports`; consult them when touching hot recomposition paths.

### Motion & focus
- Navigation is D-pad-first. Preserve focus-thief guards (recent commits: "harden hero play focus against late thieves").
- Don't introduce pointer-first affordances on TV screens.

### Imagery
- Artwork pipeline: TMDB / TheTVDB primary, RPDB + TOP Posters optional. Coil + SVG support handles loading/caching.
- Trailer-first browsing: hero surfaces prefer autoplay trailer with graceful fallback to poster/backdrop when blocked.

### Iconography
- Formatter icons live under app resources as `formatter_icon_<id>.png` (kebab-case). Match this scheme for new formatter/provider additions.

## Documentation tone (internal)

- **Terse over ceremonial.** README is the bar: bullets over paragraphs, no filler adjectives.
- **Cite evidence.** When documenting a convention, name a file path (ideally with a line number). "`NetworkResult` lives at `app/src/main/java/com/nexio/tv/core/network/NetworkResult.kt`."
- **Prefer root causes over workarounds.** Call out when a fix is a workaround and why.
- **Say what's unstable.** Don't hide caveats in footnotes (TrueHD, WEB-DL DV, any experimental benchmark path).
- **OpenSpec first for non-trivial changes.** Scaffold `proposal.md` + `tasks.md` + spec deltas under `openspec/changes/<verb-led-id>/` and validate with `openspec validate <id> --strict` before implementation.

## Reference Links

- **Setup guide (users):** https://johnneerdael.github.io/nexio/start-here/
- **Features overview (users):** https://johnneerdael.github.io/nexio/features/
- **Releases:** https://github.com/johnneerdael/nexio/releases
- **Internal docs:** `docs/`, `docs-site/`, `docs/architecture/`, `docs/engineering/`
- **Conductor context:** `conductor/product.md`, `conductor/tech-stack.md`, `conductor/workflow.md`, `conductor/docs/`
- **OpenSpec:** `openspec/AGENTS.md`, `openspec/project.md`, `openspec/changes/`, `openspec/specs/`
