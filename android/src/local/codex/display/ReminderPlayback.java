package local.codex.display;

final class ReminderPlayback {
    private static final long WINDOW = 300_000;
    private static final long REPEAT_PAUSE = 6_000;

    private ReminderPlayback() {}

    static boolean active(long scheduled, long now) {
        return remaining(scheduled, now) > 0;
    }

    static long remaining(long scheduled, long now) {
        long age = now - scheduled;
        return age < 0 ? 0 : Math.max(0, WINDOW - age);
    }

    static long phase(long elapsed, long duration) {
        return Math.max(0, elapsed) % (duration + REPEAT_PAUSE);
    }
}
