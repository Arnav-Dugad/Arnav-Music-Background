#!/usr/bin/env bash
set -euo pipefail

# Android 17's emulator image crashes in GoldfishMapper while persisting recent-task
# screenshots. Disable only that emulator feature, using the real Binder interface
# rather than guessing transaction numbers. Do not change the app or stop System UI.
# Restore hidden API enforcement before any app/test process starts.
original_policy=$(adb shell settings get global hidden_api_policy | tr -d '\r')
restore_policy() {
  if [ "$original_policy" = "null" ]; then
    adb shell settings delete global hidden_api_policy
  else
    adb shell settings put global hidden_api_policy "$original_policy"
  fi
}
trap restore_policy EXIT
adb push /tmp/snapshot-control.jar /data/local/tmp/snapshot-control.jar
adb shell settings put global hidden_api_policy 1
adb shell CLASSPATH=/data/local/tmp/snapshot-control.jar app_process /system/bin DisableTaskSnapshots
restore_policy
trap - EXIT
