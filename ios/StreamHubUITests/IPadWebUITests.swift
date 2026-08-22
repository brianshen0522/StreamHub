import XCTest

/// The web app on an iPad, driven by real touch.
///
/// Separate from the phone suite because Safari's chrome is not the same shape:
/// on a phone the address bar is a button near the bottom that becomes a field
/// when tapped, and on an iPad it is a text field labelled "Address" in the top
/// toolbar. The phone helper looks for a field identified as "URL" near the
/// bottom and finds nothing at all.
///
/// This exists because nothing else here can produce a real gesture. Safari's
/// page content is invisible to XCUITest and safaridriver's synthesised taps do
/// not land on an iPad session, so a tap is the one thing only this can do — and
/// iOS will not start a video without one. Whether it worked is read afterwards
/// from the progress the browser reported to the server, not from the pixels.
///
/// Needs the QA stack on http://localhost:5200 and the `viewer` account.
final class IPadWebUITests: XCTestCase {

    private var safari: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    func testAnEpisodePlaysAndSurvivesInterruptionOnAnIPad() throws {
        safari.activate()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 20))
        dismissOnboarding()

        openURL("localhost:5200/__qa-signin")
        sleep(6)
        capture("ipad-01-signed-in")

        openURL("localhost:5200/" + Self.watchQuery)
        // Sources are probed one at a time, and this is the slow part.
        sleep(50)
        capture("ipad-02-player")

        // The real gesture. iOS refuses to start a video without one, which is
        // the whole reason this test exists rather than a scripted one.
        // Once, not twice: a second tap is a second toggle, which pauses what
        // the first one started and leaves the interruption below testing a
        // video that was already stopped.
        tap(0.54, 0.305)
        sleep(30)
        capture("ipad-03-playing")

        XCUIDevice.shared.press(.home)
        sleep(12)
        capture("ipad-04-backgrounded")

        safari.activate()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 20))
        sleep(12)
        capture("ipad-05-returned")

        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(10)
        capture("ipad-06-landscape")

        XCUIDevice.shared.orientation = .portrait
        sleep(10)
        capture("ipad-07-portrait-again")

        XCTAssertTrue(safari.state == .runningForeground,
                      "Safari did not survive the interruptions")
        capture("ipad-08-final")
    }

    // MARK: - Harness

    // Episode 008, so the position it leaves behind cannot be confused with
    // anything an earlier pass recorded.
    private static let watchQuery =
        "?v=eyJwIjoiNzc3dHYiLCJ1IjoiaHR0cHM6Ly83Nzd0di5haS92b2QvZGV0YWlsL2lkLzE4MTEzNC5odG1sIiwidCI6"
        + "IueBq-W9seW_jeiAhSIsIm0iOiJ1bmtub3duIiwiZXAiOiLnrKwwMDjpm4YiLCJ4IjoxfQ"

    /// Types an address into Safari's bar and goes there.
    ///
    /// The element the tap lands on is not the element that ends up with the
    /// keyboard: several text fields on an iPad carry the label "Address", and
    /// the hittable one is the tab bar's title container, which hands focus to
    /// an editor that only appears afterwards. Typing at the *application*
    /// rather than at an element sidesteps the question — it goes wherever the
    /// keyboard actually is.
    private func openURL(_ url: String) {
        safari.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.039)).tap()
        sleep(2)
        if safari.buttons["Clear text"].waitForExistence(timeout: 3) {
            safari.buttons["Clear text"].tap()
        }
        safari.typeText("\(url)\n")
        sleep(4)
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
