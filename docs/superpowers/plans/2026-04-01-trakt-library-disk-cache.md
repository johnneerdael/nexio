# Trakt Library Disk Cache Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Trakt watchlist and personal lists restore from disk-backed cache instead of live fetch, keep the blocking Library loader limited to the first uncached Trakt sync, preserve cached Trakt content during refresh/mutation failure, and compact the readable debrid list rows to one smaller filename/title line.

**Architecture:** Add a dedicated `TraktLibrarySnapshotStore` that persists the Trakt library snapshot plus hydrated metadata, restore that state during `TraktLibraryService` startup, and remove observer-driven Trakt refresh from Library startup. Keep live refresh and optimistic mutations publishing through the persisted Trakt snapshot, while `LibraryViewModel` and `LibraryScreen` distinguish first uncached Trakt bootstrap from warm-cache syncing. Handle the debrid presentation change through a small pure row-presentation helper so the UI compaction is unit-testable.

**Tech Stack:** Kotlin, SharedPreferences-backed local stores, Coroutines/Flow, Jetpack Compose for TV, JUnit4 JVM tests, MockK, Gradle, OpenSpec

---

## File Map

**Primary implementation files**

- Create: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`

**Primary test files**

- Create: `app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/library/LibraryViewModelTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/library/DebridRowPresentationTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/library/LibraryLayoutModeTest.kt` only if the readable-layout predicate or exported helper surface changes

**Spec tracking**

- Modify: `openspec/changes/persist-trakt-library-disk-cache/tasks.md`

### Task 1: Add The Persisted Trakt Library Snapshot Store

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Create: `app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/SyntheticHomeCatalogStore.kt`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/LibraryModels.kt`

- [ ] **Step 1: Write the failing snapshot-store tests**

Cover these cases in `TraktLibrarySnapshotStoreTest`:
- round-trip of Trakt tabs, entries-by-list, and hydrated metadata
- language-epoch mismatch invalidates the persisted snapshot
- corrupt payload self-clears on read

Use the existing `InMemorySharedPreferences` harness and a mocked `MetadataDiskCacheStore`.

```kotlin
@Test
fun readRestoresSnapshotAndMetadataForCurrentLanguageEpoch() {
    val prefs = InMemorySharedPreferences()
    var epoch = 4
    val context = mockContext(prefs, "trakt_library_snapshot")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } answers { epoch }
    val store = TraktLibrarySnapshotStore(context, metadataStore)

    val snapshot = samplePersistedSnapshot()
    store.write(snapshot)

    assertEquals(snapshot, store.read())

    epoch = 5
    assertNull(store.read())
}
```

- [ ] **Step 2: Run the new snapshot-store test class to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest`

Expected: FAIL because the new store class and persisted DTOs do not exist yet.

- [ ] **Step 3: Implement the store with a canonical persisted shape**

Create a store that persists:
- `schemaVersion`
- `languageEpoch`
- `listTabs`
- `entriesByList`
- `metadataByContentKey`
- `updatedAtMs`

Suggested DTO shape:

```kotlin
data class PersistedTraktLibrarySnapshot(
    val listTabs: List<LibraryListTab> = emptyList(),
    val entriesByList: Map<String, List<LibraryEntry>> = emptyMap(),
    val metadataByContentKey: Map<String, PersistedLibraryMetadata> = emptyMap(),
    val updatedAtMs: Long = 0L
)
```

Keep `allEntries` and `membershipByContent` as rebuilt service state so the persisted payload stays canonical and easier to migrate.

- [ ] **Step 4: Run the snapshot-store tests again**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest`

Expected: PASS

- [ ] **Step 5: Commit the new store and tests**

```bash
git add app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt \
        app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt
git commit -m "feat: add trakt library snapshot store"
```

