# Playback Diagnostics ZIP Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the troubleshooting settings export every retained playback-diagnostics trace as a `.zip` saved to filesystem storage such as Downloads, USB, or cloud-backed document providers, even when the device has no share target installed.

**Architecture:** Keep [`PlaybackTraceController`](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt) as the single source of truth for trace export logic. Reuse the app’s existing SAF `CreateDocument` pattern from “Copy latest to…” so the all-sessions export writes a ZIP directly to user-selected storage instead of depending on `ACTION_SEND`. Preserve the existing single-session share flow for “Export last”, and make the all-sessions row a filesystem-save flow that still includes rolled-over `*-1.jsonl` parts.

**Tech Stack:** Android/Kotlin, Jetpack Compose, Activity Result APIs, SAF/`ContentResolver`, `java.util.zip`, JUnit4, MockK

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
  - extract reusable ZIP creation so both share-intent export and filesystem-save export use the same archive builder
  - add a `copyAllToDestination(Uri)` path that writes the ZIP to SAF storage
  - expose a deterministic suggested ZIP filename helper for the UI
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackDiagnosticsSection.kt`
  - replace the all-sessions row’s `() -> Unit` callback with a `CreateDocument("application/zip")` launcher
  - keep the latest-session JSON launcher unchanged
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - update callback plumbing for the new ZIP destination flow
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - pass the new ZIP destination lambda into the diagnostics section wiring
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  - add a ViewModel action that calls `copyAllToDestination(Uri)`, refreshes trace status, and emits a toast message
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values/strings.xml`
  - change the all-sessions subtitle from “share it” to “save it”
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-de/strings.xml`
  - keep German copy aligned with the new save-to-storage behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-es/strings.xml`
  - keep Spanish copy aligned with the new save-to-storage behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-nl/strings.xml`
  - keep Dutch copy aligned with the new save-to-storage behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-zh-rCN/strings.xml`
  - keep Simplified Chinese copy aligned with the new save-to-storage behavior
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
  - update the in-app export instructions and FAQ so they describe saving a ZIP to storage instead of relying on the share sheet

### Test files

- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
  - verify the new ZIP-save path writes every retained `.jsonl`, including rotated files
  - pin the “no traces” behavior
  - pin the existing `exportAll()` share-intent path so ADB/back-compat does not regress accidentally
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`
  - verify the new ViewModel action delegates to the controller, refreshes status, and reports success/empty-state messages

## Guardrails

- Do not add `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, or direct raw-path writes to `/sdcard/Download` in this pass.
- Do not remove or weaken `exportLast()`; only the all-sessions export path changes behavior in the UI.
- Keep ZIP creation on `Dispatchers.IO`.
- Include rolled-over files like `<sessionId>-1.jsonl` in the ZIP.
- Keep the controller as the only place that knows how the ZIP is assembled.
- Update every locale file that already defines the touched playback-diagnostics strings.

---

