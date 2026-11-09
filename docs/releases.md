# Publishing the latest APK

The repository keeps **one rolling GitHub release**:

- Tag: `latest`.
- Title: **Latest**.
- Assets: `mel-android-wrapper.apk` and `SHA256SUMS`.
- Stable link: `https://github.com/diogux/mel-android-wrapper/releases/latest/download/mel-android-wrapper.apk`.

No APKs are committed to Git, and filenames do not accumulate version suffixes. Android's internal `versionCode`/`versionName` remain in the build because Android needs a monotonically increasing version code for upgrades.

## Signing

The established download is signed with the maintainer's existing local Android development key. The **public** certificate fingerprint is pinned in `release-certificate.sha256`; the private key is never committed or uploaded to GitHub. Preserve a secure local backup of the original key. A newly generated debug key will not update existing installations.

CI verifies source but does not publish its own freshly generated debug key. This avoids incompatible updates. The current release is intentionally a personal debug-signed build, not a hardened production/Play Store release.

## Publish

1. Increase `versionCode` when changing the app; update `versionName` as appropriate.
2. Commit/push source to `main` and check the Android workflow.
3. On the maintainer's machine, with the original signing key, Android SDK, JDK, Git and authenticated `gh`:

```sh
./scripts/publish-release.sh
```

The script requires clean source matching `origin/main`, builds/tests/lints, checks the signing fingerprint, and generates a checksum. It then moves only the intentional rolling `latest` tag to that commit, creates the release if needed, replaces the same two assets, and marks the release Latest. It does not force-push source branches or create historical APK releases. Git history still preserves source revisions.

The GitHub release's automatic source ZIP/tarball is provided by GitHub and is separate from the single APK asset.