### Task 2: Restore Trakt Library From Disk And Remove Observer-Triggered Live Fetch

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceTest.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt`

- [ ] **Step 1: Add the failing service tests for warm-cache restore and startup behavior**

Extend `TraktLibraryServiceTest` to prove:
- observers return restored Trakt tabs/items from the snapshot store without triggering auth/API fetch
- `refreshNow()` persists the renewed snapshot back to the store
- warm-cache refresh failure preserves the last restored snapshot in memory

```kotlin
@Test
fun restoredSnapshotIsReturnedWithoutObserverFetch() = runTest {
    val snapshotStore = mockk<TraktLibrarySnapshotStore>()
    every { snapshotStore.read() } returns samplePersistedStateWithWatchlist()

    val service = createService(snapshotStore = snapshotStore)
    advanceUntilIdle()

    assertEquals(
        listOf(TraktLibraryService.WATCHLIST_KEY),
        service.observeListTabs().first().map { it.key }
    )
    coVerify(exactly = 0) { traktAuthService.executeAuthorizedRequest<List<TraktListItemDto>>(any()) }
}
```

- [ ] **Step 2: Run the focused service tests to verify they fail for the expected reason**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TraktLibraryServiceTest`

Expected: FAIL because the service does not yet inject the snapshot store, restore persisted state, or remove observer-start refresh.

- [ ] **Step 3: Update `TraktLibraryService` startup and observer behavior**

Implementation requirements:
- inject `TraktLibrarySnapshotStore`
- inject `TraktAuthDataStore` so auth loss can clear persisted snapshot state
- restore persisted snapshot plus hydrated metadata into state during `init`
- rebuild `allEntries` and `membershipByContent` from the persisted canonical payload
- set `lastRefreshMs` from the restored snapshot timestamp
- remove `.onStart { ensureFresh() }` from `observeListTabs()`, `observeAllItems()`, and `observeMembership()`

Suggested helper seams:

```kotlin
private fun restorePersistedState(persisted: PersistedTraktLibrarySnapshot)
private fun toPersistedSnapshot(snapshot: Snapshot, metadata: Map<String, LibraryMetadata>): PersistedTraktLibrarySnapshot
private fun persistCurrentState()
```

- [ ] **Step 4: Make refresh persist through the new store**

After successful refresh and after any metadata prefetch that changes persisted metadata, write the current canonical snapshot plus metadata map back to disk.

Keep this rule: warm-cache refresh may replace the in-memory snapshot only after the renewed snapshot is ready; refresh failure must leave the previous restored snapshot visible.

- [ ] **Step 5: Run the focused service tests again**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TraktLibraryServiceTest`

Expected: PASS

- [ ] **Step 6: Commit the restore-first service behavior**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceTest.kt
git commit -m "feat: restore trakt library from disk cache"
```

### Task 3: Persist Optimistic Mutations And Clear Snapshot State On Auth Loss

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceTest.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt`

- [ ] **Step 1: Add the failing service tests for optimistic persistence and auth-loss cleanup**

Cover these cases:
- failed watchlist toggle or list-membership mutation restores the previous snapshot in memory and rewrites the previous snapshot to disk
- auth loss clears the persisted Trakt library snapshot

```kotlin
@Test
fun failedWatchlistMutationRollsBackPersistedSnapshot() = runTest {
    val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
    val service = createService(snapshotStore = snapshotStore, seedSnapshot = true)

    coEvery { traktAuthService.executeAuthorizedRequest<TraktListItemsMutationResponseDto>(any()) } returns
        Response.error(500, mockk(relaxed = true))

    assertFailsWith<IllegalStateException> {
        service.toggleWatchlist(sampleLibraryInput())
    }

    verify(atLeast = 2) { snapshotStore.write(any()) }
    assertEquals(seedItems, service.observeAllItems().first())
}
```

- [ ] **Step 2: Run the focused service tests to verify the new mutation/auth cases fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TraktLibraryServiceTest`

Expected: FAIL because optimistic persistence and auth-loss clearing are not implemented yet.

