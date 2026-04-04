# Change: Fix startup language selection for audio and subtitles

Nexio's startup language-selection behavior is currently too weak in two important cases:

- preferred audio language can lose to a non-preferred default track in MULTI-language sources
- startup subtitle selection can prefer an embedded secondary-language track even when an addon subtitle exists in the user's primary subtitle language

This change tightens startup selection so language preferences behave more like users expect, and adds an original-language audio preference mode plus a Gemini-backed subtitle fallback path.

## What changes

- Add an **Original language** audio preference option.
- During startup, correct audio-track selection toward the preferred language when container/default ordering is wrong.
- During startup, prefer downloaded/addon subtitles in the primary subtitle language over embedded subtitles in only the secondary language.
- If neither embedded nor downloaded subtitles contain the primary language, choose the best available secondary-language subtitle and auto-enable Gemini translation to the primary subtitle language when Gemini is configured.
- If Gemini fallback translation fails, keep the selected secondary-language subtitle active.

## Why

- Users expect preferred audio language to win over default track ordering.
- Users expect a downloaded subtitle in their preferred language to beat an embedded fallback in a less-preferred language.
- Gemini subtitle translation is most useful as a startup fallback when the preferred subtitle language does not exist.

## Scope

- initial playback startup only
- internal/built-in subtitle tracks
- addon/downloaded subtitle tracks
- startup audio track selection

## Non-goals

- Rewriting all manual in-session track selection behavior
- Forcing Gemini translation outside startup fallback cases
- Guaranteeing subtitle translation for bitmap-based built-in subtitle tracks
