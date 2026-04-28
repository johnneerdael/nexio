## 1. Foundation
- [ ] 1.1 Add `ResolvedField.ORGANIZATION_LIST`
- [ ] 1.2 Add `TmdbApiShapes.SEARCH_PEOPLE`, `SEARCH_COMPANIES`, `PERSON_FIND_BY_NAME`, `COMPANY_FIND_BY_NAME`

## 2. F-B-03 — DETAIL_CORE TMDB enrichment via facade
- [ ] 2.1 Add `MetadataRouterFacade.resolveRequest(...)` DETAIL_CORE coverage assertion
- [ ] 2.2 Migrate `MetaDetailsViewModel.kt:1391,1406,1414-1419` to facade

## 3. F-C-01 — TMDB person/company through IntegrationRuntime
- [ ] 3.1 Wrap `loadPersonDetails`, `loadPersonCombinedCredits` in `runtime.call`
- [ ] 3.2 Wrap `searchPeople`, `searchCompanies` in `runtime.call`

## 4. F-04-01 + F-04-03 — DETAIL_MEDIA + Trailer via facade
- [ ] 4.1 `TrailerResolver` interface + `TmdbTrailerMetadataAdapter` + `TvdbTrailerMetadataAdapter`
- [ ] 4.2 `ResolverOrchestrator` schedules TRAILERS at DETAIL_MEDIA
- [ ] 4.3 Migrate `MetaDetailsViewModel.fetchTrailerUrl` (`:2660-2700`) to facade

## 5. F-04-04 — strike ARTWORK from DETAIL_MEDIA
- [ ] 5.1 ResolverOrchestrator: ARTWORK only at DETAIL_CORE; document collapse

## 6. F-05-02 — ReviewResolver
- [ ] 6.1 `ReviewResolver` + TMDB + Trakt adapters
- [ ] 6.2 Migrate `MetaDetailsViewModel.kt:1074,1119`

## 7. F-05-03 — RecommendationResolver
- [ ] 7.1 `RecommendationResolver` + TMDB adapter
- [ ] 7.2 Migrate `MetaDetailsViewModel.kt:875`

## 8. F-05-04 + F-05-01 — OrganizationPersonResolver + DETAIL_SECONDARY
- [ ] 8.1 `OrganizationPersonResolver` + TMDB adapter
- [ ] 8.2 Migrate `MetaDetailsViewModel.kt:1505,1620,1625`
- [ ] 8.3 Migrate `CastDetailViewModel.kt:45-47`
- [ ] 8.4 Wire DETAIL_SECONDARY dispatch in facade

## 9. F-12-01 + F-12-02 — Skip-segment cleanup
- [ ] 9.1 Remove `ResolverType.SKIP_SEGMENTS` and `ResolvedField.SKIP_SEGMENTS`
- [ ] 9.2 Document `SkipIntroRepository` as canonical surface; add architecture pin

## 10. F-B-04 — ResolverOrchestrator dispatch wiring
- [ ] 10.1 Facade dispatches `resolverSchedule.networkResolvers` and attaches results
- [ ] 10.2 Validator rule `ScheduledResolversAreDispatched`

## 11. F-J-01 + F-03-03 — Architecture pins + spec
- [ ] 11.1 Tighten `MetadataRouterBoundaryTest` whitelist
- [ ] 11.2 Document Stremio-as-primary-detail-source in spec

## 12. Sign-off
- [ ] 12.1 Re-run audits; update `SIGN-OFF.md`
