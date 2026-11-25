#!/usr/bin/env python3
"""Kotlin (Android) and Swift (iPhone) protocol code talk to each other over pipes.
Build:  ../../android/gradlew -p . installDist  &&  swiftc -o build/ios-side ../../ios/iBridge/Core/*.swift main.swift
Run:    ./run.py    (exit 0 = every scenario behaved as expected)"""
import json, os, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.join(HERE, "build/install/interop/bin/interop")
IOS = os.path.join(HERE, "build/ios-side")

def scenario(name, ios_args_from_window, want_ios, want_android):
    a2i_r, a2i_w = os.pipe()
    i2a_r, i2a_w = os.pipe()
    wf = tempfile.mktemp(suffix=".json")
    android = subprocess.Popen([ANDROID, wf], stdin=i2a_r, stdout=a2i_w, stderr=subprocess.PIPE, text=True)
    for _ in range(300):
        if os.path.exists(wf) and os.path.getsize(wf) > 0: break
        time.sleep(0.05)
    window = json.load(open(wf))
    ios = subprocess.Popen([IOS] + ios_args_from_window(window), stdin=a2i_r, stdout=i2a_w, stderr=subprocess.PIPE, text=True)
    for fd in (a2i_r, a2i_w, i2a_r, i2a_w): os.close(fd)
    try:
        ios_err = ios.communicate(timeout=60)[1]
    finally:
        android.wait(timeout=10) if android.poll() is None else None
    android_err = android.stderr.read()
    ok = ios.returncode == want_ios and android.returncode == want_android
    print(f"{'PASS' if ok else 'FAIL'} {name}: ios={ios.returncode} android={android.returncode}")
    if not ok or "-v" in sys.argv:
        print(ios_err.strip()); print(android_err.strip())
    return ok

def wrong(code):
    alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    return alphabet[(alphabet.index(code[0]) + 1) % 32] + code[1:]

results = [
    scenario("QR pairing, session, 40-item page, ping", lambda w: ["qr", w["uri"]], 0, 0),
    scenario("typed code pairing, session, page, ping", lambda w: ["code", w["code"]], 0, 0),
    scenario("wrong code is refused in round 0", lambda w: ["code", wrong(w["code"])], 2, 3),
    scenario("bench: AUDIO records both ways, Android skips counters", lambda w: ["qr", w["uri"], "bench"], 0, 0),
    scenario("wrong last character is refused in round 4", lambda w: ["code", w["code"][:5] + wrong(w["code"][5])], 2, 3),
]
sys.exit(0 if all(results) else 1)
