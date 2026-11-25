#!/bin/sh
# Phase-2 spike: build the probe, push it, run it as the shell user over adb.
#   ./probe.sh check | run [waitSec] [phaseSec] | restore | stop | pull <dir>
# The call's far side is recorded: tell the other party first. Delete afterwards:
#   adb shell rm -r /data/local/tmp/ibridge-probe
# Far-side call audio stays on the phone (/data/local/tmp/ibridge-probe); `pull` copies it
# to a folder you name, which must be outside this public repo.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
A=$SDK/platform-tools/adb
JAR=$SDK/platforms/android-35/android.jar
D8=$SDK/build-tools/35.0.0/d8
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}

if [ "$1" = pull ]; then
  D=$(cd "$2" && pwd -P) || exit 1
  case "$D" in "$(cd "$(git rev-parse --show-toplevel)" && pwd -P)"*) echo "refusing: $2 is inside the repo" >&2; exit 1;; esac
  $A pull /data/local/tmp/ibridge-probe/downlink.pcm "$D/"; exit $?
fi

rm -rf build && mkdir -p build/classes
"$JAVA_HOME/bin/javac" -nowarn -source 11 -target 11 -cp "$JAR" -d build/classes CallAudioProbe.java 2>&1 | grep -v -E 'bootstrap|options|^[0-9]+ warning' || true
"$D8" --lib "$JAR" --min-api 33 --output build build/classes/app/ibridge/probe/*.class
$A push -q build/classes.dex /data/local/tmp/ibridge-probe.dex
$A shell "CLASSPATH=/data/local/tmp/ibridge-probe.dex app_process / app.ibridge.probe.CallAudioProbe $*"
