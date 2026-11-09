# Security & privacy

Tokens and pending OAuth state are encrypted with AES-GCM using Android Keystore. Password entry happens in the provider's external browser flow. Backups/device transfer exclude app preferences. The app has no analytics, ads or credential logging.

Device names/readings and pending command definitions are private app data. The widget intentionally displays selected device information on the home screen.

Permissions are INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED and SCHEDULE_EXACT_ALARM. Alarm access is user-granted for phone schedules. There is no foreground service or periodic background polling.

Do not post passwords, authorization codes, tokens, private keys, full HTTP captures or unredacted account data in issues. Report suspected vulnerabilities privately to the repository owner; use GitHub private vulnerability reporting if available. Include non-secret reproduction details and impact.

This is an unofficial client for an undocumented third-party API. Review the code and keep Android/browser components updated when using a sideloaded build.
