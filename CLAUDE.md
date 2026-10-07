# Trustmail

Trustmail is a mail client that checks every email for phishing before the user acts on it: sender authentication (SPF/DKIM/DMARC as reported by Gmail), and per-link checks (look-alike domains, mismatched link text, DNS tampering, TLS certificate, domain age).

It ships as two **native** apps. Native iOS and Android only; there is no web target.

| Folder | App | Stack |
|---|---|---|
| `ios/` | iOS app | Swift 6, SwiftUI, Xcode 27, iOS 17+, GoogleSignIn-iOS (SPM) |
| `android/` | Android app | Kotlin 2.4, Jetpack Compose, AGP 9.4, Gradle 9.8, minSdk 26 / target 37, Play Services Auth |
| `scripts/` | Shared tooling | |

Both apps use the bundle / package id `com.ospfranco.trustmail`.

## The rule: every change goes into both apps, and is tested on both

**Every change to `ios/` must be replicated in `android/`, and every change to `android/` must be replicated in `ios/`.** A change is not done until:

1. it exists in both apps with the same behavior (same strings, same thresholds, same verdicts),
2. the unit tests for it are added or updated **on both platforms**, and
3. both test suites pass, and for any UI change you have run the app on an iOS simulator **and** an Android emulator and looked at it.

If a change genuinely cannot be mirrored (a platform API difference), say so explicitly in your summary and in a code comment on both sides. Never leave one platform silently behind.

Don't report work as done after only building. Run the tests below.

## Where things live (keep these in sync)

The logic is ported line for line, so a change in one file has a twin in the other app.

| Concern | iOS (`ios/Trustmail/`) | Android (`android/app/src/main/kotlin/com/ospfranco/trustmail/`) |
|---|---|---|
| Verdicts, check kinds, labels | `Security/Verdict.swift` | `security/Verdict.kt` |
| Link checks (DNS, TLS, domain age, caches) | `Security/LinkChecks.swift` | `security/LinkChecks.kt` |
| Link scanning, URL parsing, evaluation, the risky-link dialog text (`linkWarning`: "Shown as" / "Goes to", query and fragment stripped) | `Security/SecurityScanner.swift` | `security/SecurityScanner.kt` |
| Sender (SPF/DKIM/DMARC) checks | `Security/SenderChecks.swift` | `security/SenderChecks.kt` |
| X.509 reader | `Security/X509.swift` | `security/X509.kt` |
| TLS handshake + system DNS | `Security/TlsInspector.swift` | `security/TlsInspector.kt` |
| Well-known domains (skip the domain-age check) | `Security/KnownDomains.swift`, `LinkChecks.domainAgeResult` | `security/KnownDomains.kt`, `LinkChecks.domainAgeResult` |
| Public Suffix List | `Security/PublicSuffixList.swift` | `security/PublicSuffixList.kt` |
| Base64 / UTF-8 | `Security/Bytes.swift` | `security/Bytes.kt` |
| Gmail API | `Mail/GmailClient.swift` | `mail/GmailClient.kt` |
| Google sign-in | `Mail/GoogleAuth.swift` | `mail/GoogleAuth.kt` |
| Mail/auth interfaces and models | `Mail/MailModels.swift` | `mail/MailModels.kt` |
| Saved inbox (offline snapshot) | `Mail/InboxSnapshot.swift` | `mail/InboxSnapshot.kt` |
| Demo data (debug only) | `Mail/DemoMail.swift` | `mail/DemoMail.kt` |
| Intro screen (shown once, before the first sign-in) | `UI/IntroView.swift`, `RootView` in `TrustmailApp.swift`, asset `IntroPreview` | `ui/IntroScreen.kt`, gating in `MainActivity.kt`, `res/drawable-nodpi/intro_preview.png` |
| Floating action bar (delete / archive / move / reply) and notice banner | `UI/ActionBar.swift` | `ui/ActionBar.kt` |
| Compose screen (from the inbox pencil, or the reply button) | `UI/ComposeView.swift` | `ui/ComposeScreen.kt` |
| Building the outgoing email (RFC 822) | `Mail/Mime.swift` | `mail/Mime.kt` |
| Inbox / message screens | `UI/InboxView.swift`, `UI/MessageView.swift` | `ui/InboxScreen.kt`, `ui/MessageScreen.kt` |
| Email web view (CSP, link scan script, badges) | `UI/EmailBody.swift` | `ui/EmailBody.kt` |
| Security panel and its tab (grey with a spinner while the email is scanned, then the verdict) | `UI/SecurityPanel.swift` | `ui/SecurityPanel.kt` |
| Settings (store + screen; the screen also has Sign out, shown only when signed in) | `Settings/AppSettings.swift`, `UI/SettingsView.swift` | `settings/AppSettings.kt`, `ui/SettingsScreen.kt` |
| Splash screen (the word "Trustmail" on the app background, light/dark) | `LaunchScreen.storyboard` | `res/drawable/splash_icon.xml` (+ `splash_*` colors, `Theme.Trustmail.Starting`) |
| App icon (a smiley on an envelope) | `Assets.xcassets/AppIcon.appiconset/icon-1024.png` | `res/drawable-nodpi/ic_launcher_{foreground,monochrome}.png`, `res/mipmap-anydpi-v26/ic_launcher.xml`, color `ic_launcher_background` |
| Link scan when email JavaScript is off | `UI/LinkInspector.swift` (iOS only, see below) | not needed |
| View models | `UI/Models.swift` | `ui/ViewModels.kt` |
| Theme / text styles / spacing, sender avatar (`AvatarView` / `Avatar`) | `UI/Theme.swift` | `ui/Theme.kt` |
| Sender name and avatar initial/color (`senderName`, `SenderAvatar`) | `Mail/MailModels.swift` | `mail/MailModels.kt` |

