# Phase 10 Bound Files

> Phase 10 implementation is bound to these exact Phase 6-9 TVDB source files.
> Every row was verified by file existence and symbol grep before Phase 10 tasks proceed.

| Role | Expected file | Actual file | Required symbol | Bound |
|------|--------------|-------------|-----------------|-------|
| settings_store | `TvdbSettingsDataStore.kt` | `app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt` | `class TvdbSettingsDataStore` | yes |
| token_store | `TvdbTokenStore.kt` | `app/src/main/java/com/nexio/tv/data/local/TvdbTokenStore.kt` | `class TvdbTokenStore` | yes |
| api | `TvdbApi.kt` | `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt` | `interface TvdbApi` | yes |
| auth_service | `TvdbAuthService.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt` | `class TvdbAuthService` | yes |
| identity_service | `TvdbIdentityService.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt` | `class TvdbIdentityService` | yes |
| metadata_models | `TvMetadataModels.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt` | `data class TvMetadataEnrichment` | yes |
| metadata_diagnostics | `TvMetadataDiagnostics.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt` | `enum class TvMetadataDecisionReason` | yes |
| metadata_service | `TvdbMetadataService.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` | `class TvdbMetadataService` | yes |
| metadata_router | `TvMetadataRouter.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` | `class TvMetadataRouter` | yes |
| air_availability | `TvdbAirAvailability.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt` | `enum class TvdbAirAvailabilityDiagnosticReason` | yes |
| air_calculator | `TvdbAirAvailabilityCalculator.kt` | `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt` | `class TvdbAirAvailabilityCalculator` | yes |
| settings_view_model | `TvdbSettingsViewModel.kt` | `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt` | `class TvdbSettingsViewModel` | yes |
| settings_screen | `TvdbSettingsScreen.kt` | `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt` | `fun TvdbSettingsScreen` | yes |

---

*Generated: 2026-04-15*
*Phase: 10-tvdb-reliability-updates-and-diagnostics*
*Plan: 10-00 Task 1*
