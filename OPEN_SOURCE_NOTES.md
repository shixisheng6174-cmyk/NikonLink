# Open-source package notes

This package contains the complete application source, Gradle wrapper, tests, design documents, issue templates, license, privacy statement, and one installable debug APK.

Machine-specific SDK configuration, Gradle output, private hardware logs, internal handover notes, and downloaded protocol reference files are intentionally omitted. They are not required to build or review the application and may contain local paths or device-specific details.

To build on a new machine, install Android Studio or configure JDK 17 and Android SDK 34, then run:

```bash
./gradlew :feature-connection:testDebugUnitTest :app:assembleDebug
```

The build creates a local `local.properties` file or reads `ANDROID_HOME` / `ANDROID_SDK_ROOT`; neither belongs in version control.

## First GitHub upload

Create an empty repository on GitHub, then run these commands from this folder:

```bash
git init
git add .
git commit -m "Initial open-source release"
git branch -M main
git remote add origin https://github.com/YOUR_ACCOUNT/NikonLink.git
git push -u origin main
```

Replace the remote URL with your repository URL. The APK is allowed by `.gitignore` under `releases/`; GitHub Releases can be used later for larger binary distribution.
