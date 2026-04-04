# Universal Formatter

The Universal Formatter is the new default template for stream cards. It gives end users one consistent look across sources so the list feels like one app experience instead of a set of unrelated provider styles.

The website is where you manage it, but the payoff is in the TV app: faster scanning, cleaner comparisons, and fewer moments where a good source is skipped because it looked unfamiliar.

## What changes for end users

- Stream cards keep the same structure from one source to the next.
- The title line stays compact instead of spreading important details across different layouts.
- Technical details land in predictable places, so users learn where to look.
- Mixed provider results feel like one catalog instead of a collection of mismatched formats.

## Why consistent formatting helps source selection

When every source formats the same way, people spend less time decoding the card and more time choosing the right stream.

- Resolution, audio, and quality are easier to compare at a glance.
- Duplicate-looking results stand out sooner.
- The card layout teaches users what matters most, which reduces accidental picks.
- Cleaner scanning matters more when several addons return the same title with different metadata quality.

## Why the new template matters

The Universal template is built to be the default-friendly shape for everyday browsing.

- It keeps strong identity details near the start of the card.
- It uses visual badges when a symbol is clearer than another line of text.
- It leaves room for technical context without turning every card into a paragraph.
- It works well as the baseline before you decide whether a custom template needs to be denser or simpler.

## Custom icon capabilities

The formatter now supports inline icon tokens that can be mixed into custom templates.

- Use them for resolution badges, audio badges, HDR badges, and service logos.
- Use the same icon style for provider and debrid badges so cards stay compact.
- Keep them close to the information they describe so they read like scanable markers, not decoration.
- Combine icons and text when you want a fallback that still reads clearly on smaller screens.

Examples of the idea:

- `[[icon:4k]]` for a headline resolution badge
- `[[icon:atmos]]` for premium audio
- `[[icon:netflix]]` or `[[icon:realdebrid]]` for source or service context

## When to go further

If the default Universal layout is close but not quite right, use [Formatter Getting Started](/web/admin-workspaces/formatter-getting-started) to edit, preview, and apply the template in the portal, then keep [Formatter Reference](/web/admin-workspaces/formatter) open for the exact variable and icon syntax.

## Related guides

- [Recommended Setup](/start-here/)
- [Catalog Views and Personalization](/customize/catalog-views-and-personalization)
- [Troubleshooting](/troubleshooting/)
