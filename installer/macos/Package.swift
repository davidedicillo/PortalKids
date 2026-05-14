// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "PortalKidsInstaller",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "PortalKidsInstallerCore", targets: ["PortalKidsInstallerCore"]),
        .executable(name: "PortalKidsInstaller", targets: ["PortalKidsInstaller"]),
    ],
    targets: [
        .target(name: "PortalKidsInstallerCore"),
        .executableTarget(
            name: "PortalKidsInstaller",
            dependencies: ["PortalKidsInstallerCore"],
        ),
        .testTarget(
            name: "PortalKidsInstallerCoreTests",
            dependencies: ["PortalKidsInstallerCore"],
        ),
    ],
)
