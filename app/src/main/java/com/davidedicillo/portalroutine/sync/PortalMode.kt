package com.davidedicillo.portalroutine.sync

enum class PortalMode(val storedValue: String) {
    StandalonePortal("standalone-portal"),
    HubClient("hub-client"),
    ;

    companion object {
        fun fromStoredValue(value: String?): PortalMode {
            return entries.firstOrNull { it.storedValue == value } ?: StandalonePortal
        }
    }
}
