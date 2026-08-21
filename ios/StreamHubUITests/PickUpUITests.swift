import XCTest

/// The other direction: picking up what a different device left behind.
///
/// Run after another client has watched part of something on the same account.
/// What is being checked is not that the network works — it is that this app
/// and the Android one agree about *where you were*, which they only will if
/// their two copies of `ResumeRules` still say the same thing.
final class PickUpUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    func testUpNextCarriesAnotherDevicesProgress() throws {
        let app = XCUIApplication()
        app.launch()

        // The session is in the Keychain, so a launch lands straight on the
        // shelf unless the account was signed out elsewhere.
        if app.textFields["Username"].waitForExistence(timeout: 8) {
            app.textFields["Username"].tap()
            app.textFields["Username"].typeText("viewer")
            app.secureTextFields["Password"].tap()
            app.secureTextFields["Password"].typeText("viewerpass")
            app.buttons["Sign In"].tap()
        }
        XCTAssertTrue(app.navigationBars["Watch Now"].waitForExistence(timeout: 30))

        // MARK: Up Next

        let card = app.scrollViews.buttons.firstMatch
        XCTAssertTrue(
            card.waitForExistence(timeout: 30),
            "Up Next was empty; the other device's progress never arrived"
        )
        XCTAssertTrue(
            card.label.contains("Episode"),
            "the card did not say which episode resumes: \(card.label)"
        )
        capture(app, "pickup-01-up-next")

        // MARK: Open it and play

        app.coordinate(withNormalizedOffset: CGVector(dx: 0.21, dy: 0.30)).tap()

        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 90), "the detail screen never offered Play")
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: play)
        waitForExpectations(timeout: 120)
        capture(app, "pickup-02-detail")

        play.tap()
        // Long enough to have started, short enough that the clock on screen is
        // still recognisably the resume point rather than minutes past it.
        Thread.sleep(forTimeInterval: 9)
        capture(app, "pickup-03-resumed")
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
