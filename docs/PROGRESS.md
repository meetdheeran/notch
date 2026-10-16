# Notch — progress

Plan and decisions: [PLAN.md](PLAN.md). Built in checkpointed parts; each ends built + committed + this file updated.

| Part | What | State |
|---|---|---|
| 1 | Read the system font + accent off the phone | waiting for the phone (font already follows the system via FontFamily.Default) |
| 2 | New app + island in the notch, OxygenOS look | **built 2026-10-16, not yet on the phone** |
| 3 | Live extras: earbuds + battery, screen recording / hotspot, low battery + charging done | **built 2026-10-16, not yet on the phone** |
| 4 | Quick actions panel (tap the idle island) | todo — hook `IslandService.onQuickTap` is ready |
| 5 | Vault: 4 taps → face → hidden apps | todo — hooks `onKnock`, `setFace`, `watchOutsideTaps` ready |
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
