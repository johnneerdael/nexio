package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-G-01 (Cluster D Tasks 4 + 5): the home VM's continue-watching subscription must be
 * profile-scoped at the flow API boundary, not via an in-lambda early-return on a
 * mismatched [com.nexio.tv.data.repository.ProfileOwnedContinueWatchingSnapshot.profileId].
 *
 * `HomeViewModel` is constructed with ~50 collaborators (DataStores, services, notifiers,
 * etc.), so a behaviour-level VM test is impractical here. Instead we pin the migration
 * via a source-level assertion on `HomeViewModelContinueWatching.kt`:
 *
 *   1. The unscoped form `observeSnapshot().collectLatest { ... }` (without an
 *      intervening `.filter` on `profileId`) must not appear.
 *   2. The subscription must route through one of:
 *      - `observeProfileSnapshot(<profile-id>)` (path B, Task 5 — preferred)
 *      - `observeContinueWatching(<profile-id>)` (path B legacy name)
 *      - `observeSnapshot()` with an explicit `.filter { ... profileId ... }` on the
 *        flow chain (path A in the cluster D plan)
 *
 * Task 6 uses the same source-level assertion technique for the rail/home wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelContinueWatchingProfileScopedTest {

    private val sourceFile: File = locateSourceFile()

    private fun locateSourceFile(): File {
        val relative = "src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt"
        val candidates = listOf(
            File(relative),
            File("app/$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Could not locate HomeViewModelContinueWatching.kt; tried: " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    @Test
    fun `profile switch restarts continue watching collection`() = runTest {
        val firstSession = homeSession(profileId = 1, profileSessionKey = "profile:1:runtime:1")
        val secondSession = homeSession(profileId = 2, profileSessionKey = "profile:2:runtime:1")
        val activeSession = MutableStateFlow(firstSession)
        val snapshotFlows = mapOf(
            1 to MutableSharedFlow<ContinueWatchingSnapshot>(),
            2 to MutableSharedFlow<ContinueWatchingSnapshot>()
        )
        val observedProfiles = mutableListOf<Int>()
        val emissions = mutableListOf<ProfileScopedEmission<ContinueWatchingSnapshot>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            continueWatchingProfileScopedEmissions(
                activeHomeProfileSession = activeSession,
                observeProfileSnapshot = { profileId ->
                    observedProfiles += profileId
                    snapshotFlows.getValue(profileId)
                }
            ).collect { emission ->
                emissions += emission
            }
        }

        advanceUntilIdle()
        snapshotFlows.getValue(1).emit(ContinueWatchingSnapshot(updatedAtMs = 11L))
        activeSession.value = secondSession
        advanceUntilIdle()
        snapshotFlows.getValue(2).emit(ContinueWatchingSnapshot(updatedAtMs = 22L))
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1, 2), observedProfiles)
        assertEquals(
            listOf(firstSession, firstSession, secondSession, secondSession),
            emissions.map { it.session }
        )
        assertTrue(emissions[0] is ProfileScopedEmission.Loading)
        assertTrue(emissions[1] is ProfileScopedEmission.Success)
        assertTrue(emissions[2] is ProfileScopedEmission.Loading)
        assertTrue(emissions[3] is ProfileScopedEmission.Success)
    }

    @Test
    fun `same profile session key does not restart continue watching collection`() = runTest {
        val firstSession = homeSession(
            profileId = 1,
            sessionId = "home-profile:1:runtime:1",
            profileSessionKey = "profile:1:runtime:1"
        )
        val settingsSession = homeSession(
            profileId = 1,
            sessionId = "home-profile:1:runtime:settings",
            profileSessionKey = "profile:1:runtime:1"
        )
        val activeSession = MutableStateFlow(firstSession)
        val snapshotFlow = MutableSharedFlow<ContinueWatchingSnapshot>()
        val observedProfiles = mutableListOf<Int>()
        val emissions = mutableListOf<ProfileScopedEmission<ContinueWatchingSnapshot>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            continueWatchingProfileScopedEmissions(
                activeHomeProfileSession = activeSession,
                observeProfileSnapshot = { profileId ->
                    observedProfiles += profileId
                    snapshotFlow
                }
            ).collect { emission ->
                emissions += emission
            }
        }

        advanceUntilIdle()
        activeSession.value = settingsSession
        advanceUntilIdle()
        snapshotFlow.emit(ContinueWatchingSnapshot(updatedAtMs = 33L))
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1), observedProfiles)
        assertEquals(2, emissions.size)
        assertEquals(firstSession, emissions[0].session)
        assertEquals(firstSession, emissions[1].session)
    }

    @Test
    fun `old profile emissions after switch are ignored by cancelled flow`() = runTest {
        val firstSession = homeSession(profileId = 1, profileSessionKey = "profile:1:runtime:1")
        val secondSession = homeSession(profileId = 2, profileSessionKey = "profile:2:runtime:1")
        val activeSession = MutableStateFlow(firstSession)
        val firstSnapshots = MutableSharedFlow<ContinueWatchingSnapshot>()
        val secondSnapshots = MutableSharedFlow<ContinueWatchingSnapshot>()
        val emissions = mutableListOf<ProfileScopedEmission<ContinueWatchingSnapshot>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            continueWatchingProfileScopedEmissions(
                activeHomeProfileSession = activeSession,
                observeProfileSnapshot = { profileId ->
                    if (profileId == 1) firstSnapshots else secondSnapshots
                }
            ).collect { emission ->
                emissions += emission
            }
        }

        advanceUntilIdle()
        firstSnapshots.emit(ContinueWatchingSnapshot(updatedAtMs = 11L))
        activeSession.value = secondSession
        advanceUntilIdle()
        firstSnapshots.emit(ContinueWatchingSnapshot(updatedAtMs = 99L))
        secondSnapshots.emit(ContinueWatchingSnapshot(updatedAtMs = 22L))
        advanceUntilIdle()
        job.cancel()

        val secondLoadingIndex = emissions.indexOfFirst {
            it is ProfileScopedEmission.Loading && it.session == secondSession
        }
        assertTrue(secondLoadingIndex >= 0)
        assertFalse(
            emissions.drop(secondLoadingIndex + 1).any {
                it is ProfileScopedEmission.Success && it.session == firstSession
            }
        )
        assertTrue(
            emissions.drop(secondLoadingIndex + 1).any {
                it is ProfileScopedEmission.Success && it.session == secondSession
            }
        )
    }

    @Test
    fun `continue watching subscription is profile scoped at the flow boundary`() {
        check(sourceFile.exists()) { "expected source at ${sourceFile.absolutePath}" }
        val source = sourceFile.readText()

        // Locate the snapshot subscription block. It must exist so we can analyse it.
        val subscriptionRegex = Regex(
            """continueWatchingSnapshotService\s*\.\s*(observeSnapshot|observeContinueWatching|observeProfileSnapshot)\b[^{]*"""
        )
        val match = subscriptionRegex.find(source)
            ?: error(
                "expected continueWatchingSnapshotService.observe(Snapshot|ContinueWatching|ProfileSnapshot) call"
            )

        val callsObserveSnapshot = match.groupValues[1] == "observeSnapshot"
        if (callsObserveSnapshot) {
            // Path A: tolerate observeSnapshot() only when followed by an explicit
            // .filter on profileId (or routed through observeContinueWatching elsewhere).
            // Look at a window starting at the call to allow for either chained `.filter`
            // or piping through `observeContinueWatching`.
            val windowStart = match.range.first
            val window = source.substring(
                windowStart,
                (windowStart + 600).coerceAtMost(source.length)
            )
            val hasProfileFilter = Regex("""\.filter\s*\{[^}]*profileId[^}]*}""").containsMatchIn(window) ||
                Regex("""observeContinueWatching\s*\(""").containsMatchIn(window)
            check(hasProfileFilter) {
                "F-G-01: continueWatchingSnapshotService.observeSnapshot() must be " +
                    "profile-scoped at the flow boundary (chained .filter on profileId, or " +
                    "routed via observeContinueWatching/observeProfileSnapshot). Found unscoped " +
                    "subscription at char index $windowStart in ${sourceFile.path}."
            }
        } else {
            // Path B: observeContinueWatching(...) or observeProfileSnapshot(...) must
            // receive the active profile id.
            val callExpr = match.value
            check(Regex("""profileId|activeProfileId|activeHomeProfileSession""").containsMatchIn(callExpr)) {
                "F-G-01: observeContinueWatching/observeProfileSnapshot must be invoked with " +
                    "the active profile id. Got: $callExpr"
            }
        }
    }

    @Test
    fun `no unscoped observeSnapshot collectLatest remains`() {
        check(sourceFile.exists()) { "expected source at ${sourceFile.absolutePath}" }
        val source = sourceFile.readText()

        // The legacy un-scoped pattern: observeSnapshot().collectLatest { ... } where the
        // body relied on an in-lambda profileId early-return (the F-G-01 anti-pattern).
        // Either no observeSnapshot() at all, or it must be followed (within ~120 chars,
        // i.e. on the same flow chain) by .filter / .map etc. before .collectLatest.
        val antiPattern = Regex(
            """observeSnapshot\s*\(\s*\)\s*\.\s*collectLatest\s*\{"""
        )
        check(!antiPattern.containsMatchIn(source)) {
            "F-G-01 part 1: HomeViewModelContinueWatching must not directly chain " +
                "observeSnapshot().collectLatest { ... }; introduce .filter on profileId " +
                "(path A) or migrate to observeContinueWatching() (path B)."
        }
    }

    @Test
    fun `continue watching collector is driven by active profile session`() {
        check(sourceFile.exists()) { "expected source at ${sourceFile.absolutePath}" }
        val source = sourceFile.readText()

        assertTrue(source.contains("continueWatchingProfileScopedEmissions("))
        assertTrue(source.contains(".distinctUntilChangedBy { it.profileSessionKey }"))
        assertTrue(source.contains(".flatMapLatest { session ->"))
        assertTrue(source.contains("observeProfileSnapshot(session.profileId)"))
        assertTrue(source.contains("isCurrentHomeSession(session)"))
        assertFalse(source.contains("observeProfileSnapshot(activeHomeProfileSession.profileId)"))
    }

    @Test
    fun `accepted continue watching snapshot cancels previous enrichment before eligibility check`() {
        check(sourceFile.exists()) { "expected source at ${sourceFile.absolutePath}" }
        val source = sourceFile.readText()
        val functionStart = source.indexOf("private suspend fun HomeViewModel.applyContinueWatchingSnapshotForSession")
        val cancelIndex = source.indexOf("continueWatchingEnrichmentJob?.cancel()", functionStart)
        val transformIndex = source.indexOf("buildMixedContinueWatchingTimeline(", functionStart)
        val eligibilityIndex = source.indexOf("shouldEnrichContinueWatchingProviderMetadata", functionStart)

        assertTrue(functionStart >= 0)
        assertTrue(cancelIndex >= 0)
        assertTrue(transformIndex >= 0)
        assertTrue(eligibilityIndex >= 0)
        assertTrue(cancelIndex < transformIndex)
        assertTrue(cancelIndex < eligibilityIndex)
    }

    @Test
    fun `continue watching enrichment publish is guarded by accepted snapshot version`() {
        check(sourceFile.exists()) { "expected source at ${sourceFile.absolutePath}" }
        val source = sourceFile.readText()

        assertTrue(source.contains("continueWatchingSnapshotVersion += 1L"))
        assertTrue(source.contains("val snapshotVersion = continueWatchingSnapshotVersion"))
        assertTrue(source.contains("snapshotVersion != continueWatchingSnapshotVersion"))
        assertTrue(source.contains("if (snapshotVersion != continueWatchingSnapshotVersion) return@update state"))
    }

    private fun homeSession(
        profileId: Int,
        profileSessionKey: String,
        sessionId: String = "home-$profileSessionKey",
        generation: Long = 1L
    ): HomeProfileSession {
        return if (profileId == 1) {
            HomeProfileSession.DefaultLegacy(
                generation = generation,
                sessionId = sessionId,
                profileSessionKey = profileSessionKey,
                language = "en",
                subtitleLanguage = null,
                startedAtMs = 1L
            )
        } else {
            HomeProfileSession.Secondary(
                profileId = profileId,
                generation = generation,
                sessionId = sessionId,
                profileSessionKey = profileSessionKey,
                language = "en",
                subtitleLanguage = null,
                startedAtMs = 1L,
                boundaryContext = com.nexio.tv.core.profile.SecondaryProfileRuntimeContext(
                    profileId = profileId,
                    languageTag = "en",
                    generation = generation
                )
            )
        }
    }
}
