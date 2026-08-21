import SwiftUI

/// The app's one structural decision: signed out is a screen, signed in is tabs.
///
/// Admin versus viewer is not a case here at all — the server refuses an admin
/// account at the sign-in screen because this client identifies itself, so
/// there is no signed-in-but-forbidden state to design for.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.session == nil {
            SignInView()
        } else {
            SignedInTabs()
        }
    }
}

private struct SignedInTabs: View {
    @Environment(AppModel.self) private var model
    @State private var cast = CastController()

    var body: some View {
        TabView {
            Tab("Watch Now", systemImage: "play.tv.fill") {
                NavigationStack { HomeView() }
            }
            Tab("Library", systemImage: "rectangle.stack.fill") {
                NavigationStack { LibraryView() }
            }
            Tab("Settings", systemImage: "gearshape.fill") {
                NavigationStack { SettingsView() }
            }
            Tab(role: .search) {
                NavigationStack { SearchView() }
            }
        }
        .environment(cast)
        .task { await cast.start(model: model) }
        // The strip that keeps a remote session reachable. Above the tab bar,
        // because leaving the remote screen is not the same as stopping it.
        .safeAreaInset(edge: .bottom) {
            if cast.target != nil {
                CastBar()
                    .environment(cast)
            }
        }
        .task {
            // The server marks a session live from this; without it a device
            // that is watching still reads as idle in the account's device list.
            while !Task.isCancelled {
                await model.api.heartbeat()
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }
}
