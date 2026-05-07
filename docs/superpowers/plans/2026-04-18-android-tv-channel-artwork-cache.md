# Android TV Channel Artwork Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish Android TV channel posters through app-local cached artwork URIs so RPDB/TOPPosters images are downloaded once through Coil and then served locally to the launcher.

**Architecture:** Keep Android TV's required `Poster Art URI` contract, but replace paid-service remote poster URLs with `content://` URIs backed by a dedicated channel-artwork cache under `cacheDir`. Coil remains the downloader and provider-aware cache warmer; a read-only exported `ContentProvider` serves stable local poster files to the launcher without exposing the mutable Coil cache directory directly.

**Tech Stack:** Kotlin, Android TV `androidx.tvprovider`, Android `ContentProvider`, Coil 2.7.0, Hilt, Robolectric/JUnit.

---

## Context And Constraints

Official Android TV channel docs require `PreviewProgram.Builder.setPosterArtUri(uri)` for channel programs, and the video program attributes table marks Poster Art URI as required for movie/series preview programs:

- `https://developer.android.com/training/tv/discovery/recommendations-channel`
- `https://developer.android.com/training/tv/discovery/video-programs`

Official Android `FileProvider` / `ContentProvider` docs confirm another process reads a `content://` URI through `ContentResolver.openFileDescriptor`; `FileProvider` normally relies on granted URI permissions, while a custom provider can expose a constrained read-only surface:

- `https://developer.android.com/reference/androidx/core/content/FileProvider`
- `https://developer.android.com/reference/android/content/ContentProvider.html`

Existing implementation points:

- `AndroidTvChannelPublisher` currently publishes remote `item.poster` and `item.background` URIs directly through `setPosterArtUri` / `setThumbnailUri`.
- `ArtworkImageCacheKeys.poster(item.id, item.posterProviderTag, item.poster)` already creates provider-aware Coil keys for RPDB/TOPPosters.
- `HomeCatalogRefreshCoordinator` already uses Coil `ImageLoader.execute(...)` with `diskCacheKey(...)` to prefetch images.
- `NexioApplication` configures Coil's disk cache at `cacheDir/image_cache`.

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtwork.kt`
  - Owns authority construction, stable filename derivation, poster URI creation, poster file lookup, and URI-to-file validation.

- `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProvider.kt`
  - A read-only `ContentProvider` that serves only files from `cacheDir/android_tv_channel_art/posters`.

- `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCache.kt`
  - Uses Coil to fetch a remote poster with the provider-aware disk key, then copies the cached source bytes into the dedicated channel-artwork directory and returns the local `content://` URI.

- `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkTest.kt`
  - Pure tests for stable filenames, URI generation, and URI validation.

- `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProviderTest.kt`
  - Robolectric tests for provider read-only behavior and valid file serving.

Modify:

- `app/src/main/AndroidManifest.xml`
  - Register `.core.recommendations.AndroidTvChannelArtworkProvider` as an exported read-only provider with authority `${applicationId}.channelart`.

- `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt`
  - Inject `AndroidTvChannelArtworkCache`.
  - Await local poster caching before publishing each program.
  - Pass the local poster URI into presentation generation.

- `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvProgramPresentationTest.kt`
  - Verify catalog feeds use local poster URI when available.
  - Verify Continue Watching landscape presentation remains unchanged.