### Task 1: Add Failing Controller Coverage For ZIP Save Export

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`

- [ ] **Step 1: Write a failing test for saving all sessions as a ZIP**

```kotlin
@Test
fun `copyAllToDestination writes zip containing every retained trace including rolled files`() = runTest {
    val appFilesDir = createTempDirectory("playback-trace-controller-files").toFile()
    val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
    File(tracesDir, "session-a.jsonl").writeText("{\"type\":\"started\"}\n")
    File(tracesDir, "session-a-1.jsonl").writeText("{\"type\":\"rotated\"}\n")
    File(tracesDir, "session-b.jsonl").writeText("{\"type\":\"ended\"}\n")

    val destinationUri = Uri.parse("content://tests/playback-traces.zip")
    val destinationBytes = ByteArrayOutputStream()
    val contentResolver = mockk<ContentResolver>()
    every { contentResolver.openOutputStream(destinationUri) } returns destinationBytes

    val controller = buildController(
        filesDir = appFilesDir,
        cacheDir = createTempDirectory("playback-trace-controller-cache").toFile(),
        contentResolver = contentResolver,
    )

    val written = controller.copyAllToDestination(destinationUri)

    assertTrue(written > 0L)
    ZipInputStream(ByteArrayInputStream(destinationBytes.toByteArray())).use { zip ->
        val names = mutableListOf<String>()
        while (true) {
            val entry = zip.nextEntry ?: break
            names += entry.name
        }
        assertEquals(
            setOf("session-a.jsonl", "session-a-1.jsonl", "session-b.jsonl"),
            names.toSet(),
        )
    }
}
```

- [ ] **Step 2: Write a failing empty-state test**

```kotlin
@Test
fun `copyAllToDestination returns zero when no traces exist`() = runTest {
    val destinationUri = Uri.parse("content://tests/playback-traces.zip")
    val contentResolver = mockk<ContentResolver>(relaxed = true)
    val controller = buildController(
        filesDir = createTempDirectory("playback-trace-controller-empty").toFile(),
        cacheDir = createTempDirectory("playback-trace-controller-empty-cache").toFile(),
        contentResolver = contentResolver,
    )

    val written = controller.copyAllToDestination(destinationUri)

    assertEquals(0L, written)
    verify(exactly = 0) { contentResolver.openOutputStream(any()) }
}
```

- [ ] **Step 3: Write a regression test that keeps the share-intent export alive**

```kotlin
@Test
fun `exportAll still returns ACTION_SEND zip intent`() = runTest {
    val appFilesDir = createTempDirectory("playback-trace-controller-share").toFile()
    File(appFilesDir, "playback-traces").apply {
        mkdirs()
        resolve("session-a.jsonl").writeText("{\"type\":\"started\"}\n")
    }

    val controller = buildController(
        filesDir = appFilesDir,
        cacheDir = createTempDirectory("playback-trace-controller-share-cache").toFile(),
        contentResolver = mockk(relaxed = true),
    )

    val intent = controller.exportAll()

    assertEquals(Intent.ACTION_SEND, intent?.action)
    assertEquals("application/zip", intent?.type)
}
```

- [ ] **Step 4: Run the focused test class and verify it fails on the missing save-zip API**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest"`

Expected: FAIL with unresolved `copyAllToDestination`, missing test helper plumbing, or assertions against the current share-only behavior.

- [ ] **Step 5: Commit the failing controller tests**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt
git commit -m "test: capture playback diagnostics zip save gap"
```

---

### Task 2: Refactor The Controller To Build And Save ZIP Exports

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`

- [ ] **Step 1: Extract a reusable ZIP builder that returns the generated cache file**

```kotlin
private fun buildAllSessionsZip(files: List<File>): File {
    val exportsDir = File(appContext.cacheDir, "playback-trace-exports").apply { mkdirs() }
    val zipFile = File(exportsDir, suggestedAllExportFileName())
    ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
        for (file in files) {
            zip.putNextEntry(ZipEntry(file.name))
            FileInputStream(file).use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
    return zipFile
}
```

- [ ] **Step 2: Add a suggested ZIP filename helper for UI and controller reuse**

```kotlin
internal fun suggestedAllExportFileName(nowMs: Long = System.currentTimeMillis()): String {
    return "playback-traces-$nowMs.zip"
}
```

- [ ] **Step 3: Rework `exportAll()` to reuse the shared ZIP builder**

```kotlin
suspend fun exportAll(): Intent? = withContext(Dispatchers.IO) {
    val files = listTraces()
    if (files.isEmpty()) return@withContext null
    val zipFile = buildAllSessionsZip(files)
    buildShareIntentForFile(zipFile)
}
```

- [ ] **Step 4: Add the new filesystem-save API for all sessions**

```kotlin
suspend fun copyAllToDestination(destinationUri: Uri): Long = withContext(Dispatchers.IO) {
    val files = listTraces()
    if (files.isEmpty()) return@withContext 0L

    val zipFile = buildAllSessionsZip(files)
    appContext.contentResolver.openOutputStream(destinationUri)?.use { out ->
        FileInputStream(zipFile).use { input -> input.copyTo(out) }
    } ?: return@withContext 0L

    zipFile.length()
}
```

- [ ] **Step 5: Add a small test-only helper to construct the controller cleanly**

