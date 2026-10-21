# Releasing Miramail

How to get the apps to **TestFlight** (iOS) and **Google Play testing** (Android). Once the one-time setup below
is done, a release is one command (it uses [fastlane](https://fastlane.tools)):

```bash
bundle exec fastlane beta
```

It checks the working tree is committed, runs both apps' unit tests, picks the next build number from TestFlight and
Google Play, builds both apps, and uploads them. Steps that need your Apple and Google accounts are marked **You**.

## Part 1: one-time setup

### Apple
1. **You:** join the **Apple Developer Program** (paid, https://developer.apple.com/programs/). TestFlight needs it; a
   free personal team can't distribute. The project is set to team `24CMR7378R` (`DEVELOPMENT_TEAM`, and
   `ios/ExportOptions.plist`): if that is not your paid team's ID, change it in both.
2. **You:** in App Store Connect → Apps → **+** → New App: platform iOS, bundle ID `com.miramail`
   (register it under Certificates, Identifiers & Profiles if it isn't listed), SKU anything. App names are unique
   across the App Store: if "Miramail" is taken, pick another name (it can differ from the name under the icon).
3. **You:** make sure Xcode is signed in to that Apple account (Xcode → Settings → Accounts). Xcode then creates the
   distribution certificate and provisioning profile itself (signing is automatic).

### Google Play
1. **You:** create a **Google Play Console** developer account (one-time fee, https://play.google.com/console).
2. **You:** Create app → name, default language, App, Free. The package name `com.miramail` is fixed by the
   first upload and can never change.
3. Keep **Play App Signing** on (the default). You sign uploads with the *upload key*; Google re-signs what it
   distributes with its own key. The upload key was created for you: `~/.trustmail/trustmail-upload.jks`, with its
   passwords in the git-ignored `android/keystore.properties`.
   **Back up both files somewhere safe** (a password manager or encrypted drive). If the upload key is lost you can
   ask Play support to reset it, but it is a hassle. Never commit them.

### Google Cloud (so Google sign-in works for testers). Important.
Sign-in only works when the app's signing key is registered with the Google OAuth client.
- **The OAuth clients** live in Google Cloud Console → APIs & Services → Credentials, all for
  `com.miramail` (the app was renamed from safemail, then trustmail; when the ID changes, edit the existing clients to the new bundle
  ID and package, which keeps their client IDs):
  - **iOS:** its client ID is `clientID` in `ios/Trustmail/Mail/GoogleAuth.swift`, and its reversed form
    (`com.googleusercontent.apps.<the part before .apps.googleusercontent.com>`) is the URL scheme in `ios/Info.plist`.
  - **Android, sideload APK** (the debug-signed `release` build): an Android client with SHA-1
    `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25`. No code change.
  - **Android, Google Play:** another Android client with Google's signing key (next bullet).
- **Android from Google Play** is signed by **Google's** key, not yours, so the debug key already registered does
  *not* cover it. After your first upload: Play Console → your app → **Test and release → App integrity → Play app
  signing** → copy the **App signing key certificate SHA-1**. Then Google Cloud Console → APIs & Services →
  Credentials → **Create credentials → OAuth client ID → Android**, package `com.miramail`, paste that
  SHA-1. (You can add the upload key's SHA-1 the same way, see below, for builds you install straight from the
  `.aab`.) The sideload APK for friends keeps using the debug key and its existing client.
- **The consent screen decides who can sign in.** While it is in **Testing**, only the Gmail addresses you list under
  OAuth consent screen → *Test users* (max 100) can sign in, and Google makes them sign in again about every 7 days.
  Gmail's `gmail.modify` and `gmail.send` scopes are *restricted*. To let anyone sign in you would have to publish the
  app and pass Google's OAuth verification, which includes an annual third-party security assessment (CASA). For a
  beta with friends, stay in Testing and add every tester's address (iOS and Android) as a test user.

### Both stores want these (prepare once)
- A **privacy policy URL**. Because the app reads Gmail, it must say what happens to that data (Google's "Limited
  Use" rules apply). A plain hosted page is enough, e.g. GitHub Pages.
- A **support email or URL**.
- Honest privacy answers (App Store Connect "App Privacy", Play "Data safety"). The facts about this app:
  - It has no server of its own, no analytics, no ads, no tracking.
  - It reads the user's Gmail on the device with their permission. Email content is not sent to us or stored.
  - For link checks it sends **hostnames** (not the email, not full URLs) to Cloudflare (DNS over HTTPS) and SSL
    Mate's Cert Spotter (certificate history), and makes a TLS handshake directly with each link's server (which
    therefore sees the user's IP address). `FUTURE_CHANGES.md` describes moving this to a backend.
  - Decide with your privacy policy whether that hostname lookup counts as data "collected" under each store's
    rules; declaring it is the cautious choice.
- Screenshots and a description (Play also wants a 512 px icon: `store/play-store-icon-512.png`, and a 1024 x 500
  feature graphic, which isn't generated yet). TestFlight and Play *internal* testing need almost none of this.

## Part 2: the fastlane setup (once)

1. **Install fastlane** (it is pinned in the `Gemfile`; Ruby 3.4 comes from `.ruby-version`):
   ```bash
   bundle install
   ```
2. **Create `fastlane/.env`** from `fastlane/.env.example` (it is git-ignored; never commit it) and fill in:

   **You, for iOS:** an **App Store Connect API key**. App Store Connect → Users and Access → Integrations → App Store
   Connect API → Team Keys → **+**, role **Admin** (App Manager is not enough: exporting fails with "Cloud signing permission error", because only Admin keys may create the cloud-managed distribution certificate). Note the *Key ID* and *Issuer ID*, and download the `.p8`
   file (Apple shows it only once). Save it outside the repo, e.g. `~/.trustmail/AuthKey_XXXX.p8`, and set
   `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`. With this key fastlane does not need Xcode to be signed in.

   **You, for Android:** a **Google Play service account**. In Google Cloud Console (any project): enable the
   *Google Play Android Developer API*; IAM & Admin → Service Accounts → create one → Keys → Add key → JSON, and save
   it as e.g. `~/.trustmail/play-service-account.json` (`PLAY_JSON_KEY`). Then in the Play Console → Users and
   permissions → **Invite new users**, enter the service account's email, and give it access to the app with the
   permission to release to testing tracks. (Google moves these menus around: if one is missing, search the Play
   Console help for "service account API access".)
3. **Do the very first release of each app by hand**, once:
   - iOS: the app record must exist in App Store Connect (Part 1). The first upload can already be done by fastlane.
   - Android: the Play API cannot create an app or its first release. Run `bundle exec fastlane android build` to
     make `android/app/build/outputs/bundle/play/app-play.aab`, and upload it by hand under **Testing → Internal
     testing → Create new release**. (Do the Google Cloud step in Part 1 right after.) From then on fastlane takes over.
     Until the store listing is complete, Play only accepts *draft* releases through the API: use `status:draft`.

## Part 3: every release

1. Commit your work. (fastlane refuses a dirty tree; override with `allow_dirty:true`.)
2. Choose the user-visible version if it changed: `MARKETING_VERSION` in the Xcode project and `versionName` in
   `android/app/build.gradle.kts`. **Build numbers need no editing**: fastlane reads the latest from TestFlight and
   Google Play and uses the next one.
3. Run it:

```bash
bundle exec fastlane beta                          # both apps: tests, then TestFlight and Google Play (internal)
bundle exec fastlane ios beta                      # iOS only
bundle exec fastlane android beta                  # Android only
bundle exec fastlane android beta track:alpha      # to closed testing (beta = open testing, production = live)
bundle exec fastlane ios beta groups:"Friends"     # also send to an external TestFlight group (waits for processing)
bundle exec fastlane beta notes:"Faster scanning"  # TestFlight "what to test" text
bundle exec fastlane beta skip_tests:true          # skip the unit tests
bundle exec fastlane test                          # just the unit tests of both apps
bundle exec fastlane ios build                     # make the .ipa only (build/Trustmail.ipa), upload nothing
bundle exec fastlane android build                 # make the .aab only, upload nothing
```

Options: `skip_tests`, `allow_dirty`, `notes`; iOS `groups`, `wait`, `build_number`; Android `track`, `status`,
`version_code`. Defaults can live in `fastlane/.env` (`PLAY_TRACK`, `PLAY_RELEASE_STATUS`, `TESTFLIGHT_GROUPS`).

What happens afterwards:
- **TestFlight:** the build appears in App Store Connect → TestFlight after processing (10 to 30 minutes).
  *Internal* testers (people with an App Store Connect role, up to 100) get it at once, with no review. *External*
  testers (friends, a public link) need **Beta App Review** for the first build of each version, which fastlane
  submits when you pass `groups`. Fill in Test Information once (description, feedback email, privacy policy URL).
  **Review notes must explain the login:** reviewers need a working Google account, so create a throwaway Gmail
  account, add it as a test user in Google Cloud, and put its credentials in the notes. Builds expire after 90 days.
  Apple should not ask for "Sign in with Apple": apps that are a client for a third-party mail account are exempt.
- **Google Play:** internal testing is available within minutes, with no review; closed and open testing need the
  store listing finished and are reviewed. Testers join through the opt-in link (email list or Google Group). New
  personal developer accounts must also run a closed test with a minimum number of testers for 14 days before they may
  apply for production (check the current rule in the Console).

## Part 4: without fastlane (manual fallback)

**iOS:** Xcode → pick *Trustmail* and *Any iOS Device (arm64)* → **Product → Archive** → Organizer → **Distribute App →
App Store Connect → Upload**. Or `xcodebuild archive` then `xcodebuild -exportArchive -exportOptionsPlist
ios/ExportOptions.plist` (that file uploads straight to App Store Connect). Bump `CURRENT_PROJECT_VERSION` by hand.

**Android:** `cd android && ./gradlew :app:bundlePlay`, then upload `app/build/outputs/bundle/play/app-play.aab` in the
Play Console. Bump `versionCode` by hand (pass `-PversionCode=N`). Pin an emulator first if a phone is plugged in (see
`CLAUDE.md`), or Gradle will touch the phone.

The upload key's certificate SHA-1 is `83:29:97:2E:DA:74:26:EA:C0:AE:C9:C8:27:E1:F3:B6:ED:9C:F0:F7`. Register it as
another Android OAuth client only if you install the upload-signed build directly (e.g. with `bundletool`) instead of
through Play. Installs from Play are signed by Google, whose SHA-1 you register (Part 1).

### Sideload APK for friends (no store)
`./gradlew :app:assembleRelease` gives `app/build/outputs/apk/release/app-release.apk`, signed with the **debug key**
so the already-registered Google client works. That key is public, so this is for a few friends only. Never upload
that APK to a store; the store build is `bundlePlay`.

## Where things are
| What | Where |
|---|---|
| Store credentials | `~/.trustmail/` (App Store Connect `.p8`, Play service-account JSON), referenced from `fastlane/.env` |
| Play upload key + its passwords | `~/.trustmail/trustmail-upload.jks`, `android/keystore.properties` (git-ignored; back up) |
| Android build types | `release` = debug-signed sideload APK; `play` = upload-signed bundle for Google Play |
| Release automation | `fastlane/Fastfile` (lanes), `fastlane/Appfile`, `fastlane/.env` (your keys; git-ignored), `Gemfile` |
| iOS signing | automatic, team in the Xcode project and `fastlane/Appfile`; `ios/ExportOptions.plist` for manual upload |
| iOS privacy manifest, export compliance | `ios/Trustmail/PrivacyInfo.xcprivacy`, `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption` |
| Store icons | iOS: the app icon asset (1024 px, no alpha); Play: `store/play-store-icon-512.png` |
