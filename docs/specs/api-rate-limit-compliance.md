# Engineering Spec: API Rate-Limit Compliance via OkHttp Interceptor Enforcement

**Status:** Design  
**Last Updated:** 2026-04-08  
**Target Audience:** Senior Android/Kotlin Engineers  

---

## Overview

This specification defines the consolidation of Simkl and Trakt API rate-limit enforcement from the application layer into the OkHttp interceptor layer. The goal is to guarantee structural compliance with each service's rate-limit regulations by making enforcement apply to 100% of requests, regardless of how code paths invoke the HTTP client.

### Problem Statement

Currently, rate-limit enforcement for Trakt and Simkl is split between two locations:

1. **OkHttp interceptors** — inject required headers and query parameters
2. **Application layer** — enforce request rate limits via `TraktAuthService` and `SimklAuthService`

This two-layer approach creates a compliance gap: any code that calls `traktApi.*` or `simklApi.*` directly, bypassing the service layer, will violate rate-limit agreements. The enforcement is partial, not structural.

### Goal

Move rate-limit enforcement into the OkHttp interceptor layer, where it applies to 100% of requests made through each named client. This eliminates the bypass risk and simplifies the service layer.

---

## Background

### Current Architecture

#### NetworkModule.kt

The module provides two named OkHttp clients:

- **`provideTraktOkHttpClient`** (lines 176–234) — injects Trakt headers (`User-Agent`, `trakt-api-key`, `trakt-api-version`) via interceptor. No rate-limit enforcement.
- **`provideSimklOkHttpClient`** (lines 236–265) — injects Simkl query params (`client_id`, `app-name`, `app-version`) and headers (`User-Agent`) via interceptor. No rate-limit enforcement.

#### TraktAuthService.kt

**Rate-limit fields (lines 40–55):**
- `writeRequestMutex: Mutex` — serializes write requests
- `lastWriteRequestAtMs: Long` — tracks last write timestamp
- `minWriteIntervalMs = 1_000L` — enforces 1-second minimum between writes
- `rateLimitWindowMs = 5 * 60_000L` — 5-minute sliding window for GET
- `rateLimitMaxCalls = 900` — conservative limit (Trakt allows 1000/5min per client)
- `getRequestTimestamps: ArrayDeque<Long>` — sliding window queue for GETs
- `rateLimitMutex: Mutex` — guards the queue

**Rate-limit functions:**
- `acquireGetRateSlot()` (lines 89–113) — suspends if window is full, then appends timestamp
- `executeAuthorizedRequest()` (lines 297–377) — calls `acquireGetRateSlot()` at line 312 before every GET
- `executeAuthorizedWriteRequest()` (lines 379–389) — acquires write mutex, sleeps if needed, then calls `executeAuthorizedRequest()`

**Limitation:** Trakt rate limits only apply when code routes through these two methods. Direct calls to `traktApi.foo()` bypass both the write gate and the GET window.

#### SimklAuthService.kt

**Rate-limit fields (lines 29–31):**
- `writeRequestMutex: Mutex` — serializes write requests
- `lastWriteRequestAtMs: Long` — tracks last write timestamp
- `minWriteIntervalMs = 1_000L` — enforces 1-second minimum between writes

**Rate-limit function:**
- `executeAuthorizedWriteRequest()` (lines 117–128) — acquires write mutex, sleeps if needed

**Limitation:** Only write requests are rate-limited. There is no enforcement for GET. Direct calls to `simklApi.foo()` bypass the write gate entirely.

### Why Service-Layer Enforcement Is Insufficient

1. **Partial coverage** — rate limiting only applies to code paths that call `executeAuthorizedRequest()` or `executeAuthorizedWriteRequest()`. Code that calls `@Inject traktApi: TraktApi` directly bypasses enforcement entirely.
2. **Coroutine-based delay** — `delay()` is correct for coroutine code, but it hides the blocking from OkHttp's perspective. Mixing coroutine delays and OkHttp thread pools can lead to unexpected behavior if the dispatcher isn't carefully managed.
3. **Maintenance burden** — enforcement logic is duplicated across services, and new services or API calls require remembering to route through the service layer.

---

## Design

### Overview

Move both the write rate-limit gate and the GET sliding-window gate into the OkHttp interceptor layer. The interceptor layer is the right place because:

