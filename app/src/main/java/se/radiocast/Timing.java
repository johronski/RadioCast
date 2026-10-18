package se.radiocast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stopwatch for "how long from pressing Play until there is sound".
 * Each step is marked as it happens; the summary shows them in milliseconds.
 * One shared instance, since only one start is measured at a time. Thread safe.
 */
public final class Timing {

    public static final Timing current = new Timing();

    private final List<String> labels = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private long start = -1;
    private long soundAt = -1;
    private String what = "";

    /** Starts a new measurement, e.g. when Play is pressed. */
    public synchronized void start(String what) {
        labels.clear();
        times.clear();
        this.what = what;
        start = now();
        soundAt = -1;
        labels.add("Tryck på Spela");
        times.add(start);
    }

    /** Marks a step. Ignored when nothing is being measured. */
    public synchronized void mark(String label) {
        if (start < 0) return;
        labels.add(label);
        times.add(now());
    }

    /** Marks that sound has started; returns the time it took in ms (or -1 if not measuring). */
    public synchronized long sound(String label) {
        if (start < 0) return -1;
        if (soundAt < 0) {
            mark(label);
            soundAt = times.get(times.size() - 1);
        }
        return soundAt - start;
    }

    public synchronized boolean hasResult() { return soundAt >= 0; }

    /** "3,4 s" */
    public static String seconds(long ms) {
        return String.format(new Locale("sv"), "%.1f s", ms / 1000.0);
    }

    /** One line per step: "+   412 ms  Ansluten till högtalaren (412 ms totalt)". */
    public synchronized String summary() {
        if (start < 0) return "Inget mätt än. Tryck Spela så mäts nästa start.";
        StringBuilder b = new StringBuilder(what).append("\n\n");
        long prev = start;
        for (int i = 0; i < labels.size(); i++) {
            long t = times.get(i);
            b.append(String.format(Locale.ROOT, "+%5d ms  ", t - prev)).append(labels.get(i))
                    .append(String.format(Locale.ROOT, "  (%d ms)", t - start)).append('\n');
            prev = t;
        }
        if (soundAt >= 0) b.append("\nLjud efter ").append(seconds(soundAt - start)).append('.');
        else b.append("\nInget ljud registrerat ännu.");
        return b.toString();
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
