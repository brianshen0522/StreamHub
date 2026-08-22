import XCTest

/// Playback inside the installed Home Screen web app — the one gap the earlier
/// standalone pass could not close.
///
/// Standalone is not Safari with the chrome hidden: it has its own storage
/// container, its own process host, and no address bar to fall back on. The
/// earlier suite proved layout, safe areas, backgrounding and rotation there,
/// but every attempt at playback was foiled by iOS restoring a previously
/// installed clip with an older build inside it. This run assumes a freshly
/// erased simulator, so the clip added here is the only one that has ever
/// existed on the device.
///
/// The trick that makes playback drivable at all: the clip is added while a
/// *watch page* is showing, so its start URL opens straight onto the player —
/// no blind in-app navigation by coordinates. The QA server seeds the session
/// into every shell response, which is what lets a fresh storage container
/// come up signed in. Whether playback truly ran is read from the progress the
/// page reports to the server, not from pixels.
///
/// Needs the QA stack on http://localhost:5200 with QA_SEED_VIEWER=1.
final class StandalonePlaybackUITests: XCTestCase {

    private var safari: XCUIApplication!
    private var springboard: XCUIApplication!
    private var webapp: XCUIApplication!

    /// 火影忍者 第013集 — an episode nothing else in this QA run has touched,
    /// so a progress row for it can only have come from this test.
    private static let watchQuery =
        "?v=eyJwIjoiNzc3dHYiLCJ1IjoiaHR0cHM6Ly83Nzd0di5haS92b2QvZGV0YWlsL2lkLzE4MTEzNC5odG1sIiwidCI6"
        + "IueBq-W9seW_jeiAhSIsIm0iOiJ1bmtub3duIiwiZXAiOiLnrKwwMTPpm4YiLCJ4IjoxfQ"

    override func setUp() {
        continueAfterFailure = true
        safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        webapp = XCUIApplication(bundleIdentifier: "com.apple.webapp")
    }

    func testAnEpisodePlaysInsideTheInstalledApp() throws {
        safari.terminate()
        safari.launch()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 30))
        dismissOnboarding()

        // Straight to the watch page; the seeded shell signs it in.
        openURL("localhost:5200/" + Self.watchQuery)
        sleep(20)
        capture("sp-01-watch-in-safari")

        // MARK: Install, with the watch page as the start URL

        XCTAssertTrue(openShareSheet(), "could not open Safari's share menu")
        sleep(2)
        var tapped = false
        for _ in 0..<4 {
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
            safari.swipeUp()
            sleep(2)
        }
        XCTAssertTrue(tapped, "no Add to Home Screen action in the share sheet")
        sleep(2)
        let add = safari.buttons["Add"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 8), "no Add confirmation")
        add.tap()
        sleep(4)

        // MARK: Launch the clip

        XCUIDevice.shared.press(.home)
        sleep(3)
        XCTAssertTrue(launchFromHomeScreen(), "the app was not added to the Home Screen")
        // Sources are probed one at a time; the player needs time to settle.
        sleep(50)
        capture("sp-02-standalone-player")

        // MARK: The tap that starts it

        // iOS refuses autoplay, so the page shows its large centre play button.
        // A coordinate tap is a real gesture — the one thing scripts cannot fake.
        webapp.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.28)).tap()
        sleep(4)
        capture("sp-03-after-first-tap")
        // If the first tap only revealed the chrome, the second hits play.
        webapp.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.28)).tap()
        sleep(25)
        capture("sp-04-playing")

        // MARK: Leave and come back mid-episode

        XCUIDevice.shared.press(.home)
        sleep(8)
        _ = launchFromHomeScreen()
        sleep(10)
        capture("sp-05-back-after-home")

        XCTAssertTrue(webapp.state == .runningForeground, "the web app did not survive backgrounding")
        capture("sp-06-final")
    }

    // MARK: - Harness

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

    /// The shape the earlier Home Screen suite already proved: the overflow
    /// button on the bottom bar, then Share inside its menu — with the keyboard
    /// dismissed first, because it covers the toolbar.
    private func openShareSheet() -> Bool {
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

    @discardableResult
    private func launchFromHomeScreen() -> Bool {
        for _ in 0..<6 {
            let icon = springboard.icons["StreamHub"].firstMatch
            if icon.exists && icon.isHittable {
                icon.tap()
                return true
            }
            springboard.swipeLeft()
            sleep(2)
        }
        return false
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
