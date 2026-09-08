# GPTWake

GPTWake is an always-on voice wake word for Android that opens a ChatGPT GPT-Live voice session
from a locked, screen-off tablet. Recognition runs entirely on device using sherpa-onnx:
Zipformer keyword spotting for Chinese/English, and VAD with Moonshine for Japanese.
No audio is uploaded by GPTWake. It requires no root, no unlocked
bootloader, and no Magisk or LSPosed.

The default wake phrase is `芝麻开门`. Choose Chinese/English or Japanese inside the app and
apply a custom phrase. Chinese/English uses phoneme and pinyin tokens; Japanese matches the
recognized phrase and its kana reading after a brief pause. All conversion happens on device.
Chinese/English sensitivity is adjustable, a test mode logs hits without opening
ChatGPT, and listening pauses automatically when a phone or VoIP call is detected. Listening
resumes automatically after a reboot, before the first unlock, if it was left enabled. Pressing
Stop also disables listening on the next boot.

---

## Architecture

### System constraints

Android 14+ restrictions on background microphone access and background activity starts mean a
third-party app cannot simply launch another app's voice session from the lock screen. Each of the
following was verified on a physical device.

| Constraint | Resolution | Measured |
|---|---|---|
| ChatGPT cannot obtain the microphone under keyguard | ChatGPT must remain the system default assistant, so its voice interaction service runs at `PROC_STATE_PERSISTENT` | Taking the role away yields `RECORD_AUDIO duration=0`; restoring it restores capture |
| Background activity launch is blocked by BAL | `SYSTEM_ALERT_WINDOW` exemption | Without it: `Background activity launch blocked!`; with it: allowed |
| A microphone foreground service cannot be started from the background | A transparent `showWhenLocked` shim activity supplies a visible moment | Succeeds with the screen off under a secure keyguard, without turning the screen on |
| No automatic recovery after reboot | `LOCKED_BOOT_COMPLETED` to shim to FGS, inside the 20 second temporary allowlist | Listening 1.42 s before the first unlock |
| The microphone is not reclaimed after the voice session ends | `AudioRecord` is rebuilt inside the same FGS, without going through the shim | 113 ms to resume |
| Detecting whether the ChatGPT session actually started | Composite of `AudioManager` mode and `AudioRecordingConfiguration` | Required because ChatGPT posts no notification on the lock screen path |

Wake word hit to confirmed GPT-Live session is approximately 870 ms, with the screen never turning
on and the keyguard never being dismissed.

### Runtime flow

```
Boot / LOCKED_BOOT_COMPLETED
  └─ BootReceiver
       └─ ShimActivity            transparent, showWhenLocked, does not turn the screen on
            └─ WakeService        FOREGROUND_SERVICE_TYPE_MICROPHONE
                 ├─ AudioRecord   16 kHz mono, VOICE_RECOGNITION
                 ├─ selected recognizer: KeywordSpotter or Japanese VAD + ASR
                 └─ WakeController state machine

Wake word hit
  ├─ stop feeding, then stop / join / release, wait for the capture config to disappear, drain 250 ms
  ├─ startActivity(com.openai.chatgpt/com.openai.voice.assistant.AssistantActivity)
  │    undocumented internal component; existence, exported flag and permission are checked at
  │    runtime, falling back to the public deeplink chat.com/?mode=voice
  └─ confirm the session within 5 s using audio mode plus recording configuration

Session ends
  └─ rebuild AudioRecord and a new OnlineStream inside the same FGS, resume listening
```

Two behaviours are worth stating explicitly. This app must never take the assistant role for
itself; that role has to stay with ChatGPT or lock-screen capture breaks. And `startActivity()`
does not throw when BAL blocks it, so success must be confirmed through a side channel rather than
assumed.

### Audio and inference

Capture is 16 kHz mono `PCM_16BIT` from the `VOICE_RECOGNITION` source, read in 1280-sample
(80 ms) frames on a dedicated capture thread. The model is a zipformer2 streaming KWS network with
an int8 encoder, `decode_chunk_len=32` and `T=45`, so the encoder advances 320 ms of audio per
forward pass and fires roughly 3.125 times per second while capture wakes about 12.5 times per
second. ONNX Runtime is configured with `numThreads=1` on the CPU provider, so no worker pool is
spawned.