## Task 1: Add Stable Channel Artwork URI Helpers

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtwork.kt`
- Test: `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkTest.kt`:

```kotlin
package com.nexio.tv.core.recommendations

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvChannelArtworkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `poster uri is stable and uses app channel art authority`() {
        val uri = AndroidTvChannelArtwork.posterUri(
            context = context,
            diskCacheKey = "tt15940132_rpdb_poster"
        )

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.channelart", uri.authority)
        assertEquals("poster", uri.pathSegments[0])
        assertTrue(uri.pathSegments[1].endsWith(".jpg"))
        assertEquals(uri, AndroidTvChannelArtwork.posterUri(context, "tt15940132_rpdb_poster"))
    }

    @Test
    fun `poster file resolves under channel artwork cache directory`() {
        val file = AndroidTvChannelArtwork.posterFile(
            context = context,
            diskCacheKey = "tt15940132_top_posters_poster"
        )

        assertEquals(
            File(context.cacheDir, "android_tv_channel_art/posters").canonicalFile,
            file.parentFile?.canonicalFile
        )
        assertTrue(file.name.endsWith(".jpg"))
    }

    @Test
    fun `resolve poster file rejects wrong authorities and traversal`() {
        val valid = AndroidTvChannelArtwork.posterUri(context, "tt15940132_rpdb_poster")
        val wrongAuthority = valid.buildUpon()
            .authority("com.example.other")
            .build()
        val traversal = Uri.parse("content://${context.packageName}.channelart/poster/../secret.jpg")

        assertNull(AndroidTvChannelArtwork.resolvePosterFile(context, wrongAuthority))
        assertNull(AndroidTvChannelArtwork.resolvePosterFile(context, traversal))
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkTest
```

Expected: compile failure because `AndroidTvChannelArtwork` does not exist.

- [ ] **Step 3: Implement the helper object**

Create `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtwork.kt`:

```kotlin
package com.nexio.tv.core.recommendations

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

internal object AndroidTvChannelArtwork {
    private const val AUTHORITY_SUFFIX = ".channelart"
    private const val ROOT_DIR = "android_tv_channel_art"
    private const val POSTERS_DIR = "posters"
    private const val POSTER_PATH = "poster"
    private val FILE_NAME_REGEX = Regex("^[a-f0-9]{64}\\.jpg$")

    fun authority(context: Context): String {
        return "${context.packageName}$AUTHORITY_SUFFIX"
    }

    fun posterUri(context: Context, diskCacheKey: String): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(POSTER_PATH)
            .appendPath(fileNameForDiskCacheKey(diskCacheKey))
            .build()
    }

    fun posterFile(context: Context, diskCacheKey: String): File {
        return File(posterDirectory(context), fileNameForDiskCacheKey(diskCacheKey))
    }

    fun resolvePosterFile(context: Context, uri: Uri): File? {
        if (uri.scheme != "content") return null
        if (uri.authority != authority(context)) return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        if (segments[0] != POSTER_PATH) return null
        val fileName = segments[1]
        if (!FILE_NAME_REGEX.matches(fileName)) return null
        val directory = posterDirectory(context).canonicalFile
        val file = File(directory, fileName).canonicalFile
        return file.takeIf { candidate ->
            candidate.parentFile == directory
        }
    }

    private fun posterDirectory(context: Context): File {
        return File(context.cacheDir, "$ROOT_DIR/$POSTERS_DIR")
    }

    private fun fileNameForDiskCacheKey(diskCacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(diskCacheKey.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "$digest.jpg"
    }
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtwork.kt app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkTest.kt
git commit -m "feat: add Android TV channel artwork URI helpers"
```

## Task 2: Add Read-Only Channel Artwork Provider

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProviderTest.kt`

- [ ] **Step 1: Write the failing provider tests**

Create `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProviderTest.kt`:

```kotlin
package com.nexio.tv.core.recommendations

import android.content.ContentValues
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.FileInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvChannelArtworkProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `openFile serves existing poster bytes read only`() {
        val diskKey = "tt15940132_rpdb_poster"
        val file = AndroidTvChannelArtwork.posterFile(context, diskKey)
        file.parentFile?.mkdirs()
        val bytes = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(bytes)
        val provider = Robolectric.buildContentProvider(AndroidTvChannelArtworkProvider::class.java)
            .create(AndroidTvChannelArtwork.authority(context))
            .get()

        provider.openFile(AndroidTvChannelArtwork.posterUri(context, diskKey), "r").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                assertArrayEquals(bytes, input.readBytes())
            }
        }
    }

    @Test
    fun `provider rejects write operations`() {
        val provider = Robolectric.buildContentProvider(AndroidTvChannelArtworkProvider::class.java)
            .create(AndroidTvChannelArtwork.authority(context))
            .get()
        val uri = AndroidTvChannelArtwork.posterUri(context, "tt15940132_rpdb_poster")

        assertThrows(UnsupportedOperationException::class.java) {
            provider.insert(uri, ContentValues())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.update(uri, ContentValues(), null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.delete(uri, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.openFile(uri, "rw")
        }
    }

    @Test
    fun `query returns display name and size for existing poster`() {
        val diskKey = "tt15940132_top_posters_poster"
        val file = AndroidTvChannelArtwork.posterFile(context, diskKey)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(9, 8, 7))
        val provider = Robolectric.buildContentProvider(AndroidTvChannelArtworkProvider::class.java)
            .create(AndroidTvChannelArtwork.authority(context))
            .get()

        provider.query(AndroidTvChannelArtwork.posterUri(context, diskKey), null, null, null, null).use { cursor ->
            assertNotNull(cursor)
            requireNotNull(cursor)
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(file.name, cursor.getString(cursor.getColumnIndexOrThrow("_display_name")))
            assertEquals(3L, cursor.getLong(cursor.getColumnIndexOrThrow("_size")))
        }
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkProviderTest
```

Expected: compile failure because `AndroidTvChannelArtworkProvider` does not exist.

- [ ] **Step 3: Implement the provider**

Create `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProvider.kt`:

```kotlin
package com.nexio.tv.core.recommendations

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

class AndroidTvChannelArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val appContext = context ?: return null
        val file = AndroidTvChannelArtwork.resolvePosterFile(appContext, uri) ?: return null
        return if (file.isFile) "image/jpeg" else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") {
            "Android TV channel artwork is read-only"
        }
        val appContext = context ?: throw FileNotFoundException("Provider context unavailable")
        val file = AndroidTvChannelArtwork.resolvePosterFile(appContext, uri)
            ?: throw FileNotFoundException("Unsupported artwork URI: $uri")
        if (!file.isFile || file.length() <= 0L) {
            throw FileNotFoundException("Artwork file not found: $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val appContext = context ?: return null
        val file = AndroidTvChannelArtwork.resolvePosterFile(appContext, uri) ?: return null
        if (!file.isFile) return null
        val columns = projection?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("Android TV channel artwork provider is read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Android TV channel artwork provider is read-only")
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Android TV channel artwork provider is read-only")
    }
}
```

- [ ] **Step 4: Register the provider**

Modify `app/src/main/AndroidManifest.xml` inside `<application>` after the existing `.core.search.AndroidTvSearchProvider` provider:

```xml
        <provider
            android:name=".core.recommendations.AndroidTvChannelArtworkProvider"
            android:authorities="${applicationId}.channelart"
            android:exported="true"
            android:grantUriPermissions="false" />
```

- [ ] **Step 5: Run the tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkProviderTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProvider.kt app/src/main/AndroidManifest.xml app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkProviderTest.kt
git commit -m "feat: serve Android TV artwork from local provider"
```

## Task 3: Add Coil-Backed Dedicated Channel Artwork Cache

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCache.kt`
- Test: `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCacheTest.kt`

- [ ] **Step 1: Write the failing pure cache-selection tests**

Create `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCacheTest.kt`:

```kotlin
package com.nexio.tv.core.recommendations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvChannelArtworkCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `request returns provider specific cache key for poster`() {
        val request = AndroidTvChannelArtworkCache.posterRequest(
            context = context,
            item = preview(
                id = "tt15940132",
                poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt15940132.jpg",
                posterProviderTag = "rpdb"
            )
        )

        requireNotNull(request)
        assertEquals(
            ArtworkImageCacheKeys.poster(
                itemId = "tt15940132",
                providerTag = "rpdb",
                posterUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt15940132.jpg"
            ),
            request.diskCacheKey
        )
        assertEquals(
            AndroidTvChannelArtwork.posterUri(context, request.diskCacheKey),
            request.localUri
        )
    }

    @Test
    fun `request returns null when item has no poster`() {
        assertNull(AndroidTvChannelArtworkCache.posterRequest(context, preview(poster = null)))
        assertNull(AndroidTvChannelArtworkCache.posterRequest(context, preview(poster = " ")))
    }

    private fun preview(
        id: String = "tt123",
        poster: String? = "https://images.example/poster.jpg",
        posterProviderTag: String? = null
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = "Example",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            posterProviderTag = posterProviderTag
        )
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkCacheTest
```

Expected: compile failure because `AndroidTvChannelArtworkCache` does not exist.

- [ ] **Step 3: Implement the cache**

Create `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCache.kt`:

```kotlin
package com.nexio.tv.core.recommendations

import android.content.Context
import android.net.Uri
import android.util.Log
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.sink

private const val CHANNEL_ARTWORK_CACHE_TAG = "AndroidTvChannelArtwork"

@Singleton
class AndroidTvChannelArtworkCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun cachedPosterUri(item: MetaPreview): Uri? {
        val request = posterRequest(context, item) ?: return null
        val targetFile = AndroidTvChannelArtwork.posterFile(context, request.diskCacheKey)
        if (targetFile.isFile && targetFile.length() > 0L) {
            return request.localUri
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                fetchWithCoil(request)
                copyCoilDiskCacheEntry(
                    diskCacheKey = request.diskCacheKey,
                    targetFile = targetFile
                )
                request.localUri.takeIf { targetFile.isFile && targetFile.length() > 0L }
            }.onFailure { error ->
                Log.w(
                    CHANNEL_ARTWORK_CACHE_TAG,
                    "Failed to prepare Android TV channel poster item=${item.id}",
                    error
                )
            }.getOrNull()
        }
    }

    private suspend fun fetchWithCoil(request: PosterRequest) {
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(request.remoteUrl)
                .diskCacheKey(request.diskCacheKey)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun copyCoilDiskCacheEntry(
        diskCacheKey: String,
        targetFile: File
    ) {
        val diskCache = context.imageLoader.diskCache ?: return
        diskCache.openSnapshot(diskCacheKey)?.use { snapshot ->
            copyPathToFile(snapshot.data, targetFile)
        }
    }

    private fun copyPathToFile(sourcePath: Path, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileSystem.SYSTEM.source(sourcePath).buffer().use { source ->
            tempFile.sink().buffer().use { sink ->
                sink.writeAll(source)
            }
        }
        if (tempFile.length() > 0L) {
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } else {
            tempFile.delete()
        }
    }

    data class PosterRequest(
        val remoteUrl: String,
        val diskCacheKey: String,
        val localUri: Uri
    )

    companion object {
        fun posterRequest(context: Context, item: MetaPreview): PosterRequest? {
            val posterUrl = item.poster?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val diskCacheKey = ArtworkImageCacheKeys.poster(
                itemId = item.id,
                providerTag = item.posterProviderTag,
                posterUrl = posterUrl
            )
            return PosterRequest(
                remoteUrl = posterUrl,
                diskCacheKey = diskCacheKey,
                localUri = AndroidTvChannelArtwork.posterUri(context, diskCacheKey)
            )
        }
    }
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkCacheTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCache.kt app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCacheTest.kt
git commit -m "feat: cache Android TV channel posters locally"
```

## Task 4: Use Local Poster URIs In Android TV Program Publishing

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt`
- Test: `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvProgramPresentationTest.kt`

- [ ] **Step 1: Write failing presentation tests**

Modify `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvProgramPresentationTest.kt` by adding these tests above `private fun preview(...)`:

```kotlin
    @Test
    fun `catalog feeds use local cached poster uri when available`() {
        val localPosterUri = Uri.parse("content://com.nexio.tv.channelart/poster/local.jpg")

        val presentation = AndroidTvProgramPresentation.from(
            item = preview(
                posterShape = PosterShape.LANDSCAPE,
                poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg",
                background = "https://images.example/backdrop.jpg"
            ),
            feedKey = "trakt_movie_popular",
            localPosterArtUri = localPosterUri
        )

        assertEquals(localPosterUri, presentation.posterArtUri)
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3, presentation.posterArtAspectRatio)
        assertNull(presentation.thumbnailUri)
    }

    @Test
    fun `continue watching keeps landscape artwork even when local poster is available`() {
        val localPosterUri = Uri.parse("content://com.nexio.tv.channelart/poster/local.jpg")

        val presentation = AndroidTvProgramPresentation.from(
            item = preview(
                posterShape = PosterShape.LANDSCAPE,
                poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg",
                background = "https://images.example/backdrop.jpg"
            ),
            feedKey = AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY,
            localPosterArtUri = localPosterUri
        )

        assertEquals(Uri.parse("https://images.example/backdrop.jpg"), presentation.posterArtUri)
        assertEquals(Uri.parse("https://images.example/backdrop.jpg"), presentation.thumbnailUri)
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9, presentation.posterArtAspectRatio)
    }
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvProgramPresentationTest
```

Expected: compile failure because `AndroidTvProgramPresentation.from` does not accept `localPosterArtUri`.

- [ ] **Step 3: Modify presentation selection**

In `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt`, change `AndroidTvProgramPresentation.from` signature from:

```kotlin
        fun from(
            item: MetaPreview,
            feedKey: String
        ): AndroidTvProgramPresentation {
```

to:

```kotlin
        fun from(
            item: MetaPreview,
            feedKey: String,
            localPosterArtUri: Uri? = null
        ): AndroidTvProgramPresentation {
```

Then change the catalog-poster branch from:

```kotlin
            if (!useContinueWatchingArtwork && poster != null) {
                posterArtUri = poster
                thumbnailUri = null
                posterArtAspectRatio = TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            } else {
```

to:

```kotlin
            if (!useContinueWatchingArtwork && poster != null) {
                posterArtUri = localPosterArtUri ?: poster
                thumbnailUri = null
                posterArtAspectRatio = TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            } else {
```

- [ ] **Step 4: Inject the artwork cache and await local poster creation**

In `AndroidTvChannelPublisher` constructor, change:

```kotlin
    private val feedCatalogService: AndroidTvFeedCatalogService,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService
) {
```

to:

```kotlin
    private val feedCatalogService: AndroidTvFeedCatalogService,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService,
    private val channelArtworkCache: AndroidTvChannelArtworkCache
) {
```

Change `publishProgram` from:

```kotlin
    private fun publishProgram(
        channelId: Long,
        item: MetaPreview,
        option: AndroidTvFeedOption,
        addonBaseUrl: String?,
        position: Int
    ) {
        runCatching {
            previewChannelHelper.publishPreviewProgram(
                buildProgram(
                    channelId = channelId,
                    item = item,
                    option = option,
                    addonBaseUrl = addonBaseUrl,
                    position = position
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to publish Android TV program key=${option.key} item=${item.id}", error)
        }
    }
```

to:

```kotlin
    private suspend fun publishProgram(
        channelId: Long,
        item: MetaPreview,
        option: AndroidTvFeedOption,
        addonBaseUrl: String?,
        position: Int
    ) {
        val localPosterArtUri = if (option.key == AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY) {
            null
        } else {
            channelArtworkCache.cachedPosterUri(item)
        }
        runCatching {
            previewChannelHelper.publishPreviewProgram(
                buildProgram(
                    channelId = channelId,
                    item = item,
                    option = option,
                    addonBaseUrl = addonBaseUrl,
                    position = position,
                    localPosterArtUri = localPosterArtUri
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to publish Android TV program key=${option.key} item=${item.id}", error)
        }
    }
```

Change `buildProgram` signature from:

```kotlin
    private fun buildProgram(
        channelId: Long,
        item: MetaPreview,
        option: AndroidTvFeedOption,
        addonBaseUrl: String?,
        position: Int
    ): PreviewProgram {
```

to:

```kotlin
    private fun buildProgram(
        channelId: Long,
        item: MetaPreview,
        option: AndroidTvFeedOption,
        addonBaseUrl: String?,
        position: Int,
        localPosterArtUri: Uri?
    ): PreviewProgram {
```

Change presentation creation from:

```kotlin
        val presentation = AndroidTvProgramPresentation.from(
            item = item,
            feedKey = option.key
        )
```

to:

```kotlin
        val presentation = AndroidTvProgramPresentation.from(
            item = item,
            feedKey = option.key,
            localPosterArtUri = localPosterArtUri
        )
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvProgramPresentationTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkCacheTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvProgramPresentationTest.kt
git commit -m "feat: publish Android TV channels with cached poster URIs"
```

## Task 5: Add Cache Maintenance And End-To-End Verification

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCache.kt`
- Test: `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCacheTest.kt`

- [ ] **Step 1: Add failing prune tests**

Append this test to `AndroidTvChannelArtworkCacheTest`:

```kotlin
    @Test
    fun `prune removes unreferenced channel artwork files`() {
        val keepRequest = requireNotNull(
            AndroidTvChannelArtworkCache.posterRequest(
                context,
                preview(id = "tt1", poster = "https://images.example/keep.jpg")
            )
        )
        val removeRequest = requireNotNull(
            AndroidTvChannelArtworkCache.posterRequest(
                context,
                preview(id = "tt2", poster = "https://images.example/remove.jpg")
            )
        )
        val keepFile = AndroidTvChannelArtwork.posterFile(context, keepRequest.diskCacheKey)
        val removeFile = AndroidTvChannelArtwork.posterFile(context, removeRequest.diskCacheKey)
        keepFile.parentFile?.mkdirs()
        keepFile.writeBytes(byteArrayOf(1))
        removeFile.writeBytes(byteArrayOf(2))

        AndroidTvChannelArtworkCache.pruneUnreferencedFiles(
            context = context,
            activeDiskCacheKeys = setOf(keepRequest.diskCacheKey)
        )

        assertTrue(keepFile.exists())
        assertEquals(false, removeFile.exists())
    }
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkCacheTest
```

Expected: compile failure because `pruneUnreferencedFiles` does not exist.

- [ ] **Step 3: Implement pruning**

In `AndroidTvChannelArtwork.kt`, add this function after `posterFile(...)`:

```kotlin
    fun posterDirectoryForMaintenance(context: Context): File {
        return posterDirectory(context)
    }
```

In `AndroidTvChannelArtworkCache.Companion`, add:

```kotlin
        fun pruneUnreferencedFiles(
            context: Context,
            activeDiskCacheKeys: Set<String>
        ) {
            val activeFileNames = activeDiskCacheKeys
                .map { diskCacheKey -> AndroidTvChannelArtwork.posterFile(context, diskCacheKey).name }
                .toSet()
            val directory = AndroidTvChannelArtwork.posterDirectoryForMaintenance(context)
            directory.listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.name !in activeFileNames }
                .forEach { file -> file.delete() }
        }
```

- [ ] **Step 4: Call pruning after selected feed rows are resolved**

In `AndroidTvChannelPublisher.syncNow`, after:

```kotlin
            val selectedByKey = selectedRows.associateBy { it.option.key }
```

add:

```kotlin
            AndroidTvChannelArtworkCache.pruneUnreferencedFiles(
                context = context,
                activeDiskCacheKeys = selectedRows
                    .flatMap { row -> row.items.take(MAX_PROGRAMS_PER_CHANNEL) }
                    .mapNotNull { item -> AndroidTvChannelArtworkCache.posterRequest(context, item)?.diskCacheKey }
                    .toSet()
            )
```

This keeps the dedicated channel-art cache bounded by the selected launcher feeds while Coil retains its own 200 MB size-bounded cache separately.

- [ ] **Step 5: Run focused and integration-adjacent tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkProviderTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkCacheTest --tests com.nexio.tv.core.recommendations.AndroidTvProgramPresentationTest --tests com.nexio.tv.core.recommendations.AndroidTvOwnedChannelRowsTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Manual Android TV validation**

Install a debug build on an Android TV device or emulator with launcher channels enabled.

Run:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb shell pm clear com.nexio.tv
```

In the app:

1. Configure RPDB or TOPPosters.
2. Enable Android TV launcher feeds.
3. Select a catalog feed with RPDB/TOPPosters posters.
4. Return to Android TV home.

Then inspect provider-backed program rows:

```bash
adb shell content query --uri content://android.media.tv/preview_program
```

Expected:

- Nexio preview programs have `poster_art_uri` values starting with `content://com.nexio.tv.channelart/poster/`.
- Android TV home still displays the correct RPDB/TOPPosters posters.
- Logcat does not show repeated network fetches for unchanged channel posters on subsequent syncs.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtwork.kt app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCache.kt app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvChannelArtworkCacheTest.kt
git commit -m "chore: prune cached Android TV channel artwork"
```

## Final Verification

- [ ] Run all channel artwork and publisher tests:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkProviderTest --tests com.nexio.tv.core.recommendations.AndroidTvChannelArtworkCacheTest --tests com.nexio.tv.core.recommendations.AndroidTvProgramPresentationTest --tests com.nexio.tv.core.recommendations.AndroidTvOwnedChannelRowsTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] Run the existing broader Android TV recommendation tests:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.*
```

Expected: `BUILD SUCCESSFUL`.

- [ ] Run diff hygiene:

```bash
git diff --check
```

Expected: no output and exit code `0`.

- [ ] Confirm the implementation does not alter app-internal poster/backdrop selection:

```bash
git diff -- app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt
```

Expected: no changes in this plan unless a later implementer intentionally extends in-app image rendering. This feature should affect Android TV channel publication only.

## Self-Review

**Spec coverage:**

- Local channel artwork URI backed by dedicated cache: Task 1, Task 2, Task 3.
- Coil reuse for paid-service download limits: Task 3 uses Coil `ImageLoader.execute` with provider-aware `diskCacheKey`.
- Android TV channel publishing with local URI: Task 4.
- Avoid exposing mutable Coil internals directly: Task 3 copies from Coil disk cache into a stable channel-art directory.
- Cache cleanup: Task 5.
- Android TV docs constraints: Context section and Task 4 preserve `setPosterArtUri(uri)`.

**Placeholder scan:** The plan contains concrete file paths, commands, code snippets, and expected outputs. There are no deferred implementation notes.

**Type consistency:** `AndroidTvChannelArtwork`, `AndroidTvChannelArtworkProvider`, `AndroidTvChannelArtworkCache`, `PosterRequest`, `cachedPosterUri`, `posterRequest`, and `pruneUnreferencedFiles` are introduced before later tasks reference them.
