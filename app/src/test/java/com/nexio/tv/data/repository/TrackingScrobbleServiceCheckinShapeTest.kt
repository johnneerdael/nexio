package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.javaType

/**
 * F-H-01 architecture pin: TrackingScrobbleService.checkin MUST keep `ownerProfileId: Int?` as the
 * third parameter shape. A future "harmonize the scrobble surface" refactor that migrates this to
 * `PlaybackOwnerContext` would compile and pass tests but force every checkin call site to fabricate
 * a context that never existed for checkin (which has no playback session).
 *
 * The asymmetry between scrobble (uses PlaybackOwnerContext) and checkin (uses bare Int?) is
 * deliberate. This test pins the contract via reflection.
 */
class TrackingScrobbleServiceCheckinShapeTest {

    @Test
    fun `checkin third parameter is Int (nullable)`() {
        val checkin = TrackingScrobbleService::class.declaredMemberFunctions
            .firstOrNull { it.name == "checkin" }
        requireNotNull(checkin) { "TrackingScrobbleService.checkin function not found via reflection" }

        // Parameters: index 0 is the receiver (this), 1 = item, 2 = message, 3 = ownerProfileId
        val params = checkin.parameters
        assertTrue(
            "expected at least 4 parameters (this + item + message + ownerProfileId), got ${params.size}",
            params.size >= 4
        )

        val ownerProfileIdParam = params[3]
        // The KType.javaType for Kotlin's `Int?` is the boxed `java.lang.Integer`.
        // For non-null `Int`, Kotlin compiles to primitive `int.class`.
        val typeName = ownerProfileIdParam.type.javaType.typeName
        val isNullableInt = typeName == "java.lang.Integer" || typeName == "kotlin.Int?"

        assertEquals(
            "F-H-01: TrackingScrobbleService.checkin third parameter must be Int? (nullable), got $typeName. " +
                "If you need PlaybackOwnerContext for checkin, create a NEW method like checkinWithContext(...) " +
                "rather than migrating the existing checkin signature.",
            true,
            isNullableInt
        )
    }
}