- [ ] **Step 3: Persist optimistic updates and rollback through the store**

Update `performOptimisticMutation(...)` so it:
- captures the pre-mutation snapshot + metadata state
- writes the optimistic snapshot immediately
- rewrites the previous persisted snapshot if the network mutation fails

Also add an auth observer in `init`:

```kotlin
scope.launch {
    traktAuthDataStore.isEffectivelyAuthenticated.collectLatest { authenticated ->
        if (!authenticated) clearPersistedState()
    }
}
```

Where `clearPersistedState()` should clear:
- `snapshotState`
- `metadataState`
- `lastRefreshMs`
- `snapshotStore`

- [ ] **Step 4: Run the focused service tests again**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TraktLibraryServiceTest`

Expected: PASS

- [ ] **Step 5: Commit the mutation and auth-loss behavior**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceTest.kt
git commit -m "fix: persist trakt library mutations through cache"
```

### Task 4: Limit Blocking Library Loading To Trakt Bootstrap And Keep Retry Reachable

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/library/LibraryViewModelTest.kt`
- Reference: `app/src/main/java/com/nexio/tv/domain/repository/LibraryRepository.kt`

- [ ] **Step 1: Write the failing `LibraryViewModel` loading-state tests**

Cover:
- Trakt bootstrap with no items and no tabs while syncing uses the blocking loading state
- a warm Trakt cache with an empty watchlist still avoids the blocking loading state because the watchlist tab exists
- debrid syncing without Trakt cache does not enter the blocking full-screen loading path

```kotlin
@Test
fun traktBootstrapBlocksOnlyWhileNoTabsExist() = runTest(dispatcher) {
    sourceMode.value = LibrarySourceMode.TRAKT
    isSyncing.value = true
    items.value = emptyList()
    tabs.value = emptyList()

    val viewModel = createViewModel(sourceMode, isSyncing, items, tabs)
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isLoading)

    tabs.value = listOf(watchlistTab())
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.isLoading)
}
```

- [ ] **Step 2: Run the new `LibraryViewModel` tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.library.LibraryViewModelTest`

Expected: FAIL because the dedicated Library view-model test file does not exist yet and `isLoading` still treats all non-local sources the same.

- [ ] **Step 3: Update the loading-state and action-row rules**

Implementation requirements:
- make `isLoading` depend only on the Trakt bootstrap case:

```kotlin
isLoading = sourceMode == LibrarySourceMode.TRAKT &&
    isSyncing &&
    items.isEmpty() &&
    listTabs.isEmpty()
```

- keep `isSyncing` for Trakt and debrid so the sync button/transient message still reflect background work
- show the `Sync` action whenever `sourceMode != LibrarySourceMode.LOCAL`, even if `listTabs` is still empty after a failed first sync, so retry stays reachable
- keep `Manage lists` gated on personal Trakt tabs only

- [ ] **Step 4: Run the focused view-model tests again**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.library.LibraryViewModelTest`

Expected: PASS

- [ ] **Step 5: Do a quick manual UI review**

Check in the app or emulator:
- first authenticated Trakt bootstrap still shows the full-screen loading indicator
- warm-cache Trakt refresh keeps the Library visible while syncing
- failed first Trakt sync still leaves a visible `Sync` action for retry

- [ ] **Step 6: Commit the Library loading-state changes**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt \
        app/src/test/java/com/nexio/tv/ui/screens/library/LibraryViewModelTest.kt
git commit -m "fix: limit library blocking load to trakt bootstrap"
```

