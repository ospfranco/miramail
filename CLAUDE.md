# safemail

Safemail is a mail client that checks every email for phishing before the user acts on it: sender authentication (SPF/DKIM/DMARC as reported by Gmail), and per-link checks (look-alike domains, mismatched link text, DNS tampering, TLS certificate, domain age).

It ships as two **native** apps. Native iOS and Android only; there is no web target.

| Folder | App | Stack |
|---|---|---|
| `ios/` | iOS app | Swift 6, SwiftUI, Xcode 27, iOS 17+, GoogleSignIn-iOS (SPM) |
| `android/` | Android app | Kotlin 2.4, Jetpack Compose, AGP 9.4, Gradle 9.8, minSdk 26 / target 37, Play Services Auth |
| `scripts/` | Shared tooling | |

Both apps use the bundle / package id `com.ospfranco.safemail`.

## The rule: every change goes into both apps, and is tested on both

**Every change to `ios/` must be replicated in `android/`, and every change to `android/` must be replicated in `ios/`.** A change is not done until:

1. it exists in both apps with the same behavior (same strings, same thresholds, same verdicts),
2. the unit tests for it are added or updated **on both platforms**, and
3. both test suites pass, and for any UI change you have run the app on an iOS simulator **and** an Android emulator and looked at it.

If a change genuinely cannot be mirrored (a platform API difference), say so explicitly in your summary and in a code comment on both sides. Never leave one platform silently behind.

Don't report work as done after only building. Run the tests below.

## Where things live (keep these in sync)

The logic is ported line for line, so a change in one file has a twin in the other app.

| Concern | iOS (`ios/Safemail/`) | Android (`android/app/src/main/kotlin/com/ospfranco/safemail/`) |
|---|---|---|
| Verdicts, check kinds, labels | `Security/Verdict.swift` | `security/Verdict.kt` |
| Link checks (DNS, TLS, domain age, caches) | `Security/LinkChecks.swift` | `security/LinkChecks.kt` |
| Link scanning, URL parsing, evaluation | `Security/SecurityScanner.swift` | `security/SecurityScanner.kt` |
| Sender (SPF/DKIM/DMARC) checks | `Security/SenderChecks.swift` | `security/SenderChecks.kt` |
| X.509 reader | `Security/X509.swift` | `security/X509.kt` |
| TLS handshake + system DNS | `Security/TlsInspector.swift` | `security/TlsInspector.kt` |
| Public Suffix List | `Security/PublicSuffixList.swift` | `security/PublicSuffixList.kt` |
| Base64 / UTF-8 | `Security/Bytes.swift` | `security/Bytes.kt` |
| Gmail API | `Mail/GmailClient.swift` | `mail/GmailClient.kt` |
| Google sign-in | `Mail/GoogleAuth.swift` | `mail/GoogleAuth.kt` |
| Mail/auth interfaces and models | `Mail/MailModels.swift` | `mail/MailModels.kt` |
| Demo data (debug only) | `Mail/DemoMail.swift` | `mail/DemoMail.kt` |
| Inbox / message screens | `UI/InboxView.swift`, `UI/MessageView.swift` | `ui/InboxScreen.kt`, `ui/MessageScreen.kt` |
| Email web view (CSP, link scan script, badges) | `UI/EmailBody.swift` | `ui/EmailBody.kt` |
| Security panel | `UI/SecurityPanel.swift` | `ui/SecurityPanel.kt` |
| Settings (store + screen; the screen also has Sign out, shown only when signed in) | `Settings/AppSettings.swift`, `UI/SettingsView.swift` | `settings/AppSettings.kt`, `ui/SettingsScreen.kt` |
| Splash screen (the word "safemail" on the app background, light/dark) | `LaunchScreen.storyboard` | `res/drawable/splash_icon.xml` (+ `splash_*` colors, `Theme.Safemail.Starting`) |
| App icon (a smiley on an envelope) | `Assets.xcassets/AppIcon.appiconset/icon-1024.png` | `res/drawable-nodpi/ic_launcher_{foreground,monochrome}.png`, `res/mipmap-anydpi-v26/ic_launcher.xml`, color `ic_launcher_background` |
| Link scan when email JavaScript is off | `UI/LinkInspector.swift` (iOS only, see below) | not needed |
| View models | `UI/Models.swift` | `ui/ViewModels.kt` |
| Theme / text styles / spacing | `UI/Theme.swift` | `ui/Theme.kt` |

Tests mirror each other too: `ios/SafemailTests/*Tests.swift` and `android/app/src/test/kotlin/com/ospfranco/safemail/*Test.kt` cover the same cases with the same expected strings. UI tests: `ios/SafemailUITests/` and `android/app/src/androidTest/`.

The Android splash is a vector converted from the glyph outlines of Roboto SemiBold (the font and weight of the sign-in title), centered in the 288dp box the system uses for splash icons. If the title style changes, regenerate it with `fontTools` from the Roboto file on an emulator (`/system/fonts/Roboto-Regular.ttf`), and update the iOS storyboard label to match.

### "Allow JavaScript in emails" (Settings, off by default)

The inbox has a settings button (gear, top right) opening a settings screen with this toggle. Both platforms behave the same:

