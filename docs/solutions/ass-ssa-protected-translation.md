# ASS/SSA Protected Translation

NEXIO translates ASS/SSA subtitles with a syntax-preserving pipeline:

1. Parse event records using the active `Format:` line.
2. Tokenize only the event `Text` field.
3. Preserve ASS override blocks, line breaks, hard spaces, drawing payloads, and malformed spans verbatim.
4. Send only visible subtitle language to the provider using immutable placeholders such as `⟦ASS_000⟧` and `⟦LB_000⟧`.
5. Validate every provider response before reconstruction.
6. Reconstruct ASS/SSA text from original raw tokens.
7. Render embedded subtitles through `AssSsaRenderController` and libass.

The Media3 cue translator intentionally does not handle ASS/SSA because Media3 converts ASS into generic cue geometry and strips override blocks. That path loses semantics for tags such as `\pos`, `\move`, `\clip`, `\iclip`, `\org`, `\fade`, `\fad`, drawing mode, and karaoke timing.

Manual validation:

```bash
adb connect 192.168.50.71
adb -s 192.168.50.71:5555 logcat -c
adb -s 192.168.50.71:5555 logcat -v threadtime | grep -E 'ASS_SSA_RENDER|SubtitleTranslation|TextRenderer'
```

Expected behavior:

- ASS/SSA playback uses `ASS_SSA_RENDER` / assrender logs.
- `TextRenderer` does not log entries that contain both `streamFormat=` and `text/x-ssa` for translation failures.
- Translated ASS lines preserve original positioning and movement.
- Drawing-only events are preserved without provider calls.
