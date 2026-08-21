import XCTest

/// Drives the app the way a person would, and captures each screen on the way.
///
/// This exists because `simctl` has no input injection — unlike `adb`, there is
/// no way to tap or type from outside the simulator, and AppleScript needs an
/// Accessibility grant a build machine will not have. XCUITest runs beside the
/// app and needs neither, so it is the only way to check that these screens
/// actually work rather than merely compile.
///
/// The screenshots are written to a host path. Simulator processes are ordinary
/// macOS processes, so an absolute path outside the container resolves.
final class WalkthroughUITests: XCTestCase {

    private let shots = ProcessInfo.processInfo.environment["STREAMHUB_SHOT_DIR"]

    override func setUp() {
        continueAfterFailure = false
    }

    func testSignInBrowseAndOpenATitle() {
        let app = XCUIApplication()
        app.launch()

        // MARK: Sign in

        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 20), "the sign-in screen never appeared")
        username.tap()
        username.typeText("viewer")

        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText("viewerpass")

        capture(app, "01-signin")

        app.buttons["Sign In"].tap()

        // MARK: Watch Now

        let watchNow = app.navigationBars["Watch Now"]
        XCTAssertTrue(watchNow.waitForExistence(timeout: 30), "sign-in did not reach Watch Now")
        capture(app, "02-watch-now")

        // MARK: Library and Settings
        //
        // Visited before Search on purpose. iOS 26 collapses the tab bar into
        // the floating search field while the search tab is active, so the
        // other tabs are simply not there to tap afterwards.

        tapTab(app, "Library")
        XCTAssertTrue(app.navigationBars["Library"].waitForExistence(timeout: 20))
        capture(app, "03-library")

        tapTab(app, "Settings")
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 20))
        capture(app, "04-settings")

        // MARK: Search

        tapTab(app, "Search")
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the search field never appeared")
        field.tap()
        field.typeText("love\n")

        // Scraping three providers takes a while; the first shelf appearing is
        // the signal, not a fixed sleep.
        let firstPoster = app.scrollViews.buttons.firstMatch
        XCTAssertTrue(firstPoster.waitForExistence(timeout: 90), "no search results arrived")
        capture(app, "05-search")

        // MARK: Detail

        // Tapped through the window's own coordinate space, without resolving
        // the card at all. iOS 26 draws an `AdditionalDimmingOverlay` over the
        // top edge of a scroll view and the first shelf sits partly under it,
        // which is enough for XCUITest to refuse any element-based interaction
        // with the card even though a finger lands on it perfectly well.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.21, dy: 0.30)).tap()

        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 90), "the detail screen never offered Play")
        // Sources arrive one at a time over NDJSON; Play is only real once one
        // has landed, so the button existing is not the same as it working.
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: play)
        waitForExpectations(timeout: 120)
        capture(app, "06-detail")

        // MARK: Playback
        //
        // The point of the app. A real HLS stream through the ad-stripping
        // manifest endpoint, played by AVPlayerViewController — its transport
        // appearing is the proof that the asset loaded rather than failing to
        // an empty black screen.
        play.tap()
        let transport = app.buttons["Play"].firstMatch
        let scrubber = app.otherElements.matching(
            NSPredicate(format: "identifier CONTAINS[c] 'Scrubber' OR label CONTAINS[c] 'Playback'")
        ).firstMatch
        XCTAssertTrue(
            transport.waitForExistence(timeout: 60) || scrubber.waitForExistence(timeout: 60),
            "the player never presented its transport"
        )
        // Long enough to have buffered and started, not just laid out.
        Thread.sleep(forTimeInterval: 12)
        capture(app, "07-player")
    }

    /// Taps a tab by its label, wherever the system decided to put it.
    private func tapTab(_ app: XCUIApplication, _ label: String) {
        let tab = app.buttons[label]
        XCTAssertTrue(tab.waitForExistence(timeout: 20), "no \(label) tab")
        tab.tap()
    }

    /// Taps the centre of a frame, in window coordinates.
    private func tap(_ app: XCUIApplication, at frame: CGRect) {
        app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: frame.midX, dy: frame.midY))
            .tap()
    }

    /// Saves a screenshot both as a test attachment and, when a directory was
    /// passed in, as a plain file that can be looked at without opening Xcode.
    private func capture(_ app: XCUIApplication, _ name: String) {
        let shot = XCUIScreen.main.screenshot()

        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)

        guard let shots else { return }
        let url = URL(fileURLWithPath: shots).appendingPathComponent("\(name).png")
        try? shot.pngRepresentation.write(to: url)
    }
}
