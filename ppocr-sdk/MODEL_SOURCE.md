# PP-OCRv6 Small model provenance

The bundled Android OCR SDK source is copied from the official
[`PaddlePaddle/PaddleOCR`](https://github.com/PaddlePaddle/PaddleOCR) repository,
commit `2661c7c0ef5c613e8f93c6e93b2e052399f0f854`, directory
`deploy/ppocr-android/ppocr-sdk`.

The bundled ONNX models were downloaded from the official Paddle model storage:

- Detection: `PP-OCRv6_small_det_onnx_infer.tar`
  - SHA-256: `d218f6fbf0f1c23d2161bd6ac7f5eaa6104fa89955c09290497e31008e2618e4`
- Recognition: `PP-OCRv6_small_rec_onnx_infer.tar`
  - SHA-256: `d267ab077a44a0eedb1ea8f8c542d263f211de8e9d7a029bf9fcfff7e5a88fb1`

PaddleOCR source and model files are licensed under Apache License 2.0.

QuizVision changes the recognition preprocessor to preserve BGR channel order, matching the
bundled model's `inference.yml` metadata. Build configuration is adapted to this project's Gradle
and Kotlin versions.
