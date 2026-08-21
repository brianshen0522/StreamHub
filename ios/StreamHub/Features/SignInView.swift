import SwiftUI

/// Signing in, once.
///
/// Laid out the way Apple's own sign-in sheets are: the mark, one line of
/// explanation, two fields, one prominent action. No server address — there is
/// one deployment and it is compiled in, so asking would be a setup step with
/// exactly one right answer.
struct SignInView: View {
    @Environment(AppModel.self) private var model

    @State private var login = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focus: Field?

    private enum Field { case login, password }

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 12) {
                Image(systemName: "play.tv.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(.tint)
                Text("StreamHub")
                    .font(.largeTitle.weight(.bold))
                Text("Sign in with your viewer account.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            // Grouped, with a rule between them, the way the system's own
            // sign-in sheets read. Without the divider the two fields look like
            // one control with two lines of placeholder text.
            VStack(spacing: 0) {
                TextField("Username", text: $login)
                    .textContentType(.username)
                    // Not .username-with-autocapitalisation: the Text keyboard
                    // capitalises the first letter, which silently turns
                    // "viewer" into "Viewer" and fails the sign-in with no
                    // visible cause.
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .login)
                    .submitLabel(.next)
                    .onSubmit { focus = .password }
                    .padding(.vertical, 12)

                Divider()

                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .focused($focus, equals: .password)
                    .submitLabel(.go)
                    .onSubmit { submit() }
                    .padding(.vertical, 12)
            }
            .textFieldStyle(.plain)
            .padding(.horizontal, 14)
            .background(.fill.tertiary, in: .rect(cornerRadius: 12))
            .padding(.top, 32)

            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.leading)
                    .padding(.top, 12)
            }

            Button(action: submit) {
                if busy {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text("Sign In").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(busy || login.isEmpty || password.isEmpty)
            .padding(.top, 20)

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 32)
        .frame(maxWidth: 460)
        .frame(maxWidth: .infinity)
        .onAppear { focus = .login }
    }

    private func submit() {
        guard !busy, !login.isEmpty, !password.isEmpty else { return }
        busy = true
        error = nil
        Task {
            do {
                let session = try await model.api.login(
                    login: login.trimmingCharacters(in: .whitespaces),
                    password: password
                )
                busy = false
                model.signedIn(session)
            } catch let failure as StreamHubError {
                busy = false
                error = failure.message
            } catch {
                busy = false
                self.error = "Could not reach the server."
            }
        }
    }
}
