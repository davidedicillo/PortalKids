plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.davidedicillo.portalroutine.hub.HubMainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
