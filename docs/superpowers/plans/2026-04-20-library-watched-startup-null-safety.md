# Library And Watched Startup Null-Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent startup-adjacent crashes from legacy or malformed local library, watched item, and outbox persistence entries that Gson can hydrate with null required fields.

**Architecture:** Add small post-decode normalization helpers at the persistence boundaries that still use raw Gson data-class decoding. Drop structurally unusable entries and normalize safe defaults for optional display fields before home/detail/library code sees them. Keep the existing DataStore/SharedPreferences formats unchanged.

**Tech Stack:** Kotlin, Android DataStore preferences, SharedPreferences, Gson, Gradle unit tests, existing in-memory test preferences.

---

## Scope

This plan handles the remaining code-review findings after the broader startup snapshot/cache hardening pass:

- `LibraryPreferences` reads `SavedLibraryItem` with raw Gson.
- `WatchedItemsPreferences` reads `WatchedItem` with raw Gson.
- `TraktMutationOutboxStore` reads `TraktMutationEnvelope` with raw Gson and only partially normalizes `profileId`.

This plan does not change rating-provider logic, home catalog snapshot logic, metadata disk cache logic, or release versioning.

## File Structure

Modify:

- `app/src/main/java/com/nexio/tv/data/local/LibraryPreferences.kt`  
  Add `SavedLibraryItem.sanitizedOrNull()` and use it anywhere stored JSON is decoded.
- `app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt`  
  Add `WatchedItem.sanitizedOrNull()` and use it anywhere stored JSON is decoded.
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt`  
  Add a conservative `TraktMutationEnvelope.sanitizedOrNull()` guard after raw Gson decode.

Tests:

- `app/src/test/java/com/nexio/tv/data/local/LibraryPreferencesTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/WatchedItemsPreferencesTest.kt`
- `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt`

---

### Task 1: Harden Local Library Item Reads

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/LibraryPreferences.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/LibraryPreferencesTest.kt`

- [ ] **Step 1: Inspect existing test helpers**

Run:

```bash
sed -n '1,220p' app/src/test/java/com/nexio/tv/data/local/LibraryPreferencesTest.kt
```

Expected: identify existing `Context` / DataStore test setup. If the file does not exist, create it using `InMemorySharedPreferences` is not sufficient because this class uses DataStore; use the repository's existing DataStore test pattern from nearby tests.

- [ ] **Step 2: Write failing legacy item tests**

Add these tests to `LibraryPreferencesTest.kt`. If the test file needs a temporary folder DataStore setup, place these test bodies inside the existing test class and reuse its `libraryPreferences()` helper.

```kotlin
@Test
fun `libraryItems drops entries without id type or name`() = runTest {
    val prefs = libraryPreferences()
    prefs.writeRawItems(
        setOf(
            """{"type":"movie","name":"Missing id","posterShape":"POSTER","genres":[]}""",
            """{"id":"tt123","name":"Missing type","posterShape":"POSTER","genres":[]}""",
            """{"id":"tt456","type":"movie","posterShape":"POSTER","genres":[]}"""
        )
    )

    assertEquals(emptyList<SavedLibraryItem>(), prefs.libraryItems.first())
}

@Test
fun `libraryItems normalizes missing poster shape and genres`() = runTest {
    val prefs = libraryPreferences()
    prefs.writeRawItems(
        setOf(
            """
            {
              "id":"tt123",
              "type":"movie",
              "name":"Movie",
              "poster":null,
              "background":null,
              "description":null,
              "releaseInfo":"2025",
              "imdbRating":8.3,
              "addonBaseUrl":null,
              "addedAt":42
            }
            """.trimIndent()
        )
    )

    val item = prefs.libraryItems.first().single()

    item.hashCode()
    assertEquals(PosterShape.POSTER, item.posterShape)
    assertEquals(emptyList<String>(), item.genres)
    assertEquals("tt123", item.id)
}
```

Add imports if missing:

```kotlin
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
```

Add a test-only helper if none exists. It must write directly to the same DataStore key used by `LibraryPreferences`:

```kotlin
private suspend fun LibraryPreferences.writeRawItems(items: Set<String>) {
    val field = LibraryPreferences::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val dataStore = field.get(this) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    val key = androidx.datastore.preferences.core.stringSetPreferencesKey("library_items")
    dataStore.edit { prefs -> prefs[key] = items }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.LibraryPreferencesTest
```

Expected: fail because raw Gson entries are returned with null `posterShape` or `genres`, or because invalid entries are not dropped.

- [ ] **Step 4: Implement `SavedLibraryItem` sanitizer**

Modify `LibraryPreferences.kt`:

```kotlin
private fun SavedLibraryItem.sanitizedOrNull(): SavedLibraryItem? {
    val cleanId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanType = type.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return copy(
        id = cleanId,
        type = cleanType,
        name = cleanName,
        posterShape = posterShape ?: PosterShape.POSTER,
        genres = genres.orEmpty(),
        addedAt = addedAt.coerceAtLeast(0L)
    )
}
```

Because `posterShape` and `genres` are currently non-null in `SavedLibraryItem`, this will not compile until `SavedLibraryItem` is adjusted. Modify `SavedLibraryItem.kt` only for fields that need legacy Gson compatibility:

```kotlin
val posterShape: PosterShape? = PosterShape.POSTER,
val genres: List<String>? = emptyList(),
```

Then update `SavedLibraryItem.toMetaPreview()`:

```kotlin
posterShape = posterShape ?: PosterShape.POSTER,
genres = genres.orEmpty()
```

- [ ] **Step 5: Use sanitizer after every decode**

In `LibraryPreferences.kt`, replace each `fromJson(...).getOrNull()` use with sanitizer application.

For `libraryItems`:

```kotlin
raw.mapNotNull { json ->
    runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }
        .getOrNull()
        ?.sanitizedOrNull()
}
```

For filters in `addItem` and `removeItem`:

```kotlin
runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }
    .getOrNull()
    ?.sanitizedOrNull()
    ?.let { saved -> ... }
```

When writing remote items in `mergeRemoteItems`, sanitize before serializing:

```kotlin
remoteItems.mapNotNull { it.sanitizedOrNull() }.forEach { item ->
    dedupedRemote[item.id to item.type.lowercase()] = item
}
```

- [ ] **Step 6: Run library tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.LibraryPreferencesTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/LibraryPreferences.kt app/src/main/java/com/nexio/tv/domain/model/SavedLibraryItem.kt app/src/test/java/com/nexio/tv/data/local/LibraryPreferencesTest.kt
git commit -m "fix(startup): sanitize local library items"
```

---

### Task 2: Harden Watched Item Reads

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/WatchedItem.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/WatchedItemsPreferencesTest.kt`

- [ ] **Step 1: Inspect existing test helpers**

Run:

```bash
sed -n '1,220p' app/src/test/java/com/nexio/tv/data/local/WatchedItemsPreferencesTest.kt
```

Expected: identify existing DataStore test setup. If the file does not exist, create one using the same DataStore test pattern as `LibraryPreferencesTest` from Task 1.

- [ ] **Step 2: Write failing watched item tests**

Add to `WatchedItemsPreferencesTest.kt`:

```kotlin
@Test
fun `watched items drops entries without content id type title or watched timestamp`() = runTest {
    val prefs = watchedItemsPreferences()
    prefs.writeRawItems(
        setOf(
            """{"contentType":"movie","title":"Missing id","watchedAt":1}""",
            """{"contentId":"tt123","title":"Missing type","watchedAt":1}""",
            """{"contentId":"tt456","contentType":"movie","watchedAt":1}""",
            """{"contentId":"tt789","contentType":"movie","title":"Missing watchedAt"}"""
        )
    )

    assertEquals(emptyList<WatchedItem>(), prefs.getAllItems())
}

@Test
fun `watched items normalizes valid legacy entries`() = runTest {
    val prefs = watchedItemsPreferences()
    prefs.writeRawItems(
        setOf(
            """
            {
              "contentId":"tt123",
              "contentType":"movie",
              "title":"Movie",
              "season":null,
              "episode":null,
              "watchedAt":42
            }
            """.trimIndent()
        )
    )

    val item = prefs.getAllItems().single()

    item.hashCode()
    assertEquals("tt123", item.contentId)
    assertEquals("movie", item.contentType)
    assertEquals("Movie", item.title)
    assertEquals(42L, item.watchedAt)
}
```

Add imports if missing:

```kotlin
import com.nexio.tv.domain.model.WatchedItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
```

Add helper if none exists:

```kotlin
private suspend fun WatchedItemsPreferences.writeRawItems(items: Set<String>) {
    val field = WatchedItemsPreferences::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val dataStore = field.get(this) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    val key = androidx.datastore.preferences.core.stringSetPreferencesKey("watched_items")
    dataStore.edit { prefs -> prefs[key] = items }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.WatchedItemsPreferencesTest
```

Expected: fail because invalid entries survive or null fields are returned.

- [ ] **Step 4: Implement `WatchedItem` sanitizer**

Modify `WatchedItemsPreferences.kt`:

```kotlin
private fun WatchedItem.sanitizedOrNull(): WatchedItem? {
    val cleanContentId = contentId.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanContentType = contentType.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return null
    if (watchedAt <= 0L) return null
    return copy(
        contentId = cleanContentId,
        contentType = cleanContentType,
        title = cleanTitle
    )
}
```

If this cannot compile because Gson can set non-null fields to null but Kotlin type remains non-null, make only these legacy-sensitive fields nullable in `WatchedItem.kt`:

```kotlin
val contentId: String?,
val contentType: String?,
val title: String?,
```

Then keep all public use sites working by only exposing sanitized `WatchedItem` instances from `WatchedItemsPreferences`. If direct constructors elsewhere become noisy, leave the constructor calls unchanged because Kotlin accepts non-null `String` for nullable `String?`.

- [ ] **Step 5: Use sanitizer after every decode**

In `WatchedItemsPreferences.kt`, update decode sites:

```kotlin
runCatching { gson.fromJson(json, WatchedItem::class.java) }
    .getOrNull()
    ?.sanitizedOrNull()
```

Apply this in:

