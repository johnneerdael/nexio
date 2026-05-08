# TV Artwork And Continue Watching Restore Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore TV Home/Continue Watching artwork hydration and make Continue Watching snapshot restore safe in release builds without reintroducing crashes or identity/source misclassification.

**Architecture:** Fix this at the shared restore, hydration, and display-projection boundaries. Continue Watching snapshots must be decoded as versioned persisted data, not as raw minified domain objects. TV artwork must flow through MetadataRouter, TVDB/premium candidates, ArtworkRouter, HydratedHomeOverlay, ResolvedDisplayItem, and type-safe card projection.

**Tech Stack:** Kotlin, Android, Gson, Compose, JUnit, MockK, Gradle armv7 debug/release unit tests, ADB device verification.

---

## Evidence Summary

- Device `192.168.50.98:5555` no longer reproduces the prior `LinkedTreeMap` crash after the partial crash fix, but the live data shows the fix is incomplete.
- `continue_watching_snapshot-after-fix.xml` contains schema 5 `records` stored with R8/Gson obfuscated keys (`a`, `b`, `c`, ...), not stable JSON names.
- The current `ContinueWatchingSnapshotStore.decodeRecordObject()` only reads stable names (`profileId`, `parentId`, `resumeIdentities`, ...), so release snapshots can silently drop all canonical records.
- The current decoder still defaults missing `provider` to `TRAKT` and missing `source` to `REMOTE`, which is an unsafe identity/source decision.
- Device `hydrated_home_overlay_v1-after-fix.xml` proves TV overlays exist for affected shows:
  - The Boys: `poster = nexio-artwork://decision/...provider:RPDB...`, `backdrop = nexio-artwork://asset/...TVDB:backdrop...`, `logo = nexio-artwork://asset/...TVDB:logo...`
  - Citadel: same RPDB poster plus TVDB backdrop/logo pattern.
- Persisted overlay `fields.artwork` is null because `HomeDisplayMetadata.artwork` is `@Transient`; only legacy string fields survive persistence. Projection paths must reconstruct typed artwork refs from those strings.
- Continue Watching projection copies string artwork but drops typed artwork/stable ID context in `toContinueWatchingProviderPreview()`, `continueWatchingInProgressToMetaPreview()`, and `nextUpToMetaPreview()`.
- `HomeHydrationCoordinator` aliases only the current `item.apiType`; exact `series:` vs `tv:` key drift can make valid overlays invisible.
- Metadata ID parsing likely mishandles content IDs shaped like `tmdb:tv:<id>`, causing TVDB routing to receive `tv` instead of the numeric TMDB series id.

## File Map

- Modify `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`
  - Add explicit stable record encoding.
  - Decode stable and obfuscated release schema 5 keys.
  - Validate/synthesize/quarantine records safely.
  - Decode optional nested metadata independently.
- Modify `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt`
  - Add release-obfuscated fixture and restore-invariant tests.
- Modify `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
  - Add shared artwork reconstruction from display string fields.
  - Use it in `applyTo()` and `mergeFallback()`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - Preserve display metadata artwork/stable IDs in CW provider previews.
  - Improve display metadata lookup aliases for records.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
  - Preserve typed/legacy artwork through CW carousel projection.
  - Stop poster slots from falling back to backdrops for portrait cards.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - Preserve typed/legacy artwork in `nextUpToMetaPreview()`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
  - Add series/tv alias normalization for overlay write/read.
- Modify metadata router identity parsing files:
  - `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt`
  - `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt`
  - `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolver.kt`
- Add or modify tests under:
  - `app/src/test/java/com/nexio/tv/ui/screens/home/`
  - `app/src/test/java/com/nexio/tv/core/metadata/router/`
  - Existing TVDB/artwork mapper tests if present.

## Execution Packets

Keep the work split so a passing Continue Watching restore patch cannot be mistaken for a TV artwork fix:

```text
Packet A - Continue Watching restore safety:
  Tasks 1-2

Packet B - TV artwork/premium display chain:
  Tasks 3-7

Packet C - release/device verification:
  Task 8
```

Packet B is not complete until the full TV artwork contract in Task 6 passes end to end.

## Task 1: Continue Watching Release Snapshot Migration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt`

- [ ] **Step 1: Write failing test for release-obfuscated schema 5 record restore**

Add a test using the exact device field shape:

```kotlin
@Test
fun `read restores release minified schema five record keys`() {
    val prefs = InMemorySharedPreferences()
    val context = mockContext(prefs, "continue_watching_snapshot", localePrefs("nl"))
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 1
    val store = ContinueWatchingSnapshotStore(context, metadataStore)

    prefs.edit().putString(
        "snapshot",
        """
        {
          "schemaVersion": 5,
          "languageEpoch": 1,
          "languageTag": "nl",
          "resumeItems": [],
          "nextUpItems": [],
          "traktUpNextItems": [],
          "scheduledReemit": [],
          "records": [{
            "a": 1,
            "b": "series:tvdb:393268",
            "c": "series:tvdb:393268:s2e1",
            "d": "TRAKT",
            "e": 1,
            "f": 0,
            "g": 0,
            "h": {"a": 2, "b": 1},
            "j": "REMOTE",
            "k": 1778188317000,
            "l": {
              "a": "SERIES",
              "b": {
                "canonicalProvider": "TVDB",
                "canonicalId": "393268",
                "providerIds": {"tvdb": "393268", "imdb": "tt9794044", "trakt": "171028"}
              },
              "c": 2,
              "d": 1,
              "e": 1
            },
            "m": {
              "canonicalProvider": "TVDB",
              "canonicalId": "393268",
              "providerIds": {"tvdb": "393268", "imdb": "tt9794044", "trakt": "171028"}
            },
            "n": {
              "a": "tt9794044",
              "b": "tt9794044:2:1",
              "c": "IMDB_EPISODE",
              "d": "HIGH",
              "e": ["device fixture"]
            },
            "o": {
              "a": 171028,
              "b": 13018336,
              "c": 1748780366,
              "e": {"imdb": "tt9794044", "trakt": "171028"}
            },
            "p": [{
              "a": "TRAKT_PLAYBACK",
              "b": "tt9794044",
              "c": "tt9794044:2:1",
              "d": 2,
              "e": 1,
              "f": 0,
              "g": 0,
              "h": 71.8908,
              "i": 1778188317000
            }],
            "q": "tt9794044|tt9794044:2:1|2|1",
            "r": "HIGH",
            "s": [],
            "t": "nl"
          }],
          "displayMetadataByItemKey": {},
          "metadataSnapshotsByItemKey": {},
          "updatedAtMs": 1778260165327
        }
        """.trimIndent()
    ).commit()

    val record = store.read(profileId = 1)?.records?.singleOrNull()

    assertEquals("series:tvdb:393268", record?.parentId)
    assertEquals("tt9794044|tt9794044:2:1|2|1", record?.primaryResumeLookupKey)
    assertEquals(1, record?.resumeIdentities?.size)
    assertEquals("393268", record?.canonicalKey?.canonicalParent?.canonicalId)
    assertEquals("tt9794044:2:1", record?.streamFetchIdentity?.videoId)
}
```

- [ ] **Step 2: Run red test**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest.read\\ restores\\ release\\ minified\\ schema\\ five\\ record\\ keys
```

Expected: fail because `decodeRecordObject()` does not read obfuscated keys.

- [ ] **Step 3: Implement explicit stable record encoder and dual-key decoder**

In `ContinueWatchingSnapshotStore.write()`, replace:

```kotlin
add("records", gson.toJsonTree(snapshot.records))
```

with:

```kotlin
add("records", encodeRecords(snapshot.records))
```

Add explicit encoders for `ContinueWatchingRecord`, `ResumeIdentity`, `ContinueWatchingCanonicalKey`, `ContentIdentity`, `StreamFetchIdentity`, and `TrackingIdentity` using stable property names.

Add key helpers in the decoder:

```kotlin
private fun JsonObject.stringOrNull(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> stringOrNull(key) }

private fun JsonObject.intOrNull(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> intOrNull(key) }

