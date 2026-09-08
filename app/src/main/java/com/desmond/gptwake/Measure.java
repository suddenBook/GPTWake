package com.desmond.gptwake;

import android.content.Context;
import android.os.SystemClock;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/**
 * Authoritative measurement record. One JSONL file per run in device-protected storage, written by
 * a single thread through one BufferedWriter that stays open for the whole run. logcat is only a
 * backup view, because it is a ring buffer and its own write volume perturbs the power figures.
 */
public final class Measure {

    private static final ExecutorService WRITER =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "file-writer"));

    private static volatile BufferedWriter out;
    private static volatile FileOutputStream fos;
    private static volatile File partial;
    private static volatile File finalFile;
    private static volatile String runId = "none";
    private static volatile String mode = "none";
    private static volatile long runStartElapsedNs;
    private static final AtomicLong SEQ = new AtomicLong();
    private static long lastFlushMs;

    public static boolean isRunning() {
        return out != null;
    }

    public static String runId() {
        return runId;
    }

    public static File dir(Context c) {
        File d = new File(c.createDeviceProtectedStorageContext().getFilesDir(), "measurements");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void start(Context c, String id, String runMode, String meta) {
        final long requestedAt = SystemClock.elapsedRealtimeNanos();
        final WakeWordStore.Selection selection = WakeWordStore.read(c);
        WRITER.execute(() -> {
            try {
                closeQuietly("superseded");
                runId = id;
                mode = runMode;
                SEQ.set(0);
                runStartElapsedNs = requestedAt;

                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                String base = f.format(new Date()) + "-" + id + "-" + runMode;
                partial = File.createTempFile(base.replaceAll("[^A-Za-z0-9._-]", "_") + "-",
                        ".jsonl.partial", dir(c));
                finalFile = new File(dir(c), partial.getName().replaceFirst("\\.partial$", ""));

                fos = new FileOutputStream(partial, false);
                out = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8), 1 << 16);
                lastFlushMs = System.currentTimeMillis();

                writeLine("{\"type\":\"RUN_START\"," + jstr("runId", id) + "," + jstr("mode", runMode)
                        + ",\"seq\":0,\"elapsedNs\":" + runStartElapsedNs
                        + ",\"wallUtc\":\"" + isoNow() + "\""
                        + "," + jstr("recognitionLanguage", selection.language.id)
                        + "," + jstr("keywordSha256", keywordHash(selection))
                        + ",\"score\":" + KwsEngine.keywordsScore
                        + ",\"threshold\":" + KwsEngine.keywordsThreshold
                        + ",\"trailing\":" + KwsEngine.numTrailingBlanks
                        + ",\"evalMode\":" + Cfg.evalMode
                        + ",\"micOnly\":" + Cfg.micOnly
                        + (meta == null ? "" : ",\"meta\":" + meta) + "}");
                out.flush();
                L.i("RUN_START runId=" + id + " mode=" + runMode + " file=" + partial.getName());
            } catch (Throwable t) {
                L.e("MEASURE_START_FAIL", t);
            }
        });
    }

    /** JSON object body without the surrounding braces; seq / elapsedNs are added here. */
    public static void event(String type, String bodyJson) {
        final long ns = SystemClock.elapsedRealtimeNanos();
        WRITER.execute(() -> {
            if (out == null) return;
            try {
                long seq = SEQ.incrementAndGet();
                writeLine("{" + jstr("type", type) + ",\"seq\":" + seq + ",\"elapsedNs\":" + ns
                        + ",\"sinceRunMs\":" + ((ns - runStartElapsedNs) / 1_000_000L)
                        + (bodyJson == null || bodyJson.isEmpty() ? "" : "," + bodyJson) + "}");
                long now = System.currentTimeMillis();
                if (now - lastFlushMs >= 60_000) {     // flush every 60 s, never fsync per sample
                    out.flush();
                    lastFlushMs = now;
                }
            } catch (Throwable t) {
                L.e("MEASURE_WRITE_FAIL", t);
            }
        });
    }

    public static void end(String reason) {
        WRITER.execute(() -> closeQuietly(reason));
    }

    private static void closeQuietly(String reason) {
        if (out == null) return;
        try {
            long dur = (SystemClock.elapsedRealtimeNanos() - runStartElapsedNs) / 1_000_000L;
            writeLine("{\"type\":\"RUN_END\",\"seq\":" + SEQ.incrementAndGet()
                    + "," + jstr("reason", reason) + ",\"durationMs\":" + dur
                    + ",\"samples\":" + SEQ.get() + ",\"wallUtc\":\"" + isoNow() + "\"}");
            out.flush();
            fos.getFD().sync();                       // durable only at run end
            out.close();
        } catch (Throwable t) {
            L.e("MEASURE_END_FAIL", t);
        }
        out = null;
        fos = null;
        try {
            if (partial != null && partial.exists() && finalFile != null) {
                boolean ok = partial.renameTo(finalFile);
                L.i("RUN_END reason=" + reason + " renamed=" + ok + " file=" + finalFile.getName()
                        + " sha256=" + sha256(finalFile));
            }
        } catch (Throwable t) {
            L.e("MEASURE_RENAME_FAIL", t);
        }
    }

    private static void writeLine(String s) throws Exception {
        out.write(s);
        out.write('\n');
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String sha256(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return "ERR";
        }
    }

    public static String jstr(String k, String v) {
        return JSONObject.quote(k) + ":" + JSONObject.quote(v == null ? "" : v);
    }

    private static String keywordHash(WakeWordStore.Selection selection) throws Exception {
        // Preserve historical Chinese/English hashes; Japanese has no KeywordSpotter token line.
        String identity = selection.language == WakeLanguage.JAPANESE
                ? selection.language.id + "\n" + selection.phrase + "\n" + selection.japaneseReading
                : selection.keywordLine;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private Measure() {
    }
}
