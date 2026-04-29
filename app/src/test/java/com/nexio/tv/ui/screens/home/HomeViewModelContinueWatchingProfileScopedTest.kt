package com.nexio.tv.ui.screens.home

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
}
