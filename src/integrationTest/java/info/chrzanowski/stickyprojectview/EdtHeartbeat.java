package info.chrzanowski.stickyprojectview;

import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public final class EdtHeartbeat {
    private final AtomicLong maxDelayNanos = new AtomicLong();
    private Timer timer;

    public EdtHeartbeat() {
        SwingUtilities.invokeLater(() -> {
            long[] previous = {System.nanoTime()};
            timer = new Timer(50, event -> {
                long now = System.nanoTime();
                maxDelayNanos.accumulateAndGet(Math.max(0, now - previous[0] - 50_000_000L), Math::max);
                previous[0] = now;
            });
            timer.start();
        });
    }

    public long getMaxDelayMillis() {
        return maxDelayNanos.get() / 1_000_000L;
    }

    public void stop() {
        SwingUtilities.invokeLater(() -> {
            if (timer != null) timer.stop();
        });
    }
}