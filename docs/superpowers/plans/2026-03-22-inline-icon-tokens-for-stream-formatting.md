# Inline Icon Tokens For Stream Formatting Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a shared text-first inline icon token system for stream formatter output, render those tokens as correctly sized inline platform/network icons on Android and web, and update the built-in `universal` formatter template to use those tokens instead of emoji markers.

**Architecture:** Keep the formatter engine text-only and introduce a renderer-layer token contract that survives the parser/formatter unchanged. Android and web each get a small icon registry plus rich-text renderer that converts known tokens into inline images and falls back to plain text labels when token rendering is unavailable.

**Tech Stack:** Kotlin, Jetpack Compose, Nuxt/Vue 3, TypeScript, AIO-style formatter templates, ImageMagick (`magick`) for asset conversion.

---

## File Structure

**Android formatter/text pipeline**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`
  - Update the built-in `universal` template to emit icon tokens plus plain text labels.
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt`
  - Single source of truth for token ids, fallback labels, and drawable resource ids.
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt`
  - Compose renderer that tokenizes a string and renders inline images with `InlineTextContent`.
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt`
  - Replace detail-line `Text(...)` rendering with `InlineIconText(...)`.
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt`
  - Replace detail-line `Text(...)` rendering with `InlineIconText(...)`.
- Create assets: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_*.png`
  - Android-ready copies of the supplied web icon assets.

**Web formatter preview/rendering**
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-templates.ts`
  - Update built-in `universal` template to use icon tokens.
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-icon-tokens.ts`
  - Shared token registry for web: token id, public asset path, fallback label.
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/formatter/FormatterRichText.vue`
  - Token-aware renderer that outputs inline images and text segments.
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/FormatterWorkspace.vue`
  - Use `FormatterRichText` inside the live preview instead of a plain `<p>`.