```kotlin
private fun buildController(
    filesDir: File,
    cacheDir: File,
    contentResolver: ContentResolver,
): PlaybackTraceController {
    val context = mockk<Context>()
    every { context.filesDir } returns filesDir
    every { context.cacheDir } returns cacheDir
    every { context.contentResolver } returns contentResolver
    every { context.packageName } returns "com.nexio.tv"
    return PlaybackTraceController(
        appContext = context,
        toggle = mockk(relaxed = true),
    )
}
```

- [ ] **Step 6: Run the controller tests and verify they pass**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest"`

Expected: PASS for the ZIP-save, empty-state, and share-intent regression tests.

- [ ] **Step 7: Commit the controller refactor**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt
git commit -m "feat: save playback diagnostics zip to storage"
```

---

### Task 3: Add ViewModel Coverage For The New ZIP Save Flow

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`

- [ ] **Step 1: Write a failing test for the successful ZIP-save path**

```kotlin
@Test
fun `copy all traces zip delegates to controller refreshes status and emits success message`() = runTest(dispatcher) {
    val destination = Uri.parse("content://tests/playback-traces.zip")
    val playbackTraceController =
        mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true).also {
            every { it.enabledFlow } returns emptyFlow()
            every { it.statusFlow } returns MutableStateFlow(com.nexio.tv.instrumentation.TraceStatus.EMPTY)
            coEvery { it.copyAllToDestination(destination) } returns 4096L
        }

    val viewModel = buildViewModel(playbackTraceController = playbackTraceController)
    advanceUntilIdle()

    val messages = mutableListOf<String>()
    val collectJob = backgroundScope.launch { viewModel.messages.toList(messages) }

    viewModel.copyAllTracesZipToDestination(destination)
    advanceUntilIdle()

    coVerify { playbackTraceController.copyAllToDestination(destination) }
    coVerify { playbackTraceController.refreshStatus() }
    assertTrue(messages.any { it.contains("Saved diagnostics zip") })
    collectJob.cancel()
}
```

- [ ] **Step 2: Write a failing empty-state test**

```kotlin
@Test
fun `copy all traces zip emits no trace message when nothing was written`() = runTest(dispatcher) {
    val destination = Uri.parse("content://tests/playback-traces.zip")
    val playbackTraceController =
        mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true).also {
            every { it.enabledFlow } returns emptyFlow()
            every { it.statusFlow } returns MutableStateFlow(com.nexio.tv.instrumentation.TraceStatus.EMPTY)
            coEvery { it.copyAllToDestination(destination) } returns 0L
        }

    val viewModel = buildViewModel(playbackTraceController = playbackTraceController)
    advanceUntilIdle()

    val messages = mutableListOf<String>()
    val collectJob = backgroundScope.launch { viewModel.messages.toList(messages) }

    viewModel.copyAllTracesZipToDestination(destination)
    advanceUntilIdle()

    assertTrue(messages.any { it == "No playback traces to export" })
    collectJob.cancel()
}
```

- [ ] **Step 3: Run the focused ViewModel tests to verify they fail**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`

Expected: FAIL because the new `copyAllTracesZipToDestination(Uri)` action and messages do not exist yet.

- [ ] **Step 4: Implement the new ViewModel action**

```kotlin
internal fun copyAllTracesZipToDestination(target: Uri) {
    viewModelScope.launch {
        val bytes = playbackTraceController.copyAllToDestination(target)
        playbackTraceController.refreshStatus()
        if (bytes > 0L) {
            messages.tryEmit("Saved diagnostics zip (${bytes / 1024L} KiB)")
        } else {
            messages.tryEmit("No playback traces to export")
        }
    }
}
```

- [ ] **Step 5: Run the focused ViewModel tests again and verify they pass**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`

Expected: PASS for the new ZIP-save tests and no regression in existing diagnostics state tests.

