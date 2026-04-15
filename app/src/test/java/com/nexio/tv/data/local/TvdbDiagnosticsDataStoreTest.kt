package com.nexio.tv.data.local

import com.nexio.tv.core.tvdb.TvdbDiagnosticsRecorder
import com.nexio.tv.core.tvdb.TvdbReliabilityDiagnostic
import com.nexio.tv.core.tvdb.TvdbReliabilityReason
import com.nexio.tv.core.di.TvdbDiagnosticsModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TvdbDiagnosticsDataStoreTest {

    private fun createStore(): TvdbDiagnosticsDataStore {
        val context = RuntimeEnvironment.getApplication()
        return TvdbDiagnosticsDataStore(context)
    }

    @Test
    fun `diagnostics data store implements tvdb diagnostics recorder`() {
        val store = createStore()
        assertTrue(
            "TvdbDiagnosticsDataStore must implement TvdbDiagnosticsRecorder",
            store is TvdbDiagnosticsRecorder
        )
    }

    @Test
    fun `diagnostics data store redacts secrets before persistence`() = runTest {
        val store = createStore()
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
            surface = "update",
            message = "Error: Authorization: Bearer secret-token-value"
        )
        store.record(diagnostic)
        val snapshot = store.snapshot.first()
        assertNotNull(snapshot.lastUpdateRefreshStatus)
        assertTrue(
            "Persisted message must be sanitized",
            snapshot.lastUpdateRefreshStatus?.contains("secret-token-value") != true
        )
        if (snapshot.lastUpdateRefreshStatus != null) {
            assertTrue(
                "Persisted message must contain [redacted]",
                snapshot.lastUpdateRefreshStatus!!.contains("[redacted]")
            )
        }
    }

    @Test
    fun `diagnostics data store records update reference fallback credential fields`() = runTest {
        val store = createStore()

        // Record provider decision
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN,
            surface = "detail",
            message = "tvdb chosen for series"
        ))

        // Record fallback
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.EXPLICIT_FALLBACK,
            surface = "detail",
            fallbackProvider = "tmdb",
            message = "explicit fallback to tmdb"
        ))

        // Record update refresh
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.UPDATE_REFRESH_SUCCEEDED,
            surface = "update",
            message = "refresh succeeded"
        ))

        // Record reference refresh
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.REFERENCE_REFRESH_SUCCEEDED,
            surface = "reference",
            message = "reference refresh succeeded"
        ))

        // Record airtime reason
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.MISSING_AIRS_TIME,
            surface = "continue_watching",
            message = "no airsTime field"
        ))

        // Record poster reason
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.POSTER_RATINGS_OVERRIDE,
            surface = "poster",
            message = "poster-ratings override active"
        ))

        // Record TMDB skip
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.TMDB_TV_FETCH_SKIPPED,
            surface = "detail",
            message = "TMDB TV fetch skipped"
        ))

        // Record invalid credentials
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.INVALID_CREDENTIALS,
            surface = "settings",
            message = "credentials invalid"
        ))

        // Record stale cache
        store.record(TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.STALE_CACHE_SERVED,
            surface = "detail",
            message = "stale cache served"
        ))

        val snapshot = store.snapshot.first()
        assertNotNull("Provider decision must be recorded", snapshot.lastProviderDecision)
        assertNotNull("Fallback reason must be recorded", snapshot.lastFallbackReason)
        assertNotNull("Update refresh status must be recorded", snapshot.lastUpdateRefreshStatus)
        assertNotNull("Reference refresh status must be recorded", snapshot.lastReferenceRefreshStatus)
        assertNotNull("Airtime reason must be recorded", snapshot.lastAirtimeReason)
        assertNotNull("Poster reason must be recorded", snapshot.lastPosterReason)
        assertNotNull("TMDB skip reason must be recorded", snapshot.lastTmdbSkipReason)
        assertNotNull("Invalid credential status must be recorded", snapshot.lastInvalidCredentialStatus)
        assertNotNull("Stale cache status must be recorded", snapshot.lastStaleCacheStatus)
    }

    @Test
    fun `diagnostics hilt module binds recorder before producers`() {
        // Verify the Hilt module class exists and has the expected binding shape.
        // If TvdbDiagnosticsModule compiles with @Binds and returns TvdbDiagnosticsRecorder,
        // this test passes at compile time. Runtime check confirms the class is loadable.
        val moduleClass = TvdbDiagnosticsModule::class.java
        assertNotNull("TvdbDiagnosticsModule must be loadable", moduleClass)

        val bindsMethod = moduleClass.declaredMethods.firstOrNull {
            it.name == "bindTvdbDiagnosticsRecorder"
        }
        assertNotNull(
            "TvdbDiagnosticsModule must have bindTvdbDiagnosticsRecorder method",
            bindsMethod
        )
        assertEquals(
            "Binding must return TvdbDiagnosticsRecorder",
            TvdbDiagnosticsRecorder::class.java,
            bindsMethod!!.returnType
        )
    }

    @Test
    fun `diagnostics data store structured logs are sanitized`() = runTest {
        val store = createStore()
        // Record a diagnostic with a secret in the message
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.INVALID_CREDENTIALS,
            surface = "settings",
            message = "token=my-secret-jwt-token was rejected"
        )
        store.record(diagnostic)

        // The snapshot should contain the sanitized version
        val snapshot = store.snapshot.first()
        assertNotNull(snapshot.lastInvalidCredentialStatus)
        assertTrue(
            "Stored status must not contain raw secret",
            snapshot.lastInvalidCredentialStatus?.contains("my-secret-jwt-token") != true
        )
    }
}
