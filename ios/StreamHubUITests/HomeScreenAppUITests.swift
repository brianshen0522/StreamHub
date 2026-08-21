import XCTest

/// The web app once it is installed to the Home Screen.
///
/// Worth its own pass because standalone is not Safari with the chrome hidden:
/// there is no address bar to absorb the status bar, the safe areas are the
/// app's own problem, and being sent to the background goes through a different
/// path than a browser tab. This is what an iPhone user actually has.
///
/// Needs the QA stack on http://localhost:5200.
final class HomeScreenAppUITests: XCTestCase {

    private var safari: XCUIApplication!
    private var springboard: XCUIApplication!
    /// A Home Screen web app runs inside iOS's web app host, not SpringBoard
    /// and not Safari.
    private var webapp: XCUIApplication!

    private static let watchQuery =
        "?v=eyJwIjoiNzc3dHYiLCJ1IjoiaHR0cHM6Ly83Nzd0di5haS92b2QvZGV0YWlsL2lkLzE4MTEzNC5odG1sIiwidCI6"
        + "IueBq-W9seW_jeiAhSIsIm0iOiJ1bmtub3duIiwiZXAiOiLnrKwwMDHpm4YiLCJ4IjoxfQ"

    override func setUp() {
        continueAfterFailure = true
        safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        webapp = XCUIApplication(bundleIdentifier: "com.apple.webapp")
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    func testInstallToHomeScreenAndUseIt() throws {
        safari.activate()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 20))
        dismissOnboarding()

        // Signed in first, so the installed app opens on the real thing rather
        // than on a sign-in screen.
        openURL("localhost:5200/__qa-app")
        sleep(6)
        capture("h-01-signed-in-safari")

        // MARK: Add to Home Screen

        let addedAlready = springboardHasStreamHub()
        if !addedAlready {
            XCTAssertTrue(openShareSheet(), "could not open Safari's share menu")
            sleep(2)
            capture("h-02-share-sheet")

            // The action sits well down the share sheet's list, so the sheet is
            // scrolled until it appears rather than assumed to be visible.
            var tapped = false
            for attempt in 0..<4 {
                for candidate in [safari.buttons["Add to Home Screen"].firstMatch,
                                  safari.cells["Add to Home Screen"].firstMatch,
                                  safari.staticTexts["Add to Home Screen"].firstMatch] {
                    if candidate.exists && candidate.isHittable {
                        candidate.tap()
                        tapped = true
                        break
                    }
                }
                if tapped { break }
                capture("h-03-share-scroll-\(attempt)")
                safari.swipeUp()
                sleep(2)
            }
            XCTAssertTrue(tapped, "no Add to Home Screen action in the share sheet")
            sleep(2)
            capture("h-04-add-dialog")

            let add = safari.buttons["Add"].firstMatch
            XCTAssertTrue(add.waitForExistence(timeout: 8), "no Add confirmation")
            add.tap()
            sleep(4)
        }

        // MARK: Launch it from the Home Screen

        XCUIDevice.shared.press(.home)
        sleep(3)
        capture("h-05-home-screen")

        // A newly added web app lands on the last Home Screen page, where its
        // icon exists in the tree with a zero frame until that page is on
        // screen — so it is paged to rather than tapped blind.
        XCTAssertTrue(launchFromHomeScreen(), "the app was not added to the Home Screen")
        sleep(10)
        capture("h-06-standalone-launched")

        // MARK: Look at it, then interrupt it

        // Standalone has no browser chrome, so the app's own layout is the only
        // thing keeping content out of the status bar and off the home
        // indicator. These frames are the evidence.
        capture("h-07-standalone-top-and-bottom")

        XCUIDevice.shared.press(.home)
        sleep(8)
        _ = launchFromHomeScreen()
        sleep(8)
        capture("h-08-standalone-after-background")

        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(6)
        capture("h-09-standalone-landscape")
        XCUIDevice.shared.orientation = .portrait
        sleep(6)
        capture("h-10-standalone-portrait")
    }

    // MARK: - Harness






    @discardableResult
    private func launchFromHomeScreen() -> Bool {
        for page in 0..<6 {
            let icon = springboard.icons["StreamHub"].firstMatch
            if icon.exists && icon.isHittable {
                icon.tap()
                return true
            }
            capture("h-05-home-page-\(page)")
            springboard.swipeLeft()
            sleep(2)
        }
        return false
    }

    private func springboardHasStreamHub() -> Bool {
        XCUIDevice.shared.press(.home)
        sleep(2)
        let there = springboard.icons["StreamHub"].firstMatch.waitForExistence(timeout: 4)
        safari.activate()
        sleep(2)
        return there
    }

    /// Two steps, not one. The "…" button opens Safari's own menu; "Add to
    /// Home Screen" lives in the *system* share sheet, which that menu's Share
    /// item opens. Looking for it straight after the first tap finds nothing.
    private func openShareSheet() -> Bool {
        // The keyboard from the address bar covers the toolbar, so it goes first.
        if safari.keyboards.element.exists {
            safari.typeText("\n")
            sleep(2)
        }

        let overflow = ["TabOverflowButton", "More", "Menu"]
            .map { safari.buttons[$0].firstMatch }
            .first { $0.exists && $0.isHittable }
        if let overflow {
            overflow.tap()
        } else {
            safari.coordinate(withNormalizedOffset: CGVector(dx: 0.87, dy: 0.932)).tap()
        }
        sleep(2)

        let share = safari.buttons["Share"].firstMatch
        guard share.waitForExistence(timeout: 6) else { return false }
        share.tap()
        sleep(3)
        return true
    }

    private func openURL(_ url: String) {
        if !safari.textFields["URL"].exists {
            safari.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.932)).tap()
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
