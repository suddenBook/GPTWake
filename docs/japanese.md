# Japanese wake phrases

## Model choice

The original `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` is a Chinese/English
acoustic model. Its phoneme/pinyin tokenizer cannot recognize Japanese simply by accepting kana
or transliterating Japanese into those tokens. In particular, a shared kanji character previously
received a Chinese reading. The [upstream KWS documentation](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html)
describes its language coverage and token format.

Japanese therefore has an explicit language setting and an independent offline backend:
[Moonshine Tiny Japanese 2026-02-27](https://k2-fsa.github.io/sherpa/onnx/moonshine/models-v2.html#sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27-japanese),
plus Silero VAD, using the existing sherpa-onnx 1.13.4 JNI libraries. Models and vocabulary add
about 69.4 MiB of APK assets, plus the Kuromoji/IPADIC reading dictionary. Only the selected
acoustic backend is loaded. The default remains Chinese/English with `芝麻开门`.

## Data and audio flow

1. The Compose card selects a recognition language independently of the display language.
2. `WakeWordTokenizer` validates the phrase. Japanese uses `JapaneseText` and Kuromoji readings;
   it never produces Chinese KWS tokens. Unknown kanji readings require an explicit kana reading.
3. `WakeWordStore` saves language, phrase and reading in one device-protected preference update.
   A single snapshot is read when starting or rebuilding a recognition session. Old installations
   without a language key retain their Chinese/English configuration.
4. `WakeController` pauses feeding, loads the selected backend, and creates a new session.
   Changes made during a call or ChatGPT voice session take effect when listening resumes.
5. Japanese capture frames are accumulated into 512-sample VAD windows. After 400 ms of silence,
   the detected speech segment goes to a separate ASR worker. Segments are bounded by the VAD's
   eight-second maximum; one decode and one waiting segment bound memory and backlog.
6. A recognized complete phrase is compared by spelling or normalized reading. NFKC handles
   full/half-width forms and combining marks; kana scripts, explicit long vowels, common kanji
   readings and GPT/ChatGPT spellings are normalized. Small kana, voicing and geminates remain
   distinct. There is no edit-distance or substring matching.
7. A hit returns through the existing microphone handoff and ChatGPT launch state machine.
   Session generations discard old results after edits, pauses and Stop. Native model release
   runs after any current decode, without making microphone shutdown wait for ASR.

Captured audio is neither saved nor uploaded. Ambient transcriptions are not logged. Diagnostic
`JA_ASR_STATS` records audio duration, processing time and whether a phrase matched. Capture-thread
statistics cover VAD, while `japanese-asr` and process CPU statistics include recognition work.

## Building and redistribution

Gradle registers generated assets through the Android Components API. It calls
`tools/fetch-japanese-deps.sh`, verifies the archive and each model against pinned SHA-256 hashes,
and packages the output. The script can also prepare assets before a native smoke test.
Model downloads, extracted weights, APKs and test reports are build artifacts, excluded from git.
The small synthetic speech fixtures are intentional, documented regression inputs and are only
packaged in the instrumentation test APK.

Japanese recognition is **Powered by Moonshine AI**. Japanese Moonshine weights use the
[Moonshine AI Community License](../app/src/main/assets/ja/LICENSE); commercial use has separate
conditions. GPTWake's Apache-2.0 source license does not replace that model license. Model and
dictionary notices are packaged with the APK.

## Validation and limits

Local regression tests cover script normalization, alternate readings, invalid input, Direct Boot
preferences, UI application/reset, Japanese layouts at 200% font size, native resource lifetime,
bounded work queues, stale results and call-time language changes. The Linux smoke test exercises
the actual VAD and Japanese model with three synthetic wake phrases, unrelated speech, silence
and quiet noise. The release workflow runs that test before publishing.

Two instrumentation tests also passed on Pixel 10 Pro XL running Android 17 (API 37): the actual
Android model/dictionary assets recognized all three fixture phrases, rejected other configured
phrases and negative controls, and switched Japanese → Chinese/English → Japanese successfully.
These tests feed synthetic PCM through JNI; they do not measure microphone recall or wake latency.

A brief microphone trial on the same device produced four accepted hits for `もしもし` (moshi
moshi). The longer `もしもしアシスタント` did not match in that trial: speech segments were
detected and decoded, but rejected by phrase matching. No recordings or ambient transcriptions
were retained, so the trial cannot establish pronunciation quality or a recall percentage.
The UI offers the short example without requiring a Japanese keyboard.
The same microphone session also exercised the full ChatGPT voice handoff and automatic
return to Japanese listening after the voice session ended.

Recognition is phrase-dependent. During model selection, some short phrases such as
`ねえジーピーティー` lost their initial words in synthetic speech. Strict matching correctly
rejects that incomplete result. Prefer a distinctive phrase of roughly 6–12 mora, speak it on its
own, then pause, and verify it in test mode. The optional reading resolves written pronunciations;
it does not train or boost the acoustic model. Japanese long vowels and homophones are not all
interchangeable. Human-speaker recall, accents, distant speech, TV/radio false activations and
long-duration battery consumption require separate measurement.
