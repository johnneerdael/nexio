# Search and Cast

## Purpose
Describe the Android discovery path for search and the operational expectations for cast-related handoff workflows.

## Audience
- Android users searching across configured providers
- Operators validating cross-device discovery behavior

## Prerequisites
- Configured addons and integrations
- Network access for provider queries

## Procedure and Guidance
1. Use search to find a known title and confirm result relevance.
2. Open Media Detail from search results and continue to playback selection.
3. For cast-oriented workflows, ensure account and integration state are already stable before testing handoff behavior.
4. If results are inconsistent, re-check provider and catalog configuration in web admin pages.

## Validation and Expected Outcome
- Search returns expected titles from active providers
- Result selection transitions cleanly to detail and playback
- Cross-device workflows are reproducible after configuration changes

## Related pages
- [Media Detail](./detail.md)
- [Playback Interface](./player.md)
- [Integrations](../../web/admin-workspaces/integrations.md)