- [ ] **Step 6: Commit the ViewModel wiring**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt
git commit -m "feat: wire playback diagnostics zip save action"
```

---

### Task 4: Swap The UI From Share-Only Export To Filesystem Save

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackDiagnosticsSection.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-de/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-es/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-nl/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Change the section API so “Export all sessions” receives a destination URI**

```kotlin
internal fun LazyListScope.playbackDiagnosticsItems(
    // ...
    onExportAllToDestination: (Uri) -> Unit,
    onCopyToDownloads: (Uri) -> Unit,
    // ...
)
```

- [ ] **Step 2: Replace the row click with a ZIP `CreateDocument` launcher**

```kotlin
item(key = "playback_diagnostics_export_all") {
    val createZipDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) onExportAllToDestination(uri)
    }
    SettingsActionRow(
        title = stringResource(R.string.playback_diagnostics_export_all_title),
        subtitle = stringResource(R.string.playback_diagnostics_export_all_subtitle),
        value = if (status.sessionCount > 0) {
            "${status.sessionCount} · ${"%.1f".format(status.totalBytes / (1024.0 * 1024.0))} MiB"
        } else null,
        enabled = status.sessionCount > 0,
        onClick = {
            createZipDocumentLauncher.launch(
                "playback-traces-${System.currentTimeMillis()}.zip"
            )
        },
    )
}
```

- [ ] **Step 3: Update the screen and section plumbing to call the new ViewModel action**

```kotlin
playbackDiagnosticsItems(
    // ...
    onExportAllToDestination = onExportAllSessionsToDestination,
    onCopyToDownloads = onCopyLastTraceToDownloads,
    // ...
)
```

```kotlin
onExportAllSessionsToDestination = { uri ->
    debridViewModel.copyAllTracesZipToDestination(uri)
}
```

- [ ] **Step 4: Update all user-facing copy to describe saving the ZIP instead of sharing it**

```xml
<string name="playback_diagnostics_export_all_title">Export all sessions</string>
<string name="playback_diagnostics_export_all_subtitle">
    Bundle every retained session into a .zip and save it to storage
</string>
```

Use equivalent wording in `values-de`, `values-es`, `values-nl`, and `values-zh-rCN`.

- [ ] **Step 5: Run a compile check for the settings UI**

Run: `./gradlew --no-daemon :app:compileUniversalDebugKotlin`

Expected: PASS with no signature mismatch between `PlaybackDiagnosticsSection`, `PlaybackSettingsSections`, `PlaybackSettingsScreen`, and `DebridSettingsContent`.

- [ ] **Step 6: Commit the UI and localization changes**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackDiagnosticsSection.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-de/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-es/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-nl/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat: save playback diagnostics zip via document picker"
```

---

### Task 5: Update Docs And Run Final Verification

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`

- [ ] **Step 1: Update the in-app export section in the diagnostics doc**

```md
- **Export all (.zip)** — bundles every retained session (up to 20)
  into a single zip and opens the document picker so you can save it to
  Downloads, USB storage, or a cloud-backed provider.
```

- [ ] **Step 2: Update the Fire TV FAQ entry**

```md
**"I don't see a share target on Fire TV"**
— Fire TV's share sheet is sparse. Use **Export all (.zip)** or
**Copy to…** instead — both flows write to the document picker so you
can save to Downloads, USB storage, or cloud providers.
```

- [ ] **Step 3: Run the targeted verification suite**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest" --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`

Expected: PASS for the new controller and ViewModel coverage.

- [ ] **Step 4: Run the Kotlin compile gate one more time**

Run: `./gradlew --no-daemon :app:compileUniversalDebugKotlin`

Expected: PASS.

- [ ] **Step 5: Commit the docs and verification-complete state**

```bash
git add /Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md
git commit -m "docs: update playback diagnostics zip export instructions"
```

---

## Self-Review

### Spec coverage

- The plan covers in-app ZIP creation inside the app rather than requiring an external handler.
- The plan covers filesystem persistence through SAF so the user can save to Downloads, USB, or cloud-backed storage.
- The plan explicitly preserves rolled-over JSONL files in the ZIP.
- The plan preserves the existing latest-session export/share flow and scopes the change to the broken all-sessions export path.

### Placeholder scan

- No `TODO`, `TBD`, or “handle appropriately” placeholders remain.
- Every code-changing task includes concrete code or command scaffolding.
- Every validation step names the exact Gradle command to run.

### Type consistency

- The new controller API is consistently named `copyAllToDestination(Uri)`.
- The new ViewModel action is consistently named `copyAllTracesZipToDestination(Uri)`.
- The UI callback is consistently named `onExportAllToDestination`.
