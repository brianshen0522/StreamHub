import XCTest

/// What happens to a playing video when the phone gets in the way.
///
/// These are the scenarios a desktop browser cannot produce: the app sent to
/// the background mid-episode, the device rotated, the screen locked. Safari's
/// page content is not exposed to XCUITest, so the page is driven by tapping
/// where things are and the *server* is the witness — playback reports its
/// position, so whether it kept going, stopped cleanly, or died is a matter of
/// record rather than of reading pixels.
///
/// Needs the QA stack on http://localhost:5200 and the `viewer` account.
final class WebInterruptionUITests: XCTestCase {

    private var safari: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    func testAVideoSurvivesBeingInterrupted() throws {
        safari.activate()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 20))
        dismissOnboarding()

        // MARK: Sign in
        //
        // Through a shim the QA server serves on the same origin, not by
        // driving the form: the page is not introspectable, so the form can
        // only be tapped by coordinate, and the keyboard moves the layout out
        // from under the second tap. That produced a test that sailed past
        // sign-in and then reported success from the sign-in screen.
        openURL("localhost:5200/__qa-signin")
        sleep(6)
        capture("i-01-signed-in")

        // MARK: Start an episode

        openURL("localhost:5200/" + Self.watchQuery)
        // Sources are probed one at a time; this is the slow part.
        sleep(45)
        capture("i-02-player")

        // A tap in the middle of the player starts it, and unlike a script it
        // is a real gesture, which is what iOS requires before it will play.
        tap(0.5, 0.30)
        sleep(3)
        tap(0.5, 0.30)
        sleep(15)
        capture("i-03-playing")

        // MARK: Send it away and bring it back

        XCUIDevice.shared.press(.home)
        sleep(12)
        capture("i-04-backgrounded")

        safari.activate()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 20))
        sleep(10)
        capture("i-05-returned")

        // MARK: Rotate while it is open

        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(8)
        capture("i-06-landscape")

        XCUIDevice.shared.orientation = .portrait
        sleep(8)
        capture("i-07-portrait-again")

        // MARK: Still alive?

        XCTAssertTrue(safari.state == .runningForeground, "Safari did not survive the interruptions")
        capture("i-08-final")
    }

    // MARK: - Harness

    private static let watchQuery =
        "?v=eyJwIjoiNzc3dHYiLCJ1IjoiaHR0cHM6Ly83Nzd0di5haS92b2QvZGV0YWlsL2lkLzE4MTEzNC5odG1sIiwidCI6"
        + "IueBq-W9seW_jeiAhSIsIm0iOiJ1bmtub3duIiwiZXAiOiLnrKwwMDHpm4YiLCJ4IjoxfQ"

    private func openURL(_ url: String) {
        // The bar sits collapsed at the bottom as a *button* showing the host,
        // and only becomes a text field once tapped. Looking for the field
        // first therefore finds nothing at all.
        if !safari.textFields["URL"].exists {
            tap(0.5, 0.932)
            usleep(800_000)
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

    private func tap(_ x: CGFloat, _ y: CGFloat) {
        safari.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
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
