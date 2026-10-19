# Notch — progress

Plan and decisions: [PLAN.md](PLAN.md). Built in checkpointed parts; each ends built + committed + this file updated.

| Part | What | State |
|---|---|---|
| 1 | Read the system font + accent off the phone | **done 2026-10-16**: font OPlusSans 2.0 (SysSans-En-Regular.ttf = system sans-serif, used via FontFamily.Default); accent Settings.Secure `sysui_type_accent_color` (#ff4f67d2 here) |
| 2 | New app + island in the notch, OxygenOS look | **on the phone 2026-10-16**: in the notch, charging dots showing; Prism's island switched off |
| 3 | Live extras: earbuds + battery, screen recording / hotspot, low battery + charging done | **built 2026-10-16, not yet on the phone** |
| 4 | Quick actions panel (tap the idle island) | **tested on the phone 2026-10-16**: torch on/off, Wi-Fi + Bluetooth via Shizuku, Timer — all work (Dheer) |
| 5 | Vault: 4 taps → face → hidden apps | **built 2026-10-19, not yet on the phone** (see Part 5 notes) |
| 6 | Settings / controls | basic screen done in part 2; the Hidden apps card (face setup / test / learn / forget) added 2026-10-19 |
| 7 | Retire Morse | todo |

## Part 2 notes
- Ported from Prism's island (commit c17d5cd): notch tab, music morph + card, live activities, split bubbles,
  notification drip, glyph flash, charging moment, unlock glyph, face glyph. Dropped: AI agent, ask-in-island,
  glass/lens styles, Nothing look, Morse broadcasts.
- Taps: every tap counts to the knock (4 = vault). A single tap: music → card, call/download → open it,
  idle → quick actions (part 4). Hold → Prism's assistant if installed.
- Accessibility service `Notch island` puts it in the notch (TYPE_ACCESSIBILITY_OVERLAY); it also reports the app
  in front, for re-hiding vault apps. No window content is read.

## Before testing on the phone
- Turn Prism's island off (Prism → Settings → Island) so there aren't two. Prism's accessibility switch can stay.
- Install, open Notch, switch on: Accessibility → Notch island, Notification access → Notch.
- Keep the phone awake during tests (Morse's stayAwake trick won't exist once Morse goes — add one to Notch).

## Part 3 notes
- Earbuds: ACL_CONNECTED for an audio-class device → pop "Name · 80%". Battery from hidden
  `BluetoothDevice.getBatteryLevel()` (reflection) or the hidden BATTERY_LEVEL_CHANGED broadcast within 8 s.
  Needs BLUETOOTH_CONNECT (asked from the Earbuds toggle). UNTESTED: whether OxygenOS sends that broadcast.
- Screen recording: an ongoing notification whose package contains "screenrecord" (or title "screen record…") →
  live RECORDING (breathing red dot + elapsed time). UNTESTED: OxygenOS recorder's package/notification shape.
- Hotspot: hidden WIFI_AP_STATE_CHANGED broadcast (state 13 = on) → live HOTSPOT; pull down opens tether settings.
- Fully charged: one pop when it reaches 100% on the cable. Low battery pop at 15% (from Prism).

## Part 4 notes
- Tap the idle island (one tap, nothing after it) → the island grows into a black panel: Torch, Wi-Fi, Bluetooth,
  Camera, Timer, Ask Prism (only if Prism is installed). Lit tiles use the phone's accent. Hold a tile → its settings
  page (Timer: hold starts a 5-minute timer). Tap outside / tap the panel / 6 s idle → closes.
- Wi-Fi: `svc wifi enable|disable` through Shizuku; without Shizuku, the system Wi-Fi panel opens.
- Bluetooth: `cmd bluetooth_manager enable|disable` through Shizuku; else BluetoothAdapter.enable() (still allowed on 12).
- Torch: CameraManager.setTorchMode, state from TorchCallback (so it also follows the system tile).
- Shizuku bridge ported from Prism (UserService "notch-shell"); setup step "Allow Notch in Shizuku".

## Testing tips
- Keep awake (test builds): `adb shell am start -n com.meetdheeran.notch/.MainActivity --ez stayAwake true`
  (the island window gets FLAG_KEEP_SCREEN_ON, 3 h max, stops on unplug). Morse's trick no longer works —
  Home is back on the OnePlus launcher, so Morse's HomeEntry alias is disabled.
- adb output ends lines with a carriage return: pipe through `tr -d '\r'` before `grep -x`.

## Part 5 notes (the vault)
- Built 2026-10-19 in three commits (face engine; flow + screen + grid + re-hiding; settings rows), unit tests for the
  flow (VaultFlowTest). NOT yet run on the phone: Dheer's turn, steps below.
- Hide = Shizuku `pm disable-user --user 0`, restore = `pm enable --user 0` (vault/Hider.kt); the outcome is checked
  against the package manager's own state, not pm's words. Only user-installed apps can be hidden (never system apps,
  Notch or Shizuku). The app's name and icon are saved at hide time (vault/<pkg>.png), so the grid never has to ask
  the system about a disabled app.
- The way in (vault/VaultFlow.kt, pure): 4 taps → VaultActivity (see-through) runs the face check, 2 tries, while
  the island shows the face glyph (scan / smile / red shake). Both fail → the glyph stays, dim and still
  (FaceScan.HELD) until a tap elsewhere; 3 taps on it → one more try → fail → the phone's own PIN pad (PinLock:
  DEVICE_CREDENTIAL only, as Dheer said "PIN pad"; fingerprint isn't offered). No face set up, or no camera
  permission → the PIN pad straight away. A tap anywhere during a scan gives up.
- In: the hidden apps as an OxygenOS-style sheet. Tap = restore + open (the sheet closes); hold = unhide for good;
  "+" = pick an app to hide (disabled on the spot). Tap the dim → close. A knock from Notch's settings ("Hidden apps"
  row) takes the same way in.
