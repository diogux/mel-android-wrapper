# Verification

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The current suite contains **39 tests**, covering:

- OAuth PKCE/PAR/state validation, code exchange, token refresh/rotation and session failures.
- Owned/shared device discovery, capability validation and documented request payloads.
- Three-second queue merging, draft expiry, explicit desired power and network/rate-limit behavior.
- Weekly schedules, time zones/DST, alarm access, cancellation, late/duplicate delivery and queue dispatch.
- Energy request formatting, Wh→kWh conversion, deduplication, missing/invalid readings and local dates.
- Android 8 compatibility and Android 15 native View/RemoteViews rendering.
- The scroll-jump regression: vane updates preserve the same scrolling view, controls and position.
- Widget text/touch targets growing inside existing bounds; short/standard/expanded layout behavior.

Robolectric renders previews from synthetic data. Tests do not authenticate a real Mitsubishi account or prove physical AC execution. The GitHub workflow runs build/tests/lint; generated reports are available in its job summary/logs rather than committed to Git.

## Phone acceptance checks

1. Install/update and confirm session/device restoration and browser return routing.
2. Compare readings and supported controls with the official app.
3. Move the slider repeatedly, release, and verify the final target after the quiet period.
4. Change vanes while scrolled down; the screen should stay in place.
5. Check widget behavior at the launcher's actual sizes without unexpected grid growth.
6. Create a near-future phone schedule, grant alarm access, compare resulting cloud/physical state, then test disable/delete/reboot behavior.
7. Load energy if supported and compare against the official app.
8. Confirm failure/sign-out behavior without exposing credentials.

Real browser, Keystore, OEM launcher, battery policy, cloud propagation and hardware behavior require on-device testing. A successful HTTP response is not proof of AC execution.