1. **Structural guarantee** — all HTTP requests flow through the interceptor, regardless of whether the caller knows about rate limits.
2. **Single source of truth** — no duplicated logic across service classes.
3. **Thread-native blocking** — OkHttp interceptors run on OkHttp's thread pool, so `Thread.sleep()` is the correct blocking primitive (not coroutine `delay()`).

### Trakt Rate Limits

**Requirements:**
- POST/PUT/DELETE: ≤ 1 request/second per client
- GET: ≤ 1000 requests per 5 minutes per client
- Required headers: `User-Agent`, `trakt-api-key`, `trakt-api-version` (already enforced)

**Implementation in `provideTraktOkHttpClient`:**

1. **Write rate limit (POST/PUT/DELETE):**
   - Declare `val lastMutatingRequestMs = AtomicLong(0L)` in function scope.
   - In the interceptor, before calling `chain.proceed()` for POST/PUT/DELETE:
     ```
     synchronized(lastMutatingRequestMs) {
       val elapsed = System.currentTimeMillis() - lastMutatingRequestMs.get()
       val sleepMs = (1_000L - elapsed).coerceAtLeast(0L)
       if (sleepMs > 0) Thread.sleep(sleepMs)
       lastMutatingRequestMs.set(System.currentTimeMillis())
     }
     ```
   - Proceed with the request after sleeping.

2. **GET rate limit (sliding window):**
   - Declare:
     ```
     val getWindowTimestamps = ArrayDeque<Long>()
     val getWindowLock = ReentrantLock()
     ```
   - In the interceptor, before calling `chain.proceed()` for GET:
     ```
     val getWindowLock.lock()
     try {
       val now = System.currentTimeMillis()
       val windowStart = now - 5 * 60_000L
       // Evict old timestamps
       while (getWindowTimestamps.isNotEmpty() && 
              getWindowTimestamps.first() < windowStart) {
         getWindowTimestamps.removeFirst()
       }
       // Sleep if approaching limit (use 950 as conservative buffer)
       if (getWindowTimestamps.size >= 950) {
         val oldestInWindow = getWindowTimestamps.first()
         val waitMs = oldestInWindow + 5 * 60_000L - now + 100L
         if (waitMs > 0) Thread.sleep(waitMs)
         // Re-evict after sleep
         val newNow = System.currentTimeMillis()
         val newWindowStart = newNow - 5 * 60_000L
         while (getWindowTimestamps.isNotEmpty() && 
                getWindowTimestamps.first() < newWindowStart) {
           getWindowTimestamps.removeFirst()
         }
       }
       getWindowTimestamps.addLast(System.currentTimeMillis())
     } finally {
       getWindowLock.unlock()
     }
     ```
   - Proceed with the request after recording the timestamp.

### Simkl Rate Limits

**Requirements:**
- POST/DELETE: ≤ 1 request/second per client
- Required query params: `client_id`, `app-name`, `app-version` (already enforced)
- Required header: `User-Agent` (already enforced)

**Implementation in `provideSimklOkHttpClient`:**

1. **Write rate limit (POST/DELETE):**
   - Declare `val lastMutatingRequestMs = AtomicLong(0L)` in function scope.
   - In the interceptor, before calling `chain.proceed()` for POST or DELETE:
     ```
     synchronized(lastMutatingRequestMs) {
       val elapsed = System.currentTimeMillis() - lastMutatingRequestMs.get()
       val sleepMs = (1_000L - elapsed).coerceAtLeast(0L)
       if (sleepMs > 0) Thread.sleep(sleepMs)
       lastMutatingRequestMs.set(System.currentTimeMillis())
     }
     ```
   - Proceed with the request after sleeping.

### New Imports

Add to `NetworkModule.kt`:

```kotlin
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
```

`kotlin.collections.ArrayDeque` is already available.

---

## Affected Files

| File | Changes | Rationale |
|------|---------|-----------|
| `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` | **Add:** Write rate-limit gate to `provideTraktOkHttpClient` and `provideSimklOkHttpClient`. **Add:** GET sliding-window gate to `provideTraktOkHttpClient`. **Add:** new imports (`AtomicLong`, `ReentrantLock`). | Moves enforcement into interceptor layer. |
| `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt` | **Remove:** `writeRequestMutex`, `lastWriteRequestAtMs`, `minWriteIntervalMs`, `rateLimitWindowMs`, `rateLimitMaxCalls`, `getRequestTimestamps`, `rateLimitMutex`. **Remove:** `acquireGetRateSlot()` function. **Remove:** `acquireGetRateSlot()` call in `executeAuthorizedRequest` (line 312). **Simplify:** `executeAuthorizedWriteRequest()` to one-liner: `return executeAuthorizedRequest(call)`. | Rate-limit enforcement moved to interceptor. |
| `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt` | **Remove:** `writeRequestMutex`, `lastWriteRequestAtMs`, `minWriteIntervalMs`. **Simplify:** `executeAuthorizedWriteRequest()` to one-liner: `return executeAuthorizedRequest(call)`. | Rate-limit enforcement moved to interceptor. |

