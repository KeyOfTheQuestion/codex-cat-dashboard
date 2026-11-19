package local.codex.display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ReminderPlan {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DAY = 86_400_000;

    enum Kind {
        WATER("喝水"), STRETCH("拉伸"), TASK("待办");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    final Habit water;
    final Habit stretch;
    final List<Task> tasks = new ArrayList<>();

    ReminderPlan(long now) {
        water = new Habit(Kind.WATER, LocalTime.of(9, 0), LocalTime.of(19, 0), 60, now);
        stretch = new Habit(Kind.STRETCH, LocalTime.of(10, 30), LocalTime.of(18, 30), 120, now);
    }

    static long at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(ZONE).toInstant().toEpochMilli();
    }

    static LocalDate date(long time) {
        return Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate();
    }

    static long nextDaily(LocalTime time, long now) {
        LocalDate today = date(now);
        long first = at(today, time);
        return first > now ? first : at(today.plusDays(1), time);
    }

    Status status(long now) {
        Status status = new Status();
        water.addOccurrences(status, now);
        stretch.addOccurrences(status, now);
        for (Task task : tasks) {
            if (task.first > now) {
                status.offer(new Occurrence(Kind.TASK, task, task.title, task.first));
            } else {
                long latest = task.daily ? task.first + (now - task.first) / DAY * DAY : task.first;
                if (latest > task.acknowledgedAt) {
                    status.due.add(new Occurrence(Kind.TASK, task, task.title, latest));
                }
                if (task.daily) {
                    status.offer(new Occurrence(Kind.TASK, task, task.title, latest + DAY));
                }
            }
        }
        status.due.sort(Comparator.comparingLong(item -> item.at));
        return status;
    }

    void acknowledge(Occurrence item) {
        switch (item.kind) {
            case WATER:
                water.acknowledgedAt = item.at;
                break;
            case STRETCH:
                stretch.acknowledgedAt = item.at;
                break;
            case TASK:
                if (item.task.daily) {
                    item.task.acknowledgedAt = item.at;
                } else {
                    tasks.remove(item.task);
                }
                break;
        }
    }

    static ReminderPlan load(String encoded, long now) {
        ReminderPlan plan = new ReminderPlan(now);
        if (encoded == null) {
            return plan;
        }
        try {
            JSONObject data = new JSONObject(encoded);
            JSONObject waterData = data.optJSONObject("water");
            if (waterData == null) {
                // Upgrade the former two-hour interval once while keeping the user's time window.
                plan.water.enabled = data.getBoolean("waterEnabled");
                plan.water.start = LocalTime.parse(data.getString("waterStart"));
                plan.water.end = LocalTime.parse(data.getString("waterEnd"));
                plan.water.effectiveFrom = data.getLong("effectiveFrom");
                plan.water.acknowledgedAt = data.getLong("waterAcknowledged");
            } else {
                plan.water.load(waterData);
            }
            JSONObject stretchData = data.optJSONObject("stretch");
            if (stretchData != null) {
                plan.stretch.load(stretchData);
            }
            JSONArray items = data.getJSONArray("tasks");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                Task task = new Task(item.getString("title"), item.getLong("first"), item.getBoolean("daily"));
                task.acknowledgedAt = item.getLong("acknowledgedAt");
                plan.tasks.add(task);
            }
        } catch (JSONException | java.time.DateTimeException error) {
            return new ReminderPlan(now);
        }
        return plan;
    }

    String encode() {
        try {
            JSONArray items = new JSONArray();
            for (Task task : tasks) {
                items.put(new JSONObject().put("title", task.title).put("first", task.first)
                    .put("daily", task.daily).put("acknowledgedAt", task.acknowledgedAt));
            }
            return new JSONObject().put("water", water.encode())
                .put("stretch", stretch.encode()).put("tasks", items).toString();
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    static final class Habit {
        final Kind kind;
        boolean enabled = true;
        LocalTime start;
        LocalTime end;
        int intervalMinutes;
        long effectiveFrom;
        long acknowledgedAt;

        Habit(Kind kind, LocalTime start, LocalTime end, int intervalMinutes, long now) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.intervalMinutes = intervalMinutes;
            effectiveFrom = now;
        }

        void addOccurrences(Status status, long now) {
            if (!enabled) {
                return;
            }
            LocalDate today = date(now);
            long first = at(today, start);
            long last = at(today, end);
            long interval = intervalMinutes * 60_000L;
            long latest = first + Math.floorDiv(Math.min(now, last) - first, interval) * interval;
            if (latest >= first && latest >= effectiveFrom && latest > acknowledgedAt) {
                status.due.add(new Occurrence(kind, null, kind.label, latest));
            }
            long next = first + Math.max(0, Math.floorDiv(now - first, interval) + 1) * interval;
            status.offer(new Occurrence(kind, null, kind.label, next <= last ? next : at(today.plusDays(1), start)));
        }

        void load(JSONObject data) throws JSONException {
            enabled = data.getBoolean("enabled");
            start = LocalTime.parse(data.getString("start"));
            end = LocalTime.parse(data.getString("end"));
            intervalMinutes = Math.max(1, Math.min(1440, data.getInt("intervalMinutes")));
            effectiveFrom = data.getLong("effectiveFrom");
            acknowledgedAt = data.getLong("acknowledgedAt");
        }

        JSONObject encode() throws JSONException {
            return new JSONObject().put("enabled", enabled).put("start", start.toString())
                .put("end", end.toString()).put("intervalMinutes", intervalMinutes)
                .put("effectiveFrom", effectiveFrom).put("acknowledgedAt", acknowledgedAt);
        }
    }

    static final class Task {
        final String title;
        final long first;
        final boolean daily;
        long acknowledgedAt;

        Task(String title, long first, boolean daily) {
            this.title = title;
            this.first = first;
            this.daily = daily;
        }
    }

    static final class Occurrence {
        final Kind kind;
        final Task task;
        final String title;
        final long at;

        Occurrence(Kind kind, Task task, String title, long at) {
            this.kind = kind;
            this.task = task;
            this.title = title;
            this.at = at;
        }

        boolean isSameAs(Occurrence other) {
            return other != null && kind == other.kind && task == other.task && at == other.at;
        }
    }

    static final class Status {
        final List<Occurrence> due = new ArrayList<>();
        Occurrence next;

        Occurrence forDisplay(long now) {
            for (Occurrence item : due) {
                if (ReminderPlayback.active(item.at, now)) {
                    return item;
                }
            }
            return due.isEmpty() ? null : due.get(0);
        }

        void offer(Occurrence item) {
            if (next == null || item.at < next.at) {
                next = item;
            }
        }
    }
}
