import XCTest

/// Drives a real television from the iPhone.
///
/// Needs a television signed in to the same account and connected — the
/// Android TV app on an emulator does the job. Skipped rather than failed when
/// there is none, because "no television was switched on" is not a defect in
/// this app.
final class CastUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    func testCastATitleToATelevisionAndDriveIt() throws {
        let app = XCUIApplication()
        app.launch()

        signIn(app)

        // MARK: Find something to cast

        tapTab(app, "Search")
        let field = app.searchFields.firstMatch
        // On the system search tab the first tap only selects it; the field
        // expands on a second tap of the same control.
        if !field.waitForExistence(timeout: 6) {
            tapTab(app, "Search")
        }
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        field.tap()
        field.typeText("love\n")

        let firstPoster = app.scrollViews.buttons.firstMatch
        XCTAssertTrue(firstPoster.waitForExistence(timeout: 90), "no search results arrived")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.21, dy: 0.30)).tap()

        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 90), "the detail screen never offered Play")
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: play)
        waitForExpectations(timeout: 120)

        // MARK: Pick the television

        // The cast button only exists when a receiver is connected — that is
        // the whole design — so its absence means no television is listening
        // rather than a broken screen.
        let cast = app.buttons["airplayvideo"]
        try XCTSkipUnless(
            cast.waitForExistence(timeout: 20),
            "no television is connected to this account; start the TV app first"
        )
        capture(app, "cast-01-detail")

        cast.tap()
        XCTAssertTrue(app.navigationBars["Devices"].waitForExistence(timeout: 10))

        let television = app.buttons
            .matching(NSPredicate(format: "label CONTAINS[c] 'atv' OR label CONTAINS[c] 'Android'"))
            .firstMatch
        XCTAssertTrue(television.waitForExistence(timeout: 10), "the picker did not list the television")
        capture(app, "cast-02-picker")
        television.tap()

        // MARK: Send it there

        // Play now says where it is going, which is the only visible sign that
        // the next tap will not open a player on this phone.
        let playOnTV = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play on'")).firstMatch
        XCTAssertTrue(playOnTV.waitForExistence(timeout: 15), "Play never acknowledged the television")
        playOnTV.tap()

        // MARK: The remote

        // The bar appears only once the receiver reports back, so its arrival
        // is the proof that the television accepted the command.
        let bar = app.buttons
            .matching(NSPredicate(format: "label CONTAINS[c] 'atv' OR label CONTAINS[c] 'Android'"))
            .firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 60), "the television never reported back")
        // Long enough for a stream to open and the position to start moving.
        Thread.sleep(forTimeInterval: 20)
        capture(app, "cast-03-bar")

        bar.tap()
        XCTAssertTrue(
            app.buttons["Play on iPhone"].waitForExistence(timeout: 15),
            "the remote never opened"
        )
        Thread.sleep(forTimeInterval: 6)
        capture(app, "cast-04-remote")

        // MARK: Drive it
        //
        // Paused here at the end on purpose: the television keeps whatever
        // state it was left in, so a screenshot taken afterwards from outside
        // this test can show that the command actually landed.
        // By identifier, because the bar behind this sheet carries the same
        // glyph and matching on that alone finds both.
        let transport = app.buttons["remotePlayPause"]
        XCTAssertTrue(transport.waitForExistence(timeout: 20), "the remote showed no transport control")
        XCTAssertEqual(transport.label, "Pause", "the television was not reported as playing")
        transport.tap()

        // The label only flips once the television reports back that it
        // actually paused, so this is a full round trip and not a local echo.
        expectation(
            for: NSPredicate(format: "label == %@", "Resume"),
            evaluatedWith: transport
        )
        waitForExpectations(timeout: 30)
        Thread.sleep(forTimeInterval: 4)
        capture(app, "cast-05-paused")
    }

    // MARK: - Helpers

    private func signIn(_ app: XCUIApplication) {
        let username = app.textFields["Username"]
        guard username.waitForExistence(timeout: 20) else { return }
        username.tap()
        username.typeText("viewer")
        app.secureTextFields["Password"].tap()
        app.secureTextFields["Password"].typeText("viewerpass")
        app.buttons["Sign In"].tap()
        XCTAssertTrue(
            app.navigationBars["Watch Now"].waitForExistence(timeout: 30),
            "sign-in did not reach Watch Now"
        )
    }

    private func tapTab(_ app: XCUIApplication, _ label: String) {
        let tab = app.buttons[label]
        XCTAssertTrue(tab.waitForExistence(timeout: 20), "no \(label) tab")
        tab.tap()
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
