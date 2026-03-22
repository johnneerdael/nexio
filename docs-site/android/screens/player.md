# Playback Interface

## What the player is for
The player is where Nexio turns a chosen stream into a viewing session. It is built around TV remote use, so the most important actions are always one or two clicks away.

## What you can do here
- Play and pause playback.
- Seek forward or backward.
- Switch audio tracks and subtitle tracks.
- Open subtitle delay, speed, aspect ratio, and more-actions controls.
- Open source panels to change stream, or episode panels to move within a series.
- Skip intros when a skip interval is available.
- Jump to the next episode when Nexio is ready to auto-advance.
- Open the current stream in an external player when supported.

## Important controls and overlays
- Press OK to reveal the main controls.
- `More` contains playback speed, aspect ratio, and external-player actions.
- Audio and subtitle dialogs are separate so you can change one without disturbing the other.
- The subtitle dialog has two jobs: choose a subtitle source and adjust subtitle delay.
- Episode and source side panels are where you change streams without leaving playback.
- The pause overlay, skip-intro card, and next-episode card appear only when they are relevant.

## How Back behaves
Back is intentionally layered. It closes the topmost active surface before it leaves playback.

In practice, that means Back may close one of these first:
- Playback error dialog
- Pause overlay
- More dialog
- Subtitle delay overlay
- Sources panel
- Episodes panel
- Skip-intro card
- Next-episode card
- Controls overlay

If none of those are open, Back exits the player.

## Playback features that matter on TV
- VOD cache can keep progressive streams in a local disk cache so playback handles bandwidth swings better.
- Parallel connections can fetch progressive streams in chunks across multiple connections for faster or steadier startup.
- Subtitle handling includes language preferences, add-on subtitles, subtitle delay, subtitle style, and advanced rendering.
- AI subtitle features can appear when supported by the title and your settings.
- Frame-rate matching can adjust the display for smoother motion when you enable it in settings.

## Subtitle workflow
- Nexio uses your preferred subtitle language from [Settings](./settings.md) to order subtitle choices and to decide the target language for AI translation.
- The secondary subtitle language is a fallback for normal subtitle discovery and ordering; it does not replace the main translation target.
- In the subtitle dialog, the built-in subtitle list and add-on subtitle list are separate from the `AI subtitles` toggle.
- Normal subtitle track selection still works first: you can pick a built-in track or a downloaded add-on subtitle exactly as usual.
- Subtitle delay is applied after a track or translated subtitle is active, so it affects regular subtitles and AI-translated subtitles the same way.
- Subtitle styling is shared across the normal subtitle view and the AI subtitle overlay, so size, boldness, color, outline, and vertical offset stay consistent.
- If you switch from a regular subtitle to an AI-translated one, Nexio keeps the same style settings and just swaps the text source.

## AI subtitles and Gemini
- The `AI subtitles` chip only appears when Gemini is enabled and a Gemini API key is configured in [Settings](./settings.md).
- Before using it, set your preferred subtitle language to the language you want the translated subtitles to appear in.
- When AI subtitles are enabled and you select an add-on subtitle, Nexio translates that subtitle file with Gemini and then plays the translated result.
- When AI subtitles are enabled and you are using a built-in text subtitle track, Nexio can translate the rendered cue text in an overlay instead of replacing the original track file.
- Built-in AI translation only works for text-only cues, so image-based built-in subtitles are not a fit for that path.
- The subtitle dialog can show translation progress or errors while Gemini is working, and the selection stays on the translated subtitle once it finishes.
- If you turn AI subtitles off, Nexio returns to the last regular subtitle source when one was selected.

## How it behaves when playback changes state
- Playback pauses when the app moves to the background.
- Nexio does not force an automatic resume when you come back; you stay in control.
- Display mode changes are cleaned up when playback ends so browsing does not inherit player display state.

## Best use guidance
- Use the source panel if one stream stalls instead of repeatedly reopening the same stream.
- Use subtitle settings in [Settings](./settings.md) if you want a preferred language or stronger default styling.
- If playback feels unstable, test with a simpler stream first before changing multiple settings at once.

## Troubleshooting
- If Back does not exit immediately, something else is still open on top of the player.
- If a stream fails, the player will surface an error path instead of freezing in place.
- If subtitles or audio do not look right, switch tracks first and then check your playback settings.

## Related pages
- [Media Detail](./detail.md)
- [Settings](./settings.md)
- [Search and Cast](./search-and-cast.md)
