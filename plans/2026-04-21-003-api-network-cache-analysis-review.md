# Nexio Metadata/Integration Runtime — Secondary Architecture & Critic Review

**Date:** 2026-04-21
**Status:** Review / synthesis. Not a spec, not a plan, not an implementation.
**Inputs:**

- `plans/2026-04-21-003-api-network-cache-analysis.md` — current-state audit (mine).
- `plans/2026-04-21-003-api-network-cache-analysis-preanalysis.md` — your proposed `IntegrationHub` target architecture.
- `plans/2026-04-21-003-api-network-cache-analysis-postanalysis.md` — your critique of the current system + 7-phase migration plan.

**Goal:** independently review both documents, surface agreements, concerns, gaps and tensions, and reach a defensible "way forward" we can both sign off on before any code is written.

> This is the phase where we decide **whether and how much** of the central runtime to build, not whether it is theoretically nicer.

---

## 0. How I'm approaching this review

Three lenses, applied in order:

1. **Is the diagnosis correct?** Do the problems in the post-analysis actually hurt users today, or are they latent risk?
2. **Does the proposed design solve them?** Does the `IntegrationHub` / rail-ownership model fix the audit's concrete failure modes, or is it elegant but mis-scoped?
3. **Can we get there?** Effort vs. risk vs. scope. Is there a cheaper path that captures most of the value?

I'll be blunt about the parts I think are right, and equally blunt about the parts where I see risk or overreach. Both documents are good. The post-analysis is especially good at diagnosing the mess. The pre-analysis is a coherent target — but it is a *big* target, and a heavy restructure in production code that isn't broken-broken today. So I want us to converge on a target **and** on how much of it to actually build, not just rubber-stamp the vision.

---

## 1. Framing — what is actually being proposed

Stripped to its essentials, the proposal is:

> Route **every** outbound integration call through a single central runtime (`IntegrationHub`) that owns:
> 1. the cache (one unified Room index + blob store),
> 2. provider concurrency (one "lane" per provider, concurrency = 1 by default),
> 3. a global network gate (playback / pause / boot / credential-health pauses),
> 4. rail ownership (rails as first-class cache roots, reference-counted eviction),
> 5. a generic prefetch/hydration planner,
> 6. a generalized mutation outbox (Trakt, Simkl, future providers).

Providers become thin: they only describe requests (`IntegrationSpec`), identities, and TTL policies. Retrofit/OkHttp/Coil stop being direct dependencies of feature code.

That is a **platform rewrite of the metadata layer**, even if it's phased. It is worth treating as such when estimating effort and risk below (§7).

---

## 2. Independent review of the pre-analysis (target architecture)

### 2.1 What's strongly right

- **Centralizing the cache-first invariant is the single highest-value change.** The audit confirms §1.3 of the post-analysis directly: fresh-cache-never-hits-network is not currently enforceable, because OkHttp disk cache is disabled on Trakt/Simkl/MDBList/AddonCatalog (`NetworkModule.kt:59–83`), Coil caches images independently, `MetadataDiskCacheStore` covers only TMDB/TVDB, and Kitsu has no app cache at all. A single `IntegrationHub.get()` that reads fresh disk before lane/rate/network *is* the right lever.
- **Single-flight by cache key.** The audit enumerates multiple call-site semaphores (MDBList ratings `Semaphore(4)`, MDBList seasons `Semaphore(3)`, Trakt list items `Semaphore(3)`, Trakt episode validation `Semaphore(2)`, Simkl hydration `Semaphore(4)`). These prevent fan-out but not duplicate-key fetches. Central single-flight subsumes all of them.
- **`WorkClass` + `NetworkGate` is the right abstraction** for playback behavior. `HomePlaybackWorkGate` today only cancels ~10 home-screen enrichment jobs; it misses TVDB update worker, detail screens, MDBList, Kitsu, and outbox drains. A central gate keyed on work class is a real improvement.
- **Rail as ownership root.** This is subtle but correct. Today rail snapshots and item-level metadata live in separate stores with no joined lifecycle. When a rail refresh drops an item, nothing cleans up its posters, ratings, or metadata — and nothing protects an item that's shared with another rail. The proposed `rail → rail_item → media_item → cache_entry` graph with `stale_until`-aware cleanup is architecturally clean and solves a real long-term storage-growth problem.
- **Retention classes, especially the split between `RailScoped` / `UserScoped` / `RecentlyViewed` / `GlobalReusable`.** This is the right taxonomy. User library state, TVDB reference tables, and rail-scoped discovery summaries genuinely have different lifetimes today, and we conflate them.
- **Providers expose specs, not Retrofit services.** This is the extensibility win. The audit shows at least 17 distinct API surfaces currently in the default OkHttp client (TMDB, TVDB, Kitsu, RPDB, TopPosters, OMDB, GitHub, IntroDb, Trailer, AniSkip, ARM, AnimeSkip, RealDebrid, Premiumize, TorBox, EasyDebrid, ImdbSearch). Without a spec-factory pattern, every new provider reinvents its cache, TTL, and (maybe) semaphore.

