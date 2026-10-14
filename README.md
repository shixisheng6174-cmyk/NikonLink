# NikonLink

NikonLink is an Android companion for Nikon cameras that exposes the camera's Wi-Fi PTP/IP connection in a simple mobile interface.

NikonLink lets you:

- browse photos and videos stored on the camera;
- load the album in pages as you scroll, with filename, date, format, and sort filters;
- preview images, select files, and save originals to the Android gallery;
- open Nikon Live View and use remote shutter and movie controls;
- read and adjust camera parameters when the camera exposes them as writable;
- connect through a camera access point without depending on mobile data routing.

The project is independent software. “Nikon” and camera model names are trademarks of Nikon Corporation.

## Tested setup

The current development build has been tested with a Nikon ZR access point and a Xiaomi 13 running Android 16. Other cameras and Android devices may expose different PTP/IP operations or parameter descriptors.

The app requires Android 9 (API 28) or newer. Camera support is capability based: a feature is enabled only when the connected camera reports the corresponding operation or property.

## Connect a camera

1. On the camera, open **Connect to smart device → Wi-Fi connection** and start the camera access point.
2. Join that access point in the phone's Wi-Fi settings.
3. Open NikonLink and enter the camera gateway address if it was not detected automatically.
4. Connect, then use **Album** or **Monitor / Remote**.

Keep the phone connected to the camera's Wi-Fi while browsing or transferring. The camera may stop a remote operation when its physical mode, card state, or live-view selector does not allow it; NikonLink reports the camera's error where available.

## Build

Open the project in Android Studio, or use JDK 17 and the included Gradle wrapper:

```bash
./gradlew :feature-connection:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The repository intentionally does not include `local.properties`, Gradle build directories, APKs, device logs, or private test notes. Android Studio will create `local.properties` for the local SDK path.

## Architecture

| Module | Purpose |
| --- | --- |
| `app` | Application shell and camera / remote screens |
| `core-common` | Shared connection and media types |
| `core-data` | Local camera settings and persistence |
| `feature-connection` | Wi-Fi routing, BLE scaffolding, and PTP/IP |
| `feature-browser` | Album paging, previews, filtering, and selection |
| `feature-transfer` | Transfer infrastructure kept for future background jobs |
| `feature-remote` | Remote feature module and shared contracts |

PTP/IP framing and camera behavior are based on the public PTP/IP specification and compatibility observations from the libgphoto2 project. See the source comments and tests for protocol details.

## Privacy

NikonLink is designed for local camera communication. It does not require an account or a cloud service. See [PRIVACY.md](PRIVACY.md) for the current data-handling description.

## Contributing

Bug reports and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first and include the camera model, Android version, connection mode, and a sanitized log when reporting a protocol issue.

## License

NikonLink is released under the [MIT License](LICENSE).
