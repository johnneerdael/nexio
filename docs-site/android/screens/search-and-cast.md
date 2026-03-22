# Search and Cast

## Search
Search is the fastest way to jump to a title by name. It works with a keyboard, and voice search is available when the device supports speech recognition and the microphone permission is granted.

![Search results](/images/tv-app/search-results.webp)
*Search combines typed and voice input, then groups matching titles into browsable rows so you can move straight from query to detail page.*

### What users can do
- Type at least two characters and search across active providers.
- Use voice search from the microphone button when supported.
- Open a result directly into [Media Detail](./detail.md).
- Switch into Discover browsing when search is empty and Discover is enabled.

### How it behaves
- Search does not start meaningfully until you enter at least two characters.
- Voice search will ask for microphone permission the first time it is used.
- If voice recognition is unavailable or fails, Nexio shows a clear message instead of silently doing nothing.
- Discover appears as a browse-first experience when no search query is active, but only if it is enabled in [Settings](./settings.md).

### Where the controls live
- The text field is the main input.
- The microphone button starts voice search.
- Result rows appear below the input and can be opened directly.
- Discover filters for type, catalog, and genre appear in the browse section.

## Cast
The cast experience is split across detail pages and person pages. On a title detail page, cast members take you into a person view where you can inspect filmography and jump back into related titles.

### What users can do
- Open a cast member from [Media Detail](./detail.md).
- Read person details and browse their filmography.
- Open a title from a person’s filmography back into title detail.

### How it behaves
- The cast page shows loading, error, or person-detail content.
- The filmography row is focus-driven and works like the rest of the TV UI.
- Back returns you to the previous context.

## Best use guidance
- Use search when you know the title.
- Use Discover when you want to browse within a type, catalog, or genre.
- Use cast pages when you want to keep exploring after a title has already proven useful.

## Troubleshooting
- If search returns nothing, try different keywords or wait for the relevant provider to respond.
- If voice search is not available, use typed search instead.
- If a person page fails, retry from the cast entry and confirm the title detail page loaded correctly first.

## Related pages
- [Catalogs and Library](./catalog.md)
- [Media Detail](./detail.md)
- [Playback Interface](./player.md)
- [Settings](./settings.md)
- [Integrations](../../web/admin-workspaces/integrations.md)
