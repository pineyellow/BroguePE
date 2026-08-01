# Building Brogue PE

Brogue PE supports Android 7.0 (API 24) and newer. The current build compiles
against and targets Android 16 (API 36).

## Requirements

- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- Android NDK `28.0.13004108`
- CMake 3.22.1

The project pins Android Gradle Plugin 8.9.2 and Gradle 8.11.1. Gradle
downloads these automatically during the first sync or build; they are not
installed through the Android SDK Manager.

Install the SDK components with Android Studio's SDK Manager, then initialize
the pinned SDL submodules:

```sh
git submodule update --init --recursive
```

## Configuration

Android Studio normally configures its bundled JBR and writes the local SDK
path automatically. For command-line builds:

- Set `JAVA_HOME` to a JDK/JBR directory.
- Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) to the Android SDK directory, or
  create `android/local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

`local.properties`, `.gradle/`, and `.idea/` are ignored by Git, so local paths
are not committed.

## Debug build and checks

From the `android` directory:

```sh
./gradlew test lintDebug assembleDebug
```

On Windows PowerShell, use:

```powershell
./gradlew.bat test lintDebug assembleDebug
```

The debug APK is written to
`android/app/build/outputs/apk/debug/app-debug.apk`. It uses the `.debug`
application ID and Android's debug signing certificate, so it is suitable for
local testing but not for production distribution.
