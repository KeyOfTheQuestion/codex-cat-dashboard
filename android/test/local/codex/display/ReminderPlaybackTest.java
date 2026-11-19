package local.codex.display;

public final class ReminderPlaybackTest {
    public static void main(String[] args) {
        long scheduled = 1_000_000;
        check(!ReminderPlayback.active(scheduled, scheduled - 1), "wait for the schedule");
        check(ReminderPlayback.active(scheduled, scheduled), "start at the due time");
        check(ReminderPlayback.active(scheduled, scheduled + 299_999), "repeat for five minutes");
        check(!ReminderPlayback.active(scheduled, scheduled + 300_000), "stop at the five-minute boundary");
        check(ReminderPlayback.phase(540, 2700) == 540, "play the first action");
        check(ReminderPlayback.phase(9240, 2700) == 540, "play again after a pause");

        var list = new ReminderPlan.Status();
        var older = new ReminderPlan.Occurrence(ReminderPlan.Kind.WATER, null, "喝水", scheduled - 300_000);
        var fresh = new ReminderPlan.Occurrence(ReminderPlan.Kind.STRETCH, null, "拉伸", scheduled);
        list.due.add(older);
        list.due.add(fresh);
        check(list.forDisplay(scheduled) == fresh, "a missed reminder cannot hide a new action");
        check(list.forDisplay(scheduled + 300_000) == older, "pending reminders stay available for confirmation");
        list.due.remove(fresh);
        check(list.forDisplay(scheduled) == older, "confirmation removes the current action");
        System.out.println("Five-minute playback and due reminder ordering passed");
    }

    private static void check(boolean result, String message) {
        if (!result) {
            throw new AssertionError(message);
        }
    }
}
