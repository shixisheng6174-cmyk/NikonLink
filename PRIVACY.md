# Privacy

NikonLink is a local Android application. It has no sign-in, analytics SDK, advertising SDK, or cloud upload service in this repository.

## What the app accesses

- Wi-Fi and network state, to find the local camera access point and route PTP/IP traffic through it.
- Bluetooth APIs where the platform connection flow needs them.
- Camera files, only when the user asks to browse previews or save originals.
- Local app storage for camera connection settings.

Saved media stays on the phone and is written to the Android gallery under the NikonLink folders. PTP/IP traffic is sent to the camera's local address; the app does not send media to a remote server.

## Logs

Debug builds may write protocol diagnostics to Android logcat to help diagnose a connection. Users should remove or redact SSIDs, addresses, filenames, and camera identifiers before sharing logs publicly.

## Third-party services

This project does not operate a backend. Build dependencies are downloaded by Gradle from the repositories declared in `settings.gradle.kts`.
