#!/bin/sh
# Starts `run` on the phone in its own session, so a USB drop (adbd ending the shell) can't kill it.
# ./start-detached.sh [waitSec] [phaseSec] [run|listen]. It acts on ANY call in the next waitSec seconds; disarm with ./probe.sh stop.
# Log: adb shell cat /data/local/tmp/ibridge-probe/run.log
A=${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb
$A shell "mkdir -p /data/local/tmp/ibridge-probe; rm -f /data/local/tmp/ibridge-probe/run.log; CLASSPATH=/data/local/tmp/ibridge-probe.dex setsid nohup app_process / app.ibridge.probe.CallAudioProbe ${3:-run} ${1:-600} ${2:-12} > /data/local/tmp/ibridge-probe/run.log 2>&1 < /dev/null &"
