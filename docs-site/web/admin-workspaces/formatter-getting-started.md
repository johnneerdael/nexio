## Who this is for
Anyone who wants to customize how Nexio renders stream cards on Android TV and in the web preview.

This guide is written as a hands-on tutorial. If you already know the syntax and just want the full list of variables and modifiers, jump to the [Formatter Reference](./formatter.md).

## What the formatter controls
Nexio’s formatter has two outputs:

- `Title`: the bold first line of the stream card
- `Description`: the detail lines underneath it

When uniform formatting is enabled on TV, the selected formatter becomes the source of truth for how streams are shown. The web workspace lets you build, preview, and sync those templates before they are used on your devices.

## The two ideas to learn first

### 1. Templates are text with dynamic blocks
A formatter is plain text mixed with dynamic expressions.

```text
{stream.title} ({stream.year})
```

If the stream title is `Movie Title` and the year is `2023`, the output becomes:

```text
Movie Title (2023)
```

### 2. Most formatter power comes from three building blocks

- Variables: `{stream.title}`
- Modifiers: `{stream.title::title::truncate(30)}`
- Conditions: `{stream.year::exists[" ({stream.year})"||""]}`

If you understand those three things, you can build almost any formatter.

## Your first useful title template
Start with this:

```text
{stream.title::exists["{stream.title::title}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}
```

What it does:

- shows the parsed title if Nexio found one
- falls back to `?` if it did not
- appends the year only when it exists

This is the most important formatter habit to learn early:

- don’t print optional data directly
- wrap optional data in `::exists[...]`

## Your first useful description template
Try this next:

```text
🎥 {stream.quality::exists["{stream.quality}"||"Unknown"]} • 💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]}
```

This introduces:

- text labels and separators
- numeric conditions with `>0`
- readable byte formatting with `::bytes`

## How to think about template design
The easiest way to build a formatter is to do it in passes:

1. Add the core identity.
   Title, year, season, episode.
2. Add technical quality.
   Resolution, quality, audio, runtime, size.
3. Add service context.
   Debrid provider, addon name, cached status.
4. Add premium details.
   Languages, pack status, release group, filename, SeaDex flags.

If you try to write the full formatter in one shot, debugging becomes harder than it needs to be.

## A practical title tutorial

### Step 1: Add title and year

```text
{stream.title::exists["{stream.title::title}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}
```

### Step 2: Add season and episode only for episodic content

```text
{stream.title::exists["{stream.title::title}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}{stream.seasonEpisode::exists[" ({stream.seasonEpisode::join(' ')})"||""]}
```

### Step 3: Add a resolution badge

```text
{stream.resolution::exists["[{stream.resolution}]"||""]}{stream.resolution::exists::and::stream.title::exists[" "||""]}{stream.title::exists["{stream.title::title}"||"?"]}
```

### Step 4: Upgrade the badge to Nexio inline icons

```text
{stream.resolution::exists["{stream.resolution::replace('2160p','[[icon:4k]]')::replace('1440p','[[icon:2k]]')::replace('1080p','[[icon:fullhd]]')::replace('720p','[[icon:hd]]')::replace('576p','[[icon:sd]]')::replace('480p','[[icon:sd]]')}"||""]}{stream.resolution::exists::and::stream.title::exists["   "||""]}{stream.title::exists["{stream.title::title::truncate(30)}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}{stream.seasonEpisode::exists[" ({stream.seasonEpisode::join(' ')::replace('S','S')::replace('E','E')})"||""]}
```

That pattern is what Nexio’s built-in `Universal` formatter uses today.

## Inline icons: one of Nexio’s best features
Nexio supports inline image tokens in formatter output using this syntax:

```text
[[icon:token]]
```

These tokens are rendered as real icons on Android and in the web preview, with plain-text fallback when a token cannot be rendered.

### Common resolution tokens

- `[[icon:4k]]`
- `[[icon:2k]]`
- `[[icon:fullhd]]`
- `[[icon:hd]]`
- `[[icon:sd]]`

### Common service/network tokens

- `[[icon:netflix]]`
- `[[icon:disneyplus]]`
- `[[icon:hbo]]`
- `[[icon:max]]`
- `[[icon:prime]]`
- `[[icon:appletv]]`
- `[[icon:paramount]]`
- `[[icon:peacock]]`
- `[[icon:crunchyroll]]`

### Common audio/visual tokens

- `[[icon:atmos]]`
- `[[icon:truehd]]`
- `[[icon:ddp]]`
- `[[icon:dd]]`
- `[[icon:dts]]`
- `[[icon:dtshd]]`
- `[[icon:dtsx]]`
- `[[icon:dovi]]`
- `[[icon:hdr10]]`

### Example: service badges from filename tags

```text
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}{stream.filename::~DSNP["[[icon:disneyplus]] Disney+"||""]}{stream.filename::~AMZN["[[icon:prime]] Amazon"||""]}
```

This prints only the matching service labels.

## A practical description tutorial

### Step 1: Show quality, runtime, and size

```text
🎥 {stream.quality::exists["{stream.quality}"||"Unknown"]} • ⏱️ {stream.duration::>0["{stream.duration::time}"||"Unknown"]}
💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]}
```