### Task 5: Compact The Readable Debrid Library Rows

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/library/DebridRowPresentationTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/library/LibraryLayoutModeTest.kt` only if helper visibility changes require it

- [ ] **Step 1: Write the failing debrid row-presentation tests**

Extract a pure helper or data model so the row content is unit-testable. Cover:
- `playbackFilename` wins over `playbackStreamName`
- `playbackStreamName` wins over `name`
- subtitle/detail text is omitted entirely for readable debrid rows

```kotlin
@Test
fun playbackFilenameBecomesTheOnlyReadableRowTitle() {
    val presentation = buildDebridRowPresentation(
        sampleItem(
            name = "Parsed Title",
            playbackStreamName = "Stream Title",
            playbackFilename = "Movie.2026.2160p.mkv"
        )
    )

    assertEquals("Movie.2026.2160p.mkv", presentation.title)
    assertNull(presentation.subtitle)
    assertNull(presentation.detail)
}
```

- [ ] **Step 2: Run the debrid-row tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.library.DebridRowPresentationTest`

Expected: FAIL because the presentation helper and compact-row behavior do not exist yet.

- [ ] **Step 3: Implement the compact row presentation**

Update `DebridLibraryListRow` so it:
- keeps only the current filename/title source
- renders that text with the smaller readable-row typography (`bodyMedium` scale) instead of the current oversized primary title
- removes the duplicate subtitle and path/detail line entirely
- reduces vertical padding and spacing so more rows fit on screen

Suggested pure helper:

```kotlin
internal data class DebridRowPresentation(
    val title: String
)

internal fun buildDebridRowPresentation(item: LibraryEntry): DebridRowPresentation
```

- [ ] **Step 4: Run the debrid-row tests again**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.library.DebridRowPresentationTest`

Expected: PASS

- [ ] **Step 5: Manually verify all three service tabs**

Check Real-Debrid, Premiumize, and TorBox:
- only one title line is visible
- rows are visibly shorter than before
- focus, click, and truncation behavior still feel stable

- [ ] **Step 6: Commit the compact row change**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt \
        app/src/test/java/com/nexio/tv/ui/screens/library/DebridRowPresentationTest.kt \
        app/src/test/java/com/nexio/tv/ui/screens/library/LibraryLayoutModeTest.kt
git commit -m "fix: compact debrid library rows"
```

### Task 6: Final Verification And OpenSpec Bookkeeping

**Files:**
- Modify: `openspec/changes/persist-trakt-library-disk-cache/tasks.md`
- Reference: `openspec/changes/persist-trakt-library-disk-cache/specs/library-playback/spec.md`
- Reference: `docs/superpowers/specs/2026-04-01-trakt-library-disk-cache-design.md`

- [ ] **Step 1: Mark the OpenSpec task checklist complete**

Update `openspec/changes/persist-trakt-library-disk-cache/tasks.md` so each implemented item is checked.

- [ ] **Step 2: Run the focused JVM verification suite**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest \
  --tests com.nexio.tv.data.repository.TraktLibraryServiceTest \
  --tests com.nexio.tv.ui.screens.library.LibraryViewModelTest \
  --tests com.nexio.tv.ui.screens.library.LibraryLayoutModeTest \
  --tests com.nexio.tv.ui.screens.library.DebridRowPresentationTest
```

Expected: PASS

- [ ] **Step 3: Run a compile pass**

Run: `./gradlew :app:compileDebugKotlin --continue`

Expected: PASS, or explicit identification of unrelated pre-existing compile failures if any remain.

- [ ] **Step 4: Run spec and diff hygiene checks**

Run:

```bash
openspec validate persist-trakt-library-disk-cache --strict
git diff --check
```

Expected: both PASS

- [ ] **Step 5: Commit the final verification and bookkeeping**

```bash
git add openspec/changes/persist-trakt-library-disk-cache/tasks.md
git commit -m "test: verify trakt library disk cache changes"
```

## Review Notes

- This plan intentionally keeps disk-backed restore scoped to Trakt watchlist and personal lists.
- Real-Debrid, Premiumize, and TorBox data fetch remains live-only; only their readable row
  presentation changes here.
- If `LibraryViewModel` tests reveal that current logic already satisfies one approved behavior,
  keep the test coverage and avoid refactoring for its own sake.
