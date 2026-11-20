import AppKit
import Foundation
import WebKit

private let musicURL = URL(string: "https://music.youtube.com/")!
private let signInURL = URL(string:
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
    "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
)!
private let musicHost = "music.youtube.com"
private let youtubeHosts: Set<String> = [musicHost, "youtube.com", "www.youtube.com", "m.youtube.com"]
private let allowedHosts = youtubeHosts.union([
    "consent.youtube.com",
    "accounts.google.com", "consent.google.com", "www.google.com", "www.recaptcha.net",
])
private let signingCookies: Set<String> = ["SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID"]
private let sessionPrefix = "OpenTune-YouTube-Session-v1 "
private let signedInScript = "typeof ytcfg !== 'undefined' && ytcfg.get('LOGGED_IN') === true"

private func trusted(_ url: URL) -> Bool {
    url.scheme == "https" && url.user == nil && url.password == nil &&
        (url.port == nil || url.port == 443) && allowedHosts.contains(url.host?.lowercased() ?? "")
}

private func cookieHeader(_ cookies: [HTTPCookie]) -> String? {
    // Match the cookies a request to the root of music.youtube.com would receive.
    let usable = cookies.filter { cookie in
        let domain = cookie.domain.lowercased()
        let name = cookie.name
        let value = cookie.value
        return (domain == ".youtube.com" || domain == ".music.youtube.com" || domain == musicHost) &&
            cookie.path == "/" && (cookie.expiresDate.map { $0 > Date() } ?? true) &&
            !name.isEmpty && name.utf8.allSatisfy { byte in
                byte > 0x20 && byte < 0x7f && !"()<>@,;:\\\"/[]?={}".utf8.contains(byte)
            } &&
            !value.isEmpty && value.utf8.allSatisfy { byte in byte > 0x20 && byte < 0x7f && byte != 0x3b }
    }
    guard usable.contains(where: { signingCookies.contains($0.name) }) else { return nil }
    let header = usable.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    return header.utf8.count <= 32_768 ? header : nil
}

private func sessionFrame(_ header: String) -> Data {
    Data("\(sessionPrefix)\(Data(header.utf8).base64EncodedString())\n".utf8)
}

private final class LoginWindow: NSObject, NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate, WKHTTPCookieStoreObserver {
    private let browser: WKWebView
    private let window: NSWindow
    private let originLabel = NSTextField(labelWithString: "https://accounts.google.com")
    private var finished = false
    private var eligiblePage = false
    private var pageRevision = 0
    private var sentToMusic = false

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        browser = WKWebView(frame: .zero, configuration: configuration)
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 900, height: 720),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        window.title = "OpenTune – YouTube Music Sign-in"
        window.minSize = NSSize(width: 560, height: 440)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()

        originLabel.textColor = .secondaryLabelColor
        originLabel.lineBreakMode = .byTruncatingTail
        let close = NSButton(title: "Cancel", target: self, action: #selector(cancel))
        let retry = NSButton(title: "Start over", target: self, action: #selector(reload))
        let bar = NSStackView(views: [originLabel, retry, close])
        bar.orientation = .horizontal
        bar.alignment = .centerY
        bar.spacing = 12
        bar.edgeInsets = NSEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        close.setContentCompressionResistancePriority(.required, for: .horizontal)
        retry.setContentCompressionResistancePriority(.required, for: .horizontal)
        let content = NSView()
        bar.translatesAutoresizingMaskIntoConstraints = false
        browser.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(bar)
        content.addSubview(browser)
        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            bar.topAnchor.constraint(equalTo: content.topAnchor),
            browser.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            browser.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            browser.topAnchor.constraint(equalTo: bar.bottomAnchor),
            browser.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])
        window.contentView = content

        browser.navigationDelegate = self
        browser.configuration.websiteDataStore.httpCookieStore.add(self)
        window.makeKeyAndOrderFront(nil)
        if #available(macOS 14, *) {
            NSApp.activate()
        } else {
            NSApp.activate(ignoringOtherApps: true)
        }
        reload()
    }

    @objc private func reload() {
        eligiblePage = false
        sentToMusic = false
        browser.load(URLRequest(url: signInURL))
    }

    @objc private func cancel() { exit(2) }

    func windowWillClose(_ notification: Notification) { cancel() }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        cancel()
        return .terminateNow
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor action: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = action.request.url, trusted(url) else {
            if action.targetFrame?.isMainFrame != false {
                originLabel.stringValue = "This address is outside the YouTube Music sign-in."
            }
            decisionHandler(.cancel)
            return
        }
        if action.targetFrame == nil {
            decisionHandler(.cancel)
            webView.load(action.request)
        } else {
            decisionHandler(.allow)
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        eligiblePage = false
        pageRevision += 1
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor response: WKNavigationResponse,
        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
    ) {
        decisionHandler(response.response.url.map(trusted) == true ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        if let url = webView.url, trusted(url) {
            originLabel.stringValue = "https://\(url.host ?? musicHost)"
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let url = webView.url, trusted(url), let host = url.host, youtubeHosts.contains(host) else { return }
        eligiblePage = !url.path.hasPrefix("/signin") && !url.path.hasPrefix("/login")
        guard eligiblePage else { return }
        checkSession()
        let revision = pageRevision
        for wait in [1.0, 2.5, 5.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
                if let self, self.canInspect(revision) { self.checkSession() }
            }
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        let details = error as NSError
        if details.domain == NSURLErrorDomain && details.code == NSURLErrorCancelled { return }
        originLabel.stringValue = "The sign-in page could not load. Start over to try again."
    }

    func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
        if eligiblePage { checkSession() }
    }

    private func checkSession() {
        let revision = pageRevision
        guard canInspect(revision) else { return }
        if browser.url?.host != musicHost {
            guard !sentToMusic else { return }
            // Some Google sign-ins land on YouTube's own home after the hand-off.
            browser.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
                guard let self, self.canInspect(revision), !self.sentToMusic, cookieHeader(cookies) != nil else { return }
                self.sentToMusic = true
                self.browser.load(URLRequest(url: musicURL))
            }
            return
        }
        browser.evaluateJavaScript(signedInScript) { [weak self] value, _ in
            guard let self, value as? Bool == true, self.canInspect(revision) else { return }
            self.browser.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
                guard let self, self.canInspect(revision), self.browser.url?.host == musicHost,
                      let header = cookieHeader(cookies) else { return }
                self.finished = true
                FileHandle.standardOutput.write(sessionFrame(header))
                exit(0)
            }
        }
    }

    private func canInspect(_ revision: Int) -> Bool {
        !finished && eligiblePage && pageRevision == revision
    }
}

