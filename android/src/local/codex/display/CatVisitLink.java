package local.codex.display;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class CatVisitLink {
    private static final String ENDPOINT = "http://127.0.0.1:8767/api/cat-visit";
    private static final long POLL_MS = 160;

    static final class Trip {
        final String id;
        final String from;
        final boolean hesitant;
        final int walk;
        final int gap;
        final int arrive;

        Trip(JSONObject object) {
            id = object.optString("id");
            from = object.optString("from");
            hesitant = object.optBoolean("hesitant");
            walk = Math.max(1, object.optInt("walk"));
            gap = Math.max(1, object.optInt("gap"));
            arrive = Math.max(1, object.optInt("arrive"));
        }
    }

    static final class State {
        final boolean connected;
        final boolean conflict;
        final boolean paired;
        final boolean nestOnLeft;
        final String location;
        final Trip trip;
        final long receivedAt;
        private long tripStartedAt;

        State(JSONObject object, long latency) {
            connected = true;
            conflict = false;
            paired = object.optBoolean("paired");
            nestOnLeft = object.optString("side").equals("left");
            location = object.optString("location", "home");
            JSONObject motion = object.optJSONObject("trip");
            trip = motion == null ? null : new Trip(motion);
            receivedAt = SystemClock.uptimeMillis();
            tripStartedAt = motion == null ? 0 : receivedAt - motion.optLong("elapsed") - latency;
        }

        State(boolean conflict) {
            connected = false;
            this.conflict = conflict;
            paired = false;
            nestOnLeft = false;
            location = "home";
            trip = null;
            receivedAt = 0;
            tripStartedAt = 0;
        }

        long elapsed() {
            return SystemClock.uptimeMillis() - tripStartedAt;
        }

        void keepTimeline(State previous) {
            // Keep the first local clock anchor so Wi-Fi jitter cannot rewind an in-flight pose.
            if (trip != null && previous.trip != null && !trip.id.isEmpty() && trip.id.equals(previous.trip.id)) {
                tripStartedAt = previous.tripStartedAt;
            }
        }

        boolean fresh(boolean nest) {
            return connected && SystemClock.uptimeMillis() - receivedAt < (nest ? 3_300 : 5_500);
        }
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong callUntil = new AtomicLong();
    private final String id;
    private final Consumer<State> listener;
    private ScheduledFuture<?> running;
    private int generation;
    private State last = new State(false);
    private volatile boolean holdAtHome;

    CatVisitLink(String id, Consumer<State> listener) {
        this.id = id;
        this.listener = listener;
    }

    void start(String role, boolean nestOnLeft) {
        stop();
        last = new State(false);
        listener.accept(last);
        int session = generation;
        running = worker.scheduleWithFixedDelay(() -> request(session, role, nestOnLeft),
            0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    void stop() {
        generation++;
        callUntil.set(0);
        if (running != null) {
            running.cancel(true);
            running = null;
        }
    }

    void close() {
        stop();
        worker.shutdownNow();
    }

    void call() {
        callUntil.set(SystemClock.uptimeMillis() + 3_500);
    }

    void holdAtHome(boolean hold) {
        holdAtHome = hold;
    }

    private void request(int session, String role, boolean nestOnLeft) {
        long started = SystemClock.uptimeMillis();
        long requested = callUntil.get();
        boolean calling = role.equals("nest") && requested > started;
        String address = ENDPOINT + "?role=" + role + "&id=" + id
            + "&side=" + (nestOnLeft ? "left" : "right") + (calling ? "&call=1" : "")
            + (role.equals("home") && holdAtHome ? "&hold=1" : "");
        State received;
        try {
            received = exchange(address, started);
            if (calling && received.connected) {
                callUntil.compareAndSet(requested, 0);
            }
        } catch (Exception ignored) {
            received = null;
        }
        State result = received;
        handler.post(() -> {
            if (session != generation) {
                return;
            }
            if (result != null) {
                result.keepTimeline(last);
                last = result;
            }
            listener.accept(last);
        });
    }

    private static State exchange(String address, long started) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        http.setRequestMethod("POST");
        http.setRequestProperty("X-Cat-Visit", "1");
        http.setRequestProperty("Connection", "close");
        http.setConnectTimeout(900);
        http.setReadTimeout(1_000);
        http.setInstanceFollowRedirects(false);
        try {
            int response = http.getResponseCode();
            if (response == 409) {
                return new State(true);
            }
            if (response != 200) {
                throw new IOException("Cat visit endpoint unavailable");
            }
            try (InputStream input = http.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                int size;
                while ((size = input.read(chunk)) != -1) {
                    if (buffer.size() + size > 4_096) {
                        throw new IOException("Cat visit response too large");
                    }
                    buffer.write(chunk, 0, size);
                }
                return new State(new JSONObject(buffer.toString(StandardCharsets.UTF_8.name())),
                    Math.min(150, (SystemClock.uptimeMillis() - started) / 2));
            }
        } finally {
            http.disconnect();
        }
    }
}
