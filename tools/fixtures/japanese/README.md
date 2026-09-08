# Japanese smoke-test audio

These are synthetic test fixtures, not recordings of users or a recall benchmark.
They were generated from the following generic phrases using gTTS 2.5.4 (`lang=ja`)
and resampled with FFmpeg to mono 16 kHz PCM16. The test itself runs entirely offline.

| File | Text |
|---|---|
| `call-assistant.wav` | もしもしアシスタント |
| `wake-assistant.wav` | 起きてアシスタント |
| `morning-assistant.wav` | おはようアシスタント |
| `weather.wav` | 今日の天気を教えて |

The first three are candidate wake phrases. The last is unrelated speech and must
not activate any of them. Human voices, accents, microphones, background speech,
noise, long listening sessions and power consumption require separate device tests.
