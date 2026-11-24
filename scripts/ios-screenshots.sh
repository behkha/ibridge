#!/usr/bin/env bash
# Boots an iPhone Simulator, installs the built app and screenshots the demo scenarios
# that match the mockups (Main, A-Calls, A-Persian, and the second-round screens). Usage: ios-screenshots.sh <app> <out-dir>
set -euo pipefail
APP="$1"; OUT="$2"; mkdir -p "$OUT"
UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys, re
devs = json.load(sys.stdin)["devices"]
cands = [d for rt, ds in devs.items() if "iOS" in rt for d in ds if re.fullmatch(r"iPhone \d+( Pro)?", d["name"])]
print(sorted(cands, key=lambda d: d["name"])[-1]["udid"])')
echo "simulator $UDID"
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time "9:41" --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3
xcrun simctl install "$UDID" "$APP"
shot() {
  local name="$1"; shift
  xcrun simctl terminate "$UDID" app.ibridge.ios 2>/dev/null || true
  xcrun simctl launch "$UDID" app.ibridge.ios "$@"
  sleep 6
  xcrun simctl io "$UDID" screenshot "$OUT/$name.png"
  echo "captured $name"
}
shot Main -demo messages -AppleLanguages "(en)" -AppleLocale en_US
shot A-Calls -demo calls-offline -AppleLanguages "(en)" -AppleLocale en_US
shot A-Persian -demo messages -AppleLanguages "(fa)" -AppleLocale fa_IR
shot Conversation -demo conversation -AppleLanguages "(en)" -AppleLocale en_US
shot Conversation-fa -demo conversation -AppleLanguages "(fa)" -AppleLocale fa_IR
shot Keypad -demo keypad -AppleLanguages "(en)" -AppleLocale en_US
shot Contacts -demo contacts -AppleLanguages "(en)" -AppleLocale en_US
shot Settings -demo settings -AppleLanguages "(en)" -AppleLocale en_US
shot Settings-fa -demo settings -AppleLanguages "(fa)" -AppleLocale fa_IR
shot Pairing -demo pairing -AppleLanguages "(en)" -AppleLocale en_US
shot Pairing-code -demo pairing-code -AppleLanguages "(en)" -AppleLocale en_US
