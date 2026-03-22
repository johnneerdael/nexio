# Formatter Getting Started

This guide shows how to shape Nexio stream cards for Android TV and the live web preview.

Nexio’s formatter follows the AIO-style syntax popularized by Viren070’s AIOStreams work, so the same mental model carries across built-in presets and custom templates.

## What the formatter controls
- The bold title line for each stream card.
- The supporting description lines underneath it.
- The live preview in the web portal.
- The uniform stream formatting experience on TV.

When uniform formatting is enabled, the selected formatter becomes the account’s visual standard for streams. If you also group streams across addons, the formatter still defines how each visible stream is presented. If you enable deduplication, the goal is to hide repeated variants, not to change the addon inventory itself.

## The best way to think about it
1. Start with a built-in formatter if you want a proven layout.
2. Switch to Custom if you need your own wording or line structure.
3. Preview with realistic stream data before you save.
4. Save the template only after it looks good on the kinds of streams you actually watch.

## Your first useful template
A safe starting point is to show the parsed title, then add the year only when it exists.

```text
{stream.title::exists["{stream.title::title}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}
```

That pattern keeps optional data from producing messy output.

## Build in passes
The cleanest formatter edits usually happen in this order:
1. Core identity: title, year, season, episode.
2. Technical detail: resolution, quality, audio, size, runtime.
3. Provider context: addon name, service name, cached status.
4. Extra signal: languages, release group, SeaDex, regex matches, special badges.

If you try to write everything in one shot, it is harder to tell what caused a bad result.

## The Universal formatter
Universal is the best default choice when you want one consistent, cross-addon look.

It is designed to:
- keep the title line compact
- surface useful technical detail without overloading the card
- treat SeaDex and regex matching as helpful signals
- behave well when addon results overlap

In practice, Universal is a strong starting point for people who want the clearest stream list without micromanaging every provider-specific style.

## When to use deduplication
Use deduplication when grouped streams create repeated variants of the same release.

It is most useful when:
- several addons expose the same source
- you want a shorter, cleaner list
- you care more about the best match than every duplicate source entry

Leave it off when:
- you want to compare every provider result
- you are still debugging an addon
- you need to see all available variants before choosing

## Inline icons and badges
Nexio can render inline icon tokens such as `[[icon:4k]]`, `[[icon:ddp]]`, and `[[icon:netflix]]`. They are a good way to keep stream cards readable without losing meaning.

Use them for:
- resolution badges
- audio and HDR badges
- service or network markers
- special release labels such as SeaDex or repack hints

## Custom formatter workflow
1. Select `Custom` from the formatter list.
2. Edit the name and description templates.
3. Use snippets when you need a shortcut for common expressions.
4. Preview the result against one of the sample streams.
5. Adjust again until the card reads naturally.
6. Click `Apply Changes` when you are happy.

The custom formatter editor also supports import and export, which is useful when you want to move a template between accounts.

## Practical preview tips
- Try a movie, a show, and an anime-style sample before you save.
- Toggle cached, library, private, and proxied flags to see what the card does in edge cases.
- Open the advanced preview controls when you want to check scores or SeaDex-related output.

## A simple rule of thumb
- If the formatter is making cards harder to scan, shorten it.
- If it is too bare, add one signal at a time until the card is useful again.
- If two fields tell the same story, keep the clearer one.

## Next page
Use [Formatter Reference](./formatter.md) when you want the full variable and modifier list.
