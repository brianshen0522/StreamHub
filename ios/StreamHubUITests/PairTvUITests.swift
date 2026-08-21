import XCTest

/// Signing a television in from the phone by typing the code it is showing.
///
/// The test plays the television itself — it asks the server for a code the way
/// a set would, drives the phone through approving it, and then checks that the
/// set collects a session. Nothing has to be staged beforehand, and the last
/// assertion is the one that matters: the point of this screen is not that a
/// button was tapped, it is that a television ended up signed in.
///
/// The dullest-looking assertion is the one worth keeping. The Android version
/// of this screen reformatted the text on every keystroke, which moved the caret
/// somewhere the keyboard was not expecting and silently reordered what was
/// typed — "3vxja5wj" arrived as "3VXJ-5WJA". A code one transposition out fails
/// with a message about being expired, which sends people to look at the
/// television rather than at the field.
final class PairTvUITests: XCTestCase {

    /// Where the app under test points. Overridden by `SERVER` when the runner
    /// receives one; otherwise the local server these tests are written for.
    private var server: URL {
        let raw = ProcessInfo.processInfo.environment["SERVER"] ?? "http://localhost:58787"
        return URL(string: raw)!
    }

    override func setUp() {
        continueAfterFailure = false
    }

    func testTypingTheCodeSignsTheTelevisionIn() throws {
        // MARK: Be a television

        let pairing = try startPairing()
        let userCode = pairing.userCode.replacingOccurrences(of: "-", with: "")
        XCTAssertEqual(userCode.count, 8, "the server issued a code of an unexpected shape")

        let app = XCUIApplication()
        app.launch()

        if app.textFields["Username"].waitForExistence(timeout: 8) {
            app.textFields["Username"].tap()
            app.textFields["Username"].typeText("viewer")
            app.secureTextFields["Password"].tap()
            app.secureTextFields["Password"].typeText("Viewer!2026xyz")
            app.buttons["Sign In"].tap()
        }
        XCTAssertTrue(app.navigationBars["Watch Now"].waitForExistence(timeout: 30))

        // MARK: Settings → Connect a TV

        app.buttons["Settings"].tap()
        let entry = app.buttons["Connect a TV"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10), "Settings had no way to connect a TV")
        entry.tap()

        // MARK: Type it the way a person would

        let field = app.textFields["ABCD-EFGH"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the code field never appeared")
        field.tap()
        // Lower case and unseparated, which is what somebody reading eight
        // characters off a television actually types.
        field.typeText(userCode.lowercased())

        let typed = (field.value as? String ?? "").replacingOccurrences(of: "-", with: "")
        XCTAssertEqual(typed, userCode, "the field mangled the code as it was typed")
        capture("pair-01-code-entered")

        // MARK: What is asking

        app.buttons["Continue"].tap()
        let signIn = app.buttons["Sign it in"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 20), "the code was never looked up")
        // Matched across any element rather than on static text alone:
        // LabeledContent folds its label and value into one accessibility
        // element, so the name is not addressable on its own.
        let named = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS 'Kitchen TV'"))
            .firstMatch
        XCTAssertTrue(
            named.waitForExistence(timeout: 5),
            "the confirmation did not name the device that was asking"
        )
        capture("pair-02-confirm")

        // MARK: Grant it, and check the television actually got in

        signIn.tap()
        let done = app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS 'is signed in'")
        ).firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 20), "approving never confirmed")
        capture("pair-03-signed-in")

        let collected = try poll(deviceCode: pairing.deviceCode)
        XCTAssertEqual(collected, "approved", "the television never collected a session")
    }

    // MARK: - Playing the television

    private struct Pairing { let userCode: String; let deviceCode: String }

    private func startPairing() throws -> Pairing {
        var request = URLRequest(url: server.appendingPathComponent("/api/v1/auth/device/start"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // The name the phone will show. Sending it here is what makes the
        // approval screen able to say which device is asking.
        request.setValue("StreamHub-TV/0.1.0 (Kitchen TV; Android 14)", forHTTPHeaderField: "User-Agent")
        request.setValue("tv", forHTTPHeaderField: "X-StreamHub-Client")

        let body = try send(request)
        guard
            let json = try JSONSerialization.jsonObject(with: body) as? [String: Any],
            let userCode = json["userCode"] as? String,
            let deviceCode = json["deviceCode"] as? String
        else {
            throw XCTSkip("could not start a pairing against \(server) — is the server running?")
        }
        return Pairing(userCode: userCode, deviceCode: deviceCode)
    }

    private func poll(deviceCode: String) throws -> String {
        var request = URLRequest(url: server.appendingPathComponent("/api/v1/auth/device/poll"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["deviceCode": deviceCode])

        let body = try send(request)
        let json = try JSONSerialization.jsonObject(with: body) as? [String: Any]
        return json?["status"] as? String ?? "unknown"
    }

    /// URLSession, made synchronous, because XCTest reads better without a
    /// callback pyramid for two calls.
    private func send(_ request: URLRequest) throws -> Data {
        var result: Data?
        var failure: Error?
        let waiter = expectation(description: request.url?.lastPathComponent ?? "request")
        URLSession.shared.dataTask(with: request) { data, _, error in
            result = data
            failure = error
            waiter.fulfill()
        }.resume()
        wait(for: [waiter], timeout: 30)
        if let failure { throw failure }
        guard let result else { throw XCTSkip("no response from \(server)") }
        return result
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
