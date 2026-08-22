import SwiftUI

/// Signing a television in from the phone.
///
/// Two ways in, because they suit different moments rather than being a first
/// choice and a fallback: point the camera at the set, or type the eight
/// characters when the camera will not cooperate, when the set is in another
/// room, or when somebody read the code out over the phone.
///
/// Three steps rather than one button, and the middle one is the point. A
/// device flow cannot stop somebody being talked into approving a code that is
/// not theirs; the only defence is that the person sees what is asking and can
/// recognise whether it is their own television. So the device is named, and
/// what it is about to be given is spelled out, before anything is granted.
struct PairTvView: View {
    @Environment(AppModel.self) private var model

    @State private var code = ""
    @State private var pending: PendingDevice?
    @State private var outcome: Outcome?
    @State private var busy = false
    @State private var scanning = false
    @State private var error: String?
    @FocusState private var focused: Bool

    private enum Outcome { case signedIn(String), refused }

    var body: some View {
        List {
            if let outcome {
                finished(outcome)
            } else if let pending {
                confirm(pending)
            } else {
                entry
            }

            if let error {
                Section {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Connect a TV")
        .navigationBarTitleDisplayMode(.inline)
        // No automatic focus: it would open the keyboard over a screen whose
        // first offer is to point the camera at the television instead.
    }

    private var entry: some View {
        Section {
            if scanning {
                QrScannerView { found in
                    scanning = false
                    code = UserCode.forDisplay(found)
                    lookUp()
                }
                .listRowInsets(EdgeInsets())
                Button("Type it instead") { scanning = false }
            } else {
                Button {
                    error = nil
                    scanning = true
                } label: {
                    Label("Scan the code", systemImage: "qrcode.viewfinder")
                }
            }

            TextField("ABCD-EFGH", text: $code)
                // Only ever *removes*: uppercases, drops anything that is not a
                // code character, and stops at eight. The separator is never
                // written in while they type. Inserting one mid-string moves the
                // caret to somewhere the keyboard is not expecting, and the
                // character typed at the boundary is lost — "5EH5XHS3" arrived
                // as "5EH5HS3", which then fails as an expired code and sends
                // people to look at the television rather than at the field.
                // The grouped form is on the placeholder and on the next screen.
                .onChange(of: code) { _, value in
                    let tidy = String(UserCode.normalise(value).prefix(UserCode.length))
                    if tidy != value { code = tidy }
                }
                .font(.system(.title2, design: .monospaced).weight(.bold))
                .multilineTextAlignment(.center)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .textContentType(.oneTimeCode)
                .focused($focused)
                .submitLabel(.go)
                .onSubmit { lookUp() }

            Button(busy ? "Checking…" : "Continue") { lookUp() }
                .disabled(busy || !UserCode.isComplete(code))
        } header: {
            Text("The code on your television")
        } footer: {
            Text("Enter the code your television is showing, instead of typing a password on a remote.")
        }
    }

    private func confirm(_ device: PendingDevice) -> some View {
        Section {
            LabeledContent("Device") { Text(device.deviceName) }
            LabeledContent("Code") {
                Text(device.userCode).font(.system(.body, design: .monospaced))
            }

            Button(busy ? "Signing in…" : "Sign it in") { decide(approve: true) }
                .disabled(busy)
            Button("Not my device", role: .destructive) { decide(approve: false) }
                .disabled(busy)
        } header: {
            Text("Sign this device in to your account?")
        } footer: {
            Text("It gets everything you have: your history, your favourites, and every provider you can search. Only continue if the device is in front of you and showing exactly this code.")
        }
    }

    private func finished(_ outcome: Outcome) -> some View {
        Section {
            switch outcome {
            case .signedIn(let name):
                Label("\(name) is signed in.", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            case .refused:
                Label("That code no longer works, and nothing was given access.",
                      systemImage: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            Button("Connect another") { startOver() }
        }
    }

    private func lookUp() {
        guard UserCode.isComplete(code) else {
            error = "That code is eight characters long."
            return
        }
        busy = true
        error = nil
        Task {
            do {
                pending = try await model.api.pendingDevice(code: code)
                focused = false
            } catch let failure as StreamHubError {
                // Expired, already used and never existed all arrive as 404, and
                // all mean the same thing here: fetch a fresh code.
                error = (failure.status == 404 || failure.status == 400)
                    ? "That code has expired or was already used. Get a fresh one on your television."
                    : failure.message
            } catch {
                self.error = "Could not reach the server."
            }
            busy = false
        }
    }

    private func decide(approve: Bool) {
        guard let device = pending else { return }
        busy = true
        error = nil
        Task {
            do {
                if approve {
                    try await model.api.approveDevice(code: device.userCode)
                    outcome = .signedIn(device.deviceName)
                } else {
                    try await model.api.denyDevice(code: device.userCode)
                    outcome = .refused
                }
                pending = nil
            } catch let failure as StreamHubError {
                error = failure.message
                pending = nil
            } catch {
                self.error = "Could not reach the server."
                pending = nil
            }
            busy = false
        }
    }

    private func startOver() {
        code = ""
        pending = nil
        outcome = nil
        error = nil
        focused = true
    }
}
