package com.desmond.gptwake;

import android.content.res.AssetManager;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.VersionInfo;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * sherpa-onnx keyword spotter. The KeywordSpotter and the ONNX models stay resident for the
 * lifetime of the process; only the OnlineStream is recreated between listening periods.
 */
public final class KwsEngine {

    private static final String DIR = "kws";
    private static final String ENCODER = DIR + "/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
    private static final String DECODER = DIR + "/decoder-epoch-13-avg-2-chunk-16-left-64.onnx";
    private static final String JOINER = DIR + "/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
    private static final String TOKENS = DIR + "/tokens.txt";
    private static final String KEYWORDS = DIR + "/empty_keywords.txt";

    // First production baseline. Smoke config was 1.0f / 0.25f; the model was confirmed to fire.
    public static volatile float keywordsScore = 1.5f;
    public static final float DEFAULT_THRESHOLD = 0.40f;
    public static volatile float keywordsThreshold = DEFAULT_THRESHOLD;
    public static volatile int numTrailingBlanks = 1;

    private volatile KeywordSpotter spotter;
    private volatile OnlineStream stream;
    /** Custom keyword line; when set it is passed to createStream() instead of keywords.txt. */
    public static volatile String customKeywordLine;

    // Counters that let us verify (rather than assume) why decode latency is bimodal.
    public static final java.util.concurrent.atomic.AtomicLong acceptCalls =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong readyTrueCount =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong decodeCalls =
            new java.util.concurrent.atomic.AtomicLong();

    public boolean isLoaded() {
        return spotter != null;
    }

    public synchronized void load(AssetManager assets) throws Exception {
        if (spotter != null) return;

        try {
            L.i("KWS_NATIVE_VERSION version=" + VersionInfo.Companion.getVersion()
                    + " sha1=" + VersionInfo.Companion.getGitSha1()
                    + " date=" + VersionInfo.Companion.getGitDate());
        } catch (Throwable t) {
            L.e("KWS_NATIVE_VERSION_FAIL", t);
        }

        for (String f : new String[]{ENCODER, DECODER, JOINER, TOKENS, KEYWORDS}) {
            L.i("KWS_MODEL_SHA256 " + f + " = " + sha256Asset(assets, f));
        }

        long t0 = System.currentTimeMillis();
        L.i("KWS_MODEL_LOAD_BEGIN score=" + keywordsScore
                + " threshold=" + keywordsThreshold
                + " trailingBlanks=" + numTrailingBlanks);

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(ENCODER);
        transducer.setDecoder(DECODER);
        transducer.setJoiner(JOINER);

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(TOKENS);
        model.setNumThreads(1);
        model.setProvider("cpu");
        model.setModelType("zipformer2");
        model.setDebug(false);

        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(16000);
        feat.setFeatureDim(80);

        KeywordSpotterConfig cfg = new KeywordSpotterConfig();
        cfg.setFeatConfig(feat);
        cfg.setModelConfig(model);
        cfg.setMaxActivePaths(4);
        // createStream(custom) ADDS to this file in sherpa-onnx. Keep it empty so changing the
        // wake word replaces the default instead of leaving both phrases armed.
        cfg.setKeywordsFile(KEYWORDS);
        cfg.setKeywordsScore(keywordsScore);
        cfg.setKeywordsThreshold(keywordsThreshold);
        cfg.setNumTrailingBlanks(numTrailingBlanks);

        spotter = new KeywordSpotter(assets, cfg);
        L.i("KWS_MODEL_READY loadMs=" + (System.currentTimeMillis() - t0));
    }

    public synchronized void newStream() {
        releaseStream();
        String keywords = streamKeywords();
        stream = spotter.createStream(keywords);
        if (stream.getPtr() == 0) {
            stream = null;
            throw new IllegalStateException("Keyword spotter rejected the wake word");
        }
        L.i("KWS_STREAM_NEW custom=\"" + keywords + "\"");
    }

    private static String streamKeywords() {
        String line = customKeywordLine;
        if (line == null || line.trim().isEmpty()) line = WakeWordStore.DEFAULT_LINE;
        // Per-keyword overrides take effect without reloading the resident ONNX models.
        int label = line.indexOf(" @");
        String parameters = " :" + keywordsScore + " #" + keywordsThreshold;
        return label < 0 ? line + parameters
                : line.substring(0, label) + parameters + line.substring(label);
    }

    public synchronized void releaseStream() {
        OnlineStream s = stream;
        stream = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable error) {
                L.e("KWS_STREAM_RELEASE_FAIL", error);
            }
        }
    }

    /** Feeds one frame and returns the detected keyword, or null. */
    public synchronized String accept(float[] samples, int sampleRate) {
        KeywordSpotter sp = spotter;
        OnlineStream st = stream;
        if (sp == null || st == null) return null;

        acceptCalls.incrementAndGet();
        st.acceptWaveform(samples, sampleRate);
        String hit = null;
        boolean anyReady = false;
        while (sp.isReady(st)) {
            if (!anyReady) {
                anyReady = true;
                readyTrueCount.incrementAndGet();
            }
            decodeCalls.incrementAndGet();
            sp.decode(st);
            KeywordSpotterResult r = sp.getResult(st);
            String kw = r.getKeyword();
            if (kw != null && !kw.isEmpty()) {
                sp.reset(st);
                hit = kw;
                break;
            }
        }
        return hit;
    }

    public synchronized void release() {
        releaseStream();
        KeywordSpotter sp = spotter;
        spotter = null;
        if (sp != null) {
            try {
                sp.release();
            } catch (Throwable error) {
                L.e("KWS_MODEL_RELEASE_FAIL", error);
            }
        }
    }

    private static String sha256Asset(AssetManager am, String path) {
        try (InputStream in = am.open(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }
}
