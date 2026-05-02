## 1. Spec and model
- [ ] 1.1 Add OpenSpec delta and validate it
- [ ] 1.2 Add `HydratedHomeOverlay` domain model and tests

## 2. Durable overlay store
- [ ] 2.1 Add `HydratedHomeOverlayStore` persistence, language/policy scoping, and alias tests
- [ ] 2.2 Implement store with batched observation and expiry filtering

## 3. Trace events
- [ ] 3.1 Add home hydration trace tests
- [ ] 3.2 Implement home hydration trace emitters

## 4. Overlay composition
- [ ] 4.1 Add pure overlay applier tests
- [ ] 4.2 Implement overlay applier without row reorder

## 5. HomeHydrationCoordinator
- [ ] 5.1 Add coordinator tests for visible, focused, adjacent +/-2, hero, cache hit, network hydration, ratings, failure, stale session
- [ ] 5.2 Implement coordinator through existing metadata facade and rating enrichment

## 6. HomeViewModel wiring
- [ ] 6.1 Add ViewModel pipeline tests proving visible/focused/adjacent card repaint
- [ ] 6.2 Observe overlays and compose them in `updateCatalogRowsPipeline`
- [ ] 6.3 Route visible/focused/adjacent +/-2/hero hydration through coordinator

## 7. Audit/report proof
- [ ] 7.1 Add metadata execution report scenarios for first paint, hydration update, cache hit, failure fallback, and stale profile/language/generation ignore
- [ ] 7.2 Add architecture guards preventing provider-specific home renderers, FieldResolvers, field merge paths, and hydration paths

## 8. Verification
- [ ] 8.1 Run focused unit suites and OpenSpec strict validation
- [ ] 8.2 Build and install releaseProfileable APK; validate logcat trace behavior