### Step 2: Add audio tags

```text
🎥 {stream.quality::exists["{stream.quality}"||"Unknown"]}    {stream.audioTags::exists["{stream.audioTags::join('  ')}"||""]}   ⏱️ {stream.duration::>0["{stream.duration::time}"||"Unknown"]}
```

### Step 3: Convert audio tags into icons

```text
🎥 {stream.quality::exists["{stream.quality}"||"Unknown"]}    {stream.audioTags::exists["{stream.audioTags::join('  ')::replace('Atmos','[[icon:atmos]]')::replace('TrueHD','[[icon:truehd]]')::replace('DTS-HD MA','[[icon:dtshd]]')::replace('DTS:X','[[icon:dtsx]]')::replace('DD+','[[icon:ddp]]')::replace('DD','[[icon:dd]]')::replace('EAC3','[[icon:ddp]]')::replace('AC3','[[icon:dd]]')::replace('DTS','[[icon:dts]]')}"||""]}   ⏱️ {stream.duration::>0["{stream.duration::time}"||"Unknown"]}
```

### Step 4: Add provider and addon context

```text
💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]} • ☁️ {service.name::exists["{service.name}"||"Unknown"]} • {addon.name}
```

### Step 5: Add final polish

```text
{stream.uLanguages::exists["🗣️ {stream.uLanguageEmojis::join(' ')} • "||""]}{stream.seasonPack::istrue["📦 Pack • "||""]}{stream.releaseGroup::exists["👤 {stream.releaseGroup::truncate(10)}"||""]}
📄 {stream.filename::exists["{stream.filename}"||"—"]}
```

## How conditionals actually work
This is the most important syntax pattern in the formatter:

```text
{value::condition["if true"||"if false"]}
```

Examples:

### Show text only when a value exists

```text
{stream.releaseGroup::exists["👤 {stream.releaseGroup}"||""]}
```

### Show text only when a number is greater than zero

```text
{stream.size::>0["💾 {stream.size::bytes}"||""]}
```

### Show text only when a filename contains a marker

```text
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}
```

## Advanced logic with `and` and `or`
You can chain conditions to build smarter templates.

### Show a separator only when both values exist

```text
{stream.filename::~NF::and::stream.releaseGroup::exists[" • "||""]}
```

### Show a separator if any service marker matched

```text
{stream.filename::~NF::or::stream.filename::~DSNP::or::stream.filename::~AMZN::and::stream.releaseGroup::exists[" • "||""]}
```

This is how the built-in `Universal` template avoids printing dangling separators.

### Combine quality signals

```text
{stream.seadexBest::istrue[" 🏆 BEST"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" 🥈 ALT"||""]}
```

## Building complex conditions without getting lost
Use this workflow:

1. Write one condition.
2. Preview it.
3. Add one `::and::` or `::or::`.
4. Preview again.
5. Only then add the true/false output text around it.

That keeps complex formatter logic readable and debuggable.

## A full example: today’s style of premium Nexio formatter
This example shows the kind of structure used by Nexio’s current built-in `Universal` formatter:

```text
Title:
{stream.resolution::exists["{stream.resolution::replace('2160p','[[icon:4k]]')::replace('1440p','[[icon:2k]]')::replace('1080p','[[icon:fullhd]]')::replace('720p','[[icon:hd]]')::replace('576p','[[icon:sd]]')::replace('480p','[[icon:sd]]')}"||""]}{stream.resolution::exists::and::stream.title::exists["   "||""]}{stream.title::exists["{stream.title::title::truncate(30)}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}{stream.seasonEpisode::exists[" ({stream.seasonEpisode::join(' ')::replace('S','S')::replace('E','E')})"||""]}

Description:
🎥 {stream.quality::exists["{stream.quality::title}"||""]}    {stream.audioTags::exists["{stream.audioTags::join('  ')::replace('Atmos','[[icon:atmos]]')::replace('TrueHD','[[icon:truehd]]')}"||""]}   ⏱️ {stream.duration::>0["{stream.duration::time}"||"Unknown"]}
💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]} • ☁️ {service.name::exists["{service.name}"||"Unknown"]} • {addon.name}
```

## Common mistakes to avoid

### Printing optional data directly
Bad:

```text
({stream.year})
```

Better:

```text
{stream.year::exists[" ({stream.year})"||""]}
```

### Leaving separators outside the condition
Bad:

```text
 • {stream.releaseGroup}
```

Better:

```text
{stream.releaseGroup::exists[" • 👤 {stream.releaseGroup}"||""]}
```

### Building too much at once
If a formatter breaks, delete half of it, confirm the remaining half works, then build back up.

## How to use the preview workspace well

1. Pick a built-in formatter as your starting point.
2. Change only the title first.
3. Preview with a movie sample and an episode sample.
4. Then change the description.
5. Open the JSON/debug payload when you need to inspect exact fields.
6. Test edge cases:
   - no year
   - no audio tags
   - no release group
   - no streaming-service marker

## Where to go next

- Full variable, modifier, operator, and token list: [Formatter Reference](./formatter.md)
- Formatter workspace overview: [Formatter](./formatter.md)

