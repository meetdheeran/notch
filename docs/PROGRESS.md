# Notch — progress

Plan and decisions: [PLAN.md](PLAN.md). Built in checkpointed parts; each ends built + committed + this file updated.

| Part | What | State |
|---|---|---|
| 1 | Read the system font + accent off the phone | **done 2026-10-16**: font OPlusSans 2.0 (SysSans-En-Regular.ttf = system sans-serif, used via FontFamily.Default); accent Settings.Secure `sysui_type_accent_color` (#ff4f67d2 here) |
| 2 | New app + island in the notch, OxygenOS look | **on the phone 2026-10-16**: in the notch, charging dots showing; Prism's island switched off |
| 3 | Live extras: earbuds + battery, screen recording / hotspot, low battery + charging done | **built 2026-10-16, not yet on the phone** |
| 4 | Quick actions panel (tap the idle island) | **tested on the phone 2026-10-16**: torch on/off, Wi-Fi + Bluetooth via Shizuku, Timer — all work (Dheer) |
| 5 | Vault: 4 taps → face → hidden apps | **paused 2026-10-16 — to be picked up in a fresh chat** (see Vault handoff) |
| 6 | Settings / controls | basic screen done in part 2 |
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
- adb output ends lines with a carriage return: pipe through `tr -d ''` before `grep -x`.

## Vault handoff (for a fresh chat)
- Decided: hide = Shizuku `pm disable-user --user 0`, restore = `pm enable --user 0` (tested on Ledger Demo, see PLAN.md).
  Unlock flow is in PLAN.md (4 taps → face, 2 tries → face stays on island → 3 taps on it → 1 more try → PIN).
- Hooks ready in IslandService: `onKnock`, `onOutsideTap`, `setFace(FaceScan)`, `watchOutsideTaps()`;
  NotchAccessibility.foreground tells which app is in front (to re-hide on leave).
- `vault-wip/` (git-ignored) holds Morse's face engine copied over (package renamed to com.meetdheeran.notch.face,
  PeepWatch dropped) and the 4 face models. NOT yet adapted: FaceScreens.kt still uses MorseColors/MorseFonts,
  PrismIsland broadcasts and the old two-strikes FaceCheck; FaceStore ALIAS is still "morse_face". Needs, in Notch's
  build.gradle.kts: CameraX 1.3.4, mediapipe tasks-vision 0.10.20, onnxruntime-android 1.20.0, noCompress for
  tflite/onnx/task, and Morse's fetchFaceModels task (SHA-256 checked) instead of committing the models.
- The vault UI needs an Activity (camera can't start from a background service on Android 12).
- NEVER `am force-stop com.meetdheeran.notch`: Android then removes Notch from the enabled accessibility services and
  Dheer has to switch "Notch island" on again by hand (happened 2026-10-16). To pick up a freshly started Shizuku,
  reinstall (`adb install -r`, which keeps the accessibility switch) or open Notch again instead.
- After a reboot: start Shizuku over adb (`$(pm path moe.shizuku.privileged.api)/lib/arm64/libshizuku.so`) BEFORE
  Notch starts, or Notch won't get Shizuku's binder until its process restarts. OxygenOS needs Developer options →
  "Disable permission monitoring" on, or Shizuku warns that adb is limited.
- Shizuku was being killed in the background (OplusHansManager). Asked Dheer to set Shizuku and Notch to
  "Don't optimise" in Battery optimisation. Shizuku: Notch authorised via Shizuku → Authorized applications.
- 4 taps: counted (heavy haptic) but opens nothing until the vault is built.

## Tested 2026-10-16 → 10-19 (Dheer)
- Music on the island: fixed twice — media watcher now starts when the listener connects, and re-checks when Spotify
  swaps its media session. Dheer: "everything works nice now" (pause/play, skips, buds ↔ speaker, Echo Music).
- Buds card (CMF Buds Pro 2): Fast Pair advert battery field 0x33/0x34, left/right/case decoded; card drops out of
  the notch when the case opens, names the connected pair, optional gallery picture. Works.
- Not on GitHub by Dheer's choice ("for now it will be on my device").
