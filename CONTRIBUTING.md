# Contributing

## Requirements

- JDK 17 or 21.
- Android SDK platform 35 and build-tools 35.0.0.
- Network access on the first build to resolve Gradle/plugin/test artifacts.

Gradle 8.9 is provided by the wrapper. Android Studio is optional.

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Alternatively set `sdk.dir=/path/to/android-sdk` in untracked `local.properties`. No local MELCloud credentials are needed for tests.

## Changes

Keep runtime dependencies small, respect reported capabilities, and inspect real protocol evidence before adding endpoints. Use explicit desired values rather than replayable toggle operations. Avoid background polling and never log authentication material.

For UI work, test compact/expanded widgets and both Android 8/15 compatibility paths. Existing tests render native Android Views with synthetic data; previews must not be presented as live AC verification.

Before opening a pull request, run the build, unit tests and lint. Explain the user-visible change and what was tested. Screenshots/recordings are useful for layout changes; remove personal/account data first.

## Repository hygiene

APKs, caches, verification outputs, local SDK configuration and signing keys are ignored. Keep the source tree free of generated binaries and historical APK copies. [Release publishing](docs/releases.md) maintains one rolling download.