### 2.2 Concerns

- **"Concurrency = 1 for every provider" is overcorrection and will regress user-visible performance.** The audit catalogues several places that deliberately parallelize: TMDB append-to-response does multiple parallel requests, MDBList ratings fetches 4 providers at a time, Simkl hydration runs 4 at a time. Dropping those to 1 adds visible latency on home and detail screens. I'd accept concurrency=1 as the *initial safe default* but only with a first-class way to raise it (see §4.3 below — concurrency validation policy).
- **Actor-per-provider vs. `Mutex.withLock` (preanalysis L293).** The preanalysis correctly notes an actor is cleaner operationally, but the example code shows `mutex.withLock` for the full request duration. Using a mutex around the whole network call means a single slow call blocks a 500 KB poster fetch behind a 30-second detail fetch. A queue with per-item priority (or a capacity semaphore) is a materially better default.
- **Room as the cache index is right, but be careful with blob storage.** The preanalysis correctly flags that `cacheDir` can be wiped by the OS (L242). But it understates the migration cost: RPDB posters are ~50–200 KB, we cache many per rail, and moving them out of Coil's managed cache into our own blob store without a compaction/budget strategy creates a new storage-growth class of bug. Plan needs explicit disk budget per retention class before we ship phase 4.
- **The second cache check inside the lane (preanalysis L267–269) is necessary but not sufficient for deduplication.** Two callers missing the cache at nearly the same time both enter the single-flight; that's fine. But three callers for *related* specs (e.g. TMDB `movieDetails` for movie 550 with `append=credits` vs. `append=credits,images`) produce different cache keys yet redundant network work. The preanalysis doesn't address spec-family deduplication; that's a known hard problem and we should explicitly defer it rather than pretend single-flight by exact-key solves it.
- **`stale_until` semantics need a rule, not just a field.** Using `stale_until` for rail-displayability is the right idea, but the preanalysis doesn't specify: what *creates* `stale_until` on error (provider 5xx? 429? timeout?), how long it extends, and when it's cleared. This is exactly the kind of detail that sinks rewrites.
- **Auth refresh is mentioned but not designed.** Preanalysis L906–907 (`credentialGate.awaitAllowed(spec.provider)`) hand-waves auth. Today Trakt/Simkl have dedicated auth services with refresh-on-401 flows; Kitsu has its own auth; debrid providers have their own. A `CredentialGate` is a real component, not a one-liner; if it's underspecified in phase 2 we will pay for it later.

### 2.3 Missing pieces

- **No error-handling contract.** What does the hub return on 5xx / timeout / no-network / 401? `FetchResult.Failed(error, staleFallbackAvailable)` exists (preanalysis L765) but the semantics aren't specified — do we auto-fall-back to stale? Does stale count against TTL extensions? Does UI see `Failed` or `Stale(oldValue, staleness=…)`? Without this, every feature migrating onto the hub has to invent its own error UX.
- **No 429 / Retry-After story** (user-flagged gap — covered in §4.2).
- **No observability model.** Post-analysis phase 1 mentions it, but the preanalysis target architecture has no explicit surface for per-call provenance, cache hit/miss, queue wait, or provider health. If observability isn't *baked into the hub API*, we won't be able to diagnose regressions during the migration. This is not optional.
- **No mutation path design.** `IntegrationHub.get()` is well-specified; mutation (scrobble, watch/unwatch, rate, list-add) isn't. The post-analysis §7 mentions generalizing the outbox, but the hub API in preanalysis only exposes `get / prefetch / refreshRail / pauseNetwork / setPlaybackActive`. Mutations need their own first-class API — at minimum an `enqueueMutation(spec, payload)` with ordering guarantees.
- **No migration compatibility surface.** If `IntegrationHub` is the *only* supported path, then on day 1 every feature that still goes through `TmdbApi` directly must be either migrated or shimmed. A shim layer (old Retrofit interface → hub under the hood) is essential for a phased rollout and isn't discussed.

---

## 3. Independent review of the post-analysis (critique + 7-phase plan)

### 3.1 Diagnoses I agree with

