# Formatter Chip Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add formatter-controlled stream chip tokens and an optional full-width badge row for stream cards.

**Architecture:** Extend the formatter data model to render a third optional `badgeRowTemplate`, then carry badge row text and chip-token presence through `StreamCardModel`. Add reusable Compose chip-token rendering in `ui/components`, and update both stream selection card surfaces to suppress automatic chips when formatter chip tokens are present.

**Tech Stack:** Kotlin, Jetpack Compose for Android TV, kotlinx serialization, DataStore preferences, JUnit.

---

### Task 1: Formatter Model And Tests

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioStreamPresentationAdapterTest.kt`

- [ ] **Step 1: Write failing formatter tests**

Add tests to `StreamPresentationEngineTest`:

```kotlin
@Test
fun `custom uniform formatting renders optional badge row template and detects chip token`() {
    val result = StreamPresentationEngine.organize(
        streams = listOf(stream(filename = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv", name = "⚡ RD")),
        availableAddons = listOf("Test Addon"),
        selectedAddonFilter = null,
        flags = StreamFeatureFlags(
            uniformFormattingEnabled = true,
            groupAcrossAddonsEnabled = false,
            uniformFormattingTemplate = AioFormatterSelection(
                selectedTemplateId = "custom",
                customTemplate = AioCustomTemplateSelection(
                    label = "Badge row",
                    nameTemplate = "{stream.title}",
                    descriptionTemplate = "{stream.year}",
                    badgeRowTemplate = "{service.cached::istrue[\"[[chip:cached]]\"||\"\"]}"
                )
            )
        ),
        requestContext = StreamRequestContext(contentType = "movie")
    )

    val item = result.items.single()
    assertEquals("[[chip:cached]]", item.badgeRow)
    assertEquals(true, item.hasFormatterChipTokens)
}

@Test
fun `custom uniform formatting detects inline chip token and leaves empty badge row blank`() {
    val result = StreamPresentationEngine.organize(
        streams = listOf(stream(filename = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv", name = "⚡ RD")),
        availableAddons = listOf("Test Addon"),
        selectedAddonFilter = null,
        flags = StreamFeatureFlags(
            uniformFormattingEnabled = true,
            groupAcrossAddonsEnabled = false,
            uniformFormattingTemplate = AioFormatterSelection(
                selectedTemplateId = "custom",
                customTemplate = AioCustomTemplateSelection(
                    label = "Inline badge",
                    nameTemplate = "{service.cached::istrue[\"[[chip:cached]] \"||\"\"]}{stream.title}",
                    descriptionTemplate = "{stream.year}"
                )
            )
        ),
        requestContext = StreamRequestContext(contentType = "movie")
    )

    val item = result.items.single()
    assertEquals("[[chip:cached]] Movie Title", item.title)
    assertEquals(null, item.badgeRow)
    assertEquals(true, item.hasFormatterChipTokens)
}
```

Update existing assertions in `AioStreamPresentationAdapterTest` and current formatter tests to expect `item.badgeRow == null` and `item.hasFormatterChipTokens == false` where useful.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.core.stream.AioStreamPresentationAdapterTest
```

Expected: compile failure for missing `badgeRowTemplate`, `badgeRow`, or `hasFormatterChipTokens`.

- [ ] **Step 3: Implement formatter model**

In `AioStreamFormatting.kt`:

```kotlin
data class AioTemplateDefinition(
    val id: String,
    val nameTemplate: String,
    val descriptionTemplate: String,
    val badgeRowTemplate: String = ""
)

data class AioCustomTemplateSelection(
    val label: String? = null,
    val nameTemplate: String? = null,
    val descriptionTemplate: String? = null,
    val badgeRowTemplate: String? = null
)

data class AioUniformPresentation(
    val title: String,
    val detailLines: List<String>,
    val badgeRow: String? = null,
    val hasFormatterChipTokens: Boolean = false
)
```

Extend `AioFormattedText` and `AioTemplateFormatter`:

```kotlin
data class AioFormattedText(
    val name: String,
    val description: String,
    val badgeRow: String = ""
)

class AioTemplateFormatter(
    private val nameTemplate: String,
    private val descriptionTemplate: String,
    private val badgeRowTemplate: String = ""
) {
    fun format(parseValue: AioParseValue): AioFormattedText {
        return AioFormattedText(
            name = renderTemplate(nameTemplate, parseValue),
            description = renderTemplate(descriptionTemplate, parseValue),
            badgeRow = renderTemplate(badgeRowTemplate, parseValue)
        )
    }
}
```

In `AioUniformFormatter.render`, construct the formatter with `badgeRowTemplate`, render it, trim blank output to `null`, and set chip-token presence:

```kotlin
val formatter = AioTemplateFormatter(
    nameTemplate = definition.nameTemplate,
    descriptionTemplate = definition.descriptionTemplate,
    badgeRowTemplate = definition.badgeRowTemplate
)
val formatted = formatter.format(AioParseValueFactory.from(stream, parsed, requestContext))
val badgeRow = formatted.badgeRow.trim().takeIf { it.isNotEmpty() }
return AioUniformPresentation(
    title = formatted.name.trim(),
    detailLines = formatted.description.lines().map { it.trim() }.filter { it.isNotEmpty() },
    badgeRow = badgeRow,
    hasFormatterChipTokens = containsChipToken(formatted.name) ||
        containsChipToken(formatted.description) ||
        containsChipToken(badgeRow.orEmpty())
)
```

Add a private token detector:

```kotlin
private val ChipTokenPattern = Regex("""\[\[chip:[a-z0-9_]+]]""", RegexOption.IGNORE_CASE)

private fun containsChipToken(value: String): Boolean = ChipTokenPattern.containsMatchIn(value)
```

In `resolveTemplate`, include `badgeRowTemplate = customTemplate.badgeRowTemplate.orEmpty()` when building a custom definition.

In `StreamPresentationModels.kt`, add fields to `StreamCardModel`:

```kotlin
val badgeRow: String? = null,
val hasFormatterChipTokens: Boolean = false
```

Include `selection.customTemplate?.badgeRowTemplate.orEmpty()` in the uniform presentation cache key, and pass `uniformPresentation?.badgeRow` plus `uniformPresentation?.hasFormatterChipTokens == true` into `StreamCardModel`.

- [ ] **Step 4: Run tests to verify model passes**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.core.stream.AioStreamPresentationAdapterTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt app/src/test/java/com/nexio/tv/core/stream/AioStreamPresentationAdapterTest.kt
git commit -m "feat: add formatter badge row model"
```

### Task 2: Chip Token Renderer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/components/InlineChipText.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/StreamBadgeSupport.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/StreamDetailLines.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/components/InlineChipTokenRegistryTest.kt`

- [ ] **Step 1: Write failing chip registry tests**

Create `InlineChipTokenRegistryTest.kt` with tests for known and unknown tokens:

```kotlin
package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineChipTokenRegistryTest {
    @Test
    fun `tokenize parses known chip tokens`() {
        val segments = InlineChipTokenRegistry.tokenize("A [[chip:cached]] B [[chip:torrent]]")
        val chips = segments.filterIsInstance<InlineChipSegment.ChipSegment>()

        assertEquals(listOf(StreamBadgeKind.CACHED, StreamBadgeKind.TORRENT), chips.map { it.kind })
    }

    @Test
    fun `tokenize strips unknown chip tokens to readable text`() {
        val segments = InlineChipTokenRegistry.tokenize("A [[chip:unknown_badge]]")
        val flattened = segments.joinToString("") {
            when (it) {
                is InlineChipSegment.TextSegment -> it.text
                is InlineChipSegment.ChipSegment -> it.kind.name
            }
        }

        assertEquals("A unknown_badge", flattened)
    }

    @Test
    fun `containsToken detects any chip token`() {
        assertTrue(InlineChipTokenRegistry.containsToken("before [[chip:cached]] after"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.components.InlineChipTokenRegistryTest
```

Expected: compile failure for missing `InlineChipTokenRegistry`.

- [ ] **Step 3: Implement chip token renderer**

Create `InlineChipText.kt` with:

```kotlin
package com.nexio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexio.tv.R
import com.nexio.tv.ui.theme.NexioColors
import java.util.Locale

sealed interface InlineChipSegment {
    data class TextSegment(val text: String) : InlineChipSegment
    data class ChipSegment(val kind: StreamBadgeKind) : InlineChipSegment
}

object InlineChipTokenRegistry {
    private val tokenPattern = Regex("""\[\[chip:([a-z0-9_]+)]]""", RegexOption.IGNORE_CASE)

    fun containsToken(text: String): Boolean = tokenPattern.containsMatchIn(text)

    fun tokenize(text: String): List<InlineChipSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<InlineChipSegment>()
        var cursor = 0
        tokenPattern.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                segments.appendText(text.substring(cursor, match.range.first))
            }
            val token = match.groupValues[1]
            val kind = streamBadgeKindForToken(token)
            if (kind != null) {
                segments += InlineChipSegment.ChipSegment(kind)
            } else {
                segments.appendText(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            segments.appendText(text.substring(cursor))
        }
        return segments
    }

    private fun MutableList<InlineChipSegment>.appendText(value: String) {
        if (value.isEmpty()) return
        val previous = lastOrNull()
        if (previous is InlineChipSegment.TextSegment) {
            this[lastIndex] = previous.copy(text = previous.text + value)
        } else {
            add(InlineChipSegment.TextSegment(value))
        }
    }

    private fun streamBadgeKindForToken(token: String): StreamBadgeKind? {
        return when (token.trim().lowercase(Locale.US)) {
            "cached" -> StreamBadgeKind.CACHED
            "torrent" -> StreamBadgeKind.TORRENT
            "youtube" -> StreamBadgeKind.YOUTUBE
            "external" -> StreamBadgeKind.EXTERNAL
            else -> null
        }
    }
}
```

Add reusable chip UI:

```kotlin
@Composable
fun StreamTypeChip(kind: StreamBadgeKind) {
    StreamTypeChip(
        text = when (kind) {
            StreamBadgeKind.CACHED -> stringResource(R.string.stream_type_cached)
            StreamBadgeKind.TORRENT -> stringResource(R.string.stream_type_torrent)
            StreamBadgeKind.YOUTUBE -> stringResource(R.string.stream_type_youtube)
            StreamBadgeKind.EXTERNAL -> stringResource(R.string.stream_type_external)
        },
        color = when (kind) {
            StreamBadgeKind.CACHED -> NexioColors.Success
            StreamBadgeKind.TORRENT -> NexioColors.Secondary
            StreamBadgeKind.YOUTUBE -> Color(0xFFFF0000)
            StreamBadgeKind.EXTERNAL -> NexioColors.Primary
        }
    )
}

@Composable
fun StreamTypeChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
```

Add `InlineChipText` and `FormatterBadgeRow`:

```kotlin
@Composable
fun InlineChipText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val segments = remember(text) { InlineChipTokenRegistry.tokenize(text) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is InlineChipSegment.TextSegment -> Text(
                    text = segment.text,
                    style = style,
                    maxLines = maxLines,
                    overflow = overflow
                )
                is InlineChipSegment.ChipSegment -> StreamTypeChip(segment.kind)
            }
        }
    }
}

@Composable
fun FormatterBadgeRow(
    text: String,
    modifier: Modifier = Modifier
) {
    val segments = remember(text) { InlineChipTokenRegistry.tokenize(text) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is InlineChipSegment.TextSegment -> {
                    if (segment.text.isNotBlank()) {
                        Text(
                            text = segment.text.trim(),
                            style = MaterialTheme.typography.labelSmall,
                            color = NexioTheme.extendedColors.textSecondary
                        )
                    }
                }
                is InlineChipSegment.ChipSegment -> StreamTypeChip(segment.kind)
            }
        }
    }
}
```

Update `StreamDetailLines` to call `InlineChipText` when `InlineChipTokenRegistry.containsToken(displayText)` is true, otherwise keep `InlineIconText`.

- [ ] **Step 4: Run tests to verify chip registry passes**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.components.InlineChipTokenRegistryTest --tests com.nexio.tv.ui.components.StreamDetailLinesTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/InlineChipText.kt app/src/main/java/com/nexio/tv/ui/components/StreamBadgeSupport.kt app/src/main/java/com/nexio/tv/ui/components/StreamDetailLines.kt app/src/test/java/com/nexio/tv/ui/components/InlineChipTokenRegistryTest.kt
git commit -m "feat: render formatter chip tokens"
```

### Task 3: Stream Card Layouts

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt`

- [ ] **Step 1: Update stream card surface**

In `StreamScreen.kt`, replace local `StreamTypeChip` usage with imported `com.nexio.tv.ui.components.StreamTypeChip`, add `FormatterBadgeRow`, and wrap the card body in a column. Move the current `Row` that contains the text column and addon logo inside the new outer `Column`:

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(0.8f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (InlineChipTokenRegistry.containsToken(streamName)) {
                InlineChipText(
                    text = streamName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = NexioColors.TextPrimary
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                InlineIconText(
                    text = streamName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = NexioColors.TextPrimary
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            streamSubtitle?.takeIf { it != streamName }?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioTheme.extendedColors.textSecondary
                )
            }

            StreamDetailLines(
                detailLines = detailLines,
                colorStyle = MaterialTheme.typography.bodySmall.copy(
                    color = NexioTheme.extendedColors.textSecondary
                )
            )
        }

        Column(
            modifier = Modifier.weight(0.2f),
            horizontalAlignment = Alignment.End
        ) {
            addonLogoModel?.let { model ->
                AsyncImage(
                    model = model,
                    contentDescription = stream.addonName,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    item.badgeRow?.let { badgeRow ->
        FormatterBadgeRow(text = badgeRow)
    }
}
```

Replace automatic chip row condition:

```kotlin
if (!item.hasFormatterChipTokens) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        streamBadgeKinds(stream, item.parsed).forEach { badge ->
            StreamTypeChip(badge)
        }
    }
}
```

Use `InlineChipText` for the title when it contains chip tokens; otherwise keep `InlineIconText`.

- [ ] **Step 2: Update player stream source surface**

Apply the same card body layout, automatic chip fallback condition, and reusable `StreamTypeChip` usage in `StreamComponents.kt`.

Ensure the existing `isCurrentStream` chip remains independent of formatter chip suppression.

- [ ] **Step 3: Run compile and focused component tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.components.InlineChipTokenRegistryTest --tests com.nexio.tv.ui.components.StreamBadgeSupportTest
```

Expected: PASS.

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt
git commit -m "feat: place formatter chips in stream cards"
```

### Task 4: Sync And Documentation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
- Modify: `docs-site/web/admin-workspaces/formatter.md`

- [ ] **Step 1: Write failing sync contract test changes**

Update `AccountConfigSyncContractTest` custom formatter payload examples to include:

```kotlin
badgeRowTemplate = "[[chip:cached]]"
```

Assert serialized formatter custom template includes:

```kotlin
assertEquals(
    "\"[[chip:cached]]\"",
    json["formatter"]?.jsonObject
        ?.get("customTemplate")?.jsonObject
        ?.get("badgeRowTemplate")
        ?.toString()
)
```

Update the apply test `coVerify` call to expect:

```kotlin
playerSettingsDataStore.setSyncedFormatterCustomTemplate(
    label = "Custom",
    nameTemplate = "{stream.title}",
    descriptionTemplate = "{stream.quality}",
    badgeRowTemplate = "[[chip:cached]]"
)
```

- [ ] **Step 2: Run sync contract tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest
```

Expected: compile failure for missing `badgeRowTemplate` sync properties or method parameter.

- [ ] **Step 3: Implement sync propagation**

Add `customBadgeRowTemplate: String? = null` to `SyncedFormatterTemplateSettings`, add a `syncedFormatterCustomBadgeRowTemplateKey`, load it from DataStore, and update `setSyncedFormatterCustomTemplate` to accept and store/remove `badgeRowTemplate`.

Add `val badgeRowTemplate: String = ""` to `CustomFormatterSyncTemplate`.

Pass `badgeRowTemplate` through:

```kotlin
playerSettingsDataStore.setSyncedFormatterCustomTemplate(
    label = settings.formatter.customTemplate?.label,
    nameTemplate = settings.formatter.customTemplate?.nameTemplate,
    descriptionTemplate = settings.formatter.customTemplate?.descriptionTemplate,
    badgeRowTemplate = settings.formatter.customTemplate?.badgeRowTemplate
)
```

And when exporting:

```kotlin
badgeRowTemplate = playerSettings.syncedFormatterTemplate.customBadgeRowTemplate.orEmpty()
```

Update both `toAioFormatterSelection()` functions to pass `badgeRowTemplate = customBadgeRowTemplate`.

- [ ] **Step 4: Update formatter docs**

In `docs-site/web/admin-workspaces/formatter.md`, add `badgeRowTemplate` to the formatter shape and add a chip-token section with:

```text
[[chip:cached]]
[[chip:torrent]]
[[chip:youtube]]
[[chip:external]]
```

Include inline and `badgeRowTemplate` examples from the design spec.

- [ ] **Step 5: Run sync and formatter tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt docs-site/web/admin-workspaces/formatter.md
git commit -m "feat: sync formatter badge row template"
```

### Task 5: Final Verification

**Files:**
- No new files.

- [ ] **Step 1: Run focused formatter and UI unit tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.core.stream.AioStreamPresentationAdapterTest --tests com.nexio.tv.ui.components.InlineChipTokenRegistryTest --tests com.nexio.tv.ui.components.StreamBadgeSupportTest --tests com.nexio.tv.ui.components.StreamDetailLinesTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest
```

Expected: PASS.

- [ ] **Step 2: Run compile**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Inspect final diff**

Run:

```bash
git status --short
```

Expected: only unrelated pre-existing files remain untracked or modified.

Run:

```bash
git log --oneline -5
```

Expected: this work appears as focused commits after the design commit.
