import Foundation

public enum DeviceState: Equatable {
    case noDevice
    case unauthorized(serial: String)
    case ready(serial: String)
}

public struct DeviceDetector {
    public static func classify(adbDevicesOutput: String) -> DeviceState {
        for line in adbDevicesOutput.split(separator: "\n").dropFirst() {
            let parts = line.split(whereSeparator: { $0 == "\t" || $0 == " " }).map(String.init)
            guard parts.count >= 2 else { continue }
            switch parts[1] {
            case "device":
                return .ready(serial: parts[0])
            case "unauthorized":
                return .unauthorized(serial: parts[0])
            default:
                continue
            }
        }
        return .noDevice
    }
}

public struct InstallerBundle: Equatable {
    public let adbPath: String
    public let apkPath: String
    public let hubInstallScriptPath: String

    public init(adbPath: String, apkPath: String, hubInstallScriptPath: String) {
        self.adbPath = adbPath
        self.apkPath = apkPath
        self.hubInstallScriptPath = hubInstallScriptPath
    }
}

public struct InstallerCommand: Equatable {
    public let executable: String
    public let arguments: [String]

    public init(executable: String, arguments: [String]) {
        self.executable = executable
        self.arguments = arguments
    }
}

public struct InstallPlan: Equatable {
    public let commands: [InstallerCommand]

    public static func portalOnly(deviceSerial: String, bundle: InstallerBundle) -> InstallPlan {
        InstallPlan(commands: [
            InstallerCommand(
                executable: bundle.adbPath,
                arguments: ["-s", deviceSerial, "install", "-r", bundle.apkPath]
            ),
            InstallerCommand(
                executable: bundle.adbPath,
                arguments: ["-s", deviceSerial, "shell", "cmd", "package", "set-home-activity", "com.davidedicillo.portalroutine/.HomeActivity"]
            ),
            InstallerCommand(
                executable: bundle.adbPath,
                arguments: ["-s", deviceSerial, "shell", "am", "start", "-n", "com.davidedicillo.portalroutine/.HomeActivity"]
            ),
        ])
    }

    public static func restoreFallbackLauncher(deviceSerial: String, adbPath: String) -> InstallPlan {
        InstallPlan(commands: [
            InstallerCommand(
                executable: adbPath,
                arguments: ["-s", deviceSerial, "shell", "cmd", "package", "set-home-activity", "fr.neamar.kiss/.MainActivity"]
            ),
            InstallerCommand(
                executable: adbPath,
                arguments: ["-s", deviceSerial, "shell", "input", "keyevent", "HOME"]
            ),
        ])
    }

    public static func hubOnThisMac(bundle: InstallerBundle, publicUrl: String) -> InstallPlan {
        InstallPlan(commands: [
            InstallerCommand(
                executable: bundle.hubInstallScriptPath,
                arguments: ["PORTALKIDS_EMBEDDED_INSTALL=1", "PORTALKIDS_PUBLIC_URL=\(publicUrl)"]
            ),
        ])
    }
}
