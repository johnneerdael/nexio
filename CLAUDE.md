<!-- OPENSPEC:START -->
# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:
- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:
- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

NEXIO is an Android TV / Fire TV streaming app built with Kotlin and Jetpack Compose.

- Package: `com.nexio.tv`
- Core areas: debrid integration, Trakt sync, benchmark-driven playback
- Playback stack: forked Media3 / ExoPlayer with custom extensions

When using subagent-driven-development always continue your tasks sequentially untill every task is completed without stopping!

---

## Hard design rules (do not regress)

These are project-wide invariants. Every one was learned from a real death-spiral / unresponsive-app investigation on this codebase. **New features must obey them.** If unsure whether a change violates a rule, capture a heap dump (see `analysing-heap-dumps` skill) before claiming the change is performance-neutral.

### 1. Display authority — first paint never downgrades

The full architecture is in `docs/superpowers/notes/2026-05-09-modern-home-leak-root-cause.md` and the *resolved-display authority* hard-rule doc supplied during PR review. TL;DR:

- **`ResolvedDisplaySurfaceRepository` is the single display authority.** UI surfaces (home, hero, screensaver, continue watching, detail) must consume `ResolvedDisplayItem` (or an approved per-surface projection like `ModernHomeRowItem`), never raw `MetaPreview` rows.
- **`HomeRailProjectionReducer` is the only place that merges firstPaint + overlay + existing.** Surfaces must not implement their own "if poster null fall back to backdrop" logic. Non-downgrade is enforced once, in the reducer.
- **First paint may only initialize.** It can fill empty slots when no resolved/cached state exists. It must never overwrite a `RESOLVED` or `STALE_RESOLVED` slot, never replace a hydrated logo/backdrop with null, never demote a premium poster to a raw provider URL.
- **`ArtworkBundle.poster` carries `POSTER` only.** Never `poster ?: backdrop`. Portrait card slots are typed.
- Diagnostic event: `home.display_projection` (`Nexio.MetaRoute` logcat tag). If `selected.<field>.rank == FIRST_PAINT` for items that should be hydrated, the rule is being violated upstream.

### 2. State retention — don't put hot lists in observed UiState

Compose's `SnapshotMutableStateImpl$StateStateRecord` chain retains prior versions of every observed `MutableState`. `List<MetaPreview>` / `List<CatalogRow>` fields on `HomeUiState` (or any other `data class State` collected by Compose) pin every recomposition's prior snapshot — observed in heap dumps as 6,000+ retained `CatalogRow` instances.

**Rule:** if a list is "source/lookup data" rather than "what's being rendered right now", expose it as a separate `StateFlow` on the ViewModel and collect it independently inside composables.

Pattern (committed examples):
- `HomeViewModel._displayCatalogRows` (commit `4ee5a26b6`)
- `HomeViewModel._displayHeroItems` (commit `cb59c1a5e`)

Don't reintroduce `catalogRows`, `heroItems`, `continueWatchingItems`, or any new `List<X>` field of source data into `HomeUiState`. Add new ones to ViewModel-level `StateFlow`s.

### 3. Persistence — no large blobs in SharedPreferences; stream JSON

**Never put >50 KB of data in SharedPreferences.** SharedPreferences serializes the entire map to XML on every commit, escaping every char. A 7.86 MB JSON blob via `prefs.putString(...).commit()` produced a 72 MiB transient `char[]` per persist on this codebase (commit `635ed6eff` reverted this anti-pattern).

**For JSON files >100 KB, do not use `gson.toJson(value)` then `writeText(...)`.** That overload allocates a `StringWriter` internally → materializes the whole JSON in memory as String + UTF-16 char[] (~2× file size) plus a UTF-8 byte[] before writing.

Use the streaming pattern (committed reference: `HomeCatalogSnapshotStore.streamSnapshotToFile`, commit `bc7b5061a`; artwork-cache stores, commit `d2272e8f1`):

```kotlin
FileOutputStream(tempFile).use { fos ->
    BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
        JsonWriter(bw).use { writer ->
            gson.toJson(value, type, writer)  // streams tokens directly
        }
    }
}
Files.move(tempFile, target, ATOMIC_MOVE, REPLACE_EXISTING)
```

