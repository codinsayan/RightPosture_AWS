# Putting Gemma 4 E2B on the phone

The model is **not** in the APK and must never be. It is over a gigabyte; §11.3 keeps it on the
filesystem so the pose pipeline and the language model are never resident together.

With no model present the app is fully functional: every summary comes from §10.5's template, which
is built from arithmetic and cannot fabricate anything. Adding the model changes how the summary
*reads*, not what it is allowed to say.

## 0. Which file - measured on the device, not guessed

**Download `gemma-4-E2B-it.litertlm` (the plain one, ~2.59 GB).**

Do **not** use `gemma-4-E2B-it_qualcomm_sm8750.litertlm`. It was tried on the target device on
2026-09-05 and **every backend refused it**, CPU included:

```
Failed to create engine: NOT_FOUND: llm_litert_compiled_model_executor_factory.cc:197
  Input tensor not found
```

CPU failing is the diagnostic part. CPU has no driver dependency, so a failure there is not a
device problem - it means the container has no CPU-executable text graph. The chip-specific builds
carry pre-compiled QNN payloads (`tf_lite_aux`, and `QNN_` markers are visible in the raw file) and
nothing else runnable: they are NPU artifacts, not portable models with an NPU bonus. Setting
`visionBackend` and `audioBackend` explicitly was tried and changed nothing.

### On NPU for the language model, honestly

The repo publishes Qualcomm builds for **sm8750** (the previous-generation Snapdragon 8 Elite) and
**qcs8275** only. There is no **sm8850** build, which is this project's target device. QNN context
binaries are compiled against a specific Hexagon version, so the sm8750 payload is not expected to
load on sm8850 even once `libQnnHtp.so` is in the APK.

So LLM-on-NPU is blocked upstream until an sm8850 build is published, or one is compiled with QAIRT
directly. **GPU is the achievable acceleration today** - roughly 30-50 tokens/second against 4-5 on
CPU, still fully on-device.

This does not affect §11.1's pose NPU work, which is a separate artifact from Qualcomm AI Hub and
remains the path the rubric actually measures.

## 1. Get the model

Gemma 4 E2B in LiteRT-LM format, from the LiteRT Community organisation on Hugging Face. Accept the
Gemma licence first — the download is gated.

Pick the `.litertlm` build:

- **`...q4...litertlm`** — 4-bit, the one to use. Roughly 1.4 GB, and the quantised integer weights
  are what the Hexagon NPU can actually execute.
- `...f16...litertlm` — half precision. Larger, slower on this hardware, and it will not take the
  NPU path.

For the NPU specifically the file must carry pre-compiled QNN payloads. A `.litertlm` without them
loads fine and silently runs on GPU, which looks identical from the app.

## 2. Push it to the device

The app looks in exactly one place, defined by `OnDeviceLlm.defaultModelFile`:

```
/data/data/com.example.rightposture/files/models/gemma.litertlm
```

App-private storage is not writable by `adb push` directly, so go via a temporary directory:

```bash
adb push gemma-4-e2b-it-q4.litertlm /data/local/tmp/gemma.litertlm

adb shell run-as com.example.rightposture mkdir -p files/models
adb shell "run-as com.example.rightposture cp /data/local/tmp/gemma.litertlm files/models/gemma.litertlm"
adb shell rm /data/local/tmp/gemma.litertlm

# Confirm it landed and is the size you expect
adb shell run-as com.example.rightposture ls -l files/models/
```

`run-as` needs a debuggable build, which the debug APK is.

## 3. Confirm which backend took it

The app logs the chosen rung at launch and shows it under any model-written summary.

```bash
adb logcat -s RPLlm
```

Expect one of:

```
I RPLlm: Loaded on NPU in 4812 ms
W RPLlm: libQnnHtp.so absent; NPU not attempted.
W RPLlm: GPU refused the model; falling back.
I RPLlm: No model at /data/.../gemma.litertlm; summaries will use the template.
```

`libQnnHtp.so absent` means the QNN runtime has not been copied into `app/src/main/jniLibs/` — see
the README there. That is the common case on a first run, and it is a packaging gap, not a device
limitation.

## 4. What to expect

Published figures for a Gemma-class model on Snapdragon, decode speed:

| Backend | tokens/sec |
|---|---|
| NPU (Hexagon, QNN) | 50–80+ |
| GPU (Adreno, OpenCL) | 30–50 |
| CPU | 4–5 |

A summary is three or four sentences, so the difference between the NPU and the CPU is roughly two
seconds against most of a minute. `OnDeviceLlm` gives up after 30 seconds and falls back to the
template, so on CPU the fallback may well win the race — which is the correct outcome, just not the
one worth demonstrating.

Measure it on the device rather than trusting the table. §11.2 is explicit that the numbers should be
taken in hour one, and §15 scores NPU utilisation from device data rather than from claims.

## 5. Model and pose never load together

§11.3: "unload the LLM while a live set is running; the pose pipeline must have the NPU to itself
during scoring."

`AppHost` releases the model when navigation enters `PatientExercise` or `TestExercise` — on the way
*into* the set, not on the way out, because contention during scoring is already in the data by the
time the set ends. It is re-warmed afterwards.
