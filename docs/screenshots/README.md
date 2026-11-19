# Public screenshots

`tasks-light.png` and `chat-dark.png` are real Compose screen captures populated with synthetic
example data by `PublicScreenshotsTest`. They are demonstration states, not live server sessions.
No connection to Kandev, Cloudflare or Firebase is required for capture.

Use a dedicated, fresh debug install on a connected Android device. The capture test refuses
saved server profiles; do not clear a shared user's app data to bypass this check. Read the
repository's device policy in [AGENTS.md](../../AGENTS.md) before running instrumentation.

```sh
adb devices
# For a remote device, connect the operator-provided endpoint first.
# Set ANDROID_SERIAL to the selected device if more than one is connected.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class ca.mslab.forge.ui.PublicScreenshotsTest -e forgeScreenshots true \
  ca.mslab.forge.debug.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/ca.mslab.forge.debug/files/screenshots/tasks-light.png docs/screenshots/
adb pull /sdcard/Android/data/ca.mslab.forge.debug/files/screenshots/chat-dark.png docs/screenshots/
```

The manual instrumentation command preserves the app files for `adb pull`; Gradle’s connected
test task may uninstall the test installation and remove its screenshots afterward. The capture
is opt-in and skipped by ordinary instrumentation runs.

Review every image before committing. Keep synthetic names, generic repositories and sample
messages; never capture credentials, enrollment JSON, personal task titles or a production thread.
The capture is the app content surface, so system notification/status-bar data is excluded.
