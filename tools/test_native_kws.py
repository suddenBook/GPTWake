#!/usr/bin/env python3
"""Check the packaged KWS models against the upstream model's test_wavs directory.

Requires numpy and sherpa-onnx==1.13.4. See docs/testing.md for setup.
"""

import argparse
from pathlib import Path
import tempfile
import wave

import numpy as np
import sherpa_onnx


ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/kws"


def make_spotter(keywords_file):
    return sherpa_onnx.KeywordSpotter(
        tokens=str(ASSETS / "tokens.txt"),
        encoder=str(ASSETS / "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx"),
        decoder=str(ASSETS / "decoder-epoch-13-avg-2-chunk-16-left-64.onnx"),
        joiner=str(ASSETS / "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"),
        keywords_file=str(keywords_file),
        num_threads=1,
        keywords_score=1.5,
        keywords_threshold=0.4,
    )


def detect(engine, keywords, wav):
    with wave.open(str(wav), "rb") as source:
        assert source.getframerate() == 16000 and source.getnchannels() == 1
        assert source.getsampwidth() == 2
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
        samples = samples.astype(np.float32) / 32768
    samples = np.concatenate([samples, np.zeros(16000, dtype=np.float32)])
    stream = engine.create_stream(keywords)
    hits = []
    for start in range(0, len(samples), 1280):
        stream.accept_waveform(16000, samples[start : start + 1280])
        while engine.is_ready(stream):
            engine.decode_stream(stream)
            keyword = engine.get_result(stream)
            if keyword:
                hits.append(keyword)
                engine.reset_stream(stream)
    return hits


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", type=Path, help="Upstream model's test_wavs directory")
    args = parser.parse_args()
    light = args.fixtures / "en_0.wav"
    child = args.fixtures / "en_1.wav"

    # Demonstrate the library contract behind the old bug: custom keywords add to the file.
    with tempfile.TemporaryDirectory(prefix="gptwake-kws-") as directory:
        baseline = Path(directory) / "keywords.txt"
        baseline.write_text("L AH1 V L IY0 CH AY1 L D @LOVELY_CHILD\n", encoding="utf-8")
        old = make_spotter(baseline)
        assert "LOVELY_CHILD" in detect(old, "L AY1 T AH1 P @LIGHT_UP", child)

    engine = make_spotter(ASSETS / "empty_keywords.txt")
    assert detect(engine, "L AY1 T AH1 P :1.5 #0.2 @LIGHT_UP", child) == []
    assert "LIGHT_UP" in detect(engine, "L AY1 T AH1 P :1.5 #0.2 @LIGHT_UP", light)
    assert detect(engine, "L AY1 T AH1 P :1.5 #1.0 @LIGHT_UP", light) == []
    assert "LOVELY_CHILD" in detect(engine, "L AH1 V L IY0 CH AY1 L D :1.5 #0.2 @LOVELY_CHILD", child)
    assert detect(engine, "L AH1 V L IY0 CH AY1 L D :1.5 #0.2 @LOVELY_CHILD", light) == []
    print("PASS: keyword replacement and per-stream sensitivity with the packaged native models")


if __name__ == "__main__":
    main()