- Re-hiding (vault/Vault.kt): an opened app is "exposed" (persisted in vault/apps.json) until it has been in front and
  then something else is (NotchAccessibility.foreground), or the screen goes off, or, if that failed, Shizuku comes
  back. Permission prompts, the share sheet and the file picker don't count as leaving. 15 s grace if the app never
  comes to the front. Re-hiding force-stops the app (that's what disabling does).
- The third tap of a knock pre-loads the face models (ScannerPool.warm) so the fourth starts scanning at once; a
  knock also closes an open music card or quick panel.
- Face learning (near misses after a PIN, new looks after an unlock) is on by default; the switch is in settings.

### Before testing the vault on the phone
1. `adb install -r` (keeps the accessibility switch). Shizuku running, Notch allowed in it.
2. Notch → Hidden apps → "Set up your face" (asks for the camera). Morse's face does NOT carry over: Notch has its own
   key and file. Then "Test face unlock".
3. Hide a throwaway app first (Ledger Demo): knock 4× → face → "+" → pick it. Check it's gone from the launcher.
4. Knock again → tap it in the grid → it opens. Press Home → `adb logcat -s NotchVault NotchFace` should say
   "hid … again (left for …)" and the icon should be gone again.
5. Fails: cover the camera through 2 tries → the dim face stays; tap elsewhere → gone. Again, then 3 taps on it →
   one try → the PIN pad.

### Untested / to watch
- VaultActivity is started from the island's service, like the quick panel's Camera and Timer tiles (which work).
- Whether OxygenOS's launcher drops the icon at once after `pm disable-user` (it did for Ledger Demo on 10-16).
- Screen off counts as leaving (the app is hidden again, and stopped). Dheer's call if that's too eager.
- The sheet's height with many hidden apps (grid capped at 380 dp, scrolls). The APK is 236 MB: two ABIs plus 110 MB
  of models; armeabi-v7a could be dropped (the OnePlus 7 is arm64).

### Phone notes that still apply
- NEVER `am force-stop com.meetdheeran.notch`: Android then removes Notch from the enabled accessibility services and
  Dheer has to switch "Notch island" on again by hand (happened 2026-10-16). To pick up a freshly started Shizuku,
  reinstall (`adb install -r`, which keeps the accessibility switch) or open Notch again instead.
- After a reboot: start Shizuku over adb (`$(pm path moe.shizuku.privileged.api)/lib/arm64/libshizuku.so`) BEFORE
  Notch starts, or Notch won't get Shizuku's binder until its process restarts. OxygenOS needs Developer options →
  "Disable permission monitoring" on, or Shizuku warns that adb is limited.
- Shizuku was being killed in the background (OplusHansManager). Asked Dheer to set Shizuku and Notch to
  "Don't optimise" in Battery optimisation. Shizuku: Notch authorised via Shizuku → Authorized applications.

## Tested 2026-10-16 → 10-19 (Dheer)
- Music on the island: fixed twice — media watcher now starts when the listener connects, and re-checks when Spotify
  swaps its media session. Dheer: "everything works nice now" (pause/play, skips, buds ↔ speaker, Echo Music).
- Buds card (CMF Buds Pro 2): Fast Pair advert battery field 0x33/0x34, left/right/case decoded; card drops out of
  the notch when the case opens, names the connected pair, optional gallery picture. Works.
- Not on GitHub by Dheer's choice ("for now it will be on my device").