- **Off (default):** scripts inside an email never run. **On:** inline and https scripts in the email may run. Safemail's own scanning script runs either way. The change applies the next time an email is opened.
- The CSP follows the setting on both platforms (`EmailHtml.csp(allowScripts:)`: `script-src 'unsafe-inline' https:` is added only when on).
- **iOS** also sets `WKWebpagePreferences.allowsContentJavaScript` from the setting. **Android** can't switch page JavaScript off while still running our own scan script, so its WebView keeps JavaScript enabled and relies on the CSP.
- **Every link is reported the same way in both modes**, including `javascript:` links. This needs extra work on iOS: with scripting off, WebKit deletes the `href` of `javascript:` links while parsing, so the visible web view cannot see them. `LinkInspector` therefore loads the email in a hidden web view (scripting on, CSP `default-src 'none'` so nothing runs or loads) and the scan uses its link list. The one visible difference left: on iOS with the setting off, a `javascript:` link has no badge and can't be tapped, while Android shows a badge and the unsafe-link warning.
- **Android bridge token:** with scripts allowed, the email's own scripts can reach the JavaScript bridge, so every message from our script carries a per-page random token and the app drops messages without it. (On iOS our script lives in its own content world, which the page cannot reach.)
- Tests that pin this on both sides: `EmailWebViewTests` (iOS) / `EmailWebViewTest` (Android) run a real web view with the setting on and off, check that the email's script runs only when allowed and that all three links are found. The iOS test `webKitHidesJavaScriptLinksFromAViewWithScriptingOff` is a canary: if it starts failing, `LinkInspector` is no longer needed.

### Shared data (must stay identical)

- **Public Suffix List**: `ios/Safemail/Resources/public_suffix_list.dat` and `android/app/src/main/assets/public_suffix_list.dat` are generated by `scripts/update-public-suffix-list.sh`. Never edit them by hand; re-run the script and commit both.
- **App icon**: all icon images are generated from one design by `scripts/generate-app-icon.py` (needs Pillow). Change the design there and re-run it; never edit the PNGs by hand. Keep `BACKGROUND` in the script equal to the `ic_launcher_background` color.
- **Test fixtures**: `domains.jsonl`, `leaf.der.b64`, `cn.der.b64` exist in both `ios/SafemailTests/Fixtures/` and `android/app/src/test/resources/`. Change both or neither. (`domains.jsonl` holds `tldts` reference answers from the original app; the certificates were generated with `openssl`.)
- **Colors, copy, thresholds**: verdict colors, check strings and constants such as `NEW_DOMAIN_DAYS` are duplicated by design. Grep for the other platform's twin before changing one.

## Build and test

### iOS

`xcode-select` may point at the Command Line Tools. Use the full Xcode explicitly:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd ios

# Build
xcodebuild -project Safemail.xcodeproj -scheme Safemail -destination 'platform=iOS Simulator,name=iPhone 17' build

# Unit tests (Swift Testing) + UI tests (XCUITest)
xcodebuild -project Safemail.xcodeproj -scheme Safemail -destination 'platform=iOS Simulator,name=iPhone 17' test

# Unit tests only
xcodebuild -project Safemail.xcodeproj -scheme Safemail -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:SafemailTests test
```

The Xcode project uses folder-synchronised groups: add a Swift file under `ios/Safemail/` (or `SafemailTests/`) and it is picked up automatically. The project file is hand-maintained (no XcodeGen).

### Android

Needs JDK 17+ (21 is installed) and `ANDROID_HOME` (`~/Library/Android/sdk`).

```bash
cd android
./gradlew :app:assembleDebug                 # build
./gradlew :app:testDebugUnitTest             # JVM unit tests (JUnit + MockWebServer)
./gradlew :app:lintDebug                     # lint

# UI tests on a running emulator (e.g. `emulator -avd Medium_Phone`)
./gradlew :app:connectedDebugAndroidTest
```

Versions live in `android/gradle/libs.versions.toml`.

### Demo mode (no Google account needed)

Debug builds can run against canned mail, which is what the UI tests use:

- iOS: launch with the `-demo` argument (`xcrun simctl launch <device> com.ospfranco.safemail -demo`).
- Android: `adb shell am start -n com.ospfranco.safemail/.MainActivity --ez demo true`.

The demo inbox has a plain-text email from a known, verified sender and an HTML phishing email (mismatched link text, `http`, a shortener, a `javascript:` link, and a `<script>` that rewrites a status line from "Scripts in this email are blocked." to "...are running." so tests can tell whether scripts ran). Demo runs always start with default settings (in-memory on Android, a throwaway `UserDefaults` suite on iOS), so UI tests don't leak state into each other. Real Google sign-in cannot be exercised in demo mode; test it by hand with a real account.

## Conventions

- **Native only.** No web, no cross-platform layer.
- **Prefer enums** over string-literal unions or loose strings (Swift `enum`, Kotlin `enum class`).
- Match the surrounding code: comment density, naming, idiom. Keep each platform idiomatic (SwiftUI/async-await, Compose/coroutines) while keeping behavior identical.
- Email content is **untrusted**. Never loosen the web view hardening (the CSP, blocked navigation, the content-world / bridge-token isolation) without an explicit decision. "Allow JavaScript in emails" must stay **off by default**.
- Network checks must only perform a TLS handshake against a link's host; never request the link's URL itself.
- Gmail API tokens are never logged. OAuth client IDs are public identifiers, not secrets.
- Network-dependent tests are not allowed in unit suites: use the fakes (`FakeMail`, `StubProtocol`, `MockWebServer`). Only the UI tests hit the real network.
- Backend-dependent work is tracked in `FUTURE_CHANGES.md` and marked `TODO(backend)` in code. Keep both platforms' markers aligned.

## Signing / release notes

- **Android** debug and release builds are signed with the standard React Native debug keystore at `android/app/debug.keystore` (SHA-1 `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25`), so the Android OAuth client already registered for `com.ospfranco.safemail` keeps working. Create a real release key and register its SHA-1 before any public release.
- **iOS** uses automatic signing with no team set. Pick a development team in Xcode to run on a device. Google Sign-In uses the iOS client `760054649215-log9rqaj7h239g6lr9qsink2adnla6i2` and the reversed URL scheme in `ios/Info.plist`.