In Chinese/English mode there is no voice activity detection or energy gate, so the encoder runs on all
wall-clock audio including silence. This is the dominant power cost. `docs/power.md` documents the
measurement state and the available options with their costs.

Japanese uses Silero VAD with 512-sample windows and a 400 ms silence boundary. Moonshine Tiny
Japanese runs on a separate worker for detected speech, with one in-flight decode and one waiting
segment. Recognition sessions invalidate pending results on phrase changes, calls and Stop.
Only the selected acoustic model is loaded. See [Japanese implementation and limits](docs/japanese.md).

### User interface

The UI is Jetpack Compose using Material 3 Expressive: `MaterialExpressiveTheme` with
`MotionScheme.expressive()`, dynamic color on API 31 and above, and a status indicator that morphs
between `MaterialShapes.Cookie9Sided` and `Sunny` while being scaled by live microphone amplitude.
Card container color encodes engine state, so the current state is readable without reading text.
At a readable width of 840 dp the status card spans the full width and the remaining cards split
into two columns by role; larger font sizes increase that breakpoint. The page stays scrollable
at every window size. The full-width event log has a bounded scrolling viewport, so it can be
nested safely inside the page on phones, in landscape, and in split screen.

The Expressive components ship in no stable release. They appeared in `material3 1.4.0-alpha18`,
were removed before `1.4.0-beta01`, and currently exist only in `1.5.0-alpha24`. On stable `1.4.0`
every Expressive entry point is Kotlin-`internal` and unreachable from application code, including
`MaterialExpressiveTheme`, `MotionScheme`, `MaterialTheme.motionScheme` and the increased shape
scale. The project therefore builds against `compose-bom-alpha`, which requires `compileSdk 37`.
Pin the BOM and read the release notes before bumping it.

### Source layout

The wake-word engine is Java and the UI is Kotlin; both live under `app/src/main/java`. The UI
reads engine state through `produceState` polling, so no engine class has a Compose dependency.

| Path | Contents |
|---|---|
| `KwsEngine`, `AudioProbe`, `WakeController`, `WakeService` | Capture loop, inference, state machine, foreground service |
| `ShimActivity`, `BootReceiver`, `GptLauncher`, `AudioStateMonitor` | Lock-screen and launch plumbing |
| `WakeWordTokenizer`, `WakeWordStore`, `WakeLanguage` | Phrase validation, language selection, Direct Boot aware storage |
| `JapaneseWakeEngine`, `JapaneseText` | Japanese VAD/ASR worker, kanji readings and complete-phrase matching |
| `Measure`, `PowerLogger`, `ThreadCpu`, `TrialLog` | Measurement harness |
| `ui/` | Compose theme, screen, state adapters, morphing indicator |
| `app/src/debug/` | `ControlReceiver`, an adb control surface, never in a release build |

---

## Usage

### Requirements

- Android 12L or later (`minSdk 32`); lock-screen voice previously verified on Android 16, Lenovo TB355FU
- `arm64-v8a` only
- The official ChatGPT app, installed and signed in

### Install

