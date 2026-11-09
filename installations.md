# Development toolchain & installations

This project reuses JDK 21 (JDK 17 also works), Git and Gradle 8.9. No Flutter, React Native, npm packages, Android Studio, emulator or system package installation is required.

## Android command-line setup used during development

Google command-line tools 22.0 (`commandlinetools-linux-15859902_latest.zip`) were downloaded and checked against SHA-256 `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`.

Equivalent commands, with a local SDK path of your choice:

```sh
mkdir -p android-sdk/cmdline-tools
curl -fL https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o commandlinetools.zip
printf '%s  %s\n' 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 commandlinetools.zip | sha256sum -c -
unzip -q commandlinetools.zip -d android-sdk/cmdline-tools
mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
export ANDROID_HOME="$PWD/android-sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-35' 'build-tools;35.0.0'
```

Installed SDK components: command-line tools 22.0; platform API 35 revision 2; build-tools 35.0.0. An initial unpinned build also installed build-tools 34.0.0 and platform-tools 37.0.1 automatically; the project now pins build-tools 35.0.0.

## Resolved by Gradle

- Android Gradle Plugin 8.7.3.
- Kotlin Android plugin/compiler/stdlib 2.0.21.
- Test-only JUnit 4.13.2, org.json 20240303 and Robolectric 4.14.1.
- Robolectric transitive AndroidX test/graphics dependencies and instrumented Android 8/15 JVM test runtimes.

The first build downloads these artifacts automatically:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`dependency-inventory.txt` records resolved coordinates. Regenerate it with:

```sh
./gradlew writeBuildInventory -I dependency-inventory.init.gradle
```

Existing ffmpeg/ffprobe were used to inspect a user-supplied bug recording; they were not installed for this project and are not needed to build. User recordings, machine-specific paths, SDKs/caches and signing keys are excluded from the repository. Repository preparation added no new local software installations.

## GitHub-hosted checks

The CI workflow installs the same pinned command-line tools archive (`15859902`) through `android-actions/setup-android`, then platform-tools, platform API 35 and build-tools 35.0.0 on the ephemeral runner. Java 21 is set up by `actions/setup-java`. Action revisions are pinned in the workflow. This adds no software to the maintainer's computer and publishes no CI-generated signing key or APK.
