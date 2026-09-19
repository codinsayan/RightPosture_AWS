# Native libraries

Only `arm64-v8a` is packaged — see `abiFilters` in `app/build.gradle.kts`.

## Qualcomm QNN — required for the NPU path

**Nothing in this directory ships from Maven.** The LiteRT-LM AAR contains only
`liblitertlm_jni.so`; the QNN runtime that actually reaches the Hexagon NPU comes from Qualcomm and
has to be copied in by hand. Verified by unzipping the AAR and the built APK — neither contains
`libQnnHtp.so`.

Until these files are present, **both** delegate ladders skip their NPU rung and start at GPU:

- `llm/OnDeviceLlm.kt` gates on `libQnnHtp.so` before it will construct `Backend.NPU(...)`
- `pose/PoseLandmarkerSource.kt` gates on the same file for `Delegate.NPU`

That is deliberate. Asking for an NPU backend with no driver behind it does not reliably throw — on
some Qualcomm driver versions it takes the process down natively, which no `try`/`catch` in Kotlin
can see. Checking for the file first is the only cheap way to avoid paying that.

### What to copy here

From the Qualcomm AI Engine Direct (QNN) SDK, `lib/aarch64-android/`:

```
libQnnHtp.so
libQnnHtpV79Stub.so         # V79 is Snapdragon 8 Elite Gen 5 (SM8850); match your chip
libQnnSystem.so
libQnnHtpPrepare.so
```

and from `lib/hexagon-v79/unsigned/`:

```
libQnnHtpV79Skel.so
```

The `V79` in those names is the Hexagon version, not the Snapdragon number, and it must match the
device. Copying a V75 skel onto an 8 Elite Gen 5 fails at load time with an error that names
neither file.

After copying, rebuild and check the APK actually carries them:

```bash
./gradlew :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep Qnn
```

If that prints nothing, the NPU rung will be skipped no matter what the device supports.

## Recovering from a bad NPU attempt

`OnDeviceLlm` writes a marker file into the cache directory before it tries the NPU and deletes it
on success. If a QNN initialisation takes the process down, the marker survives, and the next launch
starts at GPU instead of reproducing the crash forever. Clearing the app's cache re-arms the NPU
attempt.
