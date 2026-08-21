import XCTest

/// Leaves the account in a known state for another client to pick up.
///
/// The two phone apps never drive each other — neither announces itself as a
/// receiver, only a television does — so what has to agree between them is the
/// account: what is saved, how far a title was watched, and which episode
/// resumes. Those last two matter most, because `ResumeRules` exists three
/// times over (here, in `android/core`, and in the web player) and a
/// divergence would show up as two devices disagreeing about where you were.
///
/// This half saves a title and builds up real watch progress. The Android half
/// then checks it reads the same thing back.
final class HandoffUITests: XCTestCase {

    /// Long enough for the player to have reported progress at least twice —
    /// it writes every fifteen seconds — and far enough in that the resume
    /// rewind is visible rather than clamped at zero.
    private let watchSeconds: TimeInterval = 75

    override func setUp() {
        continueAfterFailure = false
    }

    func testSaveATitleAndWatchPartOfIt() {
        let app = XCUIApplication()
        app.launch()

        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 20))
        username.tap()
        username.typeText("viewer")
        app.secureTextFields["Password"].tap()
        app.secureTextFields["Password"].typeText("viewerpass")
        app.buttons["Sign In"].tap()
        XCTAssertTrue(app.navigationBars["Watch Now"].waitForExistence(timeout: 30))

        // MARK: Find a title

        let search = app.buttons["Search"]
        XCTAssertTrue(search.waitForExistence(timeout: 20))
        search.tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        field.tap()
        field.typeText("love\n")

        let firstPoster = app.scrollViews.buttons.firstMatch
        XCTAssertTrue(firstPoster.waitForExistence(timeout: 90), "no search results arrived")
        // iOS 26 draws a dimming overlay over the top edge of a scroll view and
        // the first shelf sits partly under it, which is enough for XCUITest to
        // refuse an element tap on a card a finger reaches fine.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.21, dy: 0.30)).tap()

        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 90), "the detail screen never offered Play")
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: play)
        waitForExpectations(timeout: 120)

        // MARK: Save it

        let add = app.buttons["Add to Library"]
        XCTAssertTrue(add.waitForExistence(timeout: 15), "the detail screen had no save control")
        add.tap()
        XCTAssertTrue(
            app.buttons["Remove from Library"].waitForExistence(timeout: 20),
            "saving did not take"
        )
        capture(app, "handoff-01-saved")

        // MARK: Watch some of it

        play.tap()
        Thread.sleep(forTimeInterval: watchSeconds)
        capture(app, "handoff-02-watched")

        // Leaving the player is what writes the closing position, so the run
        // has to come back out rather than simply end.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let done = app.buttons["Done"].firstMatch
        if done.waitForExistence(timeout: 10) {
            done.tap()
        } else {
            app.swipeDown(velocity: .fast)
        }
        XCTAssertTrue(
            play.waitForExistence(timeout: 30),
            "the player did not return to the detail screen"
        )
        Thread.sleep(forTimeInterval: 3)
        capture(app, "handoff-03-back")
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
