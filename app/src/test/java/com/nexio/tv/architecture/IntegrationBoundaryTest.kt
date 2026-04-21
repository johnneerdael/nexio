package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class IntegrationBoundaryTest {
    @Test
    fun `non integration packages do not reference provider retrofit apis directly`() {
        val forbiddenTypeNames = setOf(
            "TraktApi",
            "SimklApi",
            "TmdbApi",
            "TvdbApi",
            "KitsuApi",
            "MDBListApi",
            "OmdbApi",
            "IntroDbApi",
            "AniSkipApi",
            "AnimeSkipApi",
            "ArmApi",
            "RealDebridApi",
            "PremiumizeApi",
            "TorBoxApi",
            "EasyDebridApi",
            "RpdbApi",
            "TopPostersApi"
        )

        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.data.integration",
                "com.nexio.tv.data.remote",
                "com.nexio.tv.core.di"
            ),
            forbiddenSimpleNames = forbiddenTypeNames
        )

        if (offenders.isNotEmpty()) {
            fail("Direct provider API usage outside integration boundary: $offenders")
        }
    }
}
