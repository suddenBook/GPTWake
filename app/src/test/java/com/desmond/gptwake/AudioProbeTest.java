package com.desmond.gptwake;

import static org.junit.Assert.*;

import android.media.AudioRecord;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioRecord;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AudioProbeTest {
    @After
    public void tearDown() {
        AudioProbe.stop();
        AudioProbe.bind(null, null);
        ShadowAudioRecord.clearSource();
    }

    @Test
    public void failedCaptureNotifiesControllerWithoutClaimingToRecord() throws Exception {
        var failed = new CountDownLatch(1);
        var firstFrame = new AtomicBoolean();
        AudioProbe.bind(null, new AudioProbe.WakeListener() {
            public void onKeyword(String keyword) { fail("No engine was bound"); }
            public void onFirstFrame() { firstFrame.set(true); }
            public void onCaptureError(String reason) { failed.countDown(); }
        });
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
            public int readInShortArray(short[] audio, int offset, int size, boolean blocking) {
                return AudioRecord.ERROR_DEAD_OBJECT;
            }
        });
        AudioProbe.start("test-read-error");
        assertTrue(failed.await(3, TimeUnit.SECONDS));
        assertFalse(firstFrame.get());
        assertFalse(AudioProbe.isRunning());
        assertEquals("READ_ERR" + AudioRecord.ERROR_DEAD_OBJECT, AudioProbe.lastResult());
    }

    @Test
    public void recordingIsReportedOnlyAfterAWholeFrameArrives() throws Exception {
        var readEntered = new CountDownLatch(1);
        var allowFrame = new CountDownLatch(1);
        var failed = new CountDownLatch(1);
        var wasRecordingAtFirstFrame = new AtomicBoolean();
        AudioProbe.bind(null, new AudioProbe.WakeListener() {
            public void onKeyword(String keyword) { fail("No engine was bound"); }
            public void onFirstFrame() { wasRecordingAtFirstFrame.set(AudioProbe.isRunning()); }
            public void onCaptureError(String reason) { failed.countDown(); }
        });
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
            private boolean delivered;
            public int readInShortArray(short[] audio, int offset, int size, boolean blocking) {
                if (delivered) return AudioRecord.ERROR_DEAD_OBJECT;
                readEntered.countDown();
                try {
                    if (!allowFrame.await(3, TimeUnit.SECONDS)) return AudioRecord.ERROR;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return AudioRecord.ERROR;
                }
                delivered = true;
                return size;
            }
        });
        try {
            AudioProbe.start("test-first-frame");
            assertTrue(readEntered.await(3, TimeUnit.SECONDS));
            assertFalse(AudioProbe.isRunning());
        } finally {
            allowFrame.countDown();
        }
        assertTrue(failed.await(3, TimeUnit.SECONDS));
        assertTrue(wasRecordingAtFirstFrame.get());
        assertFalse(AudioProbe.isRunning());
    }
}
