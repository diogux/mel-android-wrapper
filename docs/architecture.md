# Architecture & protocol

```text
Native Android app / widget
          │ OAuth bearer + HTTPS
          ▼
MELCloud Home mobile BFF
          │
          ▼
Mitsubishi air-to-air unit
```

## Components

| Component | Responsibility |
| --- | --- |
| `Protocol.kt` | PKCE/PAR authentication, refresh, HTTPS API, device parsing and capability validation |
| `SecureVault.kt` | AES-GCM session storage backed by Android Keystore |
| `ClimateController.kt` | One session, serial network executor, app/widget state and command dispatch |
| `CommandQueue.kt` | Persisted latest-value coalescing, quiet period and draft expiry |
| `MainActivity.kt` / `ThermostatView.kt` | Native Views, stable bindings and code-drawn temperature dial |
| `ClimateWidget.kt` / `ClimateJobService.kt` | RemoteViews, responsive sizing and on-demand background work |
| `WeeklySchedule.kt` / `PhoneSchedules.kt` | Time-zone-aware phone schedules and Android alarm delivery |
| `Energy.kt` | Hourly telemetry parsing, validation, deduplication and local-date summaries |

The app uses Android platform APIs and Kotlin. JUnit, org.json and Robolectric are test dependencies; Robolectric/AndroidX testing artifacts are not shipped in the APK.

## Authentication

Host: `https://auth.melcloudhome.com`.

1. Generate a random PKCE verifier, SHA-256 challenge and state.
2. `POST /connect/par`, with public client `homemobile`, redirect `melcloudhome://`, code response and scopes `openid profile email offline_access IdentityServerApi`.
3. Open `/connect/authorize` with the returned request_uri in the external browser.
4. Validate callback state and login age, then exchange the code at `/connect/token`.
5. Refresh using the stored refresh token. Token rotation is persisted; network failures do not erase a usable session.

No credentials, tokens or secrets belong in source, logs or issue attachments.

## Device API

Base: `https://mobile.bff.melcloudhome.com`.

- `GET /context`: owned/guest buildings, air-to-air devices, settings, capabilities and existing schedule metadata.
- `PUT /monitor/ataunit/{unitId}`: all eight documented control fields; unchanged fields are JSON null.
- `GET /telemetry/telemetry/energy/{unitId}`: UTC `from`/`to`, interval `Hour`, measure `cumulative_energy_consumed_since_last_upload`.

Control fields: `power`, `operationMode`, `setFanSpeed`, `vaneHorizontalDirection`, `vaneVerticalDirection`, `setTemperature`, `temperatureIncrementOverride`, `inStandbyMode`.

Mode `Automatic` differs from fan/vane `Auto`. Fan/vane positions use documented strings. Validate against fresh context and carry the current mode with power changes. A 200 response means accepted by the cloud, not confirmed hardware execution.

## Scheduling / consistency

Main-screen refresh occurs on resume and once per foreground minute. There is no continuous background service. Widget commands use short Android jobs. Pending values are merged per device and sent after a three-second quiet period, with a three-second gap after a completed attempt. Drafts expire after two minutes and attempted commands are removed before PUT so process death cannot replay an uncertain write.

Phone definitions use AlarmManager and the same control queue. Mobile ATA cloud-schedule write routes/shapes were explicitly marked inferred in the inspected research, so this project does not invent those requests. Cloud schedule context is displayed read-only.

ATA energy buckets contain Wh, converted to kWh. Repeated timestamps take the maximum observation; malformed, negative/nonfinite and implausible (>100 kWh/hour) values are rejected. Missing readings remain unknown.

## Research

The implementation was informed by the MIT-licensed source of [melcloudhome](https://github.com/andrew-blake/melcloudhome) and [aiomelcloudhome](https://github.com/erwindouna/aiomelcloudhome). `research.json` records inspected revisions; `licenses/` retains notices. Relevant sources include auth/client/model code, the energy trackers, and `docs/api/ata-api-reference.md`'s schedule evidence notes. This is the newer MELCloud Home protocol, not the old MELCloud API.
