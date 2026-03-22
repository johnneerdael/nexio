# Inline Icon Tokens For Stream Formatting Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a shared text-first inline icon token system for stream formatter output, render those tokens as correctly sized inline platform and resolution icons on Android and web, and update the built-in `universal` formatter templates so both title and description can use tokenized icons with plain-text fallbacks.

**Architecture:** Keep the formatter engine text-only and introduce a renderer-layer token contract that survives the parser/formatter unchanged. Android and web each get a small icon registry plus rich-text renderer that converts known tokens into inline images, sizes them from the active text style, and falls back to plain text labels when token rendering is unavailable.

**Tech Stack:** Kotlin, Jetpack Compose, Nuxt/Vue 3, TypeScript, AIO-style formatter templates, ImageMagick (`magick`) for asset conversion.

---

## File Structure

**Android formatter/text pipeline**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`
  - Update the built-in `universal` template to emit icon tokens in both title and description.
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt`
  - Single source of truth for token ids, fallback labels, drawable resource ids, and token metadata like preferred scale class.
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt`
  - Compose renderer that tokenizes a string and renders inline images with `InlineTextContent`, sizing icons from the current text style.
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt`
  - Replace title/detail `Text(...)` rendering with `InlineIconText(...)` where formatter output can contain tokens.
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt`
  - Replace title/detail `Text(...)` rendering with `InlineIconText(...)` where formatter output can contain tokens.
- Create assets: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_*.png`
  - Android-ready copies of the supplied web icon assets, including title resolution icons.

**Web formatter preview/rendering**
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-templates.ts`
  - Update built-in `universal` template to use icon tokens in both title and description.
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-icon-tokens.ts`
  - Shared token registry for web: token id, public asset path, fallback label, and scale class.
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/formatter/FormatterRichText.vue`
  - Token-aware renderer that outputs inline images and text segments and sizes icons from the current text class.
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/FormatterWorkspace.vue`
  - Use `FormatterRichText` inside the live preview instead of plain text for both title and detail lines.