Tests mirror each other too: `ios/TrustmailTests/*Tests.swift` and `android/app/src/test/kotlin/com/ospfranco/trustmail/*Test.kt` cover the same cases with the same expected strings. UI tests: `ios/TrustmailUITests/` and `android/app/src/androidTest/`.

The Android splash is a vector converted from the glyph outlines of Roboto SemiBold (the font and weight of the sign-in title), centered in the 288dp box the system uses for splash icons. If the title style changes, regenerate it with `fontTools` from the Roboto file on an emulator (`/system/fonts/Roboto-Regular.ttf`), and update the iOS storyboard label to match.

### Email actions and reply

The floating bar at the bottom of an email has four buttons. They behave the same on both platforms:

- **Delete** moves the message to Gmail's Trash (`messages.trash`), so it can be recovered. **Archive** removes the `INBOX` label. **Move** adds a label and removes `INBOX`; the chooser lists the user's own labels (sorted, case-insensitive) and then Spam. Gmail has labels, not folders.
- After one of these works, the app goes back to the inbox, the message is gone from the list, and a notice ("Archived", "Moved to Trash", "Moved to <label>") floats at the top for a moment. A failure shows a notice ("Couldn't archive the email", ...) and stays on the email. The buttons are disabled while an action runs.
- **The inbox** is titled "Inbox" and shows the account email, a **new message** button (only when signed in) and the settings gear. On iOS, as in Mail, the gear is in the navigation bar and the account and the pencil are in the bottom toolbar; on Android, as in Gmail, the account sits under the title and "New message" is an extended floating action button. Sign out lives in the settings, not on the inbox. New message opens the same compose screen, empty.
- **Reply** opens the compose screen: To is the `Reply-To` address (else the sender), Subject gets one "Re: ", and the message is sent in the same thread (`threadId`, `In-Reply-To` and `References`). The body starts empty (the original is not quoted). Send needs a valid recipient list (`address` or `Name <address>`, separated by commas or semicolons) and goes out through `messages.send` as plain UTF-8 text. On success the compose screen closes and a "Message sent" notice shows.
- `Mime.raw` strips line breaks from headers (so text can't inject headers) and encodes non-ASCII headers as RFC 2047. Its expected output is pinned by tests that must stay identical on both platforms.

### Sender markers (inbox and email header)

The sender is marked like a link in the body:

- **Failed sender checks** (a sender check is `danger`, e.g. DMARC failed): the sender's address with its domain in red and the red ✕ marker after it (`FailedSenderLine`, accessibility id / test tag `sender-warning`). In the email header this follows the scan (`SecurityEvaluation.senderFailed`). In the inbox it is known right away: `listInbox` also asks Gmail for `Authentication-Results` (`metadataHeaders`), kept to Gmail's own like `getMessage` does, and `MessageSummary.senderFailed` runs the same `checkSenderDomain`. "Sender not verified" is a warning and is not marked.
- **First message from this sender** (inbox only): after the inbox loads, the session looks up every email's sender history in the background (`loadSenderHistory`, the same `hasCorrespondence` call as the security panel, one per email) and rows in `firstMessages` get a yellow marker and "First message from this sender" (`FirstMessageLine`, `first-message`). A lookup that fails marks nothing.

### The verdict's explanation (security panel)

Under the verdict, the panel explains a warning or a danger in plain words: what was found and what to do (`SecurityScanner.explanation`, test tag / accessibility id `security-explanation`). Unsafe findings (a failed sender check, unsafe links) take over from suspicious ones; a safe email gets none. The sender checks are told apart by `SenderCheckLabel` (the labels are an enum on both platforms), so a new sender check needs a case there and, if it can warn, a sentence in `explanation`. The exact sentences are pinned by `explainsTheVerdict` on both platforms.

### Saved inbox (offline snapshot)

The latest inbox (the list and its sender markers) is kept on the device, so the app opens straight to it and still shows it when Gmail can't be reached. Only the inbox list is saved, not email bodies, so opening an email still needs the network.

- At launch the session shows the saved copy of the signed-in account (`InboxSnapshot.account` must match), then refreshes. If the refresh fails, the copy stays with the note "Couldn't refresh your inbox. Showing the copy saved on this device." (`saved-inbox`), in place of the raw error.
- It is saved after every refresh, after the sender history comes in, and after archive/delete/move; it is deleted on sign out.
- Storage: iOS `Application Support/inbox-snapshot.json` with complete file protection, excluded from backup; Android `filesDir/inbox_snapshot.json` (private, and the app has `allowBackup="false"`). Tests and demo runs use `MemoryInboxSnapshotStore`.
- **Offline launch keeps the session.** A network failure while restoring Google sign-in no longer signs out: iOS remembers the account email and checks `GoogleAuth.isNetworkError` (the token is restored on the next request); Android keeps the account on a `NETWORK_ERROR` from `authorize()`. Real sign-in can't run in demo mode, so test this by hand with a real account in airplane mode.

### The intro screen

Shown once, before anyone has signed in: a preview image, three steps, and a note that Google sign-in comes next. It is skipped for anyone already signed in, and never shown again after "Continue" (`hasSeenIntro` in the settings). The preview image is a crop of the UI-test screenshot of the demo phishing email, made by `scripts/generate-intro-preview.py` (see Shared data).

### "Allow JavaScript in emails" (Settings, off by default)

The inbox has a settings button (gear, top right) opening a settings screen with this toggle. Both platforms behave the same:

- **Off (default):** scripts inside an email never run. **On:** inline and https scripts in the email may run. Trustmail's own scanning script runs either way. The change applies the next time an email is opened.
- The CSP follows the setting on both platforms (`EmailHtml.csp(allowScripts:)`: `script-src 'unsafe-inline' https:` is added only when on).
- **iOS** also sets `WKWebpagePreferences.allowsContentJavaScript` from the setting. **Android** can't switch page JavaScript off while still running our own scan script, so its WebView keeps JavaScript enabled and relies on the CSP.
- **Every link is reported the same way in both modes**, including `javascript:` links. This needs extra work on iOS: with scripting off, WebKit deletes the `href` of `javascript:` links while parsing, so the visible web view cannot see them. `LinkInspector` therefore loads the email in a hidden web view (scripting on, CSP `default-src 'none'` so nothing runs or loads) and the scan uses its link list. The one visible difference left: on iOS with the setting off, a `javascript:` link has no badge and can't be tapped, while Android shows a badge and the unsafe-link warning.
- **Android bridge token:** with scripts allowed, the email's own scripts can reach the JavaScript bridge, so every message from our script carries a per-page random token and the app drops messages without it. (On iOS our script lives in its own content world, which the page cannot reach.)
- Tests that pin this on both sides: `EmailWebViewTests` (iOS) / `EmailWebViewTest` (Android) run a real web view with the setting on and off, check that the email's script runs only when allowed and that all three links are found. The iOS test `webKitHidesJavaScriptLinksFromAViewWithScriptingOff` is a canary: if it starts failing, `LinkInspector` is no longer needed.

### "Ignore footer links" (Settings, off by default)

- A link is a **footer link** if it sits inside `footer, [role="contentinfo"], [id*="footer" i], [class*="footer" i]` (`EmailHtml.footerSelector` / `EmailHtml.FOOTER_SELECTOR`). The scan scripts (and `LinkInspector` on iOS) report it as `inFooter`. A URL that also appears outside the footer is not a footer link (`uniqueLinks`). Plain-text emails have no footer.
- **On:** suspicious (warning) footer links don't count toward the email's verdict, and the panel adds a green "N footer link(s) ignored" check. **Unsafe (danger) footer links always count**, so wrapping a phishing link in `class="footer"` can't hide it. Footer links are still scanned, badged, and warned about when tapped. Applies the next time an email is opened.
- Pinned by `SecurityScannerTests`/`SecurityScannerTest` (`ignoresSuspiciousFooterLinksOnlyWhenAsked`), `AppSettings` tests, and `footerLinksAreMarked` in the web view tests.

### Shared data (must stay identical)

- **Public Suffix List**: `ios/Trustmail/Resources/public_suffix_list.dat` and `android/app/src/main/assets/public_suffix_list.dat` are generated by `scripts/update-public-suffix-list.sh`. Never edit them by hand; re-run the script and commit both.
- **Intro preview image**: `ios/.../IntroPreview.imageset/intro-preview.png` and `android/.../drawable-nodpi/intro_preview.png` are crops of the `message-scanned` UI-test screenshots (`ios-message-scanned.png`, `android-message-scanned.png`), made by `scripts/generate-intro-preview.py IOS.png ANDROID.png` (needs Pillow). Re-run it when the email screen changes.
- **Well-known domains**: `scripts/known-domains.txt` is the source of truth (about 900 big names, with extra depth for Germany, Austria and Switzerland); `scripts/update-known-domains.py` validates it and writes `ios/Trustmail/Resources/known_domains.txt` and `android/app/src/main/assets/known_domains.txt`. Never edit those two by hand. For a listed domain (or anything under it) the apps skip the domain-age check *and* its Certificate Transparency request, and show "<domain> is a well-known domain, so its age isn't checked." as a green check. Matching is exact on the registrable domain, so look-alikes never match. **Never list a domain where outsiders can host content or get a subdomain** (blogspot.com, github.io, herokuapp.com, amazonaws.com, sharepoint.com, ...) or a URL shortener; the script refuses them. Run `python3 scripts/update-known-domains.py --check-dns` after editing: an entry that no longer resolves may be retired and re-registrable by an attacker, so remove it.
- **App icon**: all icon images are generated from one design by `scripts/generate-app-icon.py` (needs Pillow). Change the design there and re-run it; never edit the PNGs by hand. Keep `BACKGROUND` in the script equal to the `ic_launcher_background` color.
- **Test fixtures**: `domains.jsonl`, `leaf.der.b64`, `cn.der.b64` exist in both `ios/TrustmailTests/Fixtures/` and `android/app/src/test/resources/`. Change both or neither. (`domains.jsonl` holds `tldts` reference answers from the original app; the certificates were generated with `openssl`.)
- **Colors, copy, thresholds**: verdict colors, check strings and constants such as `NEW_DOMAIN_DAYS` are duplicated by design. Grep for the other platform's twin before changing one.

## Build and test

### iOS

`xcode-select` may point at the Command Line Tools. Use the full Xcode explicitly:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd ios

# Build
xcodebuild -project Trustmail.xcodeproj -scheme Trustmail -destination 'platform=iOS Simulator,name=iPhone 17' build

# Unit tests (Swift Testing) + UI tests (XCUITest)
xcodebuild -project Trustmail.xcodeproj -scheme Trustmail -destination 'platform=iOS Simulator,name=iPhone 17' test

# Unit tests only
xcodebuild -project Trustmail.xcodeproj -scheme Trustmail -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:TrustmailTests test
```

The Xcode project uses folder-synchronised groups: add a Swift file under `ios/Trustmail/` (or `TrustmailTests/`) and it is picked up automatically. The project file is hand-maintained (no XcodeGen).

### Android

Needs JDK 17+ (21 is installed) and `ANDROID_HOME` (`~/Library/Android/sdk`).

**If a phone is plugged in, pin the emulator first.** Gradle installs to and runs tests on *every* connected device, and plain `adb` refuses to guess. Set `export ANDROID_SERIAL=emulator-5554` (see `adb devices`) before any `./gradlew` or `adb` command, or the debug app and the UI tests will land on the phone.

```bash
cd android
export ANDROID_SERIAL=emulator-5554          # see the note above
./gradlew :app:assembleDebug                 # build
./gradlew :app:testDebugUnitTest             # JVM unit tests (JUnit + MockWebServer)
./gradlew :app:lintDebug                     # lint

# UI tests on a running emulator (e.g. `emulator -avd Medium_Phone`)
./gradlew :app:connectedDebugAndroidTest
```

Versions live in `android/gradle/libs.versions.toml`.

### Demo mode (no Google account needed)

Debug builds can run against canned mail, which is what the UI tests use:

- iOS: launch with the `-demo` argument (`xcrun simctl launch <device> com.ospfranco.trustmail -demo`). Add `-demo-signed-out` to start signed out (to see the intro and the sign-in screen), or `-demo-offline` to start with a saved inbox and Gmail out of reach.
- Android: `adb shell am start -n com.ospfranco.trustmail/.MainActivity --ez demo true`. Add `--ez demo_signed_out true` to start signed out, or `--ez demo_offline true` to start offline with a saved inbox.

The demo mailbox pauses 1.5 seconds in every scan (in the sender-history lookup), so the grey "scanning" tab stays up long enough for UI tests to see it (accessibility id / test tag `security-scanning`; once the scan is done it becomes `security-tab`). It also remembers what you do: archived, deleted or moved emails leave the inbox, and sent emails are kept. Notices stay up for 8 seconds in demo runs (2.5 otherwise) so UI tests can find them.

The demo inbox has a plain-text email from a known, verified sender and an HTML phishing email (mismatched link text, `http`, a shortener, a `javascript:` link, and a `<script>` that rewrites a status line from "Scripts in this email are blocked." to "...are running." so tests can tell whether scripts ran). Demo runs always start with default settings (in-memory on Android, a throwaway `UserDefaults` suite on iOS), so UI tests don't leak state into each other. Real Google sign-in cannot be exercised in demo mode; test it by hand with a real account.

## Conventions

- **Each platform looks native.** Behavior and strings stay identical, but the styling follows each platform: iOS uses system semantic colors, Dynamic Type, navigation bars and toolbars, grouped `Form`s and Liquid Glass (`glassBackground`, with a material fallback before iOS 26); Android uses Material 3 with dynamic color (a brand-blue scheme before Android 12), the Material type scale, `Scaffold`/app bars, `ListItem`s and tonal surfaces. Use the theme's colors and text styles, not hard-coded ones (verdict colors excepted).

- **Native only.** No web, no cross-platform layer.
- **Prefer enums** over string-literal unions or loose strings (Swift `enum`, Kotlin `enum class`).
- Match the surrounding code: comment density, naming, idiom. Keep each platform idiomatic (SwiftUI/async-await, Compose/coroutines) while keeping behavior identical.
- Email content is **untrusted**. Never loosen the web view hardening (the CSP, blocked navigation, the content-world / bridge-token isolation) without an explicit decision. "Allow JavaScript in emails" must stay **off by default**.
- Network checks must only perform a TLS handshake against a link's host; never request the link's URL itself.
- Gmail API tokens are never logged. OAuth client IDs are public identifiers, not secrets.
- Network-dependent tests are not allowed in unit suites: use the fakes (`FakeMail`, `StubProtocol`, `MockWebServer`). Only the UI tests hit the real network.
- Backend-dependent work is tracked in `FUTURE_CHANGES.md` and marked `TODO(backend)` in code. Keep both platforms' markers aligned.

## Signing / release notes

How to publish to TestFlight and Google Play (accounts, Google OAuth, store forms, commands) is in **`RELEASING.md`**. Releases run through fastlane (`fastlane/Fastfile`): `bundle exec fastlane beta` tests, builds and uploads both apps, taking the next build numbers from TestFlight and Google Play (Android reads `-PversionCode`). Credentials live in the git-ignored `fastlane/.env` and `~/.trustmail`; never commit them, and never run an upload lane unless the user asks.

- **Android build types.** `release` is the APK to sideload to friends: signed with the standard React Native debug keystore at `android/app/debug.keystore` (SHA-1 `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25`) on purpose, because that is the SHA-1 registered with the Google OAuth client, so Google sign-in works. That key is public: never put that build on a store. `play` is the Google Play build (`./gradlew :app:bundlePlay`), signed with the private upload key from `android/keystore.properties` (git-ignored; key file in `~/.trustmail`, never committed). Google re-signs Play builds with its own key, so Google sign-in from Play needs *that* key's SHA-1 registered too (see `RELEASING.md`).
- **iOS** uses automatic signing with the team set in the Xcode project. Google Sign-In uses an iOS OAuth client tied to the bundle ID plus its reversed URL scheme in `ios/Info.plist`. The OAuth clients (iOS, and Android with the debug and Play signing SHA-1s) are registered for `com.ospfranco.trustmail` in Google Cloud.
- Build numbers must increase with every store upload (`CURRENT_PROJECT_VERSION` on iOS, `versionCode` on Android).
