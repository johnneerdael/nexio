package com.nexio.tv.ui.navigation

import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.OrganizationDiscoverType
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationDetailRouteTest {

    @Test
    fun `organization detail route encodes name and discover type`() {
        val route = Screen.OrganizationDetail.createRoute(
            entityId = 49,
            entityName = "HBO Max / Originals",
            kind = MetaCompanyKind.NETWORK,
            discoverType = OrganizationDiscoverType.TV_NETWORK
        )

        assertTrue(route.contains("organization_detail/49/HBO%20Max%20%2F%20Originals"))
        assertTrue(route.contains("kind=NETWORK"))
        assertTrue(route.contains("discoverType=TV_NETWORK"))
    }
}
