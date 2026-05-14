package com.davidedicillo.portalroutine

import android.app.Application
import com.davidedicillo.portalroutine.admin.RoutineAdminServer
import com.davidedicillo.portalroutine.data.RoutineRepository
import com.davidedicillo.portalroutine.data.room.RoomRoutineStore
import com.davidedicillo.portalroutine.data.room.RoutineDatabase
import com.davidedicillo.portalroutine.sync.DeviceSettings
import com.davidedicillo.portalroutine.sync.PortalSyncRepository

class PortalKidsApplication : Application() {
    private val database by lazy { RoutineDatabase.get(this) }
    private val store by lazy { RoomRoutineStore(database.routineDao()) }
    val deviceSettings: DeviceSettings by lazy { DeviceSettings(this) }

    val repository: RoutineRepository by lazy {
        RoutineRepository(store)
    }

    val syncRepository: PortalSyncRepository by lazy {
        PortalSyncRepository(
            localRepository = repository,
            localStore = store,
            dao = database.routineDao(),
            deviceSettings = deviceSettings,
        )
    }

    private var adminServer: RoutineAdminServer? = null

    fun startAdminServer() {
        if (adminServer?.isAlive == true) return
        adminServer = RoutineAdminServer(this, repository).also { it.start() }
    }

    fun stopAdminServer() {
        adminServer?.stop()
        adminServer = null
    }

    override fun onTerminate() {
        adminServer?.stop()
        super.onTerminate()
    }
}
