package com.davidedicillo.portalroutine.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalRuntimePolicyTest {
    @Test
    fun standalonePortalStartsLocalAdminAndDoesNotSyncHub() {
        val policy = PortalRuntimePolicy(PortalMode.StandalonePortal)

        assertTrue(policy.startsLocalAdminServer)
        assertFalse(policy.syncsWithHub)
        assertTrue(policy.parentActionsUseLocalRepository)
    }

    @Test
    fun hubClientSyncsHubAndDoesNotStartLocalAdmin() {
        val policy = PortalRuntimePolicy(PortalMode.HubClient)

        assertFalse(policy.startsLocalAdminServer)
        assertTrue(policy.syncsWithHub)
        assertFalse(policy.parentActionsUseLocalRepository)
    }
}
