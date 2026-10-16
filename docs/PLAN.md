# Notch — plan (agreed with Dheer 2026-10-16)

A standalone Dynamic Island app for the OnePlus 7 (OxygenOS 12.1, Android 12), package `com.meetdheeran.notch`.
Replaces Morse (launcher dropped) and takes the island out of Prism. Must look like part of OxygenOS 12.

## Decisions
- 1B: its own app, "Notch" (1A), more features and control than Prism's island.
- Music on the island stays — "music works, nothing wrong".
- Quick actions (3A): tap the island → panel: torch, Wi-Fi, Bluetooth, camera, timer, ask Prism.
- Live extras: earbuds + battery (5A), screen recording / hotspot (5C), low battery + charging done (5D). More later from Dheer.
- Hidden apps: truly gone — not in the launcher, and **Play Store shows them as not installed** until unhidden in Notch.
  Hidden apps get no notifications (accepted, 2A).
  Method (Shizuku, shell uid): `pm uninstall -k --user 0 <pkg>` (keeps data + APK) and restore with
  `cmd package install-existing <pkg>`. Fallback if that misbehaves: `pm disable-user --user 0`.
  MUST be proven on a throwaway app first (data kept? restore works? Play Store shows "Install"?).
- Unlock flow (Dheer's words): notch 4 taps → face scan, 2 tries. If both fail, the face glyph stays on the island
  until a tap elsewhere. Tap the face 3 times → one more face try → if that fails, the PIN pad opens automatically.
  Pass → hidden apps grid; picked app is restored, opened, and re-hidden when he leaves it.

## Parts (each ends built + committed + docs/PROGRESS.md)
1. Phone check: system font file, how OOS stores the accent colour.
2. New app + island in the notch (ported from Prism, OxygenOS look); switch Prism's island off.
3. Live activities incl. the 5A/5C/5D extras.
4. Quick actions panel.
5. Vault: hide method proven on a throwaway app → face engine from Morse → the unlock flow above → grid + settings.
6. Settings / controls.
7. Retire Morse (Dheer switches Home back; then uninstall).

Research notes: pm hide needs root (no); pm suspend leaves a grey icon (no); OxygenOS Hidden Space has no API;
OOS12 has no Material You — read the accent from the phone's own settings.
