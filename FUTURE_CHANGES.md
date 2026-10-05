# Future changes

Known changes Trustmail needs that are deferred for now. Most depend on having a backend, which we don't have yet. Code that needs to change is marked with `TODO(backend)`.

## As use I want to actively request the scanning

We do not want to run all tests on every opened email

## Move link scanning to a backend

**Where:** `runNetworkChecks` and `fetchCertificateHistory` in `ios/Trustmail/Security/LinkChecks.swift` and `android/app/src/main/kotlin/com/ospfranco/trustmail/security/LinkChecks.kt`, and the `TlsInspector` in the same folders. Change both apps together.

When an email is opened, the app checks every linked host directly from the user's device:

- **DNS lookups.** One goes through the device's resolver and one through Cloudflare's DNS-over-HTTPS (`cloudflare-dns.com`).
- **A TLS handshake** with the link's server (and the server of its main domain), to read the certificate. The path and query string are never sent.
- **A Certificate Transparency lookup** at Cert Spotter (`api.certspotter.com`) for the domain's certificate history.

This leaks information to third parties:

- **The sender's server learns the user's IP address and when they opened the email.** Phishers often give each recipient a unique subdomain, so this works like a read receipt, even though we never request the URL.
- **Cloudflare and Cert Spotter see every hostname** in the user's email.

**Change:** the app sends only the list of hostnames to our backend, and the backend runs the DNS, TLS and CT checks and returns the results.

- The sender's servers then only ever see our backend's IP address.
- One result per hostname can be cached and shared across all users.
- Without the user's device in the path, the DNS spoofing check changes meaning: it would compare the backend's resolver with public DNS, not the user's network. To keep detecting local DNS tampering, keep only the device-side DNS lookup on the device and compare it against the backend's answer.

## Certificate Transparency lookups need an API key and caching

**Where:** `fetchCertificateHistory` in `LinkChecks.swift` (iOS) and `LinkChecks.kt` (Android).

The domain-age check asks Cert Spotter for the oldest certificate issued to a domain or its subdomains. Without an API key the limits are strict and apply per IP address:

| Query | Limit |
|---|---|
| Exact domain | 100 requests/hour |
| `include_subdomains=true` (what we use) | ~10 requests per few minutes, then HTTP 429 with `retry-after` |

Very well known domains (about 900 of them, with a focus on German, Austrian and Swiss names) are skipped, which spares many of those requests, but a newsletter with ten different lesser-known linked domains can still hit the limit. When that happens, the check shows "Couldn't look up certificate history" as a warning.

**Change:** run the lookup on the backend with a Cert Spotter API key, and cache results per domain for hours or days, since a domain's first-certificate date rarely changes. The API key must never ship in the app.

Two limits to keep in mind:

- **Cert Spotter only returns unexpired certificates.** The age we compute is a lower bound ("has had certificates for at least N days"). That's enough to separate days-old domains from established ones. True domain age would need a full CT history source.
- **crt.sh isn't usable as a replacement.** It has full history but took ~24 s for a small domain and returned 502/404 errors in testing.

## Other known gaps

- **Push notifications need a backend.** Gmail's `users.watch` delivers changes to Google Cloud Pub/Sub, which a server must forward to APNs/FCM. Until then the inbox only updates when the user refreshes.
- **Google OAuth verification.** Gmail scopes are restricted. Releasing beyond 100 test users requires Google's OAuth verification and a yearly CASA security assessment. While the app is in Testing mode, refresh tokens expire after 7 days. Note: if the backend handles email content, the CASA assessment scope grows. Sending only hostnames keeps it smaller.
- **Release signing.** Sideloaded Android builds use the public debug key (so the registered Google client works), which is only fine for a few friends. Google Play builds use a private upload key. See `RELEASING.md`. For a wider release, give the Android sideload build its own key too and register its SHA-1.
- **Email charsets.** Bodies are decoded as UTF-8 only, so other charsets (e.g. ISO-8859-1) display garbled.
