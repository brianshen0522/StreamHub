import XCTest

/// The console held on its side, which is the cheapest place to see a real
/// safe-area inset.
///
/// In portrait Safari the insets read zero — the browser's own chrome covers
/// the status bar — so a layout that ignores them looks identical to one that
/// does not, which is how the console's missing top inset survived. Turned on
/// its side, a notched phone reports a genuine left and right inset to any page
/// declaring `viewport-fit=cover`, and the console now pays those. This is the
/// evidence that the tokens reach the console's own rules on a device, rather
/// than only when the values are set by hand in a desktop browser.
///
/// Needs the QA stack on http://localhost:5200 and an administrator session.
final class AdminLandscapeUITests: XCTestCase {

    private var safari: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    func testTheConsoleClearsTheNotchOnItsSide() throws {
        safari.terminate()
        safari.launch()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 20))
        dismissOnboarding()

        openURL("localhost:5200/__qa-admin")
        sleep(8)
        capture("al-01-portrait")

        // Each page is loaded upright and only then turned. Safari's address
        // bar moves in landscape and is not reliably reachable there, so
        // navigating while on its side is a good way to lose the session and
        // report success from whatever page happened to be showing.
        for (name, path) in [("dashboard", "/admin"),
                             ("users", "/admin/users"),
                             ("audit", "/admin/audit")] {
            if path != "/admin" {
                openURL("localhost:5200" + path)
                sleep(6)
            }
            XCUIDevice.shared.orientation = .landscapeLeft
            sleep(6)
            capture("al-landscape-\(name)")
            XCUIDevice.shared.orientation = .portrait
            sleep(4)
        }

        capture("al-99-portrait-again")
        XCTAssertTrue(safari.state == .runningForeground)
    }

    // MARK: - Harness

    private func openURL(_ url: String) {
        // On a phone the bar sits collapsed at the bottom as a button showing
        // the host, and only becomes a text field once tapped — so looking for
        // the field first finds nothing. In landscape it moves, hence the
        // search rather than a fixed coordinate.
        if !safari.textFields["URL"].exists {
            for spot in [CGVector(dx: 0.5, dy: 0.932), CGVector(dx: 0.5, dy: 0.95),
                         CGVector(dx: 0.5, dy: 0.06)] {
                safari.coordinate(withNormalizedOffset: spot).tap()
                usleep(900_000)
                if safari.textFields["URL"].exists { break }
            }
        }
        let field = safari.textFields["URL"]
        XCTAssertTrue(field.waitForExistence(timeout: 15), "no address field")
        field.tap()
        if safari.buttons["Clear text"].waitForExistence(timeout: 3) {
            safari.buttons["Clear text"].tap()
        }
        field.typeText("\(url)\n")
        sleep(3)
    }

    private func dismissOnboarding() {
        for _ in 0..<4 {
            let labels = ["Close", "close", "Continue", "Not Now", "Dismiss", "Done", "Start Browsing"]
            guard let button = labels
                .map({ safari.buttons[$0] })
                .first(where: { $0.exists && $0.isHittable })
            else { break }
            button.tap()
            usleep(400_000)
        }
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
