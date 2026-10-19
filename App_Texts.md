
# Trustmail – alle Texte in der App

Stand: aus dem iOS-Code gelesen (`ios/Trustmail/`). Android verwendet laut CLAUDE.md dieselben Texte (Stichprobe geprüft). `{…}` sind Platzhalter, die zur Laufzeit gefüllt werden.

## 1. Gesamturteil der E-Mail (Security-Panel, `SecurityScanner.evaluateEmail`)

| Kontext                           | Text (IST)                           | Text (Soll)                             |
| --------------------------------- | ------------------------------------ | --------------------------------------- |
| Urteil grün                       | No problems found                    | Nothing suspicious here                 |
| Urteil gelb                       | Be careful with this email           | This email has some suspicious elements |
| Urteil rot                        | This email looks dangerous           | Please pay close attention to his email |
| Link-Zusammenfassung, keine Links | No links                             | We did find not links                   |
| Link-Zusammenfassung              | {n} link scanned / {n} links scanned |                                         |
| Link-Details bei Funden           | {n} unsafe, {n} suspicious           |                                         |
| Link-Details ohne Funde           | No problems found in links.          | Nothing suspicious here                 |

## 2. Absender-Prüfungen (`SenderChecks.swift`)

| Urteil | Titel (ist)                    | Detail (ist)                                                                                 | Titel (soll) | Detail (soll)                                                                                                           |
| ------ | ------------------------------ | -------------------------------------------------------------------------------------------- | ------------ | ----------------------------------------------------------------------------------------------------------------------- |
| grün   | Sender verified                | Gmail confirmed this email really comes from {domain} (DMARC).                               |              |                                                                                                                         |
| grün   | Sender verified                | Gmail confirmed this email was signed by {domain} (DKIM).                                    |              |                                                                                                                         |
| grün   | Sender verified                | Gmail confirmed this email was sent by {domain} (SPF).                                       |              |                                                                                                                         |
| rot    | Sender verification failed     | {domain} says it didn't send this email (DMARC failed). It may be forged.                    |              |                                                                                                                         |
| rot    | Sender verification failed     | This email failed {domain}'s sender checks. It may be forged.                                |              |                                                                                                                         |
| gelb   | Sender not verified            | Gmail recorded no authentication results, so {domain} can't be confirmed as the real sender. |              |                                                                                                                         |
| gelb   | Sender not verified            | Sent through {relay} on behalf of {domain}, but {domain} itself isn't verified.              |              |                                                                                                                         |
| gelb   | Sender not verified            | {domain} couldn't be verified as the real sender.                                            |              |                                                                                                                         |
| grün   | Known sender                   | You've exchanged email with this address before.                                             |              |                                                                                                                         |
| gelb   | First message from this sender | You haven't exchanged email with this address before.                                        |              | You haven't exchanged email with this address before. If you did not expect this email, pay very close attention to it. |
| gelb   | Sender history unknown         | Couldn't search your past email                                                              |              | Couldn't search your past email in order to verify if you have exchanged email with his address before.                 |

## 3. Link-Prüfungen – Bezeichnungen (`Verdict.swift`)

Link format · Link text · Encryption · Look-alike domain · Link shortener · DNS · TLS connection · Domain age · Subdomain certificate · Certificate hostname · Certificate issuer

## 4. Link-Prüfungen – Detailtexte

