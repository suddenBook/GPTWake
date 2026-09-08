package com.desmond.gptwake;

import static org.junit.Assert.*;

import android.content.res.AssetManager;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "com.k2fsa.sherpa.onnx",
        shadows = {JapaneseWakeEngineTest.Detector.class, JapaneseWakeEngineTest.Recognizer.class,
                JapaneseWakeEngineTest.Stream.class})
public class JapaneseWakeEngineTest {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private JapaneseWakeEngine engine;

    @Before
    public void setUp() {
        Detector.segments.clear();
        Detector.frames = 0;
        Recognizer.text = "おはようございます。";
        Recognizer.entered = new CountDownLatch(1);
        Recognizer.allowDecode = new CountDownLatch(1);
        Recognizer.finished = new CountDownLatch(1);
        Recognizer.released = false;
        Recognizer.fail = false;
        Recognizer.calls = 0;
        Stream.releases = 0;
        engine = new JapaneseWakeEngine(new Vad(null, new VadModelConfig()),
                new OfflineRecognizer(null, new OfflineRecognizerConfig()), worker);
        engine.newStream("おはようございます", JapaneseText.reading("おはようございます"));
    }

    @After
    public void tearDown() throws Exception {
        Recognizer.allowDecode.countDown();
        engine.release();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }

    private void speech() {
        Detector.segments.add(new SpeechSegment(0, new float[16000]));
        engine.accept(new float[1280], 16000);
    }

    private void finishDecode() throws Exception {
        Recognizer.allowDecode.countDown();
        // FIFO barrier: also waits for result matching and stream release after native decode.
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void captureKeepsRunningWhileRecognitionIsBusy() throws Exception {
        speech();
        assertTrue(Recognizer.entered.await(3, TimeUnit.SECONDS));
        assertNull(engine.accept(new float[1280], 16000));
        assertEquals(5, Detector.frames); // Two 1280-sample capture frames become five VAD windows.
        assertEquals(1, Recognizer.finished.getCount());
        finishDecode();
        assertEquals("おはようございます", engine.accept(new float[1280], 16000));
        assertEquals(1, Stream.releases);
    }

    @Test
    public void changingPhraseDiscardsAnInFlightHit() throws Exception {
        speech();
        assertTrue(Recognizer.entered.await(3, TimeUnit.SECONDS));
        engine.newStream("こんにちは", JapaneseText.reading("こんにちは"));
        finishDecode();
        assertNull(engine.accept(new float[1280], 16000));
        Recognizer.text = "こんにちは。";
        speech();
        finishDecode();
        assertEquals("こんにちは", engine.accept(new float[1280], 16000));
    }

    @Test
    public void pausingForACallDiscardsResultsFromBeforeTheCall() throws Exception {
        speech();
        assertTrue(Recognizer.entered.await(3, TimeUnit.SECONDS));
        engine.releaseStream();
        finishDecode();
        assertNull(engine.accept(new float[1280], 16000));
        engine.newStream("おはようございます", JapaneseText.reading("おはようございます"));
        assertNull(engine.accept(new float[1280], 16000));
    }

    @Test
    public void releaseWaitsForDecodeWithoutBlockingMicrophoneHandoff() throws Exception {
        speech();
        assertTrue(Recognizer.entered.await(3, TimeUnit.SECONDS));
        engine.release();
        assertFalse(Recognizer.released);
        Recognizer.allowDecode.countDown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(Recognizer.released);
        assertEquals(1, Stream.releases);
    }

    @Test
    public void continuousSpeechCannotCreateAnUnboundedQueue() throws Exception {
        Recognizer.text = "別の言葉";
        speech();
        assertTrue(Recognizer.entered.await(3, TimeUnit.SECONDS));
        for (int i = 0; i < 10; i++) speech();
        finishDecode();
        assertNull(engine.accept(new float[1280], 16000));
        finishDecode();
        assertNull(engine.accept(new float[1280], 16000));
        assertEquals(2, Recognizer.calls);
    }

    @Test
    public void decodeFailureReachesTheCaptureErrorPath() throws Exception {
        Recognizer.fail = true;
        speech();
        finishDecode();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.accept(new float[1280], 16000));
        assertEquals("decode failed", error.getCause().getMessage());
        assertEquals(1, Stream.releases);
    }

    @Implements(value = Vad.class, isInAndroidSdk = false)
    public static class Detector {
        static final ArrayDeque<SpeechSegment> segments = new ArrayDeque<>();
        static int frames;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected void __constructor__(AssetManager assets, VadModelConfig config) {}
        @Implementation protected void acceptWaveform(float[] samples) {
            assertEquals(512, samples.length);
            frames++;
        }
        @Implementation protected boolean empty() { return segments.isEmpty(); }
        @Implementation protected SpeechSegment front() { return segments.getFirst(); }
        @Implementation protected void pop() { segments.removeFirst(); }
        @Implementation protected void reset() { segments.clear(); }
        @Implementation protected void release() {}
    }

    @Implements(value = OfflineRecognizer.class, isInAndroidSdk = false)
    public static class Recognizer {
        static String text;
        static CountDownLatch entered, allowDecode, finished;
        static volatile boolean released;
        static boolean fail;
        static int calls;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected void __constructor__(AssetManager assets, OfflineRecognizerConfig config) {}
        @Implementation protected OfflineStream createStream() { return new OfflineStream(1); }
        @Implementation protected void decode(OfflineStream stream) throws InterruptedException {
            calls++;
            entered.countDown();
            assertTrue(allowDecode.await(5, TimeUnit.SECONDS));
            assertFalse(released);
            finished.countDown();
            if (fail) throw new IllegalStateException("decode failed");
        }
        @Implementation protected OfflineRecognizerResult getResult(OfflineStream stream) {
            return new OfflineRecognizerResult(text, new String[0], new float[0], "", "", "", new float[0]);
        }
        @Implementation protected void release() { released = true; }
    }

    @Implements(value = OfflineStream.class, isInAndroidSdk = false)
    public static class Stream {
        static int releases;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected void acceptWaveform(float[] samples, int rate) {}
        @Implementation protected void release() { releases++; }
    }
}