private fun JsonObject.longOrNull(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { key -> longOrNull(key) }

private fun JsonObject.floatOrNull(vararg keys: String): Float? =
    keys.firstNotNullOfOrNull { key -> floatOrNull(key) }

private fun JsonObject.objectOrNull(vararg keys: String): JsonObject? =
    keys.firstNotNullOfOrNull { key -> objectOrNull(key) }

private fun JsonObject.arrayOrNull(vararg keys: String): JsonArray? =
    keys.firstNotNullOfOrNull { key -> arrayOrNull(key) }

private inline fun <reified T : Enum<T>> JsonObject.enumOrNull(vararg keys: String): T? =
    keys.firstNotNullOfOrNull { key -> enumOrNull<T>(key) }
```

Use stable key first and release-obfuscated key second:

```kotlin
val profileId = obj.intOrNull("profileId", "a")?.takeIf { it > 0 } ?: return null
val parentId = obj.stringOrNull("parentId", "b")?.takeIf { it.isNotBlank() } ?: return null
val contentId = obj.stringOrNull("contentId", "c")?.takeIf { it.isNotBlank() } ?: return null
val provider = obj.enumOrNull<TrackingProvider>("provider", "d")
val routingVersion = obj.intOrNull("routingVersion", "e")?.takeIf { it > 0 } ?: return null
val positionMs = obj.longOrNull("positionMs", "f")?.takeIf { it >= 0L } ?: return null
val durationMs = obj.longOrNull("durationMs", "g")?.takeIf { it >= 0L } ?: return null
val episodeContext = decodeEpisodeContext(obj.objectOrNull("episodeContext", "h"))
val source = obj.enumOrNull<ContinueWatchingRecord.Source>("source", "j")
val updatedAt = obj.longOrNull("updatedAt", "k")?.takeIf { it > 0L } ?: return null
```

Decode nested obfuscated keys using the observed mapping:

```text
canonicalKey: a=mediaKind, b=canonicalParent, c=season, d=episode, e=profileId
streamFetchIdentity: a=contentId, b=videoId, c=idScheme, d=confidence, e=trace
trackingIdentity: a=traktShowId, b=traktEpisodeId, c=traktPlaybackId, d=traktMovieId, e=providerIds
resumeIdentity: a=source, b=contentId, c=videoId, d=season, e=episode, f=positionMs, g=durationMs, h=progressPercent, i=lastWatchedMs
record: l=canonicalKey, m=displayIdentity, n=streamFetchIdentity, o=trackingIdentity, p=resumeIdentities, q=primaryResumeLookupKey, r=identityConfidence, s=identityWarnings, t=languageTag
```

- [ ] **Step 4: Run green test**

Run the same focused test. Expected: pass.

- [ ] **Step 5: Run full snapshot store tests**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest
```

Expected: pass.

## Task 2: Continue Watching Restore Invariants

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt`

- [ ] **Step 1: Write failing tests for unsafe defaults and malformed optional metadata**

Add tests:

```kotlin
@Test
fun `read does not default missing provider and source to trakt remote`() {
    val store = storeWithRawSnapshot(
        """
        {
          "schemaVersion": 5,
          "languageEpoch": 1,
          "languageTag": "en",
          "resumeItems": [],
          "nextUpItems": [],
          "traktUpNextItems": [],
          "scheduledReemit": [],
          "records": [{
            "profileId": 1,
            "parentId": "movie:tmdb:1",
            "contentId": "movie:tmdb:1",
            "routingVersion": 1,
            "positionMs": 10,
            "durationMs": 100,
            "source": null,
            "updatedAt": 1000,
            "resumeIdentities": [{
              "source": "LOCAL",
              "contentId": "tt1",
              "videoId": "tt1",
              "positionMs": 10,
              "durationMs": 100,
              "lastWatchedMs": 1000
            }],
            "primaryResumeLookupKey": "tt1|tt1|null|null"
          }],
          "displayMetadataByItemKey": {},
          "metadataSnapshotsByItemKey": {},
          "updatedAtMs": 1000
        }
        """.trimIndent(),
        languageTag = "en"
    )

    assertTrue(store.read(profileId = 1)?.records.orEmpty().isEmpty())
}

@Test
fun `read keeps record when click time metadata is malformed`() {
    val store = storeWithRawSnapshot(
        validRecordJson(
            clickTimeDisplayMetadata = """{"routingVersion":"bad-int"}"""
        ),
        languageTag = "en"
    )

    val record = store.read(profileId = 1)?.records?.singleOrNull()

    assertEquals("movie:tmdb:1", record?.parentId)
    assertNull(record?.clickTimeDisplayMetadata)
}
```

If no `storeWithRawSnapshot()` helper exists, add it in the test class using `InMemorySharedPreferences`, `mockContext()`, and `MetadataDiskCacheStore` exactly as existing tests do.

- [ ] **Step 2: Run red tests**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest.read\\ does\\ not\\ default\\ missing\\ provider\\ and\\ source\\ to\\ trakt\\ remote --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest.read\\ keeps\\ record\\ when\\ click\\ time\\ metadata\\ is\\ malformed
```

Expected:
- Missing source/provider test fails because current code restores TRAKT/REMOTE.
- Malformed click-time test fails because current code drops the whole record.

- [ ] **Step 3: Implement safe provider/source inference**

Use this rule:

```text
Restore a normal record only when:
- provider is explicit and valid, or trackingIdentity has Trakt evidence;
- source is explicit and valid, or can be inferred from resume identity source;
- resumeIdentities is non-empty after decode/synthesis;
- primaryResumeLookupKey points to one resume identity.
```

Implement:

```kotlin
private fun inferRecordProvider(
    explicit: TrackingProvider?,
    trackingIdentity: TrackingIdentity?
): TrackingProvider? =
    explicit ?: trackingIdentity?.takeIf {
        it.traktShowId != null ||
            it.traktEpisodeId != null ||
            it.traktPlaybackId != null ||
            it.traktMovieId != null
    }?.let { TrackingProvider.TRAKT }

private fun inferRecordSource(
    explicit: ContinueWatchingRecord.Source?,
    resumeIdentities: List<ResumeIdentity>,
    trackingIdentity: TrackingIdentity?
): ContinueWatchingRecord.Source? =
    explicit ?: when {
        resumeIdentities.any { it.source == ContinueWatchingSource.LOCAL } -> ContinueWatchingRecord.Source.LOCAL
        trackingIdentity != null -> ContinueWatchingRecord.Source.REMOTE
        resumeIdentities.any { it.source == ContinueWatchingSource.SYNTHETIC } -> ContinueWatchingRecord.Source.SYNTHETIC
        else -> null
    }
```

Do not use `?: TrackingProvider.TRAKT` or `?: ContinueWatchingRecord.Source.REMOTE` in restore.

- [ ] **Step 4: Decode optional metadata independently**

Change click-time decode to:

```kotlin
val clickTimeDisplayMetadata = runCatching {
    obj.objectOrNull("clickTimeDisplayMetadata", "i")
        ?.let { gson.fromJson(it, ContinueWatchingMetadataSnapshot::class.java) }
}.getOrNull()
```

Construct the record outside any `runCatching` that also parses optional metadata.

- [ ] **Step 5: Enforce resume identity invariant**

For this packet, do not create normal records with no usable resume identity.

```kotlin
val decodedResumeIdentities = decodeResumeIdentities(obj.arrayOrNull("resumeIdentities", "p"))
val resumeIdentities = decodedResumeIdentities.ifEmpty {
    synthesizeLegacyResumeIdentity(
        contentId = contentId,
        episodeContext = episodeContext,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        source = source
    )
}
if (resumeIdentities.isEmpty()) return null
```

Synthesis may use:

```kotlin
private fun synthesizeLegacyResumeIdentity(
    contentId: String,
    episodeContext: ContinueWatchingRecord.EpisodeContext?,
    positionMs: Long,
    durationMs: Long,
    updatedAt: Long,
    source: ContinueWatchingRecord.Source?
): List<ResumeIdentity> {
    val resumeSource = when (source) {
        ContinueWatchingRecord.Source.LOCAL -> ContinueWatchingSource.LOCAL
        ContinueWatchingRecord.Source.REMOTE -> ContinueWatchingSource.TRAKT_PLAYBACK
        ContinueWatchingRecord.Source.SYNTHETIC -> ContinueWatchingSource.SYNTHETIC
        null -> return emptyList()
    }
    val videoId = episodeContext?.let { "$contentId:${it.season}:${it.number}" } ?: contentId
    return listOf(
        ResumeIdentity(
            source = resumeSource,
            contentId = contentId,
            videoId = videoId,
            season = episodeContext?.season,
            episode = episodeContext?.number,
            positionMs = positionMs,
            durationMs = durationMs,
            progressPercent = null,
            lastWatchedMs = updatedAt
        )
    )
}
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest
```

Expected: pass.

## Task 3: Continue Watching Artwork Projection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomePresentationTest.kt`

- [ ] **Step 1: Write failing test proving persisted overlay strings reconstruct artwork**

Add a test that builds a `HomeDisplayMetadata` with only string fields:

```kotlin
@Test
fun `continue watching meta preview preserves hydrated artwork strings as artwork refs`() {
    val metadata = HomeDisplayMetadata(
        title = "The Boys",
        poster = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:policy:1",
        posterProviderTag = "rpdb",
        backdrop = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1",
        logo = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:def:variant:none:imageLang:en:policy:1"
    )
    val item = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = "tt1190634",
            contentType = "series",
            name = "The Boys",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1190634:1:1",
            season = 1,
            episode = 1,
            episodeTitle = null,
            position = 1,
            duration = 10,
            lastWatched = 100,
            progressPercent = 10f
        ),
        displayMetadata = metadata
    )

    val preview = continueWatchingInProgressToMetaPreview(item)

    assertTrue(preview.poster.orEmpty().contains("provider:RPDB"))
    assertEquals("rpdb", preview.posterProviderTag)
    assertNotNull(preview.artwork?.poster)
    assertNotNull(preview.artwork?.backdrop)
    assertNotNull(preview.artwork?.logo)
}
```

Expected current failure: `preview.artwork` is null and `posterProviderTag` is not preserved.

- [ ] **Step 2: Add shared reconstruction helper**

In `HomeDisplayMetadata.kt`, add:

```kotlin
fun HomeDisplayMetadata.toArtworkBundleFromDisplayFields(): ArtworkBundle? {
    val structured = artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = structured?.poster ?: displayPoster.toLegacyArtworkRef(ArtworkType.POSTER),
        backdrop = structured?.backdrop ?: displayBackdrop.toLegacyArtworkRef(ArtworkType.BACKDROP),
        logo = structured?.logo ?: displayLogo.toLegacyArtworkRef(ArtworkType.LOGO),
        thumbnail = structured?.thumbnail ?: displayThumbnail.toLegacyArtworkRef(ArtworkType.THUMBNAIL)
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}
```

Also add local conversion:

```kotlin
private fun String?.toLegacyArtworkRef(imageType: ArtworkType): ArtworkDisplayRef.LegacyString? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.startsWith("nexio-artwork://asset/") ||
            value.startsWith("nexio-artwork://decision/") ||
            value.startsWith("nexio-placeholder://") ->
            ArtworkDisplayRef.LegacyString(
                value = value,
                imageType = imageType,
                trace = ArtworkTrace.empty()
            )

        value.startsWith("https://api.top-posters", ignoreCase = true) ||
            value.startsWith("https://api.ratingposterdb", ignoreCase = true) ->
            null

        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ->
            ArtworkDisplayRef.LegacyString(
                value = value,
                imageType = imageType,
                trace = ArtworkTrace(
                    selectedProvider = "COMPATIBILITY_REMOTE",
                    selectedRole = "RAIL_PREVIEW"
                )
            )

        else -> null
    }
}
```

Import `ArtworkDisplayRef`, `ArtworkTrace`, and `ArtworkType`.

- [ ] **Step 3: Add converter safety tests**

Add tests near the new helper or in the nearest domain model test:

```kotlin
@Test
fun `artwork reconstruction rejects raw premium urls`() {
    val metadata = HomeDisplayMetadata(
        poster = "https://api.top-posters.example/poster/foo",
        backdrop = "https://api.ratingposterdb.com/backdrop/foo",
        logo = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:def:variant:none:imageLang:en:policy:1"
    )

    val bundle = metadata.toArtworkBundleFromDisplayFields()

    assertNull(bundle?.poster)
    assertNull(bundle?.backdrop)
    assertNotNull(bundle?.logo)
}

@Test
fun `artwork reconstruction accepts durable nexio decision and asset refs`() {
    val metadata = HomeDisplayMetadata(
        poster = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:policy:1",
        backdrop = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1"
    )

    val bundle = metadata.toArtworkBundleFromDisplayFields()

    assertNotNull(bundle?.poster)
    assertNotNull(bundle?.backdrop)
}
```

- [ ] **Step 4: Use reconstructed artwork in CW previews**

In `ContinueWatchingItem.toContinueWatchingProviderPreview()`, `continueWatchingInProgressToMetaPreview()`, and `nextUpToMetaPreview()`, set:

```kotlin
posterProviderTag = displayMetadata.posterProviderTag,
artwork = displayMetadata.toArtworkBundleFromDisplayFields(),
firstPaintStableIds = providerIdsFromContinueWatchingContentId(contentId = itemContentId)
```

Add this local helper in the file that builds the CW `MetaPreview` values:

```kotlin
private fun providerIdsFromContinueWatchingContentId(contentId: String): ProviderIds {
    val value = contentId.trim()
    if (value.isBlank()) return ProviderIds()
    return when {
        value.startsWith("tt", ignoreCase = true) -> ProviderIds(imdb = value)
        value.startsWith("imdb:", ignoreCase = true) -> ProviderIds(imdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("tvdb:", ignoreCase = true) -> ProviderIds(tvdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("tmdb:tv:", ignoreCase = true) -> ProviderIds(tmdb = value.substringAfter("tmdb:tv:").takeIf { it.isNotBlank() })
        value.startsWith("tmdb:", ignoreCase = true) -> ProviderIds(tmdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("trakt:", ignoreCase = true) -> ProviderIds(trakt = value.substringAfter(':').takeIf { it.isNotBlank() })
        else -> ProviderIds()
    }
}
```

Use actual IDs only. Do not derive provider IDs from titles, release years, or display text.

- [ ] **Step 5: Keep poster fields type-safe**

For portrait cards, keep only poster fallback:

```kotlin
imageUrl = firstNonBlank(
    displayMetadata.displayPoster,
    item.info.thumbnail
)
```

Do not use `displayMetadata.displayBackdrop` as a poster fallback in portrait mode.

Also add tests:

```kotlin
@Test
fun `portrait continue watching card ignores backdrop when poster is missing`() {
    val metadata = HomeDisplayMetadata(
        title = "The Boys",
        poster = null,
        backdrop = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1",
        logo = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:def:variant:none:imageLang:en:policy:1"
    )
    val item = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = "tt1190634",
            contentType = "series",
            name = "The Boys",
            poster = null,
            backdrop = metadata.displayBackdrop,
            logo = metadata.displayLogo,
            displayMetadata = metadata,
            videoId = "tt1190634:1:1",
            season = 1,
            episode = 1,
            episodeTitle = null,
            episodeDescription = null,
            thumbnail = null,
            released = null,
            hasAired = true,
            airDateLabel = null,
            lastWatched = 100,
            imdbRating = null,
            genres = emptyList(),
            releaseInfo = null
        )
    )

    val carousel = buildContinueWatchingItem(
        item = item,
        useLandscapePosters = false,
        airsDateTemplate = "%s",
        upcomingLabel = "upcoming"
    )

    assertNull(carousel.imageUrl)
}
```

Rule to enforce in implementation:

```text
Poster card model must take its image only from ArtworkBundle.poster or legacy displayPoster.
It must not use ArtworkBundle.backdrop, displayBackdrop, ArtworkBundle.logo, or displayLogo.
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.ModernHomePresentationTest
```

Expected: pass.

## Task 4: Overlay Alias Normalization For TV Rows

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeys.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt` or existing nearest hydration test.

- [ ] **Step 1: Write failing tests for row-key and canonical-key aliases**

Test that an overlay written for a TV row is readable through all expected aliases:

```kotlin
@Test
fun `tv overlay aliases include row key canonical tvdb key and series tv variants`() {
    val aliases = HomeArtworkOverlayKeys.aliasesFor(
        rowItemKey = "series:tmdb:76479",
        contentId = "tmdb:tv:76479",
        itemType = "series",
        providerIds = ProviderIds(tmdb = "76479", tvdb = "355567", imdb = "tt1190634"),
        canonicalProvider = ProviderId.TVDB,
        canonicalId = "355567"
    )

    assertTrue("series:tmdb:76479" in aliases)
    assertTrue("tv:tmdb:76479" in aliases)
    assertTrue("series:tvdb:355567" in aliases)
    assertTrue("tv:tvdb:355567" in aliases)
    assertTrue("series:imdb:tt1190634" in aliases)
    assertTrue("tv:imdb:tt1190634" in aliases)
}

@Test
fun `canonical tvdb overlay is readable by trakt tv row aliases`() {
    val aliases = HomeArtworkOverlayKeys.aliasesFor(
        rowItemKey = "series:trakt:171028",
        contentId = "trakt:171028",
        itemType = "series",
        providerIds = ProviderIds(trakt = "171028", tvdb = "355567", imdb = "tt1190634"),
        canonicalProvider = ProviderId.TVDB,
        canonicalId = "355567"
    )

    assertTrue("series:trakt:171028" in aliases)
    assertTrue("series:tvdb:355567" in aliases)
    assertTrue("series:imdb:tt1190634" in aliases)
}
```

Expected current failure: alias generation is private to `HomeHydrationCoordinator` and uses only the original `item.apiType`.

- [ ] **Step 2: Add shared alias generator**

Create `HomeArtworkOverlayKeys.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import java.util.Locale

internal object HomeArtworkOverlayKeys {
    fun aliasesFor(
        rowItemKey: String,
        contentId: String,
        itemType: String,
        providerIds: ProviderIds,
        canonicalProvider: ProviderId?,
        canonicalId: String?
    ): Set<String> = buildSet {
        rowItemKey.trim().takeIf { it.isNotBlank() }?.let(::add)
        contentId.trim().takeIf { it.isNotBlank() }?.let { id ->
            add(homeDisplayItemKey(itemType, id))
            addTypedProviderAlias(itemType, id)
        }
        addStableAliases(itemType, providerIds)
        if (canonicalProvider != null && !canonicalId.isNullOrBlank()) {
            addStableAliases(
                itemType,
                when (canonicalProvider) {
                    ProviderId.TMDB -> ProviderIds(tmdb = canonicalId)
                    ProviderId.TVDB -> ProviderIds(tvdb = canonicalId)
                    ProviderId.IMDB -> ProviderIds(imdb = canonicalId)
                    ProviderId.TRAKT -> ProviderIds(trakt = canonicalId)
                    ProviderId.KITSU -> ProviderIds(kitsu = canonicalId)
                    else -> ProviderIds()
                }
            )
        }
    }

    private fun MutableSet<String>.addTypedProviderAlias(itemType: String, contentId: String) {
        val parts = contentId.trim().split(':').filter { it.isNotBlank() }
        if (parts.size < 2) return
        val provider = parts[0].lowercase(Locale.US)
        val id = when {
            parts.size >= 3 && parts[1].equals("tv", ignoreCase = true) -> parts[2]
            parts.size >= 3 && parts[1].equals("movie", ignoreCase = true) -> parts[2]
            else -> parts[1]
        }
        normalizedTypes(itemType).forEach { type ->
            add("$type:$provider:$id")
        }
    }

    private fun MutableSet<String>.addStableAliases(type: String, ids: ProviderIds) {
        normalizedTypes(type).forEach { normalizedType ->
            ids.imdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:imdb:$it") }
            ids.tmdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:tmdb:$it") }
            ids.tvdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:tvdb:$it") }
            ids.trakt?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:trakt:$it") }
            ids.kitsu?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:kitsu:$it") }
        }
    }

    private fun normalizedTypes(type: String): Set<String> =
        when (type.lowercase(Locale.US)) {
            "series", "tv", "show" -> setOf("series", "tv")
            "movie" -> setOf("movie")
            else -> setOf(type.lowercase(Locale.US))
        }
}
```

- [ ] **Step 3: Use shared alias generator for write and read scope**

Replace `HomeHydrationCoordinator.overlayAliases()` with a call to `HomeArtworkOverlayKeys.aliasesFor(...)`, passing:

```kotlin
HomeArtworkOverlayKeys.aliasesFor(
    rowItemKey = itemKey,
    contentId = item.id,
    itemType = item.apiType,
    providerIds = mergedProviderIds,
    canonicalProvider = overlay.canonicalProvider,
    canonicalId = overlay.canonicalId
)
```

Where `mergedProviderIds` includes:

```kotlin
item.firstPaintStableIds
bundle?.source?.observedIds
bundle?.sidecars?.imdbId
bundle?.canonical?.tmdbMovieId
bundle?.canonical?.tvdbSeriesId
bundle?.canonical?.kitsuAnimeId
```

Also update `hydratedHomeOverlayItemKeysForRows()` or the observer key construction to include `HomeArtworkOverlayKeys.aliasesFor(...)` for row items when provider IDs are available. This makes the lookup scope use the same key universe as writes.

- [ ] **Step 4: Run hydration tests**

Run the focused hydration/overlay test class. Expected: pass.

## Task 5: Typed TMDB Series ID Parsing

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolver.kt`
- Test: nearest metadata router test class under `app/src/test/java/com/nexio/tv/core/metadata/router/`

- [ ] **Step 1: Write failing parser and identity-input tests**

Do not assert that TV routes target TMDB as primary. For non-anime series, the primary route must remain TVDB; TMDB is a source/known ID for identity bridging.

Add direct parser coverage:

```kotlin
@Test
fun `provider native id parser extracts numeric tmdb tv id`() {
    assertEquals("1399", providerNativeIdFromContentId("tmdb:tv:1399", "tmdb"))
    assertEquals("550", providerNativeIdFromContentId("tmdb:movie:550", "tmdb"))
    assertEquals("1399", providerNativeIdFromContentId("tmdb:1399", "tmdb"))
}
```

Add router/identity coverage:

```kotlin
@Test
fun `tmdb tv content id routes series to tvdb while preserving tmdb source id`() = runTest {
    val request = MetadataRequest(
        contentId = "tmdb:tv:1399",
        contentType = ContentType.SERIES,
        sourceContext = MetadataSourceContext(itemType = "series"),
        language = "en",
        depth = MetadataDepth.DETAIL_CORE
    )

    val route = router.route(request)

    assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
    assertEquals("1399", route.sourceIds[MetadataPrimaryProvider.TMDB])
}
```

If the actual route model does not expose `sourceIds`, assert the layer that does exist: parsed request/source identity, stable-id bundle input, or the `MetadataIdentityResolver` argument. Do not assert `route.targetIds[TMDB]` unless the route model truly uses TMDB as a target for TVDB bridging.

Expected current failure: parsed TMDB id is `tv` or the TMDB source ID is not preserved.

- [ ] **Step 2: Fix provider-native ID extraction**

Centralize parsing:

```kotlin
internal fun providerNativeIdFromContentId(contentId: String, provider: String): String? {
    val parts = contentId.trim().split(':').filter { it.isNotBlank() }
    if (parts.isEmpty()) return null
    if (!parts[0].equals(provider, ignoreCase = true)) return null
    return when {
        parts.size >= 3 && parts[1].equals("tv", ignoreCase = true) -> parts[2]
        parts.size >= 3 && parts[1].equals("movie", ignoreCase = true) -> parts[2]
        parts.size >= 2 -> parts[1]
        else -> null
    }
}
```

Use it anywhere current code does `substringAfter(':').substringBefore(':')` for provider IDs.

- [ ] **Step 3: Run metadata router tests**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests '*MetadataRouter*' --tests '*MetadataIdentityResolver*'
```

Expected: pass.

## Task 6: TV Artwork Hydration Contract Test

**Files:**
- Test: existing Home hydration integration/unit test area under `app/src/test/java/com/nexio/tv/ui/screens/home/`
- Modify production only if this test identifies a failing layer.

- [ ] **Step 1: Add end-to-end TV row fixture test**

Create a fixture for:

```text
Trakt Trending Show
type = series
id = trakt:171028 or tmdb:tv:76479
stable ids = tvdb:355567, imdb:tt1190634, tmdb:76479, trakt:171028
firstPaint poster/backdrop/logo = null
premium provider configured
TVDB fixture has type 2 poster, type 3 backdrop, type 23 logo
```

Assert the complete chain:

```text
hydration requested
ProviderIds survive preview mapping
routeProvider = TVDB
tvdb ID resolved
TVDB extended short=false executes or cache-hits
TVDB POSTER/BACKDROP/LOGO candidates exist
RPDB poster candidate generated from ProviderIds
selected poster = RPDB POSTER if materializable, else TVDB POSTER
selected backdrop = TVDB BACKDROP
selected logo = TVDB LOGO
overlay written under canonical key
overlay readable by exact Home row key and series/tv aliases
Home card selects POSTER only for portrait poster slot
```

Name:

```text
trakt_tv_row_without_first_paint_artwork_hydrates_tvdb_and_premium_artwork
```

- [ ] **Step 2: Run contract test and fix boundaries until the full chain passes**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests '*trakt_tv_row_without_first_paint_artwork_hydrates_tvdb_and_premium_artwork*'
```

Expected initial result is allowed to fail. It is acceptable to fix one failing boundary at a time, but Task 6 is not complete until the full contract passes:

```text
TV row input
→ TVDB route
→ TVDB identity
→ TVDB artwork candidates
→ premium TV poster candidate
→ ArtworkRouter per-type selection
→ overlay write
→ Home overlay read
→ type-safe card rendering
```

## Task 7: TVDB And Premium Candidate Regression Guards

**Files:**
- Modify nearest TVDB adapter tests.
- Modify nearest premium artwork candidate/router tests.

- [ ] **Step 1: Add TVDB type mapping tests**

Add or verify:

```text
tvdb_type_2_maps_to_poster_candidate
tvdb_type_3_maps_to_backdrop_candidate
tvdb_type_23_maps_to_logo_candidate
tvdb_extended_image_maps_to_poster_fallback_only
tvdb_backdrop_never_maps_to_poster
tvdb_logo_never_maps_to_poster
```

- [ ] **Step 2: Add premium TV candidate tests**

Premium TV poster candidates must be generated from `ProviderIds`, not only from the row `contentId`. For TV, supported IDs include IMDb, TVDB, TMDB series id, and Trakt if supported by the provider. If no supported ID exists, emit a rejected candidate trace with `reason=NO_SUPPORTED_ID`.

Add:

```text
premium_poster_candidate_generated_for_tv_with_tvdb_id
premium_poster_candidate_generated_for_tv_with_imdb_id
premium_poster_candidate_generated_for_tv_with_tmdb_series_id
premium_tv_candidate_uses_provider_ids_when_content_id_is_not_premium_supported
premium_poster_wins_over_tvdb_poster_for_tv
premium_poster_rejected_with_trace_when_no_supported_tv_id
```

The key fixture:

```kotlin
val item = MetaPreview(
    id = "trakt:171028",
    type = ContentType.SERIES,
    rawType = "series",
    name = "The Boys",
    poster = null,
    background = null,
    logo = null,
    firstPaintStableIds = ProviderIds(
        trakt = "171028",
        tmdb = "76479",
        tvdb = "355567",
        imdb = "tt1190634"
    )
)
```

Expected: premium candidate generation can use `tt1190634`, `355567`, or `76479` even though `contentId = trakt:171028`.

- [ ] **Step 3: Run artwork tests**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests '*Tvdb*Artwork*' --tests '*Premium*Artwork*' --tests '*ArtworkRouter*'
```

Expected: pass.

## Task 8: Device Verification Without Raw Evidence Commit

**Files:**
- Create if missing: `tools/reporting/summarize_artwork_state.py`
- Create: `review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json`

- [ ] **Step 1: Build and install release APK**

Run:

```bash
./gradlew :app:assembleArmv7Release
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/armv7/release/app-armv7-release.apk
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv 1
```

Expected: install succeeds and app launches.

- [ ] **Step 2: Do not clear logcat; capture a timestamped window**

Run:

```bash
adb -s 192.168.50.98:5555 logcat -d -v threadtime > tmp/crash-investigation-2026-05-08/logcat-post-tv-artwork-cw-fix.txt
```

Expected: file is local-only and not staged.

- [ ] **Step 3: Pull local evidence but commit only sanitized summary**

Pull:

```bash
adb -s 192.168.50.98:5555 shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/hydrated_home_overlay_v1.xml /sdcard/hydrated_home_overlay_v1.xml'
adb -s 192.168.50.98:5555 pull /sdcard/hydrated_home_overlay_v1.xml tmp/crash-investigation-2026-05-08/hydrated_home_overlay_v1-postfix.xml
adb -s 192.168.50.98:5555 shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/continue_watching_snapshot.xml /sdcard/continue_watching_snapshot.xml'
adb -s 192.168.50.98:5555 pull /sdcard/continue_watching_snapshot.xml tmp/crash-investigation-2026-05-08/continue_watching_snapshot-postfix.xml
```

Do not add these raw files to git.

Create a sanitized summary with counts only:

```json
{
  "fatalCrashCount": 0,
  "continueWatchingRecordCount": 0,
  "continueWatchingRecordsWithResumeIdentityCount": 0,
  "tvOverlayCount": 0,
  "tvOverlaysWithRpdbPosterCount": 0,
  "tvOverlaysWithTvdbBackdropCount": 0,
  "tvOverlaysWithTvdbLogoCount": 0,
  "traktTvRowsSeen": 0,
  "traktTvRowsWithPosterRef": 0,
  "traktTvRowsWithBackdropAsPoster": 0,
  "traktTvRowsWithTvdbLogo": 0,
  "traktTvRowsWithRpdbPosterDecision": 0,
  "tvRowsWithPremiumPosterCandidate": 0,
  "tvRowsWithTvdbPosterCandidate": 0,
  "tvRowsWithTvdbBackdropCandidate": 0,
  "tvRowsWithTvdbLogoCandidate": 0,
  "tmdbTvIdParseFailures": 0,
  "overlayAliasMissCount": 0,
  "rawTopPostersUrlCount": 0,
  "backdropUsedAsPosterCount": 0
}
```

- [ ] **Step 4: Verify no raw secrets or raw premium URLs are staged**

Run:

```bash
git diff --cached --name-only
git diff --cached -- review-dossier
```

Expected: only sanitized summary/docs are staged; no raw XML/logcat with URLs or device history.

## Required Verification Commands

Run before claiming fixed:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.ModernHomePresentationTest
./gradlew :app:testArmv7ReleaseUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest --tests com.nexio.tv.ui.screens.home.ModernHomePresentationTest
./gradlew :app:assembleArmv7Release
```

Then run the ADB verification in Task 8 without clearing logcat.

## Acceptance Criteria

- Release-obfuscated schema 5 Continue Watching records restore with concrete `ResumeIdentity` objects.
- New writes use stable JSON keys for Continue Watching records.
- Missing provider/source no longer silently become `TRAKT`/`REMOTE`.
- Optional malformed click-time metadata cannot drop an otherwise valid record.
- No normal restored record has empty `resumeIdentities` or a null/unreferenced `primaryResumeLookupKey`.
- Continue Watching series items preserve hydrated RPDB poster, TVDB backdrop, and TVDB logo through `MetaPreview` and Modern Home row projection.
- Raw Top-Posters/RPDB URLs are not wrapped as typed artwork refs.
- TV row overlays are readable through exact row keys, canonical TVDB keys, and `series:` / `tv:` aliases generated by one shared helper.
- `tmdb:tv:<id>` preserves the numeric TMDB series id for TVDB identity resolution while non-anime series still route primarily to TVDB.
- Poster cards never render backdrops/logos as poster fallback.
- Premium TV poster candidates are generated from ProviderIds, not only row content IDs.
- The full `trakt_tv_row_without_first_paint_artwork_hydrates_tvdb_and_premium_artwork` contract passes.
- TVDB type 2/3/23 and premium TV poster candidate tests pass.
- No raw device evidence, raw Top-Posters URLs, credentials, or logcat dumps are committed.

## Commit Plan

Use small commits:

```text
test: cover release continue watching snapshot restore
fix: migrate continue watching snapshot records safely
test: cover tv artwork overlay projection
fix: preserve hydrated tv artwork through home projection
test: cover tv metadata identity and artwork routing
fix: normalize tv overlay aliases and tmdb tv ids
docs: add sanitized tv artwork verification summary
```

Do not stage:

```text
tmp/crash-investigation-2026-05-08/*.xml
tmp/crash-investigation-2026-05-08/*logcat*.txt
app/src/main/assets/openrouter_reasoning_models.json
media
```
