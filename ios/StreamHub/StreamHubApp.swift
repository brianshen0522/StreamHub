import SwiftUI

@main
struct StreamHubApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                // One brand decision, and the only one. Everything else is the
                // system's own material and typography — a custom-skinned
                // control set is what makes an app feel like a wrapped web page.
                .tint(.streamHubAccent)
                // Dark regardless of the system setting, which is what the TV
                // app does and for the same reason: artwork is the content, and
                // a light chrome around it changes how every poster reads. It
                // also keeps this client looking like the web and Android ones,
                // which are dark-only.
                .preferredColorScheme(.dark)
        }
    }
}
