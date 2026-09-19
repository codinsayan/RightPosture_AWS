# Pose runtime choice

## Baseline

- API: MediaPipe Tasks Vision `1.0.0`.
- Model: `app/src/main/assets/pose_landmarker_full.task`.
- Required output: 33 image landmarks and 33 world landmarks.
- Verified fallback: CPU delegate on physical Android device.

## Runtime order

1. Qualcomm QNN/NPU source when event device and matching artifacts are available.
2. MediaPipe GPU delegate after a device load/inference test passes.
3. MediaPipe CPU delegate as required fallback.

Each backend must emit the same internal landmark frame. QNN artifacts are not assumed to be compatible with MediaPipe's `.task` loader.

`POSE_BACKEND_ORDER` now locks this order in executable code and a unit test. Camera, overlay, and
geometry consume only `LandmarkFrame`; they will not change when QNN lands. The QNN runner itself
cannot be completed before its device-targeted context binaries and QAIRT Android libraries exist.

## NPU setup dependency

Exact Qualcomm export target cannot be selected before event phone model or chipset is known. Before event, configure Qualcomm AI Hub and practice its `mediapipe_pose` export. During event, select the exact device reported by AI Hub, profile output, then integrate behind the pose-source boundary.

Current Qualcomm CLI syntax:

```text
pip install qai-hub-models
qai-hub-models install mediapipe_pose
qai-hub configure --api_token API_TOKEN
qai-hub-models export mediapipe_pose --target-runtime qnn_context_binary --precision float --device "EXACT AI HUB DEVICE NAME"
```

Never commit or share the API token. Record the exact phone model, chipset, Android version, and
AI Hub device name before export.