| Prüfung               | Urteil | Text (Ist)                                                                                                 | Text (soll)                                                                                                                                                                               |
| --------------------- | ------ | ---------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Link format           | rot    | Points to a raw IP address instead of a domain.                                                            | Points to a raw IP address instead of a domain. That's very unusual. This might be an attempt at fraud.                                                                                   |
| Link format           | rot    | Hides the real destination behind an "@".                                                                  | Hides the real destination behind an "@".  This might be an attempt at fraud.                                                                                                             |
| Link format           | gelb   | Link could not be parsed.                                                                                  | Our audit was actively obstructed—that's rather unusual.                                                                                                                                  |
| Link format           | rot    | Uses a "{scheme}:" link, which can run code.                                                               | Uses a "{scheme}:" link, which can run code and open other applications.                                                                                                                  |
| Link text             | rot    | Text shows {shownHost} but the link goes to {host}.                                                        | Text shows {shownHost} but the link goes to {host}. This might be an attempt at fraud.                                                                                                    |
| Encryption            | gelb   | Not encrypted (http).                                                                                      | The link is not encrypted (http), which is either unprofessional or suspicious.                                                                                                           |
| Look-alike domain     | gelb   | Uses look-alike (punycode) characters in the domain.                                                       | So called punycode was used in this domail, which is either unprofessional or suspicious.                                                                                                 |
| Link shortener        | gelb   | Shortened link hides the final destination.                                                                | We can not check the final destination of this link, since it is hidden behind a shortened link, which is either unprofessional or suspicious.                                            |
| DNS                   | gelb   | Couldn't reach public DNS to compare answers.                                                              | We couldn't reach public DNS to compare answers, you could try to check again from another network.                                                                                       |
| DNS                   | gelb   | {host} doesn't exist or has no addresses.                                                                  | {host} doesn't exist or has no addresses, which is either unprofessional or suspicious.                                                                                                   |
| DNS                   | rot    | Your network resolves {host}, but public DNS says the domain doesn't exist.                                | Your network resolves {host}, but public DNS says the domain doesn't exist. This might be an attempt at fraud.                                                                            |
| DNS                   | rot    | Your network points {host} to a private address ({ip}) instead of its public one.                          | Your network points {host} to a private address ({ip}) instead of its public one. This might be an attempt at fraud.                                                                      |
| DNS                   | grün   | DNS answers differ (normal for CDNs), but the server proved its identity with a valid certificate.         |                                                                                                                                                                                           |
| DNS                   | rot    | Your network and public DNS disagree on this domain's address, and the server can't prove its identity.    | Your network and public DNS disagree on this domain's address, and the server can't prove its identity. This might be an attempt at fraud.                                                |
| DNS                   | gelb   | Your network and public DNS disagree on this domain's address, and the link isn't encrypted to confirm it. | Your network and public DNS disagree on this domain's address, and the link isn't encrypted to confirm it. This can happen. Double-check if this email makes a reasonable request to you. |
| DNS                   | grün   | Your network and public DNS agree on the address.                                                          |                                                                                                                                                                                           |
| Domain age            | gelb   | Couldn't look up {domain}'s certificate history.                                                           | Couldn't look up {domain}'s certificate history, which is either unprofessional or suspicious.                                                                                            |
| Domain age            | rot    | {domain} has no certificates in public logs. It's likely brand new.                                        | {domain} has no certificates in public logs. It's likely brand new. This might be an attempt at fraud.                                                                                    |
| Domain age            | gelb   | {domain} has never had an HTTPS certificate, so its age can't be checked.                                  | {domain} has never had an HTTPS certificate, so its age can't be checked.                                                                                                                 |
| Domain age            | rot    | {domain}'s first certificate was issued only {age} ago. New domains are often used for phishing.           | {domain}'s first certificate was issued only {age} ago. New domains are often used for phishing.                                                                                          |
| Domain age            | grün   | {domain} has had certificates for at least {age}.                                                          |                                                                                                                                                                                           |
| Domain age            | grün   | {domain} is a well-known domain, so its age isn't checked.                                                 |                                                                                                                                                                                           |
| TLS connection        | gelb   | Couldn't open a secure connection to check the certificate ({error}).                                      | Couldn't open a secure connection to check the certificate ({error}). This can happen. Double-check if this email makes a reasonable request to you.                                      |
| Certificate hostname  | grün   | Certificate is valid for {host}.                                                                           |                                                                                                                                                                                           |
| Certificate hostname  | rot    | Certificate is for {otherSite}, not {host}.                                                                | Certificate is for {otherSite}, not {host}. This might be an attempt at fraud.                                                                                                            |
| Certificate issuer    | rot    | Your device doesn't trust this certificate: {error}.                                                       | Certificate is for {otherSite}, not {host}. This might be an attempt at fraud.                                                                                                            |
| Certificate issuer    | grün   | Issued by {issuer}.                                                                                        |                                                                                                                                                                                           |
| Certificate issuer    | gelb   | Issued by {issuer}, which isn't a common certificate authority.                                            | Issued by {issuer}, which isn't a common certificate authority. This can happen. Double-check if this email makes a reasonable request to you.                                            |
| Subdomain certificate | grün   | {host} uses the same certificate as {apex}.                                                                |                                                                                                                                                                                           |
| Subdomain certificate | rot    | {host} got its own certificate only {age} ago, on an otherwise established domain.                         | {host} got its own certificate only {age} ago, on an otherwise established domain. This might be an attempt at fraud.                                                                     |
| Subdomain certificate | grün   | {host} has its own certificate, issued {age} ago.                                                          |                                                                                                                                                                                           |

Hilfstexte: Alter wird als "{n} hours" (unter 1 Tag) oder "{n} days" ausgegeben. Fallbacks: "Unknown issuer", "another site", "Invalid host: {host}", "TLS handshake did not complete", "Server sent no certificate".

## 5. Intro-Screen (`IntroView.swift`)

