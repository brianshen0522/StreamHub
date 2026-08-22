import XCTest

/// Second half of the standalone playback check, run against the clip the
/// install test left behind: one tap to resume, then let it run. The first
/// test's double-tap toggled play and then pause — five seconds of progress
/// proved the tap starts playback, and this proves it *keeps* playing.
final class StandaloneResumeUITests: XCTestCase {

    func testPlaybackKeepsRunning() throws {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let webapp = XCUIApplication(bundleIdentifier: "com.apple.webapp")

        // The clip may still be foreground from the previous test; going home
        // first makes the launch path the same either way.
        XCUIDevice.shared.press(.home)
        sleep(2)
        var launched = false
        for _ in 0..<6 {
            let icon = springboard.icons["StreamHub"].firstMatch
            if icon.exists && icon.isHittable {
                icon.tap()
                launched = true
                break
            }
            springboard.swipeLeft()
            sleep(2)
        }
        XCTAssertTrue(launched, "the installed app is not on the Home Screen")
        sleep(12)

        // One tap. Play and pause share the spot, so parity matters: the
        // previous test left it paused.
        webapp.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.28)).tap()
        sleep(45)

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "sr-01-after-45s"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(webapp.state == .runningForeground)
    }
}
