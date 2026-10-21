package io.github.op7418.sivenyutv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VolumeMonitorTest {
    private VolumeMonitor.Reading reading = new VolumeMonitor.Reading(20, 100, false);
    private final List<VolumeMonitor.Reading> shown = new ArrayList<>();
    private final Clock clock = new Clock();
    private final VolumeMonitor monitor = new VolumeMonitor(() -> reading, clock, shown::add);

    @Test public void systemVolumeChangeShowsFeedbackWithoutAnyKeyEvent() {
        monitor.start();
        assertTrue(shown.isEmpty());
        reading = new VolumeMonitor.Reading(21, 100, false);
        clock.tick();
        assertEquals(1, shown.size());
        assertEquals(21, shown.get(0).percent());
        clock.tick();
        assertEquals(1, shown.size());
    }

    @Test public void muteAndUnmuteAreDetectedEvenWhenIndexDoesNotChange() {
        monitor.start();
        reading = new VolumeMonitor.Reading(20, 100, true);
        clock.tick();
        assertTrue(shown.get(0).muted);
        assertEquals(0, shown.get(0).percent());
        reading = new VolumeMonitor.Reading(20, 100, false);
        clock.tick();
        assertEquals(20, shown.get(1).percent());
    }

    @Test public void pauseCancelsPollingAndResumeDoesNotShowBackgroundChanges() {
        monitor.start();
        Runnable pending = clock.pending;
        monitor.stop();
        assertNull(clock.pending);
        reading = new VolumeMonitor.Reading(40, 100, false);
        pending.run();
        assertTrue(shown.isEmpty());
        monitor.start();
        clock.tick();
        assertTrue(shown.isEmpty());
        reading = new VolumeMonitor.Reading(41, 100, false);
        clock.tick();
        assertEquals(41, shown.get(0).percent());
    }

    @Test public void unavailableReadingAndRouteChangeEstablishNewBaselines() {
        monitor.start();
        reading = null;
        clock.tick();
        reading = new VolumeMonitor.Reading(8, 15, false);
        clock.tick();
        assertTrue(shown.isEmpty());
        reading = new VolumeMonitor.Reading(9, 15, false);
        clock.tick();
        assertEquals(60, shown.get(0).percent());
        reading = new VolumeMonitor.Reading(60, 100, false);
        clock.tick();
        assertEquals(1, shown.size());
    }

    @Test public void invalidReadingsDoNotProduceFalseFeedback() {
        monitor.start();
        for (VolumeMonitor.Reading invalid : new VolumeMonitor.Reading[]{
                new VolumeMonitor.Reading(0, 0, false),
                new VolumeMonitor.Reading(-1, 100, false),
                new VolumeMonitor.Reading(101, 100, false)}) {
            reading = invalid;
            clock.tick();
        }
        reading = new VolumeMonitor.Reading(10, 100, false);
        clock.tick();
        assertTrue(shown.isEmpty());
    }

    @Test public void startIsIdempotentAndVolumeLimitsDoNotGenerateDuplicates() {
        reading = new VolumeMonitor.Reading(0, 100, false);
        monitor.start();
        monitor.start();
        assertEquals(1, clock.posts);
        clock.tick();
        assertTrue(shown.isEmpty());
        reading = new VolumeMonitor.Reading(100, 100, false);
        clock.tick();
        assertEquals(100, shown.get(0).percent());
        clock.tick();
        assertEquals(1, shown.size());
    }

    private static final class Clock implements VolumeMonitor.Scheduler {
        Runnable pending;
        int posts;
        @Override public void postDelayed(Runnable task, long delayMs) {
            assertEquals(VolumeMonitor.INTERVAL_MS, delayMs);
            assertNull(pending);
            pending = task;
            posts++;
        }
        @Override public void remove(Runnable task) {
            if (pending == task) pending = null;
        }
        void tick() {
            Runnable task = pending;
            assertNotNull(task);
            pending = null;
            task.run();
        }
    }
}
