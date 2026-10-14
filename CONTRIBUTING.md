# Contributing

Thanks for helping improve NikonLink.

## Before opening an issue

Please include:

- camera model and firmware version, if known;
- phone model and Android version;
- whether the phone joined the camera access point directly;
- the NikonLink version and the exact operation that failed;
- a short, redacted logcat excerpt when the issue is protocol related.

Do not include Wi-Fi passwords, personal photos, full device identifiers, or unredacted camera logs.

## Development

Use Android Studio with JDK 17, or run:

```bash
./gradlew :feature-connection:testDebugUnitTest :app:assembleDebug
```

Protocol changes should include a focused unit test. Hardware-only behavior should be described in the pull request, including the camera mode and storage-card state used for the test.

## Pull requests

Keep changes focused, explain user-visible behavior, and update the README or privacy documentation when behavior or permissions change.
