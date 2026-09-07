package com.desmond.gptwake;

import static org.junit.Assert.*;

import java.nio.file.Files;
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
}