| Element              | Text                                                                                                                        | Text (Soll)                                                                                                  |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Titel                | Welcome to Miramail                                                                                                         |                                                                                                              |
| Untertitel           | Miramail checks every email for phishing before you act on it.                                                              | We are your tech-friend, reviewing your emails to ensure you can respond to email inquiries with confidence. |
| Schritt 1            | **Open an email** – Miramail checks who really sent it and scans every link in it.                                          |                                                                                                              |
| Schritt 2            | **See the verdict** – A green, yellow or red mark sits next to each link, and the shield on the edge opens the full report. |                                                                                                              |
| Schritt 3            | **Tap with confidence** – Risky links ask you to confirm before they open.                                                  |                                                                                                              |
| Hinweis              | On the next step you'll sign in with your Google account, so Miramail can read your Gmail.                                  |                                                                                                              |
| Button               | Continue                                                                                                                    |                                                                                                              |
| Bild (Accessibility) | Preview: an email with a safety mark next to each link and a shield on the edge                                             |                                                                                                              |

## 6. Anmeldung und Posteingang (`InboxView.swift`)

| Element                                     | Text                                                                              |
| ------------------------------------------- | --------------------------------------------------------------------------------- |
| Titel (abgemeldet) / Splash                 | Miramail                                                                          |
| Button                                      | Sign in with Google                                                               |
| Hinweis unter betroffener Mail in der Liste | Please pay close attention to this email!                                         |
| Pencil (Accessibility)                      | New message                                                                       |
| Zahnrad (Accessibility)                     | Settings                                                                          |
| Kontoadresse                                | {email}                                                                           |
| Fehler                                      | Fehlertext des Systems/Gmail, z. B. "Gmail API {status}: {body}", "Not signed in" |

## 7. Mail-Ansicht und Security-Panel (`MessageView.swift`, `SecurityPanel.swift`)

| Element                         | Text                                          | Text (soll)                                                                           |
| ------------------------------- | --------------------------------------------- | ------------------------------------------------------------------------------------- |
| Ladehinweis                     | Scanning links…                               |                                                                                       |
| Tab (Accessibility)             | Security: {Urteil} / Security: scanning links |                                                                                       |
| Panel-Überschrift               | SECURITY                                      |                                                                                       |
| Panel-Schließen                 | Close                                         |                                                                                       |
| Abschnitt                       | LINKS                                         |                                                                                       |
| Link-Check-Zeile                | {Prüfung}: {Detail}                           |                                                                                       |
| Warnung vor Link-Öffnung (rot)  | This link looks unsafe                        | This link looks really unsafe. Only open it, if you know that their request is valid. |
| Warnung vor Link-Öffnung (gelb) | Be careful with this link                     | Be careful with this link, we see some unprofessional or fraudulent technicalities.   |
| Warnung – Text                  | {Host oder URL} + alle nicht grünen Details   |                                                                                       |
| Warnung – Buttons               | Cancel · Open anyway                          |                                                                                       |
| Link nicht öffenbar             | Can't open this link · {URL} · OK             |                                                                                       |
| Move-Dialog                     | Move to · {Label-Namen} · Cancel              |                                                                                       |

## 8. Aktionsleiste und Hinweise (`ActionBar.swift`, `Models.swift`)

| Element | Text |
|---|---|
| Buttons | Delete · Archive · Move · Reply |
| Erfolg | Archived · Moved to Trash · Moved to {label} · Message sent |
| Fehler | Couldn't archive the email · Couldn't delete the email · Couldn't move the email · Couldn't load your labels |
| Zusätzliches Ziel im Move-Dialog | Spam |

## 9. Verfassen (`ComposeView.swift`)

| Element | Text |
|---|---|
| Titel | New message / Reply |
| Felder | To · Subject |
| Button | Send |
| Betreff bei Antwort | Re: {Betreff} |
| Fehler | Fehlertext des Systems/Gmail |

## 10. Einstellungen (`SettingsView.swift`)

| Element   | Text                                                                                                                                                                                         |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Titel     | Settings                                                                                                                                                                                     |
| Schalter  | Allow JavaScript in emails                                                                                                                                                                   |
| Erklärung | Lets scripts inside an email run. Scripts can change what you see after Miramail has scanned the links, so leave this off unless an email needs it. Applies the next time you open an email. |
| Konto     | {email}                                                                                                                                                                                      |
| Button    | Sign out                                                                                                                                                                                     |

## 11. In der E-Mail eingeblendete Badges (`Verdict.glyph`)

✓ (grün) · ! (gelb) · ✕ (rot)

## 12. Sonstiges

| Ort                              | Text                                                        |
| -------------------------------- | ----------------------------------------------------------- |
| App-Name (Android `strings.xml`) | Miramail                                                    |
| Splash iOS/Android               | Miramail                                                    |
| Demo-Mail (nur Debug)            | Beispielmails in `Mail/DemoMail.swift` / `mail/DemoMail.kt` |

Hinweis: Es gibt keine Lokalisierungsdateien. Alle Texte stehen direkt im Code und sind nur auf Englisch vorhanden (auf Android nur der App-Name in `strings.xml`).