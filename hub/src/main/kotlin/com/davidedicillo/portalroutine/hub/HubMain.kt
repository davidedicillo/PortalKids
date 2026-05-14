package com.davidedicillo.portalroutine.hub

import com.davidedicillo.portalroutine.data.RoutineRepository
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val port = option(args, "--port")?.toIntOrNull()
        ?: System.getenv("PORTALKIDS_PORT")?.toIntOrNull()
        ?: 8080
    val dbPath = option(args, "--db")
        ?: System.getenv("PORTALKIDS_DB")
        ?: File(System.getProperty("user.home"), ".portalkids/portal-kids.db").absolutePath
    val publicUrl = option(args, "--public-url")
        ?: System.getenv("PORTALKIDS_PUBLIC_URL")
        ?: "http://${LanAddress.localIpv4Address() ?: "127.0.0.1"}:$port"

    val store = SqliteRoutineStore(File(dbPath))
    val repository = RoutineRepository(store)
    runBlocking {
        repository.ensureSeedData()
        val initialPin = System.getenv("PORTALKIDS_PARENT_PIN").orEmpty()
        if (initialPin.length >= 4 && !repository.hasParentPin()) {
            repository.setParentPin(initialPin)
        }
    }

    val server = HubServer(
        repository = repository,
        completionSetService = CompletionSetService(repository, store),
        publicUrl = publicUrl,
        port = port,
    )
    server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    println("PortalKids Hub listening at $publicUrl")
    println("Database: ${File(dbPath).absolutePath}")
    Thread.currentThread().join()
}

private fun option(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}
