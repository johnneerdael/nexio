# Main Port Decisions

## Rule

Do not cherry-pick code when the main implementation uses a path that this branch replaced. Port the behavior into the shared architecture boundary.

## Domain Mapping

| Main Area | Target On This Branch |
| --- | --- |
| Provider HTTP calls | IntegrationRuntime-backed provider |
| Provider identity lookup | StableIdBundleResolver and IdMappingStore |
| Detail canonical fallback | MetadataRouterFacade, ProviderPlanExecutor, ProviderPlanRunner, FieldResolver |
| Continue Watching source context | ContinueWatchingSnapshotService and route builders |
| Deterministic autoplay filters | Stream presentation/parsing/scoring path |
| Playback proxy recovery | Shared player/proxy recovery components |
| TVDB localization | TVDB runtime provider and metadata adapter |
| Modern Home refresh | CatalogRailRepository, first-paint preview stream, HydratedHomeOverlayStore |

## Architecture Guardrails

- No direct provider bypasses.
- No parallel metadata lifecycle.
- No provider-specific renderer or hydrator.
- Provider HTTP/data loading -> IntegrationRuntime-backed provider/adapters.
- Provider identity -> StableIdBundleResolver/IdMappingStore.
- Detail hydration -> MetadataRouterFacade/ProviderPlanExecutor/ProviderPlanRunner/FieldResolver.
- Home rail/display updates -> CatalogRailRepository/FirstPaintPreview/HydratedHomeOverlayStore/HomeHydrationCoordinator.
- Playback/autoplay -> StreamPresentationEngine/autoplay selector/player proxy recovery components.

## First Milestone

1. Continue Watching context parity.
2. Playback/autoplay parity.
3. Device proof for Survivor S05E10.

This order is mandatory because autoplay diagnostics are only meaningful after route context and stable IDs are correct.
