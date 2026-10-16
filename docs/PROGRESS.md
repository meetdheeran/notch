# Notch — progress

Plan and decisions: [PLAN.md](PLAN.md). Built in checkpointed parts; each ends built + committed + this file updated.

| Part | What | State |
|---|---|---|
| 1 | Read the system font + accent off the phone | waiting for the phone (font already follows the system via FontFamily.Default) |
| 2 | New app + island in the notch, OxygenOS look | **built 2026-10-16, not yet on the phone** |
| 3 | Live extras: earbuds + battery, screen recording / hotspot, low battery + charging done | todo |
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
