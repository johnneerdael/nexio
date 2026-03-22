# Addon Manager

Addon Manager is where you add and organize the providers that feed Nexio. Think of it as the source layer for content and stream metadata.

![Addon Manager](/images/management-portal/addon-manager.webp)
*Addon Manager combines install, ordering, parser selection, and enable or disable controls in one account-wide source workspace.*

## What it does
- Installs addons from a URL.
- Keeps the addon list ordered.
- Lets you enable or disable an addon without deleting its configuration.
- Lets you choose the parser preset used to interpret each provider’s stream labels.
- Refreshes the addon name, description, and logo from the manifest when available.

## When to use it
- When you are adding a new content provider.
- When stream labels look wrong and need a different parser preset.
- When you want to trim down noisy or unused addons.
- When you are setting up a new account and want a small, reliable baseline first.

## Recommended setup flow
1. Paste the addon URL or manifest URL.
2. Choose the parser preset that best matches the provider.
3. Install the addon and confirm it appears in the list.
4. Save and sync the account.
5. Open a known title and check whether the stream names look sensible.

## Parser presets
Parser presets help Nexio understand how a provider labels its streams.

- `Generic` is the safest starting point.
- `Torrentio`, `StremThru`, and `WebStreamr` are useful when the provider follows one of those styles more closely.

If a provider is installed but the titles, resolutions, or badges look off, adjust the preset before you assume the addon itself is broken.

## Good habits
- Add one provider at a time so you can tell what changed.
- Keep only the addons you actually trust and use.
- Reorder providers so the most useful ones stay near the top of the list.
- Use disable rather than remove when you may want the addon again later.

## What to expect after saving
- The addon stays attached to the account.
- Manifest details can update the visible label and logo.
- The same account should see the same addon setup on another device after sync.

## Troubleshooting
- If the addon installs but does not help discovery, check whether the provider actually exposes useful catalogs.
- If the label looks generic, switch parser presets and re-check a known title.
- If the addon still seems wrong, remove it and try a known-good provider before debugging deeper.

## Next page
Continue with [Catalog Inventory](./catalogs.md) to control how provider output is shown on Home.
