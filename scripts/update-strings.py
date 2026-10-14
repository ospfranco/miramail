#!/usr/bin/env python3
"""
Writes the user-facing strings of both apps from scripts/strings.json (English and German):

  ios/Trustmail/Resources/Localizable.xcstrings      (String Catalog, keyed by the English text)
  android/app/src/main/res/values/strings.xml        (English)
  android/app/src/main/res/values-de/strings.xml     (German)

Edit the source file, then run this script and commit all four files. Never edit the generated files by hand
(Xcode may add keys to the catalog when it builds; move them into strings.json and re-run).

  python3 scripts/update-strings.py          # validate and write
  python3 scripts/update-strings.py --check  # exit 1 if the generated files are out of date

Placeholders are written {name} and become %@ on iOS and %1$s on Android. The script refuses (exit 1) a missing
or extra translation, a translation whose placeholders differ from the English, and two strings with the same
English text but different translations (iOS keys them by the English text).
"""
import json
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "scripts/strings.json"
IOS = ROOT / "ios/Trustmail/Resources/Localizable.xcstrings"
ANDROID = ROOT / "android/app/src/main/res"

SOURCE_LANGUAGE = "en"
TRANSLATIONS = ["de"]
PLATFORMS = {"ios", "android"}
PLACEHOLDER = re.compile(r"\{([a-z_]+)\}")
NAME = re.compile(r"^[a-z][a-z0-9_]*$")


def read_source():
    entries = json.loads(SOURCE.read_text())["strings"]
    errors, names, ios_keys = [], set(), {}
    for entry in entries:
        name = entry.get("name", "")
        where = f"{name or entry}:"
        if not NAME.match(name):
            errors.append(f"{where} name must be lower_snake_case")
        if name in names:
            errors.append(f"{where} duplicate name")
        names.add(name)
        if not set(entry.get("platforms", PLATFORMS)) <= PLATFORMS:
            errors.append(f"{where} platforms must be among {sorted(PLATFORMS)}")
        english = entry.get(SOURCE_LANGUAGE, "")
        if not english:
            errors.append(f"{where} no English text")
        placeholders = PLACEHOLDER.findall(english)
        if len(set(placeholders)) != len(placeholders):
            errors.append(f"{where} each placeholder may appear only once")
        for language in TRANSLATIONS:
            if entry.get("translatable", True):
                if not entry.get(language):
                    errors.append(f"{where} no {language} translation")
                elif sorted(PLACEHOLDER.findall(entry[language])) != sorted(placeholders):
                    errors.append(f"{where} {language} placeholders differ from the English ones")
            elif language in entry:
                errors.append(f"{where} not translatable, but has a {language} translation")
        if "ios" in entry.get("platforms", PLATFORMS):
            key = ios_key(english)
            other = ios_keys.get(key)
            if other and any(other.get(language) != entry.get(language) for language in TRANSLATIONS):
                errors.append(f"{where} same English text as {other['name']} but a different translation")
            ios_keys[key] = entry
    if errors:
        sys.exit("scripts/strings.json:\n  " + "\n  ".join(errors))
    return entries


def on(platform, entries):
    return [entry for entry in entries if platform in entry.get("platforms", PLATFORMS)]


# iOS: the key is the English text as Swift's string interpolation produces it ("Moved to %@").


def ios_key(english):
    return PLACEHOLDER.sub("%@", english)


def ios_value(text, english):
    order = PLACEHOLDER.findall(english)
    if len(order) < 2:
        return PLACEHOLDER.sub("%@", text)
    return PLACEHOLDER.sub(lambda m: f"%{order.index(m.group(1)) + 1}$@", text)


def ios_catalog(entries):
    strings = {}
    for entry in on("ios", entries):
        english = entry[SOURCE_LANGUAGE]
        item = {}
        if entry.get("translatable", True):
            item["localizations"] = {
                language: {"stringUnit": {"state": "translated", "value": ios_value(entry[language], english)}}
                for language in TRANSLATIONS
            }
        else:
            item["shouldTranslate"] = False
        strings[ios_key(english)] = item
    catalog = {"sourceLanguage": SOURCE_LANGUAGE, "strings": strings, "version": "1.0"}
    return json.dumps(catalog, indent=2, ensure_ascii=False, sort_keys=True, separators=(",", " : ")) + "\n"


# Android: positional %1$s placeholders, and the escapes aapt needs.


def android_text(text, english):
    order = PLACEHOLDER.findall(english)
    text = escape(text).replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if text.startswith(("@", "?")):
        text = "\\" + text
    return PLACEHOLDER.sub(lambda m: f"%{order.index(m.group(1)) + 1}$s", text)


def android_strings(entries, language):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Generated by scripts/update-strings.py from scripts/strings.json. Don't edit. -->", "<resources>"]
    for entry in on("android", entries):
        translatable = entry.get("translatable", True)
        if language != SOURCE_LANGUAGE and not translatable:
            continue
        attributes = "" if translatable else ' translatable="false"'
        lines.append(f'    <string name="{entry["name"]}"{attributes}>{android_text(entry[language], entry[SOURCE_LANGUAGE])}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def main():
    check = "--check" in sys.argv[1:]
    entries = read_source()
    outputs = {IOS: ios_catalog(entries), ANDROID / "values/strings.xml": android_strings(entries, SOURCE_LANGUAGE)}
    for language in TRANSLATIONS:
        outputs[ANDROID / f"values-{language}/strings.xml"] = android_strings(entries, language)

    stale = [path for path, text in outputs.items() if not path.exists() or path.read_text() != text]
    if check:
        for path in stale:
            print(f"out of date: {path.relative_to(ROOT)}")
        sys.exit(1 if stale else 0)
    for path in stale:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(outputs[path])
        print(f"wrote {path.relative_to(ROOT)}")
    print(f"{len(entries)} strings, languages: {', '.join([SOURCE_LANGUAGE] + TRANSLATIONS)}")


if __name__ == "__main__":
    main()