---

## Thread Safety

### Why `Thread.sleep()` Not `delay()`

OkHttp interceptors execute on OkHttp's thread pool (typically a pool of daemon threads). When the interceptor blocks, it blocks the OkHttp dispatcher thread, not a coroutine dispatcher. Using coroutine `delay()` in an interceptor is problematic:

1. **Requires a dispatcher context** — `delay()` is a suspension function and needs access to a Kotlin coroutine dispatcher. OkHttp threads are not coroutine-aware by default.
2. **Risk of thread starvation** — attempting to use `delay()` without an appropriate context can hang the interceptor or bypass suspension entirely.
3. **Clarity** — `Thread.sleep()` is idiomatic for thread-pool code and clearly communicates that we are blocking a thread.

### Synchronization Primitives

**Write rate limit (Trakt and Simkl):**
- Use `synchronized(AtomicLong)` — simple, fair, and minimal overhead.
- The critical section is small (read elapsed, compute sleep, update timestamp).
- Fairness: threads that arrive while sleeping will queue behind the synchronized block and acquire the lock in order.

**GET rate limit (Trakt only):**
- Use `ReentrantLock` with explicit try/finally — allows complex logic (array manipulation, conditional sleep) while maintaining exception safety.
- `ReentrantLock` is reentrant (can be acquired multiple times by the same thread), which is not needed here but is not harmful.
- Fairness: `ReentrantLock()` is created without `fair=true`, so threads compete for the lock. Fair acquisition would add overhead and is not necessary for this use case.

### Atomicity and Ordering

- **Write gate:** The timestamp is read, a sleep is computed, the sleep happens, and the timestamp is updated—all within the synchronized block. No interleaving with other threads is possible.
- **GET window:** All operations (eviction, size check, sleep, re-eviction, append) happen within the lock. Timestamps are written in monotonically increasing order (each request records `System.currentTimeMillis()` after its sleep completes, or on first acquisition).

---

## Trade-offs

### Downsides

1. **OkHttp thread blocking** — the interceptor blocks OkHttp dispatcher threads during the 1-second write gate and (rarely) during the GET window sleep. This reduces concurrency for that client.
   - **Mitigation:** OkHttp's dispatcher has a default of 64 max concurrent requests and 5 per host. Blocking one thread for 1 second is negligible at typical request rates. The buffering is intentional to enforce rate limits.

2. **Burst handling under extreme load** — if 100 requests arrive simultaneously (unlikely in a TV app), the first will proceed immediately, the second will sleep 1 second, the third will sleep another 1 second, etc. This is correct behavior (spreading them out) but creates a queue.
   - **Mitigation:** This is the desired behavior. Rate limits exist to protect the service; spreading requests is the right approach.

3. **No visibility into interceptor delays** — callers cannot know whether a request was delayed by the rate-limit gate or the network. Log output is recommended (see "Testing / Verification").
   - **Mitigation:** Interceptors already log request/response times; the rate-limit delay is included in the total latency.

### Why These Trade-offs Are Acceptable

- TV apps have low request rates compared to mobile/web. A 1-second gate between writes is invisible to the user.
- Trakt and Simkl rate limits are generous (1 req/s for writes, 1000/5min for GETs). We are using conservative buffers (950 instead of 1000 for GETs).
- The alternative (no enforcement, hope code remembers to call the service layer) is unacceptable from a compliance perspective.

---

## Testing / Verification

### Unit Tests

