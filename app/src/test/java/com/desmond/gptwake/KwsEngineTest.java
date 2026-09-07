package com.desmond.gptwake;

import static org.junit.Assert.*;

import android.content.res.AssetManager;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

/** Exercises the Java/native boundary; inference with real models is checked separately. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "com.k2fsa.sherpa.onnx",
        shadows = {KwsEngineTest.Spotter.class, KwsEngineTest.Stream.class})
public class KwsEngineTest {
    private final KwsEngine engine = new KwsEngine();

    @Before
    public void loadModel() throws Exception {
        KwsEngine.customKeywordLine = null;
        KwsEngine.keywordsScore = 1.5f;
        KwsEngine.keywordsThreshold = KwsEngine.DEFAULT_THRESHOLD;
        Spotter.streams.clear();
        Spotter.created = 0;
        engine.load(RuntimeEnvironment.getApplication().getAssets());
    }

    @After
    public void releaseModel() {
        engine.release();
    }

    @Test
    public void customPhraseReplacesDefaultInsteadOfAddingToIt() throws Exception {
        try (var file = RuntimeEnvironment.getApplication().getAssets()
                .open(Spotter.config.getKeywordsFile())) {
            assertTrue(new String(file.readAllBytes(), StandardCharsets.UTF_8).trim().isEmpty());
        }
        KwsEngine.customKeywordLine = "OW1 P AH0 N @open";
        engine.newStream();
        assertEquals("OW1 P AH0 N :1.5 #0.4 @open", Spotter.keywords);
        KwsEngine.customKeywordLine = null;
        engine.newStream();
        assertEquals("zh ī m á k āi m én :1.5 #0.4 @芝麻开门", Spotter.keywords);
    }

    @Test
    public void sensitivityChangesReachNativeStreamWithoutModelReload() {
        engine.newStream();
        KwsEngine.keywordsThreshold = 0.25f;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            engine.newStream();
            assertTrue(Spotter.keywords.contains(" #0.25 @"));
            assertEquals(1, Spotter.created);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void streamCannotBeFreedWhileCaptureIsUsingIt() throws Exception {
        engine.newStream();
        Stream stream = Shadow.extract(Spotter.streams.get(0));
        stream.allowAudio = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var capture = workers.submit(() -> engine.accept(new float[1280], 16000));
            assertTrue(stream.audioEntered.await(3, TimeUnit.SECONDS));
            var restartThread = new AtomicReference<Thread>();
            var restart = workers.submit(() -> {
                restartThread.set(Thread.currentThread());
                engine.newStream();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!stream.released && System.nanoTime() < deadline) {
                Thread thread = restartThread.get();
                if (thread != null && thread.getState() == Thread.State.BLOCKED) break;
                Thread.sleep(1);
            }
            assertFalse("The native stream was released during acceptWaveform", stream.released);
            assertNotNull(restartThread.get());
            assertEquals(Thread.State.BLOCKED, restartThread.get().getState());
            stream.allowAudio.countDown();
            capture.get(3, TimeUnit.SECONDS);
            restart.get(3, TimeUnit.SECONDS);
            assertTrue(stream.released);
        } finally {
            stream.allowAudio.countDown();
            workers.shutdownNow();
        }
    }

    @Implements(value = KeywordSpotter.class, isInAndroidSdk = false)
    public static class Spotter {
        static KeywordSpotterConfig config;
        static String keywords;
        static int created;
        static final List<OnlineStream> streams = new CopyOnWriteArrayList<>();

        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected void __constructor__(AssetManager assets, KeywordSpotterConfig value) {
            config = value;
            created++;
        }
        @Implementation protected OnlineStream createStream(String value) {
            keywords = value;
            OnlineStream stream = new OnlineStream(1);
            streams.add(stream);
            return stream;
        }
        @Implementation protected boolean isReady(OnlineStream stream) { return false; }
        @Implementation protected void release() {}
    }

    @Implements(value = OnlineStream.class, isInAndroidSdk = false)
    public static class Stream {
        volatile boolean released;
        final CountDownLatch audioEntered = new CountDownLatch(1);
        CountDownLatch allowAudio = new CountDownLatch(0);

        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected void __constructor__(long pointer) {}
        @Implementation protected long getPtr() { return released ? 0 : 1; }
        @Implementation protected void release() { released = true; }
        @Implementation protected void acceptWaveform(float[] samples, int sampleRate) throws Exception {
            audioEntered.countDown();
            assertTrue(allowAudio.await(3, TimeUnit.SECONDS));
            assertFalse("Native use after release", released);
        }
    }
}
