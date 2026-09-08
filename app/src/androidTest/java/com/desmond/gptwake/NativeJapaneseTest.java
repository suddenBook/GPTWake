package com.desmond.gptwake;

import static org.junit.Assert.*;

import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android JNI, model assets and reading dictionary. No microphone permission is required. */
@RunWith(AndroidJUnit4.class)
public class NativeJapaneseTest {
    private final AssetManager models = InstrumentationRegistry.getInstrumentation()
            .getTargetContext().createDeviceProtectedStorageContext().getAssets();

    @Test
    public void realJapaneseAudioMatchesOnlyTheArmedPhrase() throws Exception {
        String[] phrases = {"もしもしアシスタント", "起きてアシスタント", "おはようアシスタント"};
        String[] files = {"call-assistant.wav", "wake-assistant.wav", "morning-assistant.wav"};
        WakeWordTokenizer tokenizer = new WakeWordTokenizer();
        tokenizer.load(models);
        KwsEngine engine = new KwsEngine();
        try {
            for (int i = 0; i < phrases.length; i++) {
                var result = tokenizer.convert(phrases[i], WakeLanguage.JAPANESE, "");
                assertTrue(result.ok);
                engine.configure(new WakeWordStore.Selection(WakeLanguage.JAPANESE,
                        phrases[i], "", result.readable));
                engine.load(models);
                engine.newStream();
                long started = SystemClock.elapsedRealtime();
                assertEquals(phrases[i], feed(engine, wave(files[i])));
                Log.i("GPTWakeNativeTest", "PASS phrase=" + phrases[i]
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                engine.newStream();
                assertNull("Old phrase must no longer wake", feed(engine, wave(files[(i + 1) % files.length])));
            }
            engine.newStream();
            assertNull("Unrelated speech must not wake", feed(engine, wave("weather.wav")));
            engine.newStream();
            assertNull("Silence must not wake", feed(engine, new float[16000]));
        } finally {
            engine.release();
        }
    }

    @Test
    public void nativeBackendsCanSwitchInBothDirections() throws Exception {
        KwsEngine engine = new KwsEngine();
        var japanese = new WakeWordStore.Selection(WakeLanguage.JAPANESE, "もしもしアシスタント",
                "", JapaneseText.reading("もしもしアシスタント"));
        try {
            engine.configure(japanese);
            engine.load(models);
            engine.newStream();
            assertEquals(japanese.phrase, feed(engine, wave("call-assistant.wav")));
            engine.configure(new WakeWordStore.Selection(WakeLanguage.ZH_EN,
                    WakeWordStore.DEFAULT_PHRASE, WakeWordStore.DEFAULT_LINE, ""));
            engine.load(models);
            engine.newStream();
            assertTrue(engine.isLoaded());
            assertNull(engine.accept(new float[1280], 16000));
            engine.configure(japanese);
            engine.load(models);
            engine.newStream();
            assertEquals(japanese.phrase, feed(engine, wave("call-assistant.wav")));
        } finally {
            engine.release();
        }
    }

    private static String feed(KwsEngine engine, float[] speech) {
        float[] padded = new float[speech.length + 24000];
        System.arraycopy(speech, 0, padded, 8000, speech.length);
        for (int offset = 0; offset < padded.length; offset += 1280) {
            float[] frame = Arrays.copyOfRange(padded, offset, Math.min(padded.length, offset + 1280));
            String hit = engine.accept(frame, 16000);
            if (hit != null) return hit;
        }
        // The app polls completion on subsequent capture frames. Allow slower Android CPUs to
        // finish, without turning synthetic audio duration into an artificial benchmark.
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < deadline) {
            String hit = engine.accept(new float[1280], 16000);
            if (hit != null) return hit;
            SystemClock.sleep(20);
        }
        return null;
    }

    private static float[] wave(String name) throws Exception {
        byte[] bytes;
        try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            bytes = input.readAllBytes();
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
        int position = 12;
        boolean formatValid = false;
        while (position + 8 <= bytes.length) {
            String kind = new String(bytes, position, 4, StandardCharsets.US_ASCII);
            int length = buffer.getInt(position + 4);
            int start = position + 8;
            assertTrue(length >= 0 && length <= bytes.length - start);
            if (kind.equals("fmt ")) {
                assertTrue(length >= 16);
                assertEquals(1, buffer.getShort(start));
                assertEquals(1, buffer.getShort(start + 2));
                assertEquals(16000, buffer.getInt(start + 4));
                assertEquals(16, buffer.getShort(start + 14));
                formatValid = true;
            } else if (kind.equals("data")) {
                assertTrue(formatValid);
                float[] samples = new float[length / 2];
                for (int i = 0; i < samples.length; i++) samples[i] = buffer.getShort(start + i * 2) / 32768f;
                return samples;
            }
            position = start + length + (length & 1);
        }
        throw new IllegalArgumentException("Missing WAV audio: " + name);
    }
}
