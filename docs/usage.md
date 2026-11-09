# Installation & usage

## Sign in

Install the APK from the [Latest release](https://github.com/diogux/mel-android-wrapper/releases/latest). This is a personal debug-signed sideload build, not a Play Store or official Mitsubishi distribution.

Sign in through the browser using your existing MELCloud Home account. The public mobile redirect scheme is `melcloudhome://`, also used by the official app. Choose **Air Conditioner** for the callback. If the official app captures it automatically, clear its **Open by default** preference in Android settings and restart sign-in.

Passwords are entered only on the provider's browser page. Never paste passwords, authorization codes or access/refresh tokens into issues.

## Controls

- **Power:** toggles the desired state; a fresh cloud read validates the command.
- **Target:** drag and release the slider. After three quiet seconds, only the final value is sent. A new drag cancels an older queued target.
- **Mode/fan/vanes:** choose a supported option. Rapid edits merge into a final payload; command attempts are separated by at least three seconds.
- **Device:** choose among owned/shared air-to-air units.
- **Refresh:** reconcile cloud state. A successful command response alone does not prove the AC acted on it.

Capabilities drive which controls appear. Some units omit a Fan-only capability flag; it is shown only when advertised or already reported. Explicit unsupported vane flags take precedence over inferred support.

## Widget

Tap **Add home-screen widget**, or long-press your launcher → Widgets → **Air Conditioner**. Widget instances follow the device selected inside the app. The default request is 4×1 cells; actual grid placement is controlled by the launcher. Short and taller layouts adapt to available space, with text and control rows growing inside the existing box.

The title/temperature opens the app. Mode cycles supported choices; −/+ use the device's temperature step. Refresh requests a cloud snapshot. Room/status details are omitted in the shortest layout, with status available as an accessibility description. Resize taller to show them.

The widget does not poll periodically in the background. Android jobs handle on-demand work and may be deferred by battery/network restrictions. Readings are cached snapshots. Existing widget instances may retain an older launcher reservation until resized or re-added; normal app updates do not require re-adding.

## Phone schedules

Choose **Phone schedules → Add schedule**. Select a time, weekdays, desired power, and mode/temperature for on events. Times use the AC's reported time zone, falling back to the phone's zone.

On Android 12+, enabling a schedule offers a button to **Alarms & reminders** settings. Until access is granted, definitions remain saved but paused. The phone must stay powered on, signed in and online. Force-stopping the app prevents alarms until it is reopened. Signing out disables all phone schedules.

Definitions re-arm after reboot, app open/update, time changes and alarm access grants. Spring DST gaps move forward; repeated autumn times run at the earlier occurrence once. Events delivered over two minutes late are skipped, and stale command drafts expire. **Queued** is a queue result, not confirmation from the hardware.

Up to 40 definitions can be saved. Each remains associated with its original unit. Existing Mitsubishi cloud schedules are read-only here and may run independently; manage them in the official app.

## Energy

Tap **Load energy** if the unit advertises an energy meter. The app shows seven days of returned hourly consumption, grouped into local dates and converted from Wh to kWh. Duplicate timestamps are not added twice. Invalid/implausible values are excluded. Gaps are —, not zero; totals include only returned readings and the current day may be incomplete.

Energy is loaded manually, at least 30 seconds between attempts. Reports are session-memory data, cleared at sign-out/process death. Unsupported devices and empty responses are explicitly identified.

## Connection failures

Refresh before retrying an uncertain command. Failed writes are not blindly replayed. HTTP rate limits respect Retry-After with a one-minute minimum. For expired/rejected sessions, sign in again. If readings/control behavior differ from the official app, include non-secret reproduction steps in a bug report.
