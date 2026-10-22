# Notch

A Dynamic Island for the OnePlus 7, living in the camera notch and looking like part of OxygenOS 12.

- **Live things in the notch**: what's playing (with a card when tapped), calls, timers, navigation, downloads,
  charging, screen recording, the hotspot. Two at once: the second waits in a bubble beside the island.
- **Pops**: a new notification drips out of the notch for a moment; Wi-Fi or Bluetooth joined, low battery, fully
  charged, earbuds connected with their battery; open the earbuds' case and left / right / case battery drop out.
- **Quick actions**: tap the idle island for torch, Wi-Fi, Bluetooth, camera, timer and Prism.
- **The vault**: tap the island four times and the front camera looks for you (nothing on screen but a small face
  on the island). Your face opens a sheet of hidden apps; tap one and it comes back and opens, and it's hidden
  again the moment you leave it. Two misses and the face stays on the island; three taps on it buy one more try,
  then the phone's own PIN pad opens. Hidden apps are disabled for the user through Shizuku, so they leave the
  launcher and the app drawer and get no notifications.

## Needs

- Android 12 (OxygenOS 12.1 on the OnePlus 7 is what it's built and tested on).
- Accessibility → **Notch island** on: that's what lets the island sit above the status bar, inside the notch.
  It reads nothing on screen; it also tells Notch which app is in front, so a hidden app can be hidden again.
- Notification access, for music, calls, timers and the notification peek.
- [Shizuku](https://shizuku.rikka.app/) for the Wi-Fi tile and for hiding apps. On Android 12 it has to be started
  again after every reboot.
- The camera, only while the vault's face check or face setup runs.

## Face unlock

Three open models run on the phone, fetched by the build (not kept in git) and checked by SHA-256: MediaPipe's face
landmarker, FaceNet-512 on ONNX Runtime, and MiniFASNet to tell a real face from a photo or a screen. Prints are
numbers, not pictures, encrypted with a key in the phone's secure hardware; nothing leaves the phone. Sources and
licences: [docs/licenses/FACE-MODELS.md](docs/licenses/FACE-MODELS.md).

## Building

Android Studio or `gradlew assembleDebug`. The first build downloads the face models (about 110 MB) into
`app/src/main/assets/models/`. Plan and decisions in [docs/PLAN.md](docs/PLAN.md), state in
[docs/PROGRESS.md](docs/PROGRESS.md).
