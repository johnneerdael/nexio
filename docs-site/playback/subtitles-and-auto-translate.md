# Subtitles and Auto-Translate

Nexio keeps the subtitle experience simple first, then adds Gemini-backed translation when you actually need it.

## Baseline recommendation

- Start with the built-in subtitle track when the title has one.
- Use addon subtitles when the built-in track is missing or poor.
- Set your primary subtitle language to the language you want to read most often.
- Leave subtitle delay at zero until you have a real sync problem.
- Keep your subtitle style consistent so translated subtitles do not feel visually different from normal ones.

## How Auto-Translate works

- `Auto-Translate` appears in the subtitle dialog after Gemini is enabled and the API key is present.
- Nexio sends the selected text subtitle to Gemini, then plays back the translated result as a normal subtitle track or overlay.
- Text-based subtitle formats such as SRT and VTT are the best fit.
- Bitmap or image-based built-in subtitles are not supported for translation.
- Translated subtitles are cached locally, so the same source and target language do not need to be translated again right away.

## Where to add the API key

- Open `Settings > Integration > Google Gemini`.
- Turn on Gemini.
- Paste your Google AI Studio API key in `AI Studio API Key`.
- Nexio stores that key through account sync, so you do not have to re-enter it on every device.

## What to expect

- Translation is not instant. Expect a short delay while Gemini processes the subtitle.
- Longer subtitle files take longer because Nexio translates them in chunks.
- Quality depends on the source subtitle quality and the language pair. It is good enough for practical viewing, but it is still machine translation.
- Because this uses an external Gemini API call, it can count against your Google AI Studio usage limits or billing plan.

If Auto-Translate fails, keep watching with the original subtitle track and try again later with a cleaner source.

## If this is not working

- Confirm Gemini is enabled and that the API key is saved before you open the subtitle dialog.
- Use a text subtitle track such as SRT or VTT. Bitmap subtitle tracks cannot be translated.
- If translation is missing only on one title, try a cleaner subtitle source first.
- If the feature should be available but never appears, move to [Troubleshooting](/troubleshooting/).

## Related guides

- [Playback](/playback/)
- [Playback Tuning](/playback/playback-tuning)
- [Troubleshooting](/troubleshooting/)
- [Advanced](/advanced/)
