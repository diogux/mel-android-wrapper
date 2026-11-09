#!/usr/bin/env bash
# Publish one rolling release with a stable APK filename and signing identity.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
repo="diogux/mel-android-wrapper"
command -v gh >/dev/null
command -v java >/dev/null
if [[ -n "$(git status --porcelain)" ]]; then
  echo 'Commit source changes before publishing.' >&2
  exit 1
fi
commit="$(git rev-parse HEAD)"
remote_commit="$(git ls-remote origin refs/heads/main | cut -f1)"
if [[ "$commit" != "$remote_commit" ]]; then
  echo 'Publish only the commit currently on origin/main; push or update first.' >&2
  exit 1
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f local.properties ]]; then
  sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
signer="$sdk/build-tools/35.0.0/apksigner"
if [[ ! -x "$signer" ]]; then
  echo 'Set ANDROID_HOME or sdk.dir to an SDK with build-tools 35.0.0.' >&2
  exit 1
fi
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
mkdir -p release
cp app/build/outputs/apk/debug/app-debug.apk release/mel-android-wrapper.apk
actual_certificate="$("$signer" verify --print-certs release/mel-android-wrapper.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
expected_certificate="$(tr -d '[:space:]' < docs/release-certificate.sha256)"
if [[ "$actual_certificate" != "$expected_certificate" ]]; then
  echo 'Signing certificate differs from the established release. Nothing uploaded.' >&2
  echo 'Use the original local signing key so existing installations can update.' >&2
  exit 1
fi
(cd release && sha256sum mel-android-wrapper.apk > SHA256SUMS)
if gh api "repos/$repo/git/ref/tags/latest" >/dev/null 2>&1; then
  # Only this intentionally rolling tag moves; never rewrite a source branch.
  gh api --method PATCH "repos/$repo/git/refs/tags/latest" -f sha="$commit" -F force=true >/dev/null
else
  gh api --method POST "repos/$repo/git/refs" -f ref=refs/tags/latest -f sha="$commit" >/dev/null
fi
if ! gh release view latest --repo "$repo" >/dev/null 2>&1; then
  gh release create latest --repo "$repo" --verify-tag --draft --title 'Latest' --notes-file docs/release-notes.md
fi
gh release upload latest release/mel-android-wrapper.apk release/SHA256SUMS --repo "$repo" --clobber
gh release edit latest --repo "$repo" --draft=false --latest --title 'Latest' --notes-file docs/release-notes.md
echo "Published https://github.com/$repo/releases/latest"