**Don't read-after-write for diagnostic verification.** Atomic rename is strongly consistent. Re-parsing what you just wrote allocates O(file_size) for no information gain (commit `1c78824d7`).

**For JSON cache reads >50 KB, do not use `gson.fromJson(rawString, type)`.** That overload wraps the String in a `java.io.StringReader` whose `str` field pins the entire String for the duration of the parse. Multiple concurrent reads of similarly-shaped TVDB cache entries (per Modern Home pipeline emission) appeared in the heap as 3 × 205 KiB transient `char[]` orphans plus the String backing storage — observed via `heaptrail -i ... -l --preview-bytes 65536` showing matching `{"airsDays":...}` content held by `StringReader.str`. Use a streaming `JsonReader` over a `BufferedReader` so the file/bytes-on-disk are never materialized as a String at all:

```kotlin
FileInputStream(file).use { fis ->
    BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
        JsonReader(br).use { reader ->
            gson.fromJson<T>(reader, type)  // streams tokens; no big String
        }
    }
}
```

When SharedPreferences is the read source, the prefs map already materializes each entry as a String (no streaming entry point exists), so the rule only applies once the migration to file-backed JSON has happened — but that migration is itself implied by the >50 KB SharedPreferences ban above.

### 4. Coroutines — no suspending forEach over lists

`list.forEach { ... suspend ... }` allocates an `ArrayList$Itr` whose `this$0` field pins the parent list. When the lambda body suspends, the iterator is saved in the continuation — pinning the list across all suspension points until the function completes. On this codebase that retained 33,000+ iterators with hundreds of MB of CatalogRow chains (commit `7bdffc525` audit).

**Rule:** in any `suspend fun` (or any lambda that may suspend, including coroutine bodies), iterate via `for (i in list.indices) { val item = list[i]; ... }` instead of `list.forEach { ... }` / `list.forEachIndexed { ... }`. Indexed-for compiles to a primitive int counter — no iterator allocation, nothing pinned.

`list.map { ... suspend ... }` and `list.flatMap { ... suspend ... }` have the same problem. If you need to suspend per element, use indexed-for + `mutableListOf`.

`flow.collect { ... }` is fine — it's not `Iterable.forEach`.

### 5. Memoization — at every reference-fresh boundary

When upstream produces fresh-but-content-equal instances on every emission (timestamps in `updatedAtMs`, `_uiState.update {}.copy()`, `gson.toJsonTree`, etc.), reference equality breaks at every consumer. Compose stability skip → invalidated. `===` cache-hit guards → always miss. The cascade re-allocates identical content per emission.

**Rule:** at every layer that produces output for downstream consumers, memoize content→reference. The pattern (committed examples: `ResolvedDisplayProjectionCache` `de6caa0be`, `CatalogRowMemo` `bf844a7bc`, `HomeResolvedDisplayMapper` `c2f132f0e`):

```kotlin
@Singleton
class FooMemo @Inject constructor() {
    private val cache = mutableMapOf<Key, Foo>()

    @Synchronized
    fun intern(input: List<X>): List<Foo> {
        val active = HashSet<Key>(input.size)
        val out = input.map { x ->
            val key = signature(x)
            active += key
            val cached = cache[key]
            if (cached != null && cached == foo(x)) cached
            else foo(x).also { cache[key] = it }
        }
        cache.keys.retainAll(active)  // bound to active set
        return out
    }
}
```

Also memoize the **outer list reference** when all elements are reference-stable — see `ResolvedDisplayProjectionCache.internRailsList` (commit `63f1c4346`). Without it, the consumer's `===` guard on `state.copy(field = list)` fails every emission.

For Compose-rendered helpers (e.g. an `overlayResolvedDisplay(item, resolved): MetaPreview` that allocates a new value per recomposition), wrap in `remember(item, resolved) { compute() }` at the call site. Reference-stable inputs → `remember` returns the cached value.

---

## When investigating performance issues

Always: capture a heap dump first. The `analysing-heap-dumps` skill (`heaptrail` CLI, supports JAVA PROFILE 1.0.3) will identify retainer chains. Sustained allocation rate is visible in `adb logcat | grep "Background concurrent"` — death-spiral signature is GCs every <1 s with >30 MB LOS per cycle.
