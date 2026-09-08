# Validation

Run with JDK 21 and the SDK packages listed in the README:

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

The release workflow runs these checks before signing and publishing. It keeps the R8 mapping,
test reports, and UI snapshots as a diagnostics artifact for 90 days.

## Regression coverage

- Launch the actual activity on simulated Android 12L (API 32) and Android 14 (API 34).
- Measure and scroll the Compose screen at phone, landscape, tall-phone, and tablet sizes,
  including a 200% font scale and a populated log with long lines. Native-rendered snapshots
  are written to `app/build/reports/ui-snapshots/`.
- Drive the controller through queued launch/Stop, late callbacks, incoming calls, voice-session
  keyword edits, capture failure, model failure, and microphone reacquisition.
- Exercise the Java/native boundary with test doubles, including a concurrent stream reset
  while audio is being consumed. The test checks that the stream stays alive until consumption
  completes.
- Check Direct Boot preferences, boot prerequisites, permission ordering, Android 12L APIs,
  mixed Chinese/English tokenization, syllable warnings, and diagnostic JSON.
- Check Japanese readings, language selection, complete-phrase matching, bounded asynchronous
  decoding, stale results after edits/calls, native shutdown and Japanese layouts. Check that
  measurement records retain the language and phrase identity captured when the run was requested.

The original phone-layout test reproduced the exact infinite-height exception from issue #1
before the layout fix. The concurrency test also fails when synchronization is removed.

## Native-model smoke test

This check runs real inference using the committed model weights and sherpa-onnx 1.13.4 on Linux.
Download the upstream speech fixtures outside the checkout:

```bash
python3 -m venv /tmp/gptwake-native-venv
/tmp/gptwake-native-venv/bin/pip install numpy sherpa-onnx==1.13.4
mkdir -p /tmp/gptwake-native-fixtures
gh release download kws-models --repo k2-fsa/sherpa-onnx \
  --pattern 'sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2' \
  --dir /tmp/gptwake-native-fixtures
tar -xjf /tmp/gptwake-native-fixtures/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2 \
  -C /tmp/gptwake-native-fixtures
/tmp/gptwake-native-venv/bin/python tools/test_native_kws.py \
  /tmp/gptwake-native-fixtures/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/test_wavs
```

It verifies that a new phrase replaces the previous one and that changing the per-stream
threshold affects detections without reloading the model. The empty base keyword file is
intentional: sherpa-onnx adds custom stream keywords to its base file.

## Device scope

Japanese inference can be checked on Linux with committed synthetic fixtures:

```bash
bash tools/fetch-japanese-deps.sh
python3 -m venv /tmp/gptwake-native-venv
/tmp/gptwake-native-venv/bin/pip install -r tools/requirements-native.txt
/tmp/gptwake-native-venv/bin/python tools/test_native_japanese.py
```

To exercise the actual Android JNI libraries and packaged models on an attached arm64 device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The Japanese instrumentation tests require no microphone permission and feed synthetic PCM.
They passed on Pixel 10 Pro XL, Android 17/API 37, including Japanese → Chinese/English → Japanese
model switching. Device reports remain under the ignored `app/build/` directory. Do not commit
device identifiers, screenshots, raw logcat or local recordings.

For a debug build, configure Japanese through the UI or the existing adb receiver:

```bash
adb shell am broadcast -a com.desmond.gptwake.CTRL -p com.desmond.gptwake \
  --es cmd set_wakeword --es language ja --es phrase もしもしアシスタント
```

An optional `--es reading` supplies kana. Omitting `--es language` retains the receiver's
Chinese/English behavior. Set `ANDROID_SERIAL` when multiple devices are connected.

The simulated Android tests and Linux inference test do not exercise HyperOS firmware, physical
microphone hardware, a secure keyguard, or the installed ChatGPT app. The project previously
validated its lock-screen flow on the Lenovo TB355FU. For a device smoke test of this release,
launch the app, grant setup permissions, apply a custom phrase and sensitivity, start listening,
exercise a voice session and an incoming call, then Stop and reboot to verify it stays stopped.
