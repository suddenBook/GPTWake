package com.desmond.gptwake;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MeasureTest {
    @Test
    public void diagnosticStringsRoundTripAsJson() throws Exception {
        String text = "quoted \"word\", backslash \\, newline\n and tab\t";
        assertEquals(text, new JSONObject("{" + Measure.jstr("text", text) + "}").getString("text"));
    }

    @Test
    public void queuedRunHasStrictlyIncreasingSequenceNumbers() throws Exception {
        ExecutorService writer = ReflectionHelpers.getStaticField(Measure.class, "WRITER");
        CountDownLatch release = new CountDownLatch(1);
        writer.execute(() -> {
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        var context = RuntimeEnvironment.getApplication();
        try {
            Measure.start(context, "sequence", "test", null);
            Measure.event("FIRST", "");
            Measure.end("done");
        } finally {
            release.countDown();
        }
        writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
        var files = Measure.dir(context).listFiles((directory, name) -> name.endsWith(".jsonl"));
        assertNotNull(files);
        assertEquals(1, files.length);
        var lines = Files.readAllLines(files[0].toPath());
        assertEquals(3, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(i, new JSONObject(lines.get(i)).getInt("seq"));
        }
    }

    @Test
    public void measurementIdentifiesLanguageAndPhraseAtTheTimeItWasRequested() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var preferences = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("wakeword", 0);
        ExecutorService writer = ReflectionHelpers.getStaticField(Measure.class, "WRITER");
        CountDownLatch release = new CountDownLatch(1);
        writer.execute(() -> {
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            WakeWordStore.reset(context);
            Measure.start(context, "legacy", "test", null);
            Measure.end("done");
            preferences.edit().putString("language", "ja").putString("phrase", "こんにちは")
                    .putString("japanese_reading", "こんにちは").commit();
            Measure.start(context, "japanese-one", "test", null);
            Measure.end("done");
            preferences.edit().putString("phrase", "おはよう")
                    .putString("japanese_reading", "おはよう").commit();
            Measure.start(context, "japanese-two", "test", null);
            Measure.end("done");
            WakeWordStore.reset(context);
        } finally {
            release.countDown();
        }
        writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
        var records = new HashMap<String, JSONObject>();
        var files = Measure.dir(context).listFiles((directory, name) -> name.endsWith(".jsonl"));
        assertNotNull(files);
        for (var file : files) {
            var record = new JSONObject(Files.readAllLines(file.toPath()).get(0));
            records.put(record.getString("runId"), record);
        }
        assertEquals("zh-en", records.get("legacy").getString("recognitionLanguage"));
        String legacyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(WakeWordStore.DEFAULT_LINE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(legacyHash, records.get("legacy").getString("keywordSha256"));
        assertEquals("ja", records.get("japanese-one").getString("recognitionLanguage"));
        assertNotEquals(records.get("japanese-one").getString("keywordSha256"),
                records.get("japanese-two").getString("keywordSha256"));
    }
}
