# Building Brogue PE

Brogue PE targets Android 7.0 (API 24) and newer.

## Requirements

- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK Platform 35
- Android NDK `28.0.13004108`
- CMake 3.22.1

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

## Build

From the `android` directory:

```sh
./gradlew assembleDebug
```

On Windows PowerShell, use `./gradlew.bat assembleDebug`.
The APK is written under `android/app/build/outputs/apk/`.
