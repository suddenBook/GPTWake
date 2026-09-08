#!/usr/bin/env python3
"""Exercise bundled Japanese VAD + ASR with synthetic speech and negative controls.

Requires numpy and sherpa-onnx==1.13.4. Run from any working directory.
The Java tests cover kanji readings and lifecycle races; this checks real model inference.
"""
from pathlib import Path
import hashlib
import time
import unicodedata
import wave

import numpy as np
import sherpa_onnx


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "app/build/generated/japaneseAssets/ja"
FIXTURES = ROOT / "tools/fixtures/japanese"
RATE = 16000
PHRASES = {
    "call-assistant.wav": "もしもしアシスタント",
    "wake-assistant.wav": "起きてアシスタント",
    "morning-assistant.wav": "おはようアシスタント",
}


def spelling(text):
    # Fixed-fixture spelling checks. JapaneseTextTest exercises the actual application's
    # reading dictionary, pronunciation overrides, long vowels and matching rules.
    normalized = unicodedata.normalize("NFKC", text).lower()
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c
                   for c in normalized if c.isalnum() or c == "ー")


def read_audio(path):
    with wave.open(str(path)) as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (RATE, 1, 2)
        return np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768


def main():
    assert sherpa_onnx.__version__ == "1.13.4", sherpa_onnx.__version__
    for line in (ROOT / "app/src/main/assets/ja/SHA256SUMS").read_text().splitlines():
        digest, name = line.split()
        assert hashlib.sha256((MODEL / name).read_bytes()).hexdigest() == digest, name
    recognizer = sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
        encoder=str(MODEL / "encoder_model.ort"),
        decoder=str(MODEL / "decoder_model_merged.ort"),
        tokens=str(MODEL / "tokens.txt"), num_threads=1,
    )
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = str(MODEL / "silero_vad.onnx")
    config.silero_vad.threshold = 0.5
    config.silero_vad.min_silence_duration = 0.4
    config.silero_vad.min_speech_duration = 0.25
    config.silero_vad.max_speech_duration = 8
    config.silero_vad.window_size = 512
    config.sample_rate = RATE
    config.num_threads = 1
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=30)
    rng = np.random.default_rng(7311)
    cases = {name: read_audio(FIXTURES / name) for name in (*PHRASES, "weather.wav")}
    cases["silence"] = np.zeros(RATE * 3, dtype=np.float32)
    cases["quiet-noise"] = rng.normal(0, 0.002, RATE * 3).astype(np.float32)
    for name, audio in cases.items():
        vad.reset()
        # Same fixed-size VAD windows as the app, with silence to close the utterance.
        audio = np.concatenate((np.zeros(RATE // 2, dtype=np.float32), audio,
                                np.zeros(RATE, dtype=np.float32)))
        transcripts = []
        started = time.monotonic()
        for offset in range(0, len(audio) - 511, 512):
            vad.accept_waveform(audio[offset:offset + 512])
            while not vad.empty():
                samples = vad.front.samples
                vad.pop()
                if not RATE // 4 <= len(samples) <= RATE * 9:
                    continue
                stream = recognizer.create_stream()
                stream.accept_waveform(RATE, samples)
                recognizer.decode_stream(stream)
                transcripts.append(stream.result.text)
        detected = {phrase for phrase in PHRASES.values()
                    if any(spelling(t) == spelling(phrase) for t in transcripts)}
        expected = {PHRASES[name]} if name in PHRASES else set()
        assert detected == expected, (name, transcripts, detected, expected)
        if name in ("silence", "quiet-noise"):
            assert not transcripts, (name, transcripts)
        print(f"PASS {name}: {transcripts!r}; {time.monotonic() - started:.3f}s")
    print("Japanese native smoke test passed: 3 phrases, replacement isolation, 3 negative controls")


if __name__ == "__main__":
    main()
