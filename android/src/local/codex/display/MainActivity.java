package local.codex.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff101714;
    private static final int SURFACE = 0xff1b2720;
    private static final int TEXT = 0xfff2f2e5;
    private static final int MUTED = 0xffa7b7ab;
    private static final int GREEN = 0xffc8eb9d;
    private static final int MINT = 0xff98d2b1;
    private static final int ORANGE = 0xffffca8e;
    private static final int LINE = 0xff39483d;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final DateTimeFormatter HEADER_CLOCK = DateTimeFormatter.ofPattern("M月d日 EEE  HH:mm:ss", Locale.CHINA);
    private static final DateTimeFormatter RESET = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.CHINA);
    private static final NumberFormat NUMBERS = NumberFormat.getNumberInstance(Locale.CHINA);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private ReminderPlan plan;
    private UsageEndpoint usageEndpoint;
    private boolean foreground;
    private boolean fetching;
    private TextView dashboardTitle;
    private TextView clock;
    private TextView connection;
    private TextView bubble;
    private LinearLayout speech;
    private Button acknowledge;
    private TextView tokens;
    private TextView tokenLabel;
    private TextView tokenTime;
    private Quota fiveHours;
    private Quota weekly;
    private LinearLayout workBuddyPanel;
    private TextView workBuddyStatus;
    private TextView workBuddyRemaining;
    private TextView workBuddyUsed;
    private CatView cat;
    private CatVisitView visitView;
    private CatVisitLink visitLink;
    private String visitRole;
    private boolean nestOnLeft;
    private ReminderPlan.Occurrence currentReminder;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            renderReminders();
            // Leave a small margin after the wall-clock boundary for uptime-based Handler scheduling.
            handler.postDelayed(this, 1_015 - Math.floorMod(System.currentTimeMillis(), 1_000));
        }
    };
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!fetching) {
                fetchUsage();
            }
            handler.postDelayed(this, 5_000);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        preferences = getSharedPreferences("dashboard", MODE_PRIVATE);
        usageEndpoint = UsageEndpoint.parse(preferences.getString("usage_address", ""), preferences.getString("usage_token", ""));
        plan = ReminderPlan.load(preferences.getString("reminders", null), System.currentTimeMillis());
        visitRole = preferences.getString("cat_visit_role", "off");
        nestOnLeft = preferences.getBoolean("cat_visit_nest_left", false);
        String visitId = preferences.getString("cat_visit_id", "");
        if (visitId.isEmpty()) {
            visitId = UUID.randomUUID().toString().replace("-", "");
            preferences.edit().putString("cat_visit_id", visitId).apply();
        }
        visitLink = new CatVisitLink(visitId, snapshot -> visitView.update(snapshot));
        savePlan();
        displayScreen();
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        resumeScreen();
        hideBars();
    }

    @Override
    protected void onPause() {
        foreground = false;
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(poll);
        visitLink.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        visitLink.close();
        network.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            hideBars();
        }
    }

    private void hideBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private void resumeScreen() {
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(poll);
        if (!visitRole.equals("nest")) {
            handler.post(clockTick);
            handler.post(poll);
        }
        if (!visitRole.equals("off")) {
            visitView.prepareSprites();
            visitLink.start(visitRole, nestOnLeft);
        } else {
            visitView.update(new CatVisitLink.State(false));
        }
    }

    private void displayScreen() {
        FrameLayout screen = new FrameLayout(this);
        if (visitRole.equals("nest")) {
            visitView = new CatVisitView(this, null);
            visitView.setOnClickListener(view -> {
                visitView.touchPillow();
                visitLink.call();
            });
            screen.addView(visitView, new FrameLayout.LayoutParams(-1, -1));
            Button settings = button("串门设置", view -> showVisitSettings());
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            corner.setMargins(dp(16), dp(13), dp(17), 0);
            screen.addView(settings, corner);
            screen.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
                corner.topMargin = Math.max(dp(13), cutout.top);
                corner.rightMargin = Math.max(dp(17), cutout.right);
                settings.setLayoutParams(corner);
                return insets;
            });
        } else {
            screen.addView(dashboard(), new FrameLayout.LayoutParams(-1, -1));
            visitView = new CatVisitView(this, cat);
            screen.addView(visitView, new FrameLayout.LayoutParams(-1, -1));
        }
        setContentView(screen);
    }

    private void showVisitSettings() {
        int selected = visitRole.equals("nest") ? 3 : visitRole.equals("home") ? (nestOnLeft ? 2 : 1) : 0;
        String[] modes = {"单屏用量看板", "用量看板 · 小窝在右", "用量看板 · 小窝在左", "小窝"};
        new AlertDialog.Builder(this).setTitle("双屏串门")
            .setSingleChoiceItems(modes, selected, (dialog, choice) -> {
                dialog.dismiss();
                boolean wasNest = visitRole.equals("nest");
                visitRole = choice == 3 ? "nest" : choice == 0 ? "off" : "home";
                nestOnLeft = choice == 2;
                preferences.edit().putString("cat_visit_role", visitRole)
                    .putBoolean("cat_visit_nest_left", nestOnLeft).apply();
                visitLink.stop();
                if (wasNest != visitRole.equals("nest")) {
                    displayScreen();
                    hideBars();
                }
                if (foreground) {
                    resumeScreen();
                }
            }).setNegativeButton("取消", null).show();
    }

    private View dashboard() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(17), dp(12), dp(17), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
            view.setPadding(Math.max(dp(17), cutout.left), Math.max(dp(12), cutout.top),
                Math.max(dp(17), cutout.right), Math.max(dp(12), cutout.bottom));
            return insets;
        });

        LinearLayout header = row();
        dashboardTitle = label("Codex 用量", 22, TEXT, true);
        header.addView(dashboardTitle, new LinearLayout.LayoutParams(0, -2, 1));
        clock = label("", 15, TEXT, true);
        clock.setFontFeatureSettings("'tnum'");
        header.addView(clock);
        spacer(header, 12, 1);
        header.addView(button("串门", view -> showVisitSettings()));
        spacer(header, 8, 1);
        header.addView(button("用量连接", view -> showUsageSettings()));
        spacer(header, 8, 1);
        header.addView(button("提醒设置", view -> showSettings()));
        root.addView(header, -1, dp(39));
        spacer(root, 1, 8);

        LinearLayout body = row();
        body.setGravity(Gravity.TOP);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(petPanel(), new LinearLayout.LayoutParams(0, -1, 0.88f));
        spacer(body, 10, 1);
        body.addView(metricsPanel(), new LinearLayout.LayoutParams(0, -1, 1.12f));

        spacer(root, 1, 7);
        connection = label("正在读取 Codex 用量", 11, MUTED, false);
        root.addView(connection, -1, -2);
        return root;
    }

    private View petPanel() {
        LinearLayout panel = vertical();
        panel.setPadding(dp(11), dp(9), dp(11), dp(9));
        panel.setBackground(rounded(0xff29251d, 0xff534433, 13));
        panel.addView(label("桌面橘猫", 12, ORANGE, true));
        cat = new CatView(this);
        panel.addView(cat, new LinearLayout.LayoutParams(-1, 0, 1));

        speech = row();
        speech.setPadding(dp(9), dp(5), dp(6), dp(5));
        speech.setBackground(rounded(0xff3a3024, 0xff634536, 8));
        bubble = label("暂无提醒", 15, ORANGE, true);
        bubble.setMaxLines(2);
        bubble.setEllipsize(TextUtils.TruncateAt.END);
        speech.addView(bubble, new LinearLayout.LayoutParams(0, -2, 1));
        acknowledge = button("确认", view -> {
            if (currentReminder != null) {
                plan.acknowledge(currentReminder);
                savePlan();
                renderReminders();
                cat.celebrate();
            }
        });
        acknowledge.setTextColor(0xff352011);
        acknowledge.setBackground(rounded(ORANGE, ORANGE, 6));
        acknowledge.setVisibility(View.GONE);
        speech.addView(acknowledge);
        panel.addView(speech, -1, dp(56));
        return panel;
    }

    private View metricsPanel() {
        LinearLayout column = vertical();
        LinearLayout top = tile(0xff213025);
        tokenLabel = label("今日 token", 13, MUTED, true);
        top.addView(tokenLabel);
        tokens = label("—", 33, GREEN, true);
        tokens.setSingleLine();
        tokens.setAutoSizeTextTypeUniformWithConfiguration(18, 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        top.addView(tokens, -1, dp(40));
        tokenTime = label("服务电脑 · 北京时间", 11, MUTED, false);
        top.addView(tokenTime);
        column.addView(top, new LinearLayout.LayoutParams(-1, 0, 1));
        spacer(column, 1, 8);

        LinearLayout limits = row();
        fiveHours = new Quota("5 小时剩余");
        weekly = new Quota("每周剩余");
        limits.addView(fiveHours.panel, new LinearLayout.LayoutParams(0, -1, 1));
        spacer(limits, 8, 1);
        limits.addView(weekly.panel, new LinearLayout.LayoutParams(0, -1, 1));
        column.addView(limits, new LinearLayout.LayoutParams(-1, 0, 1));
        workBuddyPanel = tile(0xff292a20);
        LinearLayout workBuddyHeader = row();
        workBuddyHeader.addView(label("WorkBuddy", 13, ORANGE, true),
            new LinearLayout.LayoutParams(0, -2, 1));
        workBuddyStatus = label("", 11, MUTED, false);
        workBuddyHeader.addView(workBuddyStatus);
        workBuddyPanel.addView(workBuddyHeader);
        spacer(workBuddyPanel, 1, 4);
        LinearLayout credits = row();
        workBuddyRemaining = addCreditMetric(credits, "剩余积分", ORANGE);
        workBuddyUsed = addCreditMetric(credits, "当前积分包已用", TEXT);
        workBuddyPanel.addView(credits, -1, -2);
        LinearLayout.LayoutParams workBuddyLayout = new LinearLayout.LayoutParams(-1, -2);
        workBuddyLayout.topMargin = dp(8);
        workBuddyPanel.setVisibility(View.GONE);
        column.addView(workBuddyPanel, workBuddyLayout);
        return column;
    }

    private TextView addCreditMetric(LinearLayout row, String title, int color) {
        LinearLayout metric = vertical();
        metric.addView(label(title, 11, MUTED, false));
        TextView value = label("—", 23, color, true);
        value.setSingleLine();
        value.setAutoSizeTextTypeUniformWithConfiguration(12, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        metric.addView(value, -1, dp(29));
        row.addView(metric, new LinearLayout.LayoutParams(0, -2, 1));
        return value;
    }

    private final class Quota {
        final LinearLayout panel = tile(SURFACE);
        final TextView value = label("—", 30, TEXT, true);
        final TextView reset = label("重置 —", 11, MUTED, false);
        final ProgressBar bar = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);

        Quota(String title) {
            panel.addView(label(title, 13, MUTED, true));
            panel.addView(value);
            panel.addView(reset);
            bar.setMax(100);
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
            spacer(panel, 1, 4);
            panel.addView(bar, -1, dp(4));
        }

        void display(JSONObject data) {
            if (data == null) {
                value.setText("—");
                reset.setText("重置 —");
                bar.setProgress(0);
                return;
            }
            double remaining = data.optDouble("remaining");
            long resetsAt = data.optLong("resetsAt");
            value.setText(Double.isNaN(remaining) ? "—" : NUMBERS.format(remaining) + "%");
            reset.setText(resetsAt > 0 ? "重置 " + RESET.format(beijing(resetsAt)) : "重置 —");
            bar.setProgress((int) Math.round(remaining));
            bar.setProgressTintList(ColorStateList.valueOf(remaining <= 20 ? ORANGE : MINT));
        }
    }

    private void renderReminders() {
        long now = System.currentTimeMillis();
        ZonedDateTime local = beijing(now);
        clock.setText(HEADER_CLOCK.format(local));
        ReminderPlan.Status status = plan.status(now);
        ReminderPlan.Occurrence previous = currentReminder;
        currentReminder = status.due.isEmpty() ? null : status.due.get(0);
        boolean due = currentReminder != null;
        boolean newReminder = due && !currentReminder.isSameAs(previous);
        cat.setReminder(due ? currentReminder.kind : null, newReminder);
        if (due != (previous != null)) {
            speech.setBackground(due ? rounded(0xff503324, 0xffd69761, 8)
                : rounded(0xff3a3024, 0xff634536, 8));
        }
        if (newReminder) {
            speech.animate().cancel();
            speech.setScaleX(0.985f);
            speech.setScaleY(0.985f);
            speech.animate().scaleX(1).scaleY(1).setDuration(420).start();
        }
        acknowledge.setVisibility(due ? View.VISIBLE : View.GONE);
        if (due) {
            String spoken;
            switch (currentReminder.kind) {
                case WATER:
                    spoken = "一起喝口水吧。";
                    break;
                case STRETCH:
                    spoken = "伸个懒腰，活动一下。";
                    break;
                default:
                    spoken = "到点啦 · " + currentReminder.title;
                    break;
            }
            String more = status.due.size() > 1 ? " · 另有 " + (status.due.size() - 1) + " 项" : "";
            updateBubble(spoken + more);
        } else if (status.next != null) {
            String spoken = "下次 " + formatSchedule(status.next.at, now) + " · " + status.next.title;
            updateBubble(spoken);
        } else {
            updateBubble("暂无提醒");
        }
    }

    private void updateBubble(String text) {
        if (!TextUtils.equals(bubble.getText(), text)) {
            bubble.setText(text);
        }
    }

    private void fetchUsage() {
        fetching = true;
        UsageEndpoint endpoint = usageEndpoint;
        network.execute(() -> {
            JSONObject data = null;
            String failure = "用量服务连接中断 · 自动重试";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint.apiUrl).openConnection();
                connection.setConnectTimeout(2_000);
                connection.setReadTimeout(2_000);
                connection.setInstanceFollowRedirects(false);
                if (!endpoint.token.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + endpoint.token);
                }
                int httpStatus = connection.getResponseCode();
                if (httpStatus == 401 || httpStatus == 403) {
                    failure = "用量服务拒绝访问 · 检查连接地址及令牌";
                } else if (httpStatus != 200) {
                    failure = "用量服务响应 HTTP " + httpStatus;
                } else {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            connection.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder body = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            body.append(line);
                        }
                        JSONObject parsed = new JSONObject(body.toString());
                        if (parsed.has("quota") && parsed.has("tokenUsage")) {
                            data = parsed;
                        }
                        if (data == null) {
                            failure = "用量服务返回了无法识别的数据";
                        }
                    }
                }
            } catch (org.json.JSONException ignored) {
                failure = "用量服务返回了无法识别的数据";
            } catch (java.io.IOException ignored) {
                // An interrupted connection is retried by the normal polling loop.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            JSONObject snapshot = data;
            String error = failure;
            handler.post(() -> {
                fetching = false;
                if (foreground) {
                    if (endpoint != usageEndpoint) {
                        fetchUsage();
                    } else if (snapshot != null) {
                        renderUsage(snapshot);
                    } else {
                        connectionStatus(error, ORANGE);
                    }
                }
            });
        });
    }

    private void renderUsage(JSONObject data) {
        long quotaUpdated = 0;
        long tokensUpdated = 0;
        JSONObject quota = data.optJSONObject("quota");
        if (quota != null) {
            fiveHours.display(quota.optJSONObject("fiveHours"));
            weekly.display(quota.optJSONObject("weekly"));
            quotaUpdated = quota.optLong("updatedAt");
        }
        JSONObject tokenUsage = data.optJSONObject("tokenUsage");
        if (tokenUsage != null) {
            String today = ReminderPlan.date(System.currentTimeMillis()).toString();
            tokens.setText(today.equals(tokenUsage.optString("day"))
                ? NUMBERS.format(tokenUsage.optLong("tokens")) : "—");
            tokensUpdated = tokenUsage.optLong("updatedAt");
        }
        renderWorkBuddy(data.optJSONObject("workBuddy"));
        if (data.optBoolean("quotaError") || data.optBoolean("tokenError")) {
            connectionStatus("部分用量暂未更新 · 自动重试", ORANGE);
        } else if (quotaUpdated > 0 && tokensUpdated > 0) {
            connectionStatus("实时更新   ·   token " + CLOCK.format(beijing(tokensUpdated))
                + "   ·   额度 " + CLOCK.format(beijing(quotaUpdated)), MINT);
        } else {
            connectionStatus("正在读取 Codex 用量", MUTED);
        }
    }

    private void renderWorkBuddy(JSONObject data) {
        boolean visible = data != null;
        workBuddyPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        dashboardTitle.setText(visible ? "AI 用量" : "Codex 用量");
        tokenLabel.setText(visible ? "Codex 今日 token" : "今日 token");
        if (!visible) {
            return;
        }
        double remaining = data.optDouble("remaining");
        double used = data.optDouble("used");
        workBuddyRemaining.setText(Double.isFinite(remaining) ? NUMBERS.format(remaining) : "—");
        workBuddyUsed.setText(Double.isFinite(used) ? NUMBERS.format(used) : "—");
        long updated = data.optLong("updatedAt");
        boolean stale = data.optBoolean("stale");
        workBuddyStatus.setTextColor(stale ? ORANGE : MUTED);
        if (updated <= 0) {
            workBuddyStatus.setText("等待 WorkBuddy 数据");
        } else {
            String time = CLOCK.format(beijing(updated));
            workBuddyStatus.setText(stale ? "上次 " + time + " · 暂未更新" : time + " 更新");
        }
    }

    private void connectionStatus(String message, int color) {
        connection.setText(message);
        connection.setTextColor(color);
    }

    private void showUsageSettings() {
        LinearLayout fields = form();
        fields.addView(label("填写服务根地址或 /api/usage 完整地址。", 13, MUTED, false));
        EditText address = new EditText(this);
        address.setSingleLine();
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint(UsageEndpoint.DEFAULT_ADDRESS);
        address.setContentDescription("用量服务地址");
        address.setText(usageEndpoint.address);
        fields.addView(address);
        EditText token = new EditText(this);
        token.setSingleLine();
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("访问令牌（可选）");
        token.setContentDescription("访问令牌");
        token.setText(usageEndpoint.token);
        fields.addView(token);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Codex 用量连接")
            .setView(fields).setNegativeButton("取消", null).setNeutralButton("恢复默认", (view, which) ->
                saveUsageEndpoint(UsageEndpoint.parse("", ""))).setPositiveButton("保存", null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                saveUsageEndpoint(UsageEndpoint.parse(address.getText().toString(), token.getText().toString()));
                dialog.dismiss();
            } catch (IllegalArgumentException error) {
                address.setError(error.getMessage());
            }
        });
    }

    private void saveUsageEndpoint(UsageEndpoint endpoint) {
        usageEndpoint = endpoint;
        preferences.edit().putString("usage_address", endpoint.address).putString("usage_token", endpoint.token).apply();
        tokens.setText("—");
        fiveHours.display(null);
        weekly.display(null);
        renderWorkBuddy(null);
        connectionStatus("正在读取 Codex 用量", MUTED);
        if (foreground && !fetching) {
            fetchUsage();
        }
    }

    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("提醒设置 · 北京时间")
            .setItems(new String[]{"喝水时段和间隔", "拉伸时段和间隔", "添加定时待办", "管理已安排待办"}, (dialog, choice) -> {
                if (choice == 0) {
                    editHabit(plan.water);
                } else if (choice == 1) {
                    editHabit(plan.stretch);
                } else if (choice == 2) {
                    addTask();
                } else {
                    showTasks();
                }
            }).setNegativeButton("关闭", null).show();
    }

    private void editHabit(ReminderPlan.Habit habit) {
        LinearLayout fields = form();
        CheckBox enable = new CheckBox(this);
        String title = habit.kind.label + "提醒";
        enable.setText("启用" + title);
        enable.setChecked(habit.enabled);
        fields.addView(enable);
        LocalTime[] times = {habit.start, habit.end};
        fields.addView(button("开始  " + CLOCK.format(times[0]), view ->
            chooseTime((Button) view, "开始", times, 0)));
        fields.addView(button("结束  " + CLOCK.format(times[1]), view ->
            chooseTime((Button) view, "结束", times, 1)));
        EditText interval = new EditText(this);
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setHint("间隔（分钟）");
        interval.setContentDescription("间隔（分钟）");
        interval.setText(Integer.toString(habit.intervalMinutes));
        fields.addView(interval);
        TextView feedback = label("", 12, ORANGE, false);
        fields.addView(feedback);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title)
            .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int minutes;
            try {
                minutes = Integer.parseInt(interval.getText().toString());
            } catch (NumberFormatException error) {
                minutes = 0;
            }
            if (minutes < 1 || minutes > 1440 || times[1].isBefore(times[0])) {
                feedback.setText("间隔需为 1–1440 分钟，且结束时间应晚于或等于开始时间。");
                return;
            }
            if (enable.isChecked() != habit.enabled || minutes != habit.intervalMinutes
                || !times[0].equals(habit.start) || !times[1].equals(habit.end)) {
                habit.enabled = enable.isChecked();
                habit.start = times[0];
                habit.end = times[1];
                habit.intervalMinutes = minutes;
                habit.effectiveFrom = System.currentTimeMillis();
                habit.acknowledgedAt = 0;
                savePlan();
                renderReminders();
            }
            dialog.dismiss();
        });
    }

    private void addTask() {
        LinearLayout fields = form();
        EditText title = new EditText(this);
        title.setSingleLine();
        title.setHint("待办名称");
        title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        fields.addView(title);

        ZonedDateTime start = beijing(System.currentTimeMillis() + 60_000);
        LocalDate[] date = {start.toLocalDate()};
        LocalTime[] time = {start.toLocalTime().withSecond(0).withNano(0)};
        CheckBox daily = new CheckBox(this);
        daily.setText("每天重复");
        fields.addView(daily);
        Button dateButton = button("日期  " + date[0], view -> new DatePickerDialog(this,
            (picker, year, month, day) -> {
                date[0] = LocalDate.of(year, month + 1, day);
                ((Button) view).setText("日期  " + date[0]);
            }, date[0].getYear(), date[0].getMonthValue() - 1, date[0].getDayOfMonth()).show());
        fields.addView(dateButton);
        fields.addView(button("时间  " + CLOCK.format(time[0]), view ->
            chooseTime((Button) view, "时间", time, 0)));
        daily.setOnCheckedChangeListener((button, checked) -> dateButton.setVisibility(checked ? View.GONE : View.VISIBLE));
        TextView feedback = label("", 12, ORANGE, false);
        fields.addView(feedback);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("定时待办")
            .setView(fields).setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            long now = System.currentTimeMillis();
            long first = daily.isChecked() ? ReminderPlan.nextDaily(time[0], now) : ReminderPlan.at(date[0], time[0]);
            String name = title.getText().toString().trim();
            if (name.isEmpty() || first <= now) {
                feedback.setText(name.isEmpty() ? "请填写待办名称。" : "请选择晚于当前时间的日期和时间。");
                return;
            }
            plan.tasks.add(new ReminderPlan.Task(name, first, daily.isChecked()));
            savePlan();
            renderReminders();
            dialog.dismiss();
        });
    }

    private void showTasks() {
        long now = System.currentTimeMillis();
        String[] choices = new String[plan.tasks.size()];
        for (int index = 0; index < choices.length; index++) {
            ReminderPlan.Task item = plan.tasks.get(index);
            String time = item.daily ? "每天 " + CLOCK.format(beijing(item.first)) : formatSchedule(item.first, now);
            choices[index] = item.title + " · " + time;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("已安排待办").setNegativeButton("关闭", null);
        if (choices.length == 0) {
            dialog.setMessage("暂无待办");
        } else {
            dialog.setItems(choices, (list, index) -> new AlertDialog.Builder(this)
                .setTitle(choices[index]).setNegativeButton("取消", null)
                .setPositiveButton("删除", (confirmation, item) -> {
                    plan.tasks.remove(index);
                    savePlan();
                    renderReminders();
                }).show());
        }
        dialog.show();
    }

    private void chooseTime(Button button, String label, LocalTime[] times, int index) {
        LocalTime chosen = times[index];
        new TimePickerDialog(this, (picker, hour, minute) -> {
            times[index] = LocalTime.of(hour, minute);
            button.setText(label + "  " + CLOCK.format(times[index]));
        }, chosen.getHour(), chosen.getMinute(), true).show();
    }

    private void savePlan() {
        preferences.edit().putString("reminders", plan.encode()).apply();
    }

    private static ZonedDateTime beijing(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ReminderPlan.ZONE);
    }

    private static String formatSchedule(long millis, long now) {
        ZonedDateTime local = beijing(millis);
        LocalDate today = ReminderPlan.date(now);
        LocalDate date = local.toLocalDate();
        if (date.equals(today)) {
            return CLOCK.format(local);
        }
        if (date.equals(today.plusDays(1))) {
            return "明天 " + CLOCK.format(local);
        }
        return RESET.format(local);
    }

    private LinearLayout form() {
        LinearLayout fields = vertical();
        fields.setPadding(dp(20), dp(5), dp(20), dp(1));
        return fields;
    }

    private LinearLayout tile(int fill) {
        LinearLayout box = vertical();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(11), dp(5), dp(11), dp(5));
        box.setBackground(rounded(fill, LINE, 11));
        return box;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(34));
        button.setMinimumHeight(dp(34));
        button.setPadding(dp(10), dp(3), dp(10), dp(3));
        button.setBackground(rounded(0xff263129, LINE, 6));
        button.setOnClickListener(onClick);
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), stroke);
        return background;
    }

    private void spacer(LinearLayout parent, int width, int height) {
        parent.addView(new View(this), dp(width), dp(height));
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