- **§1.1 "No single network authority"** — audit-confirmed. `MetaDetailsViewModel` calling Trakt comments directly, Kitsu having no cache, RPDB/TopPosters outside the provider runtime, TVDB update worker on its own path — all real, all present in current code.
- **§1.2 "Five fragmented cache families"** — the audit identifies at least six if you count Trakt/Simkl/MDBList auth datastores separately. The post-analysis's count is right.
- **§1.3 "Fresh cache doesn't universally mean no network"** — most important finding. Trakt/Simkl/MDBList explicitly disable the HTTP cache *and* don't have a comprehensive app-level disk cache for non-library data (Trakt comments = no cache at all; MDBList ratings = memory only). This genuinely is the highest-leverage change.
- **§1.4 "Playback gating is too narrow"** — confirmed. `HomePlaybackWorkGate` cancels ~10 home enrichment jobs; it does not touch the TVDB update worker (which is big and boot-scheduled), detail screens, or outbox drains.
- **§1.7 "Rails aren't ownership roots"** — confirmed. `MDBListDiscoverySnapshotStore`, `TraktLibrarySnapshotStore`, `SimklLibrarySnapshotStore`, `CatalogDiskCacheStore` each have their own semantics and none of them cross-reference at the item level.
- **§1.8 "Simkl on Trakt outbox is a naming smell"** — confirmed (`SimklScrobbleService`, `SimklLibraryService`, `SimklProgressService` all call `TraktMutationOutboxCoordinator`). This isn't a bug but it is the kind of cross-wiring that makes new contributors trip.
- **§1.10 "TopPosters base URL drift"** — audit §9 flagged the same thing. `PosterRatingsUrlResolver` uses `api.top-posters.com`, test fixtures reference `api.top-streaming.stream`. Worth resolving before we bake provider identity into cache keys, or we pay for the bug twice.

### 3.2 Diagnoses where I'd temper the framing

- **§1.5 "Provider concurrency rules inconsistent."** True, but the implication ("all providers should be concurrency=1") isn't supported. Trakt's 500 ms gate is protecting against Trakt's server-side policy. TMDB has no such policy and parallelism is intentional. The right frame is "concurrency policy should be explicit per provider," not "everything to 1."
- **§1.6 "Boot fan-out not centrally scheduled."** True, but the biggest boot work item is the TVDB update coordinator, and that is already deferred-scheduled via WorkManager *after* the immediate catch-up. The immediate catch-up is the thing that should move into a `BackgroundHydration` work class — not the periodic WorkManager job. I'd narrow the §1.6 claim to "immediate boot network work isn't centrally scheduled."
- **§1.9 "RPDB/TopPosters outside the runtime."** Agree this is wrong architecturally. But Coil with a 10-day TTL plus `ImageCacheTtlWorker` is actually one of the *best-behaved* parts of the current system from a user-visible correctness standpoint. The migration risk is high and the user-visible win is medium. I would phase this *later* than the post-analysis suggests, not earlier (see §6).

### 3.3 7-phase migration plan — assessment

The phases:

| Phase | Scope | My assessment |
|---|---|---|
| 1. Observability | Interceptor logging + debug screen | **Agree, do first.** Also cheap — ~1–2 weeks. |
| 2. Kitsu POC | Move Kitsu onto `IntegrationHub` | **Agree Kitsu is the best POC candidate.** But this phase quietly requires building `IntegrationHub`, `CacheStore`, `ProviderLane`, `NetworkGate`, `SingleFlight`, Room schema, `IntegrationSpec` model, `FetchResult`, and the test harness. It's not a "small first phase" — it's the entire platform. We should either rename it or split it. |
| 3. Trakt comments + MDBList ratings | Two obvious gaps | Agree these are next. Easy user-visible wins. |
| 4. RPDB / TopPosters via hub | Coil fetcher or file resolver | **Defer.** High risk, medium return. See §6. |
| 5. TMDB / TVDB | The big one | **Needs splitting.** TVDB has update invalidation which is its own subproject. |
| 6. Rails first-class | Ownership graph + orphan cleanup | **Agree, but only once 2/3/5 are done.** Rail ownership without anything to own is empty. |
| 7. Mutation outbox generalization | Trakt + Simkl + future adapters | **Agree, can happen in parallel with 5/6.** It's largely a renaming + abstraction exercise. |

The plan's biggest risk is that **phase 2 is undersold** — it's where all the foundational work actually happens. If we schedule phase 2 as "a few weeks to migrate Kitsu" we'll blow the estimate and demoralize the effort.

### 3.4 What the post-analysis is missing

