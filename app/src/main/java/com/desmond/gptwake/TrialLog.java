package com.desmond.gptwake;

import java.util.Locale;

import android.content.Context;
import android.os.SystemClock;

/**
 * Trial markers for recall runs. Raw counts alone cannot tell 9-of-10 apart from 9 utterances,
 * so every spoken attempt is bracketed. Feedback is haptic only: a tone would enter the mic.
 */
public final class TrialLog {

    private static volatile String currentTrial;
    private static volatile String currentDistance;
    private static volatile long beginNs;
    private static volatile long beginRaw, beginAccepted, beginSuppressed;

    public static synchronized void begin(Context c, String distance, int index, long raw,
                                          long accepted, long suppressed) {
        currentTrial = distance + "-" + String.format(Locale.ROOT, "%02d", index);
        currentDistance = distance;
        beginNs = SystemClock.elapsedRealtimeNanos();
        beginRaw = raw;
        beginAccepted = accepted;
        beginSuppressed = suppressed;
        L.i("TRIAL_BEGIN id=" + currentTrial + " distance=" + distance);
        Measure.event("TRIAL_BEGIN", Measure.jstr("trialId", currentTrial) + ","
                + Measure.jstr("distance", distance));
    }

    public static synchronized void end(Context c, long raw, long accepted, long suppressed) {
        if (currentTrial == null) {
            L.i("TRIAL_END_NO_ACTIVE_TRIAL");
            return;
        }
        long dRaw = raw - beginRaw;
        long dAcc = accepted - beginAccepted;
        long dSup = suppressed - beginSuppressed;
        long durMs = (SystemClock.elapsedRealtimeNanos() - beginNs) / 1_000_000L;
        boolean hit = dAcc > 0;
        L.i("TRIAL_END id=" + currentTrial + " distance=" + currentDistance
                + " hit=" + hit + " durMs=" + durMs
                + " rawHits=" + dRaw + " acceptedHits=" + dAcc + " duplicateSuppressed=" + dSup
                + " class=" + (hit ? "TP_USER" : "MISS_USER"));
        Measure.event("TRIAL_END", Measure.jstr("trialId", currentTrial) + ","
                + Measure.jstr("distance", currentDistance)
                + ",\"hit\":" + hit + ",\"durMs\":" + durMs
                + ",\"rawHits\":" + dRaw + ",\"acceptedHits\":" + dAcc
                + ",\"duplicateSuppressed\":" + dSup
                + "," + Measure.jstr("class", hit ? "TP_USER" : "MISS_USER"));
        currentTrial = null;
    }

    public static boolean active() {
        return currentTrial != null;
    }

    public static String currentTrialId() {
        return currentTrial;
    }


    private TrialLog() {
    }
}
