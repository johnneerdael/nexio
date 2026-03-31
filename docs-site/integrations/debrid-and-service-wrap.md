# Debrid and Service Wrap

Service Wrap is Nexio's account-level playback layer for supported debrid providers. In plain language: you connect your provider once in Nexio, and supported addons can use that shared connection without you pasting provider secrets into every addon.

## Supported providers

- Real-Debrid
- Premiumize
- TorBox
- EasyDebrid

## Why Service Wrap matters

- It keeps provider secrets inside Nexio's integration settings instead of spreading them across addons.
- It lets you install supported addons without entering the same provider credentials again and again.
- It gives Nexio a central place to resolve cached playback links before playback starts.

## Where setup happens

- In the TV app, open `Settings > Integration > Debrid`.
- In the website, the [Portal Integrations bridge page](/web/admin-workspaces/integrations) still points to the account snapshot and saved-secret controls.

## Recommended defaults

- Turn on the provider you actually use.
- Leave the others off unless you have active accounts with them.
- Keep Service Wrap enabled so compatible addons can resolve cached streams automatically.
- For most users, one provider is enough: Real-Debrid if that is your account, or Premiumize, TorBox, or EasyDebrid if that is the one you pay for.

## What to expect in Nexio

- Supported addons can keep their normal stream discovery flow while Nexio handles the provider-backed resolution behind the scenes.
- When a cached result is available, Nexio can turn it into a direct playback link.
- Debrid-backed items can also surface in Library when the provider supports library integration.

## If this is not working

- Confirm the provider you actually use is enabled before you troubleshoot addon-specific results.
- Recheck the saved provider credential or token if resolution suddenly stops working.
- If you expect browsable provider tabs, move to [Library Integration](/integrations/library-integration).
- If playback still fails after the provider is connected, move to [Troubleshooting](/troubleshooting/).

## Related guides

- [Recommended Setup](/start-here/recommended-setup)
- [Library Integration](/integrations/library-integration)
- [Playback](/playback/)
- [Troubleshooting](/troubleshooting/)
