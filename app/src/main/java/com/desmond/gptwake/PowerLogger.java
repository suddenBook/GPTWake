package com.desmond.gptwake;

import java.util.Locale;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Battery and CPU every 10 s.
 *
 * PSS-class memory and per-thread CPU are captured once at each END of a run rather than
 * periodically: collecting them walks the whole smaps of a ~250 MB process and opens two files per
 * thread, which perturbs the very draw being measured. Full meminfo is also captured outside the
 * app, before and after each run.
 */
public final class PowerLogger {

    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "power-logger"));
    private static ScheduledFuture<?> task;
    private static long lastWallMs;
    private static long lastCpuMs;
    private static long seq;

    private static String num(BatteryManager bm, int id, String name) {
        try {
            long v = bm.getLongProperty(id);
            if (v == Long.MIN_VALUE) return "\"" + name + "\":\"UNSUPPORTED\"";
            return "\"" + name + "\":" + v;
        } catch (Throwable t) {
            return "\"" + name + "\":\"UNSUPPORTED\"";
        }
    }

    public static synchronized void start(Context c, String tag) {
        stop();
        Context app = c.getApplicationContext();
        lastWallMs = SystemClock.elapsedRealtime();
        lastCpuMs = Process.getElapsedCpuTime();
        seq = 0;
        L.i("POWERLOG_START tag=" + tag + " intervalMs=" + Cfg.powerLogIntervalMs);
        heavySample(app, tag, "start");
        task = EXEC.scheduleAtFixedRate(() -> sample(app, tag),
                Cfg.powerLogIntervalMs, Cfg.powerLogIntervalMs, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
            L.i("POWERLOG_STOP");
        }
    }

    /** Captures the end-of-run heavy metrics. Call before tearing a measurement run down. */
    public static synchronized void finish(Context c, String tag) {
        stop();
        heavySample(c.getApplicationContext(), tag, "end");
    }

    /**
     * PSS and per-thread CPU are expensive to collect: getMemoryInfo walks the full smaps of a
     * ~250MB process and procTaskCpuMs opens two files per thread across ~35 threads. Running them
     * inside the recording window measurably perturbs the quantity being measured, so they are
     * captured once at each end of the run instead of every 60 s.
     */
    private static void heavySample(Context app, String tag, String phase) {
        try {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);

            StringBuilder tasks = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> e : ThreadCpu.procTaskCpuMs().entrySet()) {
                if (!first) tasks.append(',');
                first = false;
                tasks.append('"').append(e.getKey().replace('"', '_')).append("\":").append(e.getValue());
            }
            tasks.append('}');

            Measure.event("HEAVY", Measure.jstr("tag", tag) + "," + Measure.jstr("phase", phase)
                    + ",\"totalPssKb\":" + mi.getTotalPss()
                    + ",\"privateDirtyKb\":" + mi.getTotalPrivateDirty()
                    + ",\"privateCleanKb\":" + mi.getTotalPrivateClean()
                    + ",\"swapPssKb\":" + mi.getTotalSwappablePss()
                    + ",\"nativeHeapAllocKb\":" + (Debug.getNativeHeapAllocatedSize() / 1024)
                    + ",\"rssKb\":" + Sys.rssKb()
                    + ",\"procTaskCpuMsCumulative\":" + tasks);
        } catch (Throwable t) {
            L.e("POWER_HEAVY_FAIL", t);
        }
    }

    private static void sample(Context app, String tag) {
        try {
            ThreadCpu.publish("power-logger");

            long nowWall = SystemClock.elapsedRealtime();
            long nowCpu = Process.getElapsedCpuTime();
            long wallDelta = nowWall - lastWallMs;
            long cpuDelta = nowCpu - lastCpuMs;
            lastWallMs = nowWall;
            lastCpuMs = nowCpu;

            BatteryManager bm = app.getSystemService(BatteryManager.class);
            Intent bi = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int volt = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            int temp = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int level = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int plugged = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            StringBuilder threads = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> e : ThreadCpu.deltasMs().entrySet()) {
                if (!first) threads.append(',');
                first = false;
                threads.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            threads.append('}');

            long rssKb = Sys.rssKb();

            StringBuilder body = new StringBuilder();
            body.append(Measure.jstr("tag", tag))
                // A run recorded while charging measures charge current, not app draw. The one
                // measurement in this repo's history was taken plugged in, and its positive
                // currentNowUA was read as if it were consumption. Make that impossible to miss.
                .append(",\"onBattery\":").append(plugged == 0)
                .append(",\"wallDeltaMs\":").append(wallDelta)
                .append(",\"cpuDeltaMs\":").append(cpuDelta)
                .append(String.format(Locale.ROOT, ",\"cpuOneCorePct\":%.2f",
                        wallDelta > 0 ? cpuDelta * 100.0 / wallDelta : 0))
                .append(",\"threadCpuDeltaMs\":").append(threads)
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, "chargeUAh"))
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, "currentNowUA"))
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, "currentAvgUA"))
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, "energyNWh"))
                .append(",\"voltageMv\":").append(volt)
                .append(",\"tempDeciC\":").append(temp)
                .append(",\"levelPct\":").append(level)
                .append(",\"plugged\":").append(plugged)
                .append(",\"rssKb\":").append(rssKb);

            Measure.event("SAMPLE", body.toString());

            if (++seq % 6 == 0) {   // one logcat line per minute, the file stays authoritative
                L.i("POWER tag=" + tag + " seq=" + seq
                        + String.format(Locale.ROOT, " cpuOneCorePct=%.2f", wallDelta > 0 ? cpuDelta * 100.0 / wallDelta : 0)
                        + " levelPct=" + level + " tempDeciC=" + temp + " rssKb=" + rssKb
                        + (plugged == 0 ? "" : " PLUGGED_IN_NOT_A_DRAIN_MEASUREMENT"));
            }
        } catch (Throwable t) {
            L.e("POWER_SAMPLE_FAIL", t);
        }
    }

    private PowerLogger() {
    }
}
