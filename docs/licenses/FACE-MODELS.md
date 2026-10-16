# Face unlock models

Morse's face unlock runs three open models on the phone. None of them is kept in this repository: the build
downloads them into `app/src/main/assets/models/` (see `fetchFaceModels` in `app/build.gradle.kts`, which checks
each file's SHA-256) and they ship inside the APK. No photo or print leaves the phone.

| File | What it does | Source | Licence |
|---|---|---|---|
| `face_landmarker.task` (3.8 MB) | Finds the face and tracks 478 points on it, 30 times a second | Google MediaPipe, [face_landmarker float16 v1](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker) | Apache-2.0 |
| `facenet512_weights.onnx` (94 MB) | Turns a face into a print of 512 numbers (FaceNet, Inception-ResNet-v1, trained on VGGFace2) | [serengil/deepface_models v1.0](https://github.com/serengil/deepface_models/releases/tag/v1.0), converted from [davidsandberg/facenet](https://github.com/davidsandberg/facenet) 20180402-114759 | MIT — © 2019 Sefik Ilkin Serengil; © 2016 David Sandberg |
| `spoof_model_scale_2_7.tflite`, `spoof_model_scale_4_0.tflite` (12.4 MB) | Tells a real face from a photo or a screen (MiniFASNet) | [minivision-ai/Silent-Face-Anti-Spoofing](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing), TFLite conversion from [shubham0204/OnDevice-Face-Recognition-Android](https://github.com/shubham0204/OnDevice-Face-Recognition-Android) @ f6405d0 | Apache-2.0 |

The first face unlock (ML Kit + MobileFaceNet, `mobilefacenet.tflite`, Apache-2.0 — `face_detection_tflite-LICENSE.txt`)
is replaced by these.

## MIT licence (deepface, facenet)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

The Apache-2.0 licence text is in `face_detection_tflite-LICENSE.txt`.
