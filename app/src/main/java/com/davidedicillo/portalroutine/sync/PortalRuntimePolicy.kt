package com.davidedicillo.portalroutine.sync

data class PortalRuntimePolicy(
    val mode: PortalMode,
) {
    val startsLocalAdminServer: Boolean
        get() = mode == PortalMode.StandalonePortal

    val syncsWithHub: Boolean
        get() = mode == PortalMode.HubClient

    val parentActionsUseLocalRepository: Boolean
        get() = mode == PortalMode.StandalonePortal
}
