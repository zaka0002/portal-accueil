# Portal Accueil

A home screen for Meta Portals running Android 10, with hands-free "Alexa" built in.

On Android 10 the Portal only lets the app in front use the microphone, so a wake-word app running behind your launcher keeps getting silenced. Portal Accueil is the launcher itself: it is on screen almost all the time, so it keeps hearing "Alexa" and hands the turn to the Portal's own Alexa client. It replaces Réveil Alexa + a separate launcher, and Portal HA Bridge's dashboard.

The easiest way to install it is [JailPortal](https://github.com/zaka0002/jailportal): one click installs it, sets every permission below and makes it the home screen.

## The home screen

- Large clock and date, weather for the next 4 days ([Open-Meteo](https://open-meteo.com/), no account), a **Talk to Alexa** button and the listening state.
- All your apps, 4 per row. Long-press an app and drag it onto another to move it there; near the top or bottom edge the list scrolls by itself.
- Long-press an app and let go to rename it, move it to the start or the end, hide it, give it another icon (handy for Meta's lab-flask icons), open its info or uninstall it.
- Long-press the clock to bring hidden apps back, go back to alphabetical order, see the listening state or open Android settings.

Weather is set to Reims (`CITY` and `WEATHER_URL` in `HomeActivity.java`).

## How "Alexa" works

- Listens offline with [Vosk](https://alphacephei.com/vosk/) and `vosk-model-small-fr-0.22`, restricted to the grammar `["alexa", "[unk]"]`. No audio leaves the Portal.
- Fires only on a **finished** recognition that **ends with "alexa"**, with word confidence ≥ 0.85, so TV and conversation don't set it off.
- On a hit it releases the mic and beeps, brings Meta's Alexa client (`com.amazon.alexa.multimodal.falcon/.SIMActivity`) to the front and 0.8 s later sends it `com.amazon.alexa.multimodal.falcon.LISTEN`. Falcon's `TURN_DONE` broadcast ends the turn (one within 1.5 s is the known cold-start abort, so LISTEN is sent once more). When the turn started on the home screen, it comes back to the home screen, which also hides falcon's leftover "Send a message to Alexa" page.
- With another app in front, the capture gets silenced; a watchdog (`AudioRecordingConfiguration.isClientSilenced()`) reopens it while an invisible activity is on top for under a second.

Say "Alexa", wait for the beep, then give your command. Status page: long-press the clock → listening state, or `adb shell am start -n fr.portal.accueil/.MainActivity`. Events are logged to `/sdcard/Android/data/fr.portal.accueil/files/journal.txt`.

## Requirements

- A Portal with ADB enabled (Settings → Debug → ADB Enabled).
- Meta's Alexa client **falcon** (`com.amazon.alexa.multimodal.falcon`), signed in at amazon.com/code. Get it from [starbrightlab/hey-dist](https://github.com/starbrightlab/hey-dist/releases/tag/v0.1.0) (SHA-256 `76133f807e492e46aaf58e6ae503e623a93af840eaa3eccfb8630f1ecab3268d`), or let JailPortal install it. It needs `pm grant` of `RECORD_AUDIO`, `READ_PHONE_STATE` and `INTERACT_ACROSS_USERS` and `appops set … SYSTEM_ALERT_WINDOW allow`.
- Uninstall Réveil Alexa and turn off Portal HA Bridge's Alexa support (or uninstall it), so only one app listens.
- Keeping [Immortal](https://github.com/starbrightlab/immortal) installed is recommended: it confirms app installs on the Portal and can repair falcon.

## Manual install

```sh
adb install -r -g portal-accueil.apk
adb shell appops set fr.portal.accueil SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +fr.portal.accueil
adb shell cmd package set-home-activity fr.portal.accueil/.HomeActivity
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

To go back to Immortal: `adb shell cmd package set-home-activity com.immortal.launcher/.HomeActivity`.

## Build

JDK 17 and the Android SDK, then `./gradlew assembleRelease` (output: `app/build/outputs/apk/release/app-release.apk`, signed with the debug key).

## License

MIT for this app's code. [Vosk](https://github.com/alphacep/vosk-api) and the `vosk-model-small-fr-0.22` model are Apache-2.0, by Alpha Cephei. Not affiliated with Meta or Amazon; "Alexa" is a trademark of Amazon.
