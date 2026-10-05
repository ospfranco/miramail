#!/usr/bin/env bash
# Downloads the Public Suffix List and writes the same trimmed copy into both apps:
# ICANN section only (what the original `tldts` getDomain() used), comments stripped,
# non-ASCII labels converted to punycode so rules match the hostnames found in links.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
targets=(
  "$root/ios/Trustmail/Resources/public_suffix_list.dat"
  "$root/android/app/src/main/assets/public_suffix_list.dat"
)

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl -fsSL https://publicsuffix.org/list/public_suffix_list.dat -o "$tmp"

python3 - "$tmp" "${targets[0]}" <<'PY'
import sys

source, target = sys.argv[1], sys.argv[2]
rules, in_icann = [], False
for line in open(source, encoding="utf-8"):
    line = line.strip()
    if "===BEGIN ICANN DOMAINS===" in line:
        in_icann = True
    elif "===END ICANN DOMAINS===" in line:
        break
    if not in_icann or not line or line.startswith("//"):
        continue
    rule = line.split()[0].lower()
    labels = [l if l.isascii() else l.encode("idna").decode("ascii") for l in rule.split(".")]
    rules.append(".".join(labels))
open(target, "w", encoding="ascii").write("\n".join(rules) + "\n")
print(f"{len(rules)} rules")
PY

for target in "${targets[@]:1}"; do
  mkdir -p "$(dirname "$target")"
  cp "${targets[0]}" "$target"
done
