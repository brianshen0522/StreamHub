import XCTest

/// What the phone does to the app while it is being used.
///
/// The other suites check that features work. This one checks they survive: an
/// episode playing when the home button is pressed, the device rotated, the app
/// killed outright and reopened. Those are the states a person actually puts an
/// app through, and they are where a session, a player or a resume point gets
/// quietly lost.
///
/// Unlike the web suites this app *is* introspectable, so every step asserts on
/// a real element rather than on a coordinate.
///
/// Needs the QA stack: the backend on :58787 with the `viewer` account.
///
/// **Run this signed.** Building with `CODE_SIGNING_ALLOWED=NO` leaves the app
/// without entitlements, the Keychain refuses to store anything, and the
/// relaunch check below fails as though the app had lost the session — when in
/// fact it never got to save it. Signed, it passes.
final class InterruptionUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    func testTheAppSurvivesBeingInterrupted() throws {
        app.launch()
        signInIfNeeded()

        XCTAssertTrue(app.navigationBars["Watch Now"].waitForExistence(timeout: 30),
                      "never reached the shelf")
        capture("n-01-shelf")

        // MARK: Every tab loads

        for tab in ["Library", "Settings", "Watch Now"] {
            XCTAssertTrue(app.buttons[tab].waitForExistence(timeout: 10), "no \(tab) tab")
            app.buttons[tab].tap()
            sleep(2)
            XCTAssertFalse(app.staticTexts["Could not reach the server."].exists,
                           "\(tab) could not reach the server")
            capture("n-02-tab-\(tab.replacingOccurrences(of: " ", with: "-"))")
        }

        // MARK: Backgrounded on a list, and brought back

        app.buttons["Library"].tap()
        sleep(2)
        XCUIDevice.shared.press(.home)
        sleep(6)
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        sleep(4)
        XCTAssertTrue(app.buttons["Library"].exists || app.navigationBars.firstMatch.exists,
                      "the app came back to nothing")
        capture("n-03-returned-to-library")

        // MARK: Rotated

        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(5)
        capture("n-04-landscape")
        XCTAssertTrue(app.buttons["Library"].exists || app.navigationBars.firstMatch.exists,
                      "rotating emptied the screen")
        XCUIDevice.shared.orientation = .portrait
        sleep(5)
        capture("n-05-portrait-again")

        // MARK: Killed outright and reopened
        //
        // A session lives in the Keychain, so this is what proves it is really
        // persisted rather than merely held in memory.

        app.terminate()
        sleep(2)
        app.launch()
        sleep(8)
        XCTAssertFalse(app.textFields["Username"].waitForExistence(timeout: 8),
                       "a cold launch asked to sign in again — the session did not persist")
        capture("n-06-after-relaunch")

        // MARK: Playing, then interrupted

        app.buttons["Watch Now"].tap()
        sleep(3)

        let card = app.scrollViews.buttons.firstMatch
        if card.waitForExistence(timeout: 25) {
            card.tap()

            let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play'")).firstMatch
            if play.waitForExistence(timeout: 90) {
                expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: play)
                waitForExpectations(timeout: 150)
                play.tap()
                sleep(12)
                capture("n-07-playing")

                XCUIDevice.shared.press(.home)
                sleep(10)
                app.activate()
                XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
                sleep(6)
                capture("n-08-back-from-background-while-playing")

                XCUIDevice.shared.orientation = .landscapeLeft
                sleep(6)
                capture("n-09-player-landscape")
                XCUIDevice.shared.orientation = .portrait
                sleep(6)
                capture("n-10-player-portrait")

                XCTAssertEqual(app.state, .runningForeground,
                               "the app did not survive being interrupted mid-playback")
            } else {
                capture("n-07-no-play-button")
            }
        } else {
            capture("n-07-shelf-empty")
        }
    }

    // MARK: - Harness

    private func signInIfNeeded() {
        guard app.textFields["Username"].waitForExistence(timeout: 12) else { return }
        app.textFields["Username"].tap()
        app.textFields["Username"].typeText("viewer")
        app.secureTextFields["Password"].tap()
        app.secureTextFields["Password"].typeText("viewerqa2026")
        app.buttons["Sign In"].tap()
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