- Create public assets: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/*`
  - Stable public paths for network and resolution icons used by the preview and future formatter surfaces.

**Tests**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`
  - Assert the built-in template emits the new title and description icon tokens.
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
  - Assert rendered title/detail lines contain the new tokenized output where expected.
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`
  - Lock token parsing, fallback, and scale metadata behavior on Android.
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs`
  - Lock token parsing/fallback and icon sizing classes on web.

## Shared Token Contract

Use a formatter-safe literal token syntax that does **not** collide with `{section.field}` placeholders:

**Network/service tokens**
- `[[icon:netflix]]`
- `[[icon:disneyplus]]`
- `[[icon:hbo]]`
- `[[icon:max]]`
- `[[icon:prime]]`
- `[[icon:appletv]]`
- `[[icon:paramount]]`
- `[[icon:peacock]]`
- `[[icon:crunchyroll]]`

**Resolution/title tokens**
- `[[icon:4k]]`
- `[[icon:2k]]`
- `[[icon:fullhd]]`
- `[[icon:hd]]`
- `[[icon:sd]]`

Templates should emit the token immediately followed by the plain text label when the label matters, or emit the token alone when the icon fully replaces text in the title.

Examples:

```txt
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}
```

```txt
{stream.resolution::exists["{stream.resolution::replace('2160p','[[icon:4k]]')::replace('1440p','[[icon:2k]]')::replace('1080p','[[icon:fullhd]]')::replace('720p','[[icon:hd]]')::replace('576p','[[icon:sd]]')::replace('480p','[[icon:sd]]')}"||""]}{stream.resolution::exists::and::stream.title::exists[" • "||""]}{stream.title::exists["{stream.title::title::truncate(30)}"||"?"]}
```

That guarantees:
- supported renderers show image + optional label
- unsupported renderers still show sensible plain text after token stripping/fallback mapping
- formatter output stays text-first and portable

**Default `universal` title mapping requirement**
- `2160p` -> `[[icon:4k]]`
- `1440p` -> `[[icon:2k]]`
- `1080p` -> `[[icon:fullhd]]`
- `720p` -> `[[icon:hd]]`
- `576p` -> `[[icon:sd]]`
- `480p` -> `[[icon:sd]]`
- The plan implementation must update both Android and web built-in `universal` templates from the current star-rating title prefix to this icon-based mapping.

## Icon Sizing Rules

The icon renderer must size icons from the active text style rather than using a fixed pixel size.

**Cross-platform requirements**
- Detail-line icons should render at approximately `1.0em` of the surrounding text height.
- Title icons should render slightly larger than detail icons by following the actual title text style, with a small multiplier for token classes marked `title-resolution`.
- Baseline alignment must keep icons visually centered with adjacent text instead of sitting low like badges.
- The registry must support a token metadata field such as `scaleClass = INLINE | TITLE_PROMINENT` so title resolution icons can intentionally render a bit larger without special-casing token ids in UI code.

**Android-specific rules**
- `InlineIconText` should accept the same `TextStyle` used by the caller and derive placeholder `em` dimensions from `fontSize`.
- For `TITLE_PROMINENT` tokens, scale to roughly `1.1em`-`1.15em` of the active title text size.
- For normal inline tokens, scale to roughly `0.95em`-`1.0em`.

**Web-specific rules**
- `FormatterRichText` should accept a visual variant prop such as `title` or `detail` and map it to CSS variables.
- Use `height: 1em` for detail icons and `height: 1.1em`-`1.15em` for title-resolution icons.
- Width should be `auto` so the supplied badge icons keep correct aspect ratio.

## Asset Preparation Rules

- Convert supplied `ico` files into clean PNGs before adding Android drawables.
- Add the new resolution badge assets to the same registry and normalization flow.
- Preserve transparent backgrounds.
- Keep width auto at render time so rectangular title badges are not distorted.
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
  - `formatter_icon_4k.png`
  - `formatter_icon_2k.png`
  - `formatter_icon_fullhd.png`
  - `formatter_icon_hd.png`
  - `formatter_icon_sd.png`

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
magick /Users/jneerdael/Scripts/nexio/nexio-web/4k.webp -background none /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/4k.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/2k.webp -background none /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/2k.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/fullhd.webp -background none /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/fullhd.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/hd.webp -background none /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/hd.png
magick /Users/jneerdael/Scripts/nexio/nexio-web/sd.webp -background none /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/sd.png
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
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/4k.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_4k.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/2k.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_2k.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/fullhd.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_fullhd.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/hd.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_hd.png
cp /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/sd.png /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_sd.png
```

---

### Task 1: Lock The Expanded Token Contract In Tests First

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs`

- [ ] **Step 1: Add a failing Android formatter expectation for icon-mapped title and network output**

Update the built-in universal template assertions to expect the star-rating title prefix to be replaced by the new resolution badge mapping, for example:

```kotlin
assertTrue(titleLine.contains("[[icon:4k]]"))
assertFalse(titleLine.contains("⭐⭐⭐⭐⭐ 4K"))
assertTrue(detailLine.contains("[[icon:netflix]] Netflix"))
```

- [ ] **Step 2: Add a failing Android token registry test**

Create a token parsing/fallback/scale test like:

```kotlin
@Test
fun `token registry marks title badges as prominent`() {
    val token = InlineIconTokenRegistry.resolve("4k")
    assertEquals(ScaleClass.TITLE_PROMINENT, token?.scaleClass)
}
```

- [ ] **Step 3: Add a failing web token renderer test**

Create a Node/Vue-safe parsing test like:

```js
assert.equal(parseFormatterRichText('[[icon:4k]]', 'title')[0].scaleClass, 'title-prominent')
```

- [ ] **Step 4: Run the focused tests to confirm they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioTemplateFormatterTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.ui.components.InlineIconTokenRegistryTest
cd /Users/jneerdael/Scripts/nexio/nexio-web && node --test tests/formatter-rich-text.test.mjs
```

Expected:
- Android tests fail because title/detail tokens and scale metadata are not yet implemented
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
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/4k.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/2k.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/fullhd.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/hd.png`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons/sd.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_netflix.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_disneyplus.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_hbo.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_max.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_prime.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_appletv.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_paramount.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_peacock.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_crunchyroll.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_4k.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_2k.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_fullhd.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_hd.png`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi/formatter_icon_sd.png`

- [x] **Step 1: Normalize the source assets into consistent PNGs**

Run the `magick` commands listed in the asset preparation section.

- [x] **Step 2: Copy the normalized PNGs into Android drawables**

Run the `cp` commands listed in the asset preparation section.

- [x] **Step 3: Verify the generated assets exist**

Run:

```bash
ls -1 /Users/jneerdael/Scripts/nexio/nexio-web/public/formatter-icons
ls -1 /Users/jneerdael/Scripts/nexio/app/src/main/res/drawable-nodpi | rg '^formatter_icon_'
```

Expected:
- all fourteen normalized web assets exist
- all fourteen Android drawable assets exist

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

- [x] **Step 1: Implement the Android token registry**

Add a focused registry with entries like:

```kotlin
enum class ScaleClass { INLINE, TITLE_PROMINENT }

data class InlineIconToken(
    val id: String,
    @DrawableRes val drawableRes: Int,
    val fallbackLabel: String,
    val scaleClass: ScaleClass
)
```

Include all network tokens plus all five resolution tokens.

- [x] **Step 2: Implement Android tokenization and fallback behavior**

Add a parser that turns:

```txt
[[icon:4k]]
```

into an icon segment and strips unknown tokens to fallback plain text.

- [x] **Step 3: Implement Compose rich text rendering with size-aware icons**

Build `InlineIconText` so it:
- accepts `text`, `style`, and `maxLines`
- derives placeholder size from `style.fontSize`
- uses `1.1em`-ish scaling for `TITLE_PROMINENT`
- uses `1.0em`-ish scaling for normal inline tokens
- keeps width proportional to the asset aspect ratio

- [x] **Step 4: Use `InlineIconText` for formatter-driven title and detail text**

Update both stream card implementations so formatter-produced title and detail lines render through the new component.

- [x] **Step 5: Run focused Android tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioTemplateFormatterTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.ui.components.InlineIconTokenRegistryTest
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected:
- tests pass
- Kotlin compile succeeds

- [ ] **Step 6: Commit the Android renderer work**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt \
        /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt \
        /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt \
        /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt \
        /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt \
        /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt
git commit -m "feat: render formatter icon tokens on android"
```

### Task 4: Build The Web Token Registry And Rich Text Renderer

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-icon-tokens.ts`
- Create: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/formatter/FormatterRichText.vue`
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/components/portal/FormatterWorkspace.vue`
- Modify: `/Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-templates.ts`
- Test: `/Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs`

- [x] **Step 1: Implement the shared web token registry**

Expose entries like:

```ts
export const FORMATTER_ICON_TOKENS = {
  '4k': { src: '/formatter-icons/4k.png', fallbackLabel: '4K', scaleClass: 'title-prominent' },
  netflix: { src: '/formatter-icons/netflix.png', fallbackLabel: 'Netflix', scaleClass: 'inline' }
}
```

- [x] **Step 2: Implement token parsing helpers for web rich text**

Split strings into text and icon segments, preserving fallback labels when needed.

- [x] **Step 3: Implement `FormatterRichText.vue` with size-aware icon rendering**

Render icons with:
- detail variant using `height: 1em`
- title variant using `height: 1.1em`-`1.15em` for `title-prominent` tokens
- `width: auto`
- baseline-friendly vertical alignment

- [x] **Step 4: Use `FormatterRichText` for preview title and detail text**

Replace plain string rendering in the formatter preview.

- [x] **Step 5: Update built-in universal web template to use icon tokens**

Add resolution tokens in the title and network tokens in the description.

- [ ] **Step 6: Run focused web tests**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web
node --test tests/formatter-rich-text.test.mjs
./node_modules/.bin/vue-tsc --noEmit -p tsconfig.formatter-preview.json
```

Expected:
- parser/render tests pass
- focused web typecheck passes

- [ ] **Step 7: Commit the web renderer work**

```bash
git add /Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-icon-tokens.ts \
        /Users/jneerdael/Scripts/nexio/nexio-web/components/portal/formatter/FormatterRichText.vue \
        /Users/jneerdael/Scripts/nexio/nexio-web/components/portal/FormatterWorkspace.vue \
        /Users/jneerdael/Scripts/nexio/nexio-web/utils/formatter-templates.ts \
        /Users/jneerdael/Scripts/nexio/nexio-web/tests/formatter-rich-text.test.mjs
git commit -m "feat: render formatter icon tokens on web"
```

### Task 5: Verify End-To-End Output And Document Fallback Behavior

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/docs/superpowers/plans/2026-03-22-inline-icon-tokens-for-stream-formatting.md`
  - Mark completion notes if you maintain execution notes inline.
- Optional docs note: `/Users/jneerdael/Scripts/nexio/nexio-web/DESIGN.md`
  - Add a short formatter token note only if this file already documents preview behavior.

- [x] **Step 1: Run Android verification**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioTemplateFormatterTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.ui.components.InlineIconTokenRegistryTest
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected:
- all targeted Android tests pass
- app compiles

- [ ] **Step 2: Run web verification**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web
node --test tests/formatter-rich-text.test.mjs
./node_modules/.bin/vue-tsc --noEmit -p tsconfig.formatter-preview.json
npm run build
```

Expected:
- tests pass
- focused typecheck passes
- Nuxt production build succeeds

- [ ] **Step 3: Manually inspect fallback behavior**

Confirm that:
- title badges scale with the title text and look slightly larger than detail icons
- detail icons align with the text baseline
- unknown tokens degrade to plain text labels, not broken image markup
- the same formatter string remains readable on surfaces that do not render icons

- [ ] **Step 4: Commit final polish if needed**

```bash
git add /Users/jneerdael/Scripts/nexio
git commit -m "chore: finalize formatter inline icon token support"
```