- The extended API inventory (user's first flagged gap — §4.1 below).
- The 429 / Retry-After / backoff design (user's second flagged gap — §4.2).
- A concurrency-validation policy that lets us *safely raise* concurrency from 1 per provider (user's third flagged gap — §4.3).
- A credible effort estimate (§7).
- Rollback / kill-switch design for the migration itself. If phase 2 ships and half of Kitsu is on the hub and half isn't, we need a runtime flag to force everything back through the old path while we diagnose.

---

## 4. Gaps flagged by you

### 4.1 Extended API inventory — real-debrid, premiumize, torbox, easydebrid, theintrodb, omdb, anime-skip

The audit found that all of these already live in the default OkHttp client (row 37 of the client catalogue). They behave differently enough that a one-size-fits-all lane default of concurrency=1 would be wrong for some of them.

I'd classify them in four tiers:

| Tier | Providers | Behavior | Lane config |
|---|---|---|---|
| **A — Playback-critical, debrid** | real-debrid, premiumize, torbox, easydebrid | User clicks "play". We fan-out *on purpose* to find the fastest working link. Latency is UX. Requests are small. 429s happen but are provider-specific. | concurrency **4–8**, `allowDuringPlayback = true`, `neverPause = true`. |
| **B — Metadata, long-TTL, per-episode** | theintrodb / introdb, anime-skip (intro/outro timestamps), aniskip, arm | Called once per episode playback; response is immutable after publication; cache hit rate should approach 100% once populated. | concurrency **1–2**, long TTL (30+ days), `allowDuringPlayback = true` (they're playback-adjacent), cacheable to disk. |
| **C — Metadata fallback** | omdb, github (version check) | Fallback / supplementary only. Rare. | concurrency **1**, `allowDuringPlayback = false`, ephemeral cache OK. |
| **D — Already in audit's 8** | trakt, simkl, tmdb, tvdb, kitsu, mdblist, rpdb, topposters | As discussed. | Per-provider, see post-analysis §9. |

Consequences for the design:

- **Lane config must support `allowDuringPlayback` and `neverPause` flags**, not just concurrency. The preanalysis's `NetworkGate` hints at this via `WorkClass` but the lane config only has `maxConcurrent` and `minStartGapMs` — it needs a pause-policy field.
- **`WorkClass` needs two additions**: `PlaybackResolution` (for tier A — debrid link resolution during play) and `PlaybackAdjacent` (for tier B — intro/outro timestamps). These cannot be blocked by `playback pause` the same way background hydration is.
- **Cache policy for tier B is "one-shot per episode, essentially never refetch"**. Retention class should be `GlobalReusable` or a new `EpisodeImmutable` — *not* `RailScoped` (the episode isn't on a rail) and *not* `UserScoped` (it's not user-specific).
- **Debrid providers may need request ordering, not dedup.** In practice we try them in user-preference order and take the first hit. Single-flight by cache key doesn't match that use case — it's a different abstraction (a *resolver chain*). The hub should either accommodate this or we should keep debrid resolution outside the hub and *only* pipe it through the observability/gate layer. My recommendation: the latter — keep debrid resolution as a specialized path, but use the same logging/gate substrate.

**Bottom line on 4.1:** the `IntegrationHub` design as written is biased toward metadata-style GETs. Before we commit to it as the universal API, we need to explicitly decide which API classes are in scope and which are adjacent (instrumented but not migrated). My recommendation: scope the hub to tiers B, C, D. Leave tier A (debrid) using the lane/gate/observability infrastructure but *not* the cache/single-flight/spec model.

### 4.2 429 / rate-limit handling

Neither document specifies this. Today the audit confirms: there is *no* centralized 429 handling anywhere. Trakt/Simkl gates throttle request *starts* but do not react to 429 responses. OkHttp has no retry-on-429 interceptor. This is a real latent bug — a server-side policy change would cascade into user-visible failures with no graceful degradation.

A minimally correct 429 design:

1. **Parse `Retry-After` on every response**, regardless of provider. Supported formats: delta-seconds, HTTP-date.
2. **Per-provider back-off state** kept in the lane. On 429:
   - Lane enters `Cooling` state with `cooldownUntil = now + retryAfter` (clamped to sensible min/max, e.g. 1 s / 5 min).
   - All requests on that lane block until `cooldownUntil`.
   - The triggering request fails with `FetchResult.RateLimited(retryAfter)` and the caller's policy decides whether to serve stale cache, wait, or surface to UI.
3. **Exponential back-off on repeated 429s without Retry-After**: 1s → 2s → 5s → 15s → 60s, reset after a successful request.
4. **Circuit-breaker after N consecutive 429s or 5xx** (e.g. 5). Lane enters `Tripped`, all non-cache reads return `Pending` / `Stale`. Auto-reset after a longer cooldown (e.g. 15 min). Telemetry event fired.
5. **5xx treated as a weaker signal** — retry-once with short back-off, then treat as transient error. Do not circuit-break on a single 5xx.
6. **Explicit pause reason**: `NetworkGate.pause(PauseReason.RateLimited(Provider.MDBLIST, untilMs))` so UI can show "MDBList is rate-limited, retrying in N seconds" if we want (power-user / debug screen at minimum).
7. **Telemetry mandatory.** Every 429, every circuit-break, every Retry-After parse goes to the observability layer from phase 1. Without this we won't know which providers are actually rate-limited in the wild.

**Explicit non-goal:** client-side predictive rate limiting. Don't try to guess the provider's bucket; react to 429 and Retry-After. Any predictive scheme is a source of bugs and false throttling.

### 4.3 Concurrency validation policy — "heavy concurrent API calls only when validated and approved"

This is the most important governance question and the one most easily forgotten when coding starts.

Proposal:

**Every provider lane has two numbers:**

- `minConfirmedConcurrency` — the starting, conservative default. All providers launch at **1** (debrid at 2).
- `desiredConcurrency` — the target we'd like to run at, if validation permits. For TMDB this might be 4; for TVDB 2; for MDBList 1; for RPDB 2.

**Raising `actualConcurrency` above `minConfirmedConcurrency` requires all of:**

1. **≥7 days of clean telemetry** at the current level (zero 429s, zero circuit-breaks, p95 latency stable).
2. **Explicit config change** in a checked-in constant (no runtime auto-tune). Code review gate.
3. **Kill-switch** (`@Named("lane_concurrency_override")` remote config or local flag) that can snap any lane back to `minConfirmedConcurrency = 1` within one app restart.
4. **Debug-screen visibility** of the current `actualConcurrency` per lane.

**The lane never auto-raises concurrency.** Auto-tuning based on success rate sounds clever and is a source of oscillation / thundering-herd bugs. Human gate, always.

**What about burst requests for the same provider?** The audit shows TMDB append-to-response already handles most of this server-side (one request, multiple sections). For the cases where we still fan out (home enrichment, detail prefetch), the cap is `actualConcurrency`, not `dispatcher.perHost`.

This gives us a concrete answer to the user's requirement: "prevent heavy concurrent api calls for a service unless validated and approved." The answer is: **default-1, raise only by explicit checked-in config after clean telemetry, kill-switchable at runtime.**

---

## 5. Tensions & open questions between the documents

| # | Tension | Where | Resolution |
|---|---|---|---|
| T1 | Pre says lane is `mutex.withLock` for full request; post says lane is queue with priority. | pre L263, post L607–617 | Queue/priority. Mutex blocks poster fetches behind detail fetches. |
| T2 | Pre says concurrency=1 everywhere; audit shows intentional parallelism (TMDB append, MDBList 4-way, Simkl 4-way). | pre L18–24, audit §1 | Adopt §4.3 policy: start at 1, `desiredConcurrency` higher for specific providers, human-gated promotion. |
| T3 | Pre says prefetch is "just another low-priority request"; post says prefetch and background hydration should be pausable during playback. | pre L722–736, post §1.4 | No real conflict but it needs stating: prefetch is routed through the hub *and* has `WorkClass.Prefetch`, which the gate pauses during playback. |
| T4 | Pre's `NetworkGate.pause(reason)` is undirected; different reasons (playback, 429, credential-dead) need different semantics. | pre L342–371 | `PauseReason` is a sealed type, not a string. `playback` pauses all except `PlaybackCritical`/`PlaybackResolution`/`PlaybackAdjacent`; `rateLimited(provider)` pauses only that lane; `credentialsExpired(provider)` pauses only user-scoped work on that provider. |
| T5 | Pre includes RPDB/TopPosters as lanes (L18–24); post defers the Coil migration to phase 4. | pre L18–24, post §11 phase 4 | Include them in the *model* (they're lanes conceptually) but migrate last (phase 6 in my revised sequencing — §6.3). |
| T6 | Both docs assume Room + blobs for everything. But `MetadataDiskCacheStore` already works and predates Room. | audit §5 | Migration: new data goes to new store; old store co-exists during phases 2–5; consolidation is its own phase. Don't double-write. |
| T7 | Neither doc addresses mutations (scrobble, watch/unwatch, list-add). Outbox exists for Trakt+Simkl today. | post §1.8, §11 phase 7 | Mutations are first-class on the hub API: `hub.enqueueMutation(spec, payload)`. Generalize Trakt outbox → `ProviderMutationOutbox` in phase 7. |
| T8 | Neither doc specifies what happens when a rail expires during playback (can't refresh) but UI scrolls to a new page. | pre §rail, post §7 | UI serves stale rail (via `stale_until`) + placeholder for missing item data; no network until playback ends. Needs spec. |

---

## 6. Proposed synthesis — recommended way forward

### 6.1 Non-negotiables (do these or don't start)

1. **Observability first.** Phase 1 of the post-analysis plan, unchanged. Network interceptor that records provider, host, endpoint group, work class, cache key, hit/miss, fresh/stale/network, queue wait, network duration, bytes, response code, playback-active. Debug screen. *Without this we can't measure whether the migration helps.*
2. **Cache-first invariant is the core goal.** Every migrated path must satisfy: `if fresh cache exists, return it without entering any lane, rate limiter, or network code`. This is the only invariant that actually improves user-perceivable behavior.
3. **429/Retry-After handling and circuit-breaker (§4.2)** ships alongside phase 2. Not after. Provider servers can change policy tomorrow; we need to degrade gracefully before we go wide.
4. **Concurrency starts at 1 per provider (2 for debrid); raising requires §4.3 gate.** No auto-tune.
5. **Kill-switch per migrated provider.** A runtime flag that forces `TmdbService` (etc.) back to the old path. Without this, the migration is too risky to deploy.

### 6.2 What I'd keep from the preanalysis target

- `IntegrationHub` as the single entry point for GETs.
- `IntegrationSpec` as the normalized request model.
- `ProviderLane` per provider, with full lane config (concurrency, start-gap, pause policy, timeout).
- `NetworkGate` with sealed `PauseReason`.
- `CacheStore` = Room index + blobs.
- `FetchResult` sealed type with `Fresh / Updated / Stale / Pending / Miss / Failed / RateLimited` variants.
- Single-flight by cache key.
- Rail-aware eviction using `stale_until` (but implemented last).
- Retention classes as defined, plus `EpisodeImmutable` for tier B (anime-skip / introdb / aniskip).

### 6.3 What I'd revise from the preanalysis target

- **Lane = priority queue, not mutex-around-network.**
- **Lane config = `{ maxConcurrent, minStartGapMs, allowDuringPlayback, neverPause, defaultTimeoutMs, queuePolicy }`.**
- **`WorkClass` adds `PlaybackResolution`, `PlaybackAdjacent`, `MutationOutbox`.**
- **Mutation API as first-class:** `hub.enqueueMutation(spec, payload)` with durability via `ProviderMutationOutbox`.
- **Scope of hub ≠ all traffic.** Debrid resolution (tier A in §4.1) stays in its specialized path but uses the observability and rate-limit substrate.

### 6.4 Revised phase sequencing

I'm reordering to land the highest-value/lowest-risk work first and defer the RPDB/TopPosters Coil rewrite until the foundation is proven.

| Phase | Content | Scope |
|---|---|---|
| **P1 — Observability** | Interceptor + debug screen + telemetry schema. No behavior change. | Whole app, instrumentation only. |
| **P2 — Foundation** | Build `IntegrationHub`, `IntegrationSpec`, `ProviderLane`, `NetworkGate` (sealed `PauseReason`), `CacheStore` (Room schema + blob store), `SingleFlight`, `FetchResult`, lane config, 429/Retry-After handling, circuit-breaker, kill-switch. **No providers migrated yet.** Unit tests for all invariants. | Pure platform. 4–6 weeks. This is what post-analysis's "phase 2" implicitly requires. |
| **P3 — Kitsu on hub** | Migrate Kitsu (small surface, no existing cache). Proves the platform end-to-end. Fallback flag in place. | 2–3 weeks after P2 lands. |
| **P4 — Gap fills** | Trakt comments + MDBList ratings + tier B (introdb / anime-skip / aniskip / arm). Obvious user-visible wins, no cache to migrate (add cache where there's none). | 3–4 weeks. |
| **P5 — Mutation outbox generalization** | Rename `TraktMutationOutboxCoordinator` → `ProviderMutationOutbox`; add adapter pattern. Can run in parallel with P4 if we have the people. | 2 weeks. |
| **P6 — TMDB migration** | Move TMDB details/images/credits/videos/reviews/recommendations onto the hub. Reduce in-memory maps to short-lived L1. | 3–4 weeks. |
| **P7 — TVDB migration** | TVDB metadata calls + update invalidation events + worker as `Maintenance` work class. | 4 weeks. TVDB update is a subproject. |
| **P8 — Rails first-class** | Move discovery snapshots to `RailStore` / `RailItemStore` / `MediaIdentityStore`. Implement `stale_until`-aware orphan cleanup. | 4 weeks. |
| **P9 — RPDB / TopPosters** | Custom Coil fetcher backed by hub, or local-file resolver. Only after storage budget story is solid. | 3 weeks. Previously post-analysis phase 4; deferred. |
| **P10 — Consolidation** | Retire `MetadataDiskCacheStore`, provider-specific snapshot stores where duplicated, process-lifetime maps. | 2–3 weeks. |

### 6.5 Deferred / explicitly out of scope

- **Auto-tuning of concurrency.** Human gate per §4.3.
- **Spec-family deduplication.** (TMDB `append=credits` vs. `append=credits,images` as related work.) Known hard, defer.
- **Debrid resolution migration onto the hub.** Keep specialized, instrument.
- **Coil's own disk cache retirement.** Let Coil own thumbnails/posters sourced directly; hub owns only provider-specialized image variants (RPDB/TopPosters-generated).
- **Playback prefetch policy rewrite.** Out of scope for this restructure; existing `HomePlaybackWorkGate` behavior preserved via `WorkClass` mapping.

---

## 7. Effort / risk realism

### 7.1 Effort estimate

Adding phases P1–P10 with single-digit weeks per phase: **roughly 6–9 months of focused work for one engineer**, or 4–6 months with two. The post-analysis's phases (7 of them, no estimate given) implicitly compress this.

The riskiest estimates:

- **P2 (foundation) is 4–6 weeks if nothing goes wrong.** It will go wrong. Budget 8 weeks and don't start P3 until P2's invariants have integration tests passing under load.
- **P7 (TVDB) is 4 weeks and may slip.** TVDB update invalidation touches many memory caches; every one needs to subscribe to invalidation events.
- **P8 (rails) is 4 weeks and depends on identity resolution.** `IdentityStore` (external_id → media_key) is non-trivial: different providers give different ID types at different points, we sometimes only know the TMDB ID until enrichment attaches IMDb/TVDB/Trakt IDs.

### 7.2 Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| P2 foundation bugs cascade across all later phases | **High** | Extensive unit + integration tests in P2 before any provider migrates. Kill-switch mandatory. |
| Storage growth from unified blob store | **Medium** | Per-retention-class disk budget implemented in P2. Eviction tested in P2. |
| User-visible latency regression from concurrency=1 | **Medium** | Telemetry comparison (pre-migration vs. post) before/after each provider migration. §4.3 gate to raise concurrency. |
| Migration partially lands, half-old/half-new | **High** | Kill-switch per provider. Fallback path preserved until the provider is fully on the hub AND telemetry is clean for ≥2 weeks. |
| TVDB update invalidation misses a memory cache | **Medium** | All memory caches in P7 migrate to subscribe to invalidation events; checked-in inventory. |
| Coil replacement for RPDB introduces flicker/broken-image bugs | **Medium** | P9 deferred until P2–P8 are stable. Feature-flagged. Compare against baseline visually before launch. |
| Auth refresh races with lane serialization | **Medium** | `CredentialGate` is explicitly designed in P2, not retrofitted in P3. |
| "Just tune OkHttp" never gets tried, and turns out to be 80% of the value | **Low but worth acknowledging** | See §9 — pragmatic alternative evaluation before committing to the full platform rewrite. |

---

## 8. Decision points requiring explicit sign-off before coding starts

1. **Do we commit to the platform rewrite (§6.4 P1–P10) as the direction?** Yes / No / Conditional.
2. **Is the effort estimate of 6–9 months acceptable?** If not, are we willing to reduce scope (e.g. stop at P7) or add people?
3. **Concurrency default = 1 per provider (2 for debrid), raising per §4.3.** Agreed?
4. **Scope of hub excludes debrid resolution (tier A in §4.1), treats it as instrumented-but-specialized.** Agreed?
5. **RPDB/TopPosters migration deferred to P9, not P4.** Agreed?
6. **Rails as first-class is P8, not P6.** Agreed? (Post-analysis had rails earlier.)
7. **Coil's disk cache stays for non-provider-generated images; hub owns only provider-specialized images.** Agreed?
8. **TopPosters base URL anomaly (`api.top-posters.com` vs `api.top-streaming.stream`) is resolved as a prerequisite, not during migration.** Who owns this investigation?
9. **Kill-switch + old-path fallback is mandatory for every migrated provider.** Agreed?
10. **Observability (P1) is a standalone deliverable we ship and run for ≥2 weeks before starting P2.** Agreed?

---

## 9. Pragmatic alternative path (for comparison)

If the answer to decision #1 is "no" or "conditional," there's a ~20% effort path that captures ~60% of the value:

**What you'd build:**

1. **Observability as P1.** (Same as full plan.)
2. **Central response interceptor** that, regardless of provider, parses 429/Retry-After, applies exponential back-off, and circuit-breaks. (~1 week; a single OkHttp `Interceptor`.)
3. **Central per-host concurrency limit** via OkHttp `Dispatcher.setMaxRequestsPerHost(provider, n)`. Still not truly a lane (no priority queue, no pause policy) but it does bound concurrency. Default n=1, raise per §4.3.
4. **Fill the three obvious cache gaps** without a unified model: Kitsu disk cache in `MetadataDiskCacheStore`, Trakt comments cache, MDBList ratings disk cache. (~2 weeks each.)
5. **Widen the playback gate.** `HomePlaybackWorkGate` → `AppPlaybackWorkGate`, with a `WorkClass` enum that any worker/job/service can register against. Route TVDB update catch-up, outbox drains, MDBList refresh, and detail-screen prefetch through it. (~2 weeks.)
6. **Rename `TraktMutationOutboxCoordinator` → `ProviderMutationOutbox`.** (~1 week.)

**What you give up:**

- Rail-aware eviction (storage growth remains unbounded on long-running installs).
- `IntegrationHub` as a single entry point (direct Retrofit usage remains possible).
- Unified identity model (cross-provider item correlation remains ad-hoc).
- The "every new provider is trivially safe" extensibility property (new providers still reinvent their cache).

**Total:** ~8–10 weeks, low risk, no platform rewrite. Captures the **user-visible** wins (429 handling, cache gaps filled, playback gate broader, rate-limit safety) without the architectural-ambition wins.

**My honest recommendation:** if the team is small, do the pragmatic path first, ship it, then decide whether the full rewrite is still worth doing in 6 months based on how much pain remains. If the team can support 6–9 months of focused platform work and we agree on the decision points in §8, the full plan is the right long-term answer.

The worst outcome is **starting the full rewrite, stopping halfway, and living with a hybrid** — that gets the cost of both plans and the benefits of neither.

---

## 10. Recommended first concrete step once we agree

Assuming we adopt §6.4 (full plan):

**Ship P1 (observability) and nothing else, for 2 weeks of real usage.** Then:

1. Measure cache hit/miss ratios per provider.
2. Measure queue-wait time (today: only Trakt/Simkl have a queue to measure, but we can instrument OkHttp dispatcher wait).
3. Measure actual 429 rates per provider.
4. Measure cold-start fan-out (how many requests hit the network in the first 30 s after launch).
5. Identify the top 5 endpoints by byte count and by frequency.

That data decides the exact migration sequence inside P3–P10 and gives us a baseline to compare against after each migration. It also tells us whether the pragmatic path (§9) would actually be enough — because if cache hit rate is already 90% for TMDB and 429s are near zero for everyone, the full rewrite is solving a smaller problem than it looks.

**Do not start P2 until P1 data is in hand.**

---

## Appendix A — Provider-by-provider summary of my recommendation

| Provider | Keep current | Change | Migration phase |
|---|---|---|---|
| Trakt | gate, outbox, library store | move through hub, generalize outbox, cache comments | P4, P5 |
| Simkl | gate | hub, own outbox adapter, retain activity-driven freshness | P4, P5 |
| TMDB | `MetadataDiskCacheStore` TTLs | hub, single-flight, L1 shrink | P6 |
| TVDB | disk cache, update coordinator | hub, invalidation events, worker as Maintenance | P7 |
| Kitsu | (nothing — no cache today) | full hub migration, disk cache, lane | P3 (POC) |
| MDBList | discovery snapshot store | hub, ratings disk cache, replace semaphores with priorities | P4 |
| RPDB | Coil caching | custom Coil fetcher or file resolver via hub | P9 |
| TopPosters | Coil caching | same as RPDB; resolve base URL anomaly first | P9 (prereq: URL investigation) |
| RealDebrid / Premiumize / TorBox / EasyDebrid | current direct path | **do not migrate**; instrument + 429 handling only | P1 + 429 interceptor |
| IntroDb / AnimeSkip / AniSkip / ARM | current direct path | hub migration, `EpisodeImmutable` retention | P4 |
| OMDB | current direct path | hub migration, ephemeral cache | P4 |
| GitHub (version) | current direct path | hub migration, long TTL | P4 |

---

## Appendix B — Open questions for you before we commit

1. How many engineers / weeks can we realistically commit?
2. Is there an external deadline (release, feature launch) that would be impacted by a 6–9 month project?
3. Storage budget: what's the max we're willing to use on unified blob cache? (Today: OkHttp 50 MB + Coil 200 MB + MetadataDiskCacheStore + provider snapshot stores ≈ somewhere around 300 MB in the worst case.) The unified store should have an explicit cap.
4. Are we willing to ship telemetry to a backend, or is on-device-only debug screen sufficient? (Affects how quickly we can validate concurrency raises per §4.3.)
5. Which tier-A (debrid) provider behavior are we most worried about? This affects whether we need to migrate debrid sooner than P10+.
6. Is Simkl's dependence on `TraktMutationOutboxCoordinator` causing real bugs today, or just confusion? (Affects P5 priority.)

Once we have answers to §8 and Appendix B, we have a plan. Until then, we have a review.