- `allItems`
- `markAsWatched` filter
- `unmarkAsWatched` filter
- `mergeRemoteItems` local item decode
- `replaceWithRemoteItems` if it decodes current entries

Before serializing remote items in merge/replace, sanitize them:

```kotlin
remoteItems.mapNotNull { it.sanitizedOrNull() }
```

- [ ] **Step 6: Run watched tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.WatchedItemsPreferencesTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt app/src/main/java/com/nexio/tv/domain/model/WatchedItem.kt app/src/test/java/com/nexio/tv/data/local/WatchedItemsPreferencesTest.kt
git commit -m "fix(startup): sanitize watched item entries"
```

---

### Task 3: Add Conservative Outbox Envelope Guard

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt`

- [ ] **Step 1: Inspect outbox models and tests**

Run:

```bash
sed -n '1,180p' app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt
sed -n '1,220p' app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt
```

Expected: identify required fields on `TraktMutationEnvelope` and existing SharedPreferences test setup.

- [ ] **Step 2: Write failing malformed envelope test**

Add to `TraktMutationOutboxStoreTest.kt`:

```kotlin
@Test
fun `read drops malformed envelope missing required fields`() = runTest {
    val prefs = InMemorySharedPreferences()
    val store = TraktMutationOutboxStore(context = mockContext(prefs))
    prefs.edit().putString(
        "outbox",
        """
        {
          "schemaVersion": 1,
          "snapshot": {
            "items": [
              { "profileId": 1 },
              {
                "id": "valid",
                "kind": "progress",
                "payload": {},
                "createdAtMs": 1,
                "profileId": 1
              }
            ],
            "nextWritableAtMs": 0,
            "updatedAtMs": 1
          }
        }
        """.trimIndent()
    ).commit()

    val snapshot = store.read()

    assertEquals(listOf("valid"), snapshot.items.map { it.id })
}
```

Adjust JSON field names to match `TraktMutationEnvelope` after Step 1 inspection.

- [ ] **Step 3: Run test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest
```

Expected: fail if the malformed envelope survives or crashes later.

- [ ] **Step 4: Add envelope guard**

In `TraktMutationOutboxStore.kt`, change `deserializeEnvelope`:

```kotlin
private fun deserializeEnvelope(element: JsonElement?): TraktMutationEnvelope? {
    if (element == null || element.isJsonNull) return null
    return runCatching {
        val obj = element.asJsonObject
        gson.fromJson(obj, TraktMutationEnvelope::class.java)
            .copy(profileId = obj.intOrNull("profileId") ?: 1)
            .sanitizedOrNull()
    }.getOrNull()
}
```

Add a private extension near the bottom:

```kotlin
private fun TraktMutationEnvelope.sanitizedOrNull(): TraktMutationEnvelope? {
    if (id.isBlank()) return null
    if (kind.isBlank()) return null
    if (createdAtMs <= 0L) return null
    return copy(profileId = profileId.coerceAtLeast(1))
}
```

If field names differ, use the actual names from `TraktMutationOutboxModels.kt`.

- [ ] **Step 5: Run outbox tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt
git commit -m "fix(sync): drop malformed trakt outbox entries"
```

---

### Task 4: Verification

**Files:**
- Verify only.

- [ ] **Step 1: Run persistence hardening tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.LibraryPreferencesTest --tests com.nexio.tv.data.local.WatchedItemsPreferencesTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest --tests com.nexio.tv.data.local.MetadataModelSanitizersTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run app startup/cache regression tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTvdbTest --tests com.nexio.tv.data.local.SyntheticHomeCatalogStoreTest --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.CatalogDiskCacheStoreTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Optional affected-device smoke test**

If `192.168.50.71:5555` is connected and available, install release:

```bash
ANDROID_SERIAL=192.168.50.71:5555 ./gradlew :app:installUniversalRelease
```

Then ask a human to launch and select the profile. Do not send input events automatically. After launch/profile selection, run:

```bash
adb -s 192.168.50.71:5555 logcat -d -t 2500 | grep -E 'FATAL EXCEPTION|AndroidRuntime|SavedLibraryItem|WatchedItem|TraktMutationEnvelope|MetaPreview.hashCode|HomeDisplayMetadata|ratingSource|com\.nexio\.tv'
```

Expected: no `FATAL EXCEPTION` and no model null crash signatures.

- [ ] **Step 5: Commit verification-only fixes if needed**

If verification required additional changes, commit them:

```bash
git add app/src/main/java app/src/test/java
git commit -m "test: cover startup persistence compatibility"
```

If no additional changes were required, do not create an empty commit.

---

## Self-Review

**Spec coverage**

- Hardens remaining reviewed startup-adjacent persistence paths: Tasks 1-3.
- Keeps scope limited to crash prevention and does not touch unrelated startup performance warnings.
- Adds concrete tests for malformed/legacy persisted data before implementation.

**Placeholder scan**

- No forbidden placeholder patterns are present.
- Each task includes exact files, code snippets, commands, and expected results.

**Type consistency**

- Helper names are consistently `sanitizedOrNull` for entry-level persisted objects.
- The plan keeps existing storage formats and only normalizes after decode.