- Create public assets: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/*`
  - Stable public paths for network icons used by the preview and future formatter surfaces.

**Tests**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`
  - Assert the built-in template emits the new icon tokens.
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
  - Assert rendered detail lines contain the new tokenized output where expected.
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`
  - Lock token parsing/fallback behavior on Android.
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs`
  - Lock token parsing/fallback behavior on web.

## Shared Token Contract

Use a formatter-safe literal token syntax that does **not** collide with `{section.field}` placeholders:

- `[[icon:netflix]]`
- `[[icon:disneyplus]]`
- `[[icon:hbo]]`
- `[[icon:max]]`
- `[[icon:prime]]`
- `[[icon:appletv]]`
- `[[icon:paramount]]`
- `[[icon:peacock]]`
- `[[icon:crunchyroll]]`

Templates should emit the token immediately followed by the plain text label:

```txt
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}
```

That guarantees:
- supported renderers show image + label
- unsupported renderers still show a sensible plain label after token stripping
- formatter output stays text-first and portable

## Asset Preparation Rules

- Convert supplied `ico` files into clean PNGs before adding Android drawables.
- Keep assets visually balanced at roughly square 20px-32px source size for predictable inline scaling.
- Preserve transparent backgrounds.
- Use consistent output names:
  - `formatter_icon_netflix.png`
  - `formatter_icon_disneyplus.png`
  - `formatter_icon_hbo.png`
  - `formatter_icon_max.png`
  - `formatter_icon_prime.png`
  - `formatter_icon_appletv.png`
  - `formatter_icon_paramount.png`
  - `formatter_icon_peacock.png`
  - `formatter_icon_crunchyroll.png`

Suggested conversion commands:

```bash
mkdir -p /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons
mkdir -p /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi

magick /Users/jneerdael/Scripts/nexio/nexio-web/netflix.ico -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/netflix.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/disneyplus.png -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/disneyplus.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/hbo.ico -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/hbo.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/hbo.ico -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/max.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/prime.png -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/prime.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/appletv.png -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/appletv.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/paramount.ico -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/paramount.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/peacock.ico -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/peacock.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/crunchyroll.png -background none -resize 64x64 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/crunchyroll.png
```

Then copy those normalized PNGs into Android drawables:

```bash
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/netflix.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_netflix.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/disneyplus.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_disneyplus.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/hbo.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_hbo.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/max.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_max.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/prime.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_prime.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/appletv.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_appletv.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/paramount.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_paramount.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/peacock.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_peacock.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/crunchyroll.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_crunchyroll.png
```

---

### Task 1: Lock The Token Contract In Tests First

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs`

- [ ] **Step 1: Add a failing Android formatter expectation for tokenized network output**

Update the built-in universal template assertions to expect tokenized text such as:

```kotlin
assertEquals(
    """
    🗣️ 🇬🇧 🇮🇹 • [[icon:netflix]] Netflix • 👤 GROUP
    """.trimIndent(),
    someRenderedLine
)
```

- [ ] **Step 2: Add a failing Android token registry test**

Create a token parsing/fallback test like:

```kotlin
@Test
fun `token parser resolves known token and keeps fallback text`() {
    val segments = InlineIconTokenRegistry.tokenize("[[icon:netflix]] Netflix")
    assertEquals(2, segments.size)
}
```

- [ ] **Step 3: Add a failing web token renderer test**

Create a Node/Vue-safe parsing test like:

```js
assert.deepStrictEqual(parseFormatterRichText('[[icon:netflix]] Netflix')[0], {
  type: 'icon',
  token: 'netflix'
})
```

- [ ] **Step 4: Run the focused tests to confirm they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioTemplateFormatterTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.ui.components.InlineIconTokenRegistryTest
cd /Users/jneerdael/Scripts/nexio/nexio-web && node --test tests/formatter-rich-text.test.mjs
```

Expected:
- Android tests fail because tokens are not yet in the template and registry does not exist
- web test fails because parser/renderer does not exist yet

- [ ] **Step 5: Commit the red tests**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt \
        /Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs
git commit -m "test: add inline formatter icon token expectations"
```

### Task 2: Add The Shared Web/Android Icon Assets

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/netflix.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/disneyplus.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/hbo.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/max.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/prime.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/appletv.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/paramount.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/peacock.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/crunchyroll.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_netflix.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_disneyplus.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_hbo.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_max.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_prime.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_appletv.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_paramount.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_peacock.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_crunchyroll.png`

- [ ] **Step 1: Normalize the source assets into consistent PNGs**

Run the `magick` commands listed in the asset preparation section.

- [ ] **Step 2: Copy the normalized PNGs into Android drawables**

Run the `cp` commands listed in the asset preparation section.

- [ ] **Step 3: Verify the generated assets exist**

Run:

```bash
ls -1 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons
ls -1 /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi | rg '^formatter_icon_'
```

Expected:
- all nine normalized web assets exist
- all nine Android drawable assets exist

- [ ] **Step 4: Commit the assets**

```bash
git add /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons \
        /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi
git commit -m "chore: add formatter inline icon assets"
```

### Task 3: Build The Android Token Registry And Inline Renderer

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`

- [ ] **Step 1: Implement the Android token registry**

Add a focused registry with entries like:

```kotlin
data class InlineIconToken(
    val id: String,
    @DrawableRes val drawableRes: Int,
    val fallbackLabel: String,
)
```

and a tokenizer for `[[icon:token]]`.

- [ ] **Step 2: Run the registry test and confirm it still fails for rendering**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.ui.components.InlineIconTokenRegistryTest
```

Expected:
- parser test passes or partially passes
- UI renderer behavior is still missing

- [ ] **Step 3: Implement `InlineIconText` using `AnnotatedString` plus `InlineTextContent`**

The component should:
- parse tokenized text into segments
- render icons at roughly `1em`
- vertically align icons to surrounding text
- strip unknown tokens and show only fallback text

Sketch:

```kotlin
InlineIconText(
    text = detail,
    style = MaterialTheme.typography.bodySmall,
    color = NexioTheme.extendedColors.textSecondary,
)
```

- [ ] **Step 4: Replace plain detail-line `Text(...)` with `InlineIconText(...)`**

Update both:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt`

- [ ] **Step 5: Run Android compile and focused tests**

Run:

```bash
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.ui.components.InlineIconTokenRegistryTest
```

Expected:
- compile succeeds
- registry/token tests pass

- [ ] **Step 6: Commit the Android renderer**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt \
        /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt \
        /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt \
        /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt
git commit -m "feat: render formatter icon tokens on android"
```

### Task 4: Build The Web Token Registry And Rich Text Preview Renderer

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-icon-tokens.ts`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/formatter/FormatterRichText.vue`
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/FormatterWorkspace.vue`
- Test: `/Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs`

- [ ] **Step 1: Implement the web token registry and parser**

Export:

```ts
export type FormatterRichSegment =
  | { type: 'text'; value: string }
  | { type: 'icon'; token: string; src: string; label: string }
```

and a parser for `[[icon:token]]`.

- [ ] **Step 2: Run the web parsing test and confirm parser coverage**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && node --test tests/formatter-rich-text.test.mjs
```

Expected:
- parsing/token fallback tests pass

- [ ] **Step 3: Implement `FormatterRichText.vue`**

The component should:
- accept a raw formatter line
- split into segments
- render inline icons with `<img>`
- size icons to `1em`
- use `align-middle`/baseline-friendly classes
- preserve wrapping and spacing

- [ ] **Step 4: Replace plain preview description text with rich token rendering**

In `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/FormatterWorkspace.vue`:
- render title as text
- render description line-by-line with `FormatterRichText`
- do not use `v-html`

- [ ] **Step 5: Run the web build**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npm run build
```

Expected:
- production build succeeds

- [ ] **Step 6: Commit the web renderer**

```bash
git add /Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-icon-tokens.ts \
        /Users/jneerdael/Scripts/nexio/nexio-web/components/portal/formatter/FormatterRichText.vue \
        /Users/jneerdael/Scripts/nexio/nexio-web/components/portal/FormatterWorkspace.vue \
        /Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs
git commit -m "feat: render formatter icon tokens on web"
```

### Task 5: Update The Built-In Universal Template To Emit Tokens

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-templates.ts`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`

- [ ] **Step 1: Update the Android built-in template**

Change the network markers from emoji labels to tokenized labels, for example:

```txt
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}
{stream.filename::~DSNP["[[icon:disneyplus]] Disney+"||""]}
{stream.filename::~HMAX["[[icon:hbo]] HBO Max"||""]}
{stream.filename::~.MAX.["[[icon:max]] Max"||""]}
{stream.filename::~AMZN["[[icon:prime]] Amazon"||""]}
{stream.filename::~APTV["[[icon:appletv]] Apple TV+"||""]}
{stream.filename::~PMTP["[[icon:paramount]] Paramount+"||""]}
{stream.filename::~PCOK["[[icon:peacock]] Peacock"||""]}
{stream.filename::~CRTC["[[icon:crunchyroll]] Crunchyroll"||""]}
{stream.filename::~CR.["[[icon:crunchyroll]] Crunchyroll"||""]}
```

- [ ] **Step 2: Update the web built-in template with the same tokenized values**

Make `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-templates.ts` byte-for-byte aligned in meaning with the Android template.

- [ ] **Step 3: Keep the conditional separator logic intact**

Retain the existing separator condition:

```txt
{stream.filename::~NF::or::...::and::stream.releaseGroup::exists[" • "||""]}
```

- [ ] **Step 4: Run the focused Android formatter tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioTemplateFormatterTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest
```

Expected:
- formatter tests pass with tokenized output

- [ ] **Step 5: Run the web build one more time**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npm run build
```

Expected:
- build succeeds with the new tokenized template strings

- [ ] **Step 6: Commit the built-in template updates**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt \
        /Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-templates.ts \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt
git commit -m "feat: use inline icon tokens in universal formatter"
```

### Task 6: Final Verification And Cleanup

**Files:**
- Review only

- [ ] **Step 1: Run the Android verification pass**

Run:

```bash
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioTemplateFormatterTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.ui.components.InlineIconTokenRegistryTest
```

Expected:
- compile succeeds
- focused tests pass

- [ ] **Step 2: Run the web verification pass**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && node --test tests/formatter-rich-text.test.mjs
cd /Users/jneerdael/Scripts/nexio/nexio-web && npm run build
```

Expected:
- parser/render tests pass
- production build succeeds

- [ ] **Step 3: Manually sanity-check icon sizing**

Validate that:
- Android detail-line icons are roughly the same visual height as surrounding text
- web preview icons align to the text baseline and do not distort line spacing
- fallback text still reads correctly if an icon token is missing

- [ ] **Step 4: Create the final integration commit**

```bash
git add /Users/jneerdael/Scripts/nexio
git commit -m "feat: add inline network icons to stream formatter output"
```

