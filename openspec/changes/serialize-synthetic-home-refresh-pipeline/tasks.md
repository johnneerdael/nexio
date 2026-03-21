## 1. Implementation
- [x] 1.1 Keep the merged persisted Home snapshot as the only rendered startup source and remove direct Home publishes from per-source restore paths
- [x] 1.2 Remove eager observer-driven or incremental per-source Home rebuild during startup and refresh
- [x] 1.3 Add serialized Trakt refresh to the post-startup Home refresh pipeline as a disk-cache renewal step
- [x] 1.4 Add serialized MDBList refresh to the post-startup Home refresh pipeline after Trakt and before addon catalogs
- [x] 1.5 Ensure addon refresh, synthetic refresh, hydration, merged snapshot rebuild, and Home reload occur only from the serialized renewal phase
- [x] 1.6 Update startup telemetry/tests to reflect queued and completed serialized merged-snapshot refresh steps