Add tests to `NetworkModuleTest` (or create one if it doesn't exist):

1. **Trakt write rate limit:**
   - Mock the OkHttp client interceptor chain.
   - Inject two POST requests in quick succession (< 1 second apart).
   - Verify that the second request is delayed by at least 1 second.
   - Verify that `chain.proceed()` is called exactly twice.

2. **Trakt GET rate limit:**
   - Mock the interceptor chain.
   - Inject 960 GET requests (just under the 950 threshold, allowing for the sleep computation).
   - Verify no delay occurs.
   - Inject request 961.
   - Verify that request 961 is delayed until a request from the 5-minute window exits.

3. **Simkl write rate limit:**
   - Similar to Trakt write test.

### Integration Tests

1. **Bypass verification:**
   - Create a test that directly calls `@Inject traktApi.someGetEndpoint()` without going through `TraktAuthService.executeAuthorizedRequest()`.
   - Verify that the OkHttp interceptor still enforces the GET rate limit.
   - Repeat for Simkl POST.

2. **Compliance under normal load:**
   - Run the app in debug mode.
   - Enable debug logging in `NetworkModule` to print every interceptor entry/exit.
   - Execute operations that trigger Trakt GETs and writes (e.g., sync library, sync watch progress, add to list).
   - Verify that:
     - No two writes occur within 1 second.
     - No more than 950 GETs occur within any 5-minute window.
     - Logs show `REQUEST #N delayed 523ms` for late requests.

3. **Real-world burst test:**
   - Add a test mode that hammers Trakt/Simkl with 50 rapid requests of mixed types.
   - Verify that:
     - All requests eventually succeed (none are dropped).
     - Writes are serialized with ≥ 1 second between them.
     - GETs are within the window.

### Manual Verification Checklist

- [ ] `TraktAuthService` no longer has rate-limit fields or `acquireGetRateSlot()`.
- [ ] `SimklAuthService` no longer has `writeRequestMutex`, `lastWriteRequestAtMs`, or `minWriteIntervalMs`.
- [ ] `provideTraktOkHttpClient` has write rate-limit gate for POST/PUT/DELETE.
- [ ] `provideTraktOkHttpClient` has GET sliding-window gate.
- [ ] `provideSimklOkHttpClient` has write rate-limit gate for POST/DELETE.
- [ ] `executeAuthorizedWriteRequest()` in both services is a simple pass-through to `executeAuthorizedRequest()`.
- [ ] Direct calls to `traktApi.*` (bypassing service layer) still respect rate limits (verified by unit test).
- [ ] App builds without errors.
- [ ] App starts and basic flows work (library sync, playback, settings).

---

## Implementation Notes

### Order of Operations

1. Update `NetworkModule.kt` — add rate-limit gates to interceptors.
2. Update `TraktAuthService.kt` — remove rate-limit fields and `acquireGetRateSlot()`, simplify `executeAuthorizedWriteRequest()`.
3. Update `SimklAuthService.kt` — remove write rate-limit fields, simplify `executeAuthorizedWriteRequest()`.
4. Run full test suite and integration tests.
5. Manual smoke test on a device (sync library, browse, watch).

### Debug Logging Recommendation

In `NetworkModule`, consider adding debug logs to the interceptors:

```kotlin
if (BuildConfig.DEBUG && isMutatingRequest) {
  val startMs = System.currentTimeMillis()
  // ... rate limit gate ...
  val delayMs = System.currentTimeMillis() - startMs
  if (delayMs > 50) {
    Log.d("TraktRateLimit", "Delayed ${request.method} ${delayMs}ms due to rate limit")
  }
}
```

This makes debugging rate-limit issues in the field straightforward.

---

## Appendix: Regulation Summary

### Simkl

- **Required query params:** `client_id`, `app-name`, `app-version` (enforced in interceptor)
- **Required header:** `User-Agent` (enforced in interceptor)
- **Rate limits:**
  - POST/DELETE: ≤ 1 req/s per client (new enforcement in interceptor)

### Trakt

- **Required headers:** `User-Agent`, `trakt-api-key`, `trakt-api-version` (enforced in interceptor)
- **Rate limits:**
  - POST/PUT/DELETE: ≤ 1 req/s per client (new enforcement in interceptor)
  - GET: ≤ 1000 req/5min per client (new enforcement in interceptor)

---

## References

- [OkHttp Interceptors Documentation](https://square.github.io/okhttp/interceptors/)
- [Trakt API Rate Limit Policy](https://trakt.docs.apiary.io/#introduction/rate-limiting)
- [Simkl API Rate Limit Policy](https://simkl.docs.apiary.io/#introduction)
- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` — current interceptor implementations
- `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt` — current service-layer enforcement
- `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt` — current service-layer enforcement
