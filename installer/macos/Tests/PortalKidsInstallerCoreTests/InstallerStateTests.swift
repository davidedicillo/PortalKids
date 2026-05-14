import XCTest
@testable import PortalKidsInstallerCore

final class InstallerStateTests: XCTestCase {
    func testAdbDeviceOutputClassifiesEmptyUnauthorizedAndReadyStates() {
        XCTAssertEqual(DeviceDetector.classify(adbDevicesOutput: "List of devices attached\n\n"), .noDevice)
        XCTAssertEqual(
            DeviceDetector.classify(adbDevicesOutput: "List of devices attached\nABC123\tunauthorized\n"),
            .unauthorized(serial: "ABC123")
        )
        XCTAssertEqual(
            DeviceDetector.classify(adbDevicesOutput: "List of devices attached\nABC123\tdevice\n"),
            .ready(serial: "ABC123")
        )
    }

    func testInstallPlanUsesBundledToolsAndStandaloneApkByDefault() {
        let bundle = InstallerBundle(
            adbPath: "/App/Contents/Resources/platform-tools/adb",
            apkPath: "/App/Contents/Resources/PortalKids.apk",
            hubInstallScriptPath: "/App/Contents/Resources/hub/install-launch-agent.sh"
        )

        let plan = InstallPlan.portalOnly(deviceSerial: "ABC123", bundle: bundle)

        XCTAssertEqual(plan.commands.map(\.executable), [
            "/App/Contents/Resources/platform-tools/adb",
            "/App/Contents/Resources/platform-tools/adb",
            "/App/Contents/Resources/platform-tools/adb",
        ])
        XCTAssertEqual(plan.commands[0].arguments, ["-s", "ABC123", "install", "-r", "/App/Contents/Resources/PortalKids.apk"])
        XCTAssertEqual(plan.commands[1].arguments, ["-s", "ABC123", "shell", "cmd", "package", "set-home-activity", "com.davidedicillo.portalroutine/.HomeActivity"])
        XCTAssertEqual(plan.commands[2].arguments, ["-s", "ABC123", "shell", "am", "start", "-n", "com.davidedicillo.portalroutine/.HomeActivity"])
    }

    func testRestoreFallbackLauncherPlanUsesKissLauncherHomeActivity() {
        let plan = InstallPlan.restoreFallbackLauncher(
            deviceSerial: "ABC123",
            adbPath: "/App/Contents/Resources/platform-tools/adb"
        )

        XCTAssertEqual(plan.commands[0].arguments, ["-s", "ABC123", "shell", "cmd", "package", "set-home-activity", "fr.neamar.kiss/.MainActivity"])
        XCTAssertEqual(plan.commands[1].arguments, ["-s", "ABC123", "shell", "input", "keyevent", "HOME"])
    }

    func testHubInstallPlanRunsBundledHubScriptWithPublicUrl() {
        let bundle = InstallerBundle(
            adbPath: "/App/Contents/Resources/platform-tools/adb",
            apkPath: "/App/Contents/Resources/PortalKids.apk",
            hubInstallScriptPath: "/App/Contents/Resources/hub/install-launch-agent.sh"
        )

        let plan = InstallPlan.hubOnThisMac(bundle: bundle, publicUrl: "http://192.168.4.29:8080")

        XCTAssertEqual(plan.commands, [
            InstallerCommand(
                executable: "/App/Contents/Resources/hub/install-launch-agent.sh",
                arguments: ["PORTALKIDS_EMBEDDED_INSTALL=1", "PORTALKIDS_PUBLIC_URL=http://192.168.4.29:8080"]
            )
        ])
    }
}
