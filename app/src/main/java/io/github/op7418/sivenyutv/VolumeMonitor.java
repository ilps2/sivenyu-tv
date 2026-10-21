package io.github.op7418.sivenyutv;

/** Observes volume changes without depending on delivery of hardware key events. */
final class VolumeMonitor {
    static final long INTERVAL_MS = 200L;

    interface Source { Reading read(); }
    interface Listener { void changed(Reading reading); }
    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void remove(Runnable task);
    }

    static final class Reading {
        final int level;
        final int maximum;
        final boolean muted;

        Reading(int level, int maximum, boolean muted) {
            this.level = level;
            this.maximum = maximum;
            this.muted = muted;
        }

        boolean valid() {
            return maximum > 0 && level >= 0 && level <= maximum;
        }

        int percent() {
            return muted ? 0 : Math.round(100f * level / maximum);
        }
    }

    private final Source source;
    private final Scheduler scheduler;
    private final Listener listener;
    private Reading previous;
    private boolean running;
    private final Runnable tick = this::poll;

    VolumeMonitor(Source source, Scheduler scheduler, Listener listener) {
        this.source = source;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        // Entering a page establishes a baseline; it must not display a stale change.
        previous = readValid();
        scheduler.postDelayed(tick, INTERVAL_MS);
    }

    void stop() {
        running = false;
        scheduler.remove(tick);
        previous = null;
    }

    private Reading readValid() {
        Reading reading = source.read();
        return reading != null && reading.valid() ? reading : null;
    }

    private void poll() {
        if (!running) return;
        Reading current = readValid();
        // A new range usually means a route change. Establish a new baseline then.
        if (current != null && previous != null && current.maximum == previous.maximum
                && (current.level != previous.level || current.muted != previous.muted)) {
            listener.changed(current);
        }
        previous = current;
        if (running) scheduler.postDelayed(tick, INTERVAL_MS);
    }
}
