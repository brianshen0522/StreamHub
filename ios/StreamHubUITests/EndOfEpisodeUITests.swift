import XCTest

/// What happens when an episode runs out.
///
/// Needs the account seeded so the title resumes shortly before its end —
/// waiting out a twenty-four minute episode is not a test, it is a coffee
/// break. The harness that runs this sets the position; without it the wait
/// times out and the test says so rather than passing vacuously.
final class EndOfEpisodeUITests: XCTestCase {

    /// Generous: the resume lands roughly two minutes from the end, and the
    /// stream has to open first.
    private let untilTheEnd: TimeInterval = 210

    override func setUp() {
        continueAfterFailure = false
    }

    func testFinishingOffersTheNextEpisodeAndLeavesWhenDeclined() {
        let app = XCUIApplication()
        app.launch()

        if app.textFields["Username"].waitForExistence(timeout: 8) {
            app.textFields["Username"].tap()
            app.textFields["Username"].typeText("viewer")
            app.secureTextFields["Password"].tap()
            app.secureTextFields["Password"].typeText("viewerpass")
            app.buttons["Sign In"].tap()
        }
        XCTAssertTrue(app.navigationBars["Watch Now"].waitForExistence(timeout: 30))

        // The seeded title is the only thing in Up Next.
        let card = app.scrollViews.buttons.firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 30), "Up Next was empty; seed progress first")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.21, dy: 0.30)).tap()

        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 90), "the detail screen never offered Play")
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: play)
        waitForExpectations(timeout: 120)
        play.tap()

        // MARK: The end

        let prompt = app.staticTexts["Up Next"]
        XCTAssertTrue(
            prompt.waitForExistence(timeout: untilTheEnd),
            "the episode ended without offering anything"
        )
        capture(app, "end-01-prompt")

        // MARK: Declining leaves
        //
        // The whole point: a finished episode must not sit on its last frame
        // inside a full-screen cover whose only remaining control is a swipe.
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(
            play.waitForExistence(timeout: 20),
            "declining did not return to the title"
        )
        capture(app, "end-02-back-on-detail")
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
