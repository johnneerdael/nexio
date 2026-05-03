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
| Auth/session/durable credentials | AuthManager, durable credential/session stores, Supabase session authority |
| Account sync/reset | AccountSettingsSyncService, local account reset coordinator, profile credential stores |
| Profile deletion/session gates | ProfileManager and profile-scoped sync/session gate checks |
| UI theme/focus/sidebar | ThemeDataStore, AppTheme, shared focusable components, MainActivity/SidebarNavigation, LayoutPreferenceDataStore |
| Settings troubleshooting toggles | Settings/troubleshooting presentation and backing preference stores |
| Benchmark/device capability | Benchmark repository, device capability models, benchmark diagnostics/reporting |
| Build branding/release metadata | Product flavor resources, build config, and release documentation only when profileable behavior depends on it |
| Multi-domain snapshots | Split by touched file; port each behavior to its own shared architecture boundary |

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