Download the signed APK from [Releases](https://github.com/suddenBook/GPTWake/releases), or build
from source:

```bash
git clone https://github.com/suddenBook/GPTWake.git && cd GPTWake
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Building and running the checks requires JDK 21, AGP 9.3.1, and the `platforms;android-37.0` and
`build-tools;37.0.0` SDK packages. `compileSdk` is 37 because `compose.ui 1.12.0-beta02` requires
it; `targetSdk` remains 36.

The sherpa-onnx AAR and the Chinese/English KWS model are committed to the repository.
The first Gradle build downloads about 46 MB of Japanese model archives using Bash, curl, tar,
bzip2 and sha256sum, verifies pinned SHA-256 hashes, and includes the extracted models in the APK.
Generated weights stay under the ignored `app/build/` directory. Later builds reuse them;
the installed app requires no download or Internet permission. To restore dependencies yourself:

```bash
tools/fetch-deps.sh
```

### Setup

1. Open the app. It requests microphone and notification permissions on first launch; the banner at
   the top walks through whatever is still missing, including **Display over other apps**, which
   lock-screen wake and start-on-boot both depend on.
2. Confirm ChatGPT is the system default assistant, under Settings, Apps, Default apps, Digital
   assistant app. Do not change this to GPTWake; ChatGPT needs the role to record under keyguard.
3. In ChatGPT, under Settings, Voice, enable **Background conversations**. **Start with Voice** is
   optional and only affects the fallback path.
4. Return to the app and press Start.

The UI follows the system language and ships English, Chinese and Japanese. A per-app language can be chosen
under Settings, Apps, GPTWake, Language.
The wake-word language is selected separately inside GPTWake.

### Changing the wake word

Type a new phrase into the wake word card. The pinyin or phonemes and the resulting model tokens
are shown live; press Apply to arm it.

```
芝麻开门      zh ī m á k āi m én
你好电脑      n ǐ h ǎo d iàn n ǎo
open sesame   OW1 P AH0 N S EH1 S AH0 M IY0
```

Four to six syllables is recommended. Shorter phrases trigger false wakes and the app warns about
them. Recall is strongly phrase-dependent; the default phrase measures 8/10 at close range and
8/10 at three metres with the threshold at 0.40.

For Japanese, select **Japanese**, enter a phrase such as `もしもしアシスタント` or
`起きてアシスタント`, and press Apply. Without a Japanese keyboard, tap **Use もしもし
(moshi moshi)** to fill a short example, then Apply. Hiragana, katakana, half-width kana and common kanji
readings are supported. An optional kana reading handles names and alternate kanji pronunciations.
Say the phrase on its own, then pause briefly. Longer sentences containing the phrase do not
match. Japanese uses complete-phrase matching and does not use the Chinese/English sensitivity
slider. Short phrases, names and accents can be missed; use test mode to check your chosen phrase.

### Testing

Run the local regression suite and Android lint before releasing:

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

The suite runs Compose layouts on simulated Android devices, exercises the service state machine,
checks Android 12L compatibility, and tests keyword settings and capture lifecycle behavior.
See [testing details](docs/testing.md) for the native-model check and device validation scope.

`testkit.sh` drives the debug control receiver over adb.

```bash
./testkit.sh arm                 # eval mode, screen off, hits do not launch ChatGPT
./testkit.sh recall near 10      # ten prompted recall trials
./testkit.sh threshold 0.35      # change threshold and reload the model
./testkit.sh power A1            # ABBA power leg; see docs/power.md
./testkit.sh live                # follow the log
```

Set `ANDROID_SERIAL` if more than one device is attached.

### Releasing

Pushing to `main` triggers `.github/workflows/release.yml`, which builds a release APK, signs it
with `zipalign` and `apksigner`, and creates or updates the GitHub release tagged `v<versionName>`.
Gradle produces an unsigned APK and signing happens only in CI, so no signing material is stored in
the repository.

The workflow gates releases on regression tests and lint, preserves 16 KB native-library alignment,
and retains the R8 mapping and test reports for diagnosing future crash reports. Bump both
`versionCode` and `versionName` in `app/build.gradle.kts` for a new release.

Four repository secrets are required:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64 encoded on a single line |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password; must equal the keystore password for a PKCS12 keystore |

---

## License

Apache-2.0. Bundled third-party components: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
v1.13.4 (Apache-2.0) and the `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` model; the pinyin
dictionary is generated at build time by [pypinyin](https://github.com/mozillazg/python-pinyin).
The model weights and the `en.phone` dictionary redistributed here come from upstream releases;
confirm their terms before redistributing further.

Japanese recognition is **Powered by Moonshine AI**. Its Japanese model weights use the
[Moonshine AI Community License](app/src/main/assets/ja/LICENSE), with separate terms for
commercial use; they are not Apache-2.0 licensed. The APK includes that license and its
[attribution notice](app/src/main/assets/ja/Notice.txt). Silero VAD uses the MIT license.
Kuromoji 0.9.0 uses Apache-2.0 and includes IPADIC dictionary notices in the APK.