private final class LoginPageFixture: NSObject, WKNavigationDelegate {
    private let browser: WKWebView
    private let complete: () -> Void
    private var signedIn = false

    init(store: WKWebsiteDataStore, complete: @escaping () -> Void) {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = store
        browser = WKWebView(frame: .zero, configuration: configuration)
        self.complete = complete
        super.init()
        browser.navigationDelegate = self
    }

    func load() {
        let body = "<script>var ytcfg = {get: (key) => key === 'LOGGED_IN' && \(signedIn)};</script>"
        browser.loadHTMLString(body, baseURL: nil)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.evaluateJavaScript(signedInScript) { [weak self] value, error in
            guard let self, error == nil, let actual = value as? Bool, actual == self.signedIn else { exit(1) }
            if self.signedIn {
                self.complete()
            } else {
                self.signedIn = true
                self.load()
            }
        }
    }
}

private func runSelfCheck() -> Never {
    _ = NSApplication.shared
    let store = WKWebsiteDataStore.nonPersistent()
    guard !store.isPersistent, trusted(musicURL), trusted(signInURL),
          !trusted(URL(string: "http://music.youtube.com/")!),
          !trusted(URL(string: "https://music.youtube.com.example.test/")!),
          !trusted(URL(string: "https://account@music.youtube.com/")!) else { exit(1) }
    func testCookie(_ domain: String, _ name: String, _ value: String, path: String = "/") -> HTTPCookie {
        HTTPCookie(properties: [.domain: domain, .name: name, .value: value, .path: path, .secure: "TRUE"])!
    }
    let fixtures = [
        testCookie(".youtube.com", "SAPISID", "fixture"),
        testCookie("music.youtube.com", "SID", "fixture-local"),
        testCookie(".google.com", "SAPISID", "wrong-origin"),
        testCookie("music.youtube.com", "HSID", "wrong-path", path: "/unrelated"),
    ]
    let done = DispatchGroup()
    for cookie in fixtures {
        done.enter()
        store.httpCookieStore.setCookie(cookie) { done.leave() }
    }
    let page = LoginPageFixture(store: store) {
        store.httpCookieStore.getAllCookies { cookies in
            guard let header = cookieHeader(cookies),
                  Set(header.components(separatedBy: "; ")) == Set(["SAPISID=fixture", "SID=fixture-local"]),
                  let framing = String(data: sessionFrame(header), encoding: .utf8),
                  framing.hasPrefix(sessionPrefix),
                  let bytes = Data(base64Encoded: String(framing.dropFirst(sessionPrefix.count).dropLast())),
                  String(data: bytes, encoding: .utf8) == header else {
                exit(1)
            }
            print("OpenTune login self-check: ok")
            exit(0)
        }
    }
    done.notify(queue: .main) { page.load() }
    DispatchQueue.main.asyncAfter(deadline: .now() + 15) { exit(1) }
    withExtendedLifetime(page) { RunLoop.main.run() }
    exit(1)
}

if CommandLine.arguments.dropFirst() == ["--self-check"] {
    runSelfCheck()
}
guard CommandLine.arguments.count == 1 else { exit(1) }

private func installMenu(on app: NSApplication) {
    let menu = NSMenu()
    let applicationItem = NSMenuItem()
    let applicationMenu = NSMenu(title: "OpenTune Login")
    applicationMenu.addItem(withTitle: "Quit OpenTune Login", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
    applicationItem.submenu = applicationMenu
    menu.addItem(applicationItem)

    let editItem = NSMenuItem()
    let editMenu = NSMenu(title: "Edit")
    editMenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
    editMenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
    editMenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
    editMenu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
    editItem.submenu = editMenu
    menu.addItem(editItem)
    app.mainMenu = menu
}

let app = NSApplication.shared
private let controller = LoginWindow()
app.setActivationPolicy(.regular)
installMenu(on: app)
app.delegate = controller
app.run()
