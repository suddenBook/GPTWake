package com.desmond.gptwake;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WakeControllerTest {
    private MockedStatic<AudioProbe> audio;
    private MockedStatic<AudioStateMonitor> monitor;
    private MockedStatic<GptLauncher> launcher;
    private KwsEngine engine;
    private WakeController controller;
    private final List<Runnable> delayed = new ArrayList<>();

    @Before
    public void setUp() {
        audio = mockStatic(AudioProbe.class);
        monitor = mockStatic(AudioStateMonitor.class);
        launcher = mockStatic(GptLauncher.class);
        audio.when(AudioProbe::isRunning).thenReturn(true);
        engine = mock(KwsEngine.class);
        when(engine.isLoaded()).thenReturn(true);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(call -> {
                    delayed.add(call.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        controller = new WakeController(RuntimeEnvironment.getApplication(), engine, executor);
    }

    @After
    public void tearDown() {
        controller.stop();
        launcher.close();
        monitor.close();
        audio.close();
    }

    private void listen() {
        controller.start();
        controller.onFirstFrame();
        assertEquals(WakeController.State.KWS_LISTENING, controller.state());
    }

    @Test
    public void queuedLaunchDoesNotRunAfterStop() {
        listen();
        controller.onKeyword("wake");
        assertEquals(WakeController.State.MIC_HANDOFF, controller.state());
        Runnable launch = delayed.get(delayed.size() - 1);
        controller.stop();
        launch.run();
        launcher.verifyNoInteractions();
        assertEquals(WakeController.State.STOPPED, controller.state());
    }

    @Test
    public void editingKeywordDuringVoiceKeepsMicrophoneReleased() {
        listen();
        controller.onKeyword("wake");
        delayed.get(delayed.size() - 1).run();
        monitor.when(AudioStateMonitor::isVoiceConfirmed).thenReturn(true);
        controller.onAudioStateChanged("voice started");
        assertEquals(WakeController.State.VOICE_ACTIVE, controller.state());
        clearInvocations(engine);

        controller.restartStream();

        assertEquals(WakeController.State.VOICE_ACTIVE, controller.state());
        verify(engine, never()).newStream();
    }

    @Test
    public void incomingCallPausesWithoutWaitingForWakeWord() {
        listen();
        monitor.when(AudioStateMonitor::isCommunicationMode).thenReturn(true);
        controller.onAudioStateChanged("incoming call");
        assertEquals(WakeController.State.EXTERNAL_COMMUNICATION, controller.state());
        audio.verify(AudioProbe::stop);
    }

    @Test
    public void startingDuringCallDoesNotOpenMicrophone() {
        monitor.when(AudioStateMonitor::isCommunicationMode).thenReturn(true);
        controller.start();
        assertEquals(WakeController.State.EXTERNAL_COMMUNICATION, controller.state());
        audio.verify(() -> AudioProbe.start(anyString()), never());
    }

    @Test
    public void lateCallbacksCannotReviveStoppedController() {
        listen();
        controller.stop();
        clearInvocations(engine);
        controller.onKeyword("late wake");
        controller.restartStream();
        controller.onAudioStateChanged("late callback");
        assertEquals(WakeController.State.STOPPED, controller.state());
        assertEquals(0, controller.acceptedHits());
        verify(engine, never()).newStream();
    }

    @Test
    public void modelReadyDoesNotImplyMicrophoneReady() {
        audio.when(AudioProbe::isRunning).thenReturn(false);
        controller.start();
        assertNotEquals(WakeController.State.KWS_LISTENING, controller.state());
        audio.when(AudioProbe::isRunning).thenReturn(true);
        controller.onFirstFrame();
        assertEquals(WakeController.State.KWS_LISTENING, controller.state());
    }

    @Test
    public void captureFailureReleasesMicrophoneAndShowsError() {
        listen();
        controller.onCaptureError("READ_ERR-6");
        assertEquals(WakeController.State.ERROR, controller.state());
        audio.verify(AudioProbe::stop);
    }

    @Test
    public void callEndingReacquiresMicrophoneAndStream() {
        listen();
        monitor.when(AudioStateMonitor::isCommunicationMode).thenReturn(true);
        controller.onAudioStateChanged("call started");
        clearInvocations(engine);
        monitor.when(AudioStateMonitor::isCommunicationMode).thenReturn(false);
        monitor.when(AudioStateMonitor::hasOwnLiveCapture).thenReturn(true);
        controller.onAudioStateChanged("call ended");
        assertEquals(WakeController.State.KWS_LISTENING, controller.state());
        audio.verify(() -> AudioProbe.start("REACQUIRE"));
        verify(engine).newStream();
    }

    @Test
    public void failedModelLoadReleasesMicrophone() throws Exception {
        doThrow(new IllegalStateException("broken model")).when(engine).load(any());
        controller.start();
        assertEquals(WakeController.State.ERROR, controller.state());
        audio.verify(AudioProbe::stop);
    }

    @Test
    public void callEndingDuringModelLoadStillStartsCapture() throws Exception {
        monitor.when(AudioStateMonitor::isCommunicationMode).thenReturn(true);
        audio.when(AudioProbe::isRunning).thenReturn(false);
        doAnswer(call -> {
            monitor.when(AudioStateMonitor::isCommunicationMode).thenReturn(false);
            return null;
        }).when(engine).load(any());
        controller.start();
        audio.verify(() -> AudioProbe.start(anyString()));
    }
}
