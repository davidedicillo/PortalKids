import SwiftUI
import PortalKidsInstallerCore

@main
struct PortalKidsInstallerApp: App {
    var body: some Scene {
        WindowGroup {
            InstallerView()
                .frame(minWidth: 760, minHeight: 620)
        }
        .windowStyle(.titleBar)
    }
}

struct InstallerView: View {
    @StateObject private var model = InstallerViewModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            statusCard
            steps
            controls
            logView
        }
        .padding(24)
        .onAppear { model.refreshDevice() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("PortalKids Installer")
                .font(.largeTitle.bold())
            Text("Install the kid board on a Meta Portal over USB. The app runs standalone first; the Mac hub is optional.")
                .foregroundStyle(.secondary)
        }
    }

    private var statusCard: some View {
        HStack(spacing: 14) {
            Image(systemName: model.statusSymbol)
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(model.statusColor)
                .frame(width: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(model.statusTitle)
                    .font(.title3.bold())
                Text(model.statusDetail)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("Refresh") {
                model.refreshDevice()
            }
        }
        .padding(16)
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Setup Steps")
                .font(.headline)
            StepRow(symbol: "1.circle.fill", title: "Enable ADB", detail: "On the Portal, enable Developer Options and USB debugging.")
            StepRow(symbol: "2.circle.fill", title: "Connect USB", detail: "Connect the Portal to this Mac. If prompted on the Portal, allow USB debugging.")
            StepRow(symbol: "3.circle.fill", title: "Install PortalKids", detail: "The installer uses bundled ADB and the bundled APK. No Android Studio required.")
            StepRow(symbol: "4.circle.fill", title: "Optional Hub", detail: "Run the Mac hub only if this Mac is always on and you want central admin/backups.")
        }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Button("Install PortalKids") {
                    model.installPortalKids()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!model.canInstallPortal || model.isBusy)

                Button("Restore Fallback Launcher") {
                    model.restoreFallbackLauncher()
                }
                .disabled(!model.canInstallPortal || model.isBusy)
            }

            Toggle("Also run PortalKids Hub on this Mac", isOn: $model.installHub)
            HStack {
                TextField("Hub URL", text: $model.hubUrl)
                    .textFieldStyle(.roundedBorder)
                    .disabled(!model.installHub || model.isBusy)
                Button("Install Hub") {
                    model.installHubOnThisMac()
                }
                .disabled(!model.installHub || model.isBusy)
            }
        }
    }

    private var logView: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Installer Log")
                .font(.headline)
            ScrollView {
                Text(model.logText)
                    .font(.system(.body, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(minHeight: 130)
            .padding(10)
            .background(Color(nsColor: .textBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}

struct StepRow: View {
    let symbol: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(.teal)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.bold())
                Text(detail)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

@MainActor
final class InstallerViewModel: ObservableObject {
    @Published var deviceState: DeviceState = .noDevice
    @Published var logText = "Ready.\n"
    @Published var isBusy = false
    @Published var installHub = false
    @Published var hubUrl: String

    private let runner = ProcessRunner()

    init() {
        self.hubUrl = Self.defaultHubUrl()
    }

    var canInstallPortal: Bool {
        if case .ready = deviceState { return true }
        return false
    }

    var statusSymbol: String {
        switch deviceState {
        case .noDevice: "cable.connector"
        case .unauthorized: "exclamationmark.triangle.fill"
        case .ready: "checkmark.circle.fill"
        }
    }

    var statusColor: Color {
        switch deviceState {
        case .noDevice: .secondary
        case .unauthorized: .orange
        case .ready: .green
        }
    }

    var statusTitle: String {
        switch deviceState {
        case .noDevice: "No Portal detected"
        case .unauthorized: "Portal waiting for authorization"
        case .ready: "Portal ready"
        }
    }

    var statusDetail: String {
        switch deviceState {
        case .noDevice:
            "Enable USB debugging, connect the Portal by USB, then click Refresh."
        case .unauthorized(let serial):
            "Device \(serial) is connected. Accept the USB debugging prompt on the Portal."
        case .ready(let serial):
            "Device \(serial) is ready to install."
        }
    }

    func refreshDevice() {
        Task {
            isBusy = true
            append("START Detecting Portal")
            do {
                let output = try runner.capture(executable: bundle.adbPath, arguments: ["devices"])
                deviceState = DeviceDetector.classify(adbDevicesOutput: output)
                append("OK Detection complete")
            } catch {
                append("ERROR Detection failed: \(error.localizedDescription)")
            }
            isBusy = false
        }
    }

    func installPortalKids() {
        guard case .ready(let serial) = deviceState else { return }
        Task { await run("Installing PortalKids", refreshAfter: true) {
            for command in InstallPlan.portalOnly(deviceSerial: serial, bundle: self.bundle).commands {
                try self.runner.run(command)
            }
        } }
    }

    func restoreFallbackLauncher() {
        guard case .ready(let serial) = deviceState else { return }
        Task { await run("Restoring fallback launcher", refreshAfter: true) {
            for command in InstallPlan.restoreFallbackLauncher(deviceSerial: serial, adbPath: self.bundle.adbPath).commands {
                try self.runner.run(command)
            }
        } }
    }

    func installHubOnThisMac() {
        Task { await run("Installing Mac hub") {
            for command in InstallPlan.hubOnThisMac(bundle: self.bundle, publicUrl: self.hubUrl).commands {
                try self.runner.run(command)
            }
        } }
    }

    private func run(_ title: String, refreshAfter: Bool = false, operation: @escaping () async throws -> Void) async {
        isBusy = true
        append("START \(title)")
        do {
            try await operation()
            append("OK \(title) complete")
            if title == "Installing PortalKids" {
                append("Standalone admin will be available at http://<portal-ip>:8080 after PIN setup.")
            }
        } catch {
            append("ERROR \(title) failed: \(error.localizedDescription)")
        }
        isBusy = false
        if refreshAfter {
            refreshDevice()
        }
    }

    private func append(_ line: String) {
        logText += "\(line)\n"
    }

    private var bundle: InstallerBundle {
        let resources = Bundle.main.resourceURL ?? URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        return InstallerBundle(
            adbPath: resources.appendingPathComponent("platform-tools/adb").path,
            apkPath: resources.appendingPathComponent("PortalKids.apk").path,
            hubInstallScriptPath: resources.appendingPathComponent("hub/scripts/install-launch-agent.sh").path
        )
    }

    private static func defaultHubUrl() -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/ipconfig")
        process.arguments = ["getifaddr", "en0"]
        let pipe = Pipe()
        process.standardOutput = pipe
        do {
            try process.run()
            process.waitUntilExit()
            let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !output.isEmpty {
                return "http://\(output):8080"
            }
        } catch {
        }
        return "http://127.0.0.1:8080"
    }
}

final class ProcessRunner {
    func run(_ command: InstallerCommand) throws {
        _ = try capture(executable: command.executable, arguments: command.arguments)
    }

    func capture(executable: String, arguments: [String]) throws -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        var environment = ProcessInfo.processInfo.environment
        var actualArguments = arguments
        while let first = actualArguments.first, first.contains("=") {
            let pieces = first.split(separator: "=", maxSplits: 1).map(String.init)
            if pieces.count == 2 {
                environment[pieces[0]] = pieces[1]
                actualArguments.removeFirst()
            } else {
                break
            }
        }
        process.environment = environment
        process.arguments = actualArguments

        let pipe = Pipe()
        let errorPipe = Pipe()
        process.standardOutput = pipe
        process.standardError = errorPipe
        try process.run()
        process.waitUntilExit()

        let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        let errorOutput = String(data: errorPipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        guard process.terminationStatus == 0 else {
            throw InstallerError.commandFailed(errorOutput.ifBlank(output).ifBlank("Exit \(process.terminationStatus)"))
        }
        return output
    }
}

enum InstallerError: LocalizedError {
    case commandFailed(String)

    var errorDescription: String? {
        switch self {
        case .commandFailed(let message): message
        }
    }
}

private extension String {
    func ifBlank(_ fallback: String) -> String {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self
    }
}
