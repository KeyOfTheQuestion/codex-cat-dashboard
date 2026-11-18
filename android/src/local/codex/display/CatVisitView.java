package local.codex.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

final class CatVisitView extends View {
    private static final float ROOM_WIDTH = 1_000;
    private static final float ROOM_HEIGHT = 460;
    private static final float PILLOW_X = 514;
    private static final float PILLOW_Y = 338;
    private static final float ENTRANCE_FLOOR = 332;
    private static final float PILLOW_FLOOR = PILLOW_Y + 17;
    private static final float NEST_CAT_HEIGHT = 210;
    private static final int SETTLE_MS = 245;
    private static final int[] FLOOR_LINES = {291, 315, 346, 391, 449};
    private static final long TAP_TIME = 750;
    private final CatView homeCat;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sprite = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF target = new RectF();
    private final Path outline = new Path();
    private final int[] anchor = new int[2];
    private final int[] surface = new int[2];
    private final LinearGradient walls = new LinearGradient(0, 0, 0, 460,
        new int[]{0xff201a20, 0xff412928, 0xff141217}, new float[]{0, 0.63f, 1}, Shader.TileMode.CLAMP);
    private final RadialGradient glow = new RadialGradient(514, 297, 385,
        new int[]{0x91bb744e, 0x4e9a5550, 0x00000000}, new float[]{0, 0.52f, 1}, Shader.TileMode.CLAMP);
    private Bitmap visitAtlas;
    private Bitmap standingAtlas;
    private Rect[] visitors;
    private Rect[] standing;
    private CatVisitLink.State visit = new CatVisitLink.State(false);
    private long touched = -TAP_TIME;

    CatVisitView(Context context, CatView homeCat) {
        super(context);
        this.homeCat = homeCat;
        if (homeCat != null) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else {
            setContentDescription("橘猫的小窝，轻触呼唤或摸摸橘猫");
        }
    }

    void update(CatVisitLink.State state) {
        visit = state;
        if (homeCat != null) {
            homeCat.setVisibility(showsRegularCat() ? VISIBLE : INVISIBLE);
        }
        invalidate();
    }

    void touchPillow() {
        touched = SystemClock.uptimeMillis();
        invalidate();
    }

    void prepareSprites() {
        loadSprites();
        visitAtlas.prepareToDraw();
        if (homeCat != null) {
            standingAtlas.prepareToDraw();
        }
    }

    private boolean showsRegularCat() {
        if (visit.conflict) {
            return false;
        }
        return !visit.fresh(false) || (visit.location.equals("home") && visit.trip == null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        if (homeCat != null) {
            drawDashboardCat(canvas);
        } else {
            drawNest(canvas, now);
        }
    }

    private void drawDashboardCat(Canvas canvas) {
        if (showsRegularCat()) {
            return;
        }
        homeCat.getLocationInWindow(anchor);
        getLocationInWindow(surface);
        float originalX = anchor[0] - surface[0] + homeCat.getWidth() / 2f;
        float floor = anchor[1] - surface[1] + homeCat.getHeight() * 0.96f;
        float height = Math.min(homeCat.getWidth(), homeCat.getHeight() * 0.91f);
        float edge = visit.nestOnLeft ? -height * 0.42f : getWidth() + height * 0.42f;
        CatVisitLink.Trip trip = visit.trip;
        if (trip == null || !visit.fresh(false)) {
            message(canvas, visit.conflict ? "另一台已选用量看板" : "橘猫在小窝",
                originalX, floor - height * 0.48f, dp(15), 0xffd9b48e, false);
            return;
        }

        long elapsed = visit.elapsed();
        boolean outgoing = trip.from.equals("home");
        if (outgoing && elapsed < 0) {
            float lean = trip.hesitant ? (float) Math.sin((elapsed + 900) / 245.0) * 3 : 0;
            drawStanding(canvas, originalX, floor, height, !visit.nestOnLeft, lean,
                trip.hesitant && elapsed < -700, 1);
            if (trip.hesitant) {
                message(canvas, "听见啦…", originalX + height * 0.5f * (visit.nestOnLeft ? -1 : 1),
                    floor - height * 0.86f, dp(13), 0xffffcc9b, true);
            }
        } else if (outgoing && elapsed < trip.walk) {
            float t = elapsed / (float) trip.walk;
            float x = mix(originalX, edge, t * t * (2 - t));
            float walking = smooth(clamp(elapsed / 100f));
            drawStanding(canvas, x, floor, height, !visit.nestOnLeft, 0, false, 1 - walking);
            drawWalk(canvas, x, floor, height * 0.96f, !visit.nestOnLeft, elapsed, walking);
        } else if (!outgoing && elapsed >= trip.walk + trip.gap) {
            long entering = elapsed - trip.walk - trip.gap;
            float progress = clamp(entering / (float) trip.arrive);
            float x = mix(edge, originalX, easeToStop(progress));
            float resting = smooth(clamp((entering - trip.arrive + 130) / 130f));
            drawWalk(canvas, x, floor, height * 0.96f, visit.nestOnLeft, elapsed, 1 - resting);
            drawStanding(canvas, x, floor, height, !visit.nestOnLeft, 0, false, resting);
        } else {
            message(canvas, "橘猫正在串门", originalX, floor - height * 0.48f, dp(15), 0xffd9b48e, false);
        }
        postInvalidateOnAnimation();
    }

    private void drawNest(Canvas canvas, long now) {
        float scaleX = getWidth() / ROOM_WIDTH;
        float scaleY = getHeight() / ROOM_HEIGHT;
        boolean enterFromLeft = !visit.nestOnLeft;
        canvas.save();
        canvas.scale(scaleX, scaleY);
        room(canvas, enterFromLeft, now);
        boolean fresh = visit.fresh(true);
        CatVisitLink.Trip trip = fresh ? visit.trip : null;
        long elapsed = trip == null ? 0 : visit.elapsed();
        if (trip == null) {
            if (fresh && visit.location.equals("nest")) {
                drawSleeping(canvas, PILLOW_X, PILLOW_FLOOR, enterFromLeft, now, 1);
            }
        } else if (trip.from.equals("home") && elapsed >= trip.walk + trip.gap) {
            arriving(canvas, enterFromLeft, trip, elapsed, now);
        } else if (trip.from.equals("nest") && elapsed < trip.walk) {
            float t = clamp(elapsed / (float) trip.walk);
            float x = mix(PILLOW_X, doorway(enterFromLeft), t * t * (2 - t));
            float walking = smooth(clamp(elapsed / 150f));
            drawSleeping(canvas, x, PILLOW_FLOOR, enterFromLeft, now, 1 - walking);
            drawWalk(canvas, x, mix(PILLOW_FLOOR, ENTRANCE_FLOOR, t), NEST_CAT_HEIGHT,
                !enterFromLeft, elapsed, walking);
        }
        long tapAge = now - touched;
        if (tapAge < TAP_TIME) {
            tapRipple(canvas, tapAge / (float) TAP_TIME);
        }
        canvas.restore();

        String prompt;
        if (visit.conflict) {
            prompt = "另一台已选小窝";
        } else if (!fresh) {
            prompt = "等待 Mac 串门服务";
        } else if (!visit.paired) {
            prompt = "等待另一台打开用量看板";
        } else if (trip != null) {
            prompt = elapsed < 0 && trip.hesitant ? "它听见啦…" : "正在串门…";
        } else if (visit.location.equals("nest")) {
            prompt = "橘猫在打盹";
        } else {
            prompt = "点小窝叫橘猫";
        }
        message(canvas, prompt, getWidth() * PILLOW_X / ROOM_WIDTH, getHeight() - dp(19), dp(14), 0xfff0cba8, false);
        if (trip != null || tapAge < TAP_TIME) {
            postInvalidateOnAnimation();
        } else {
            postInvalidateDelayed(65);
        }
    }

    private void room(Canvas canvas, boolean fromLeft, long now) {
        fill.setStrokeWidth(1);
        fill.setShader(walls);
        canvas.drawRect(0, 0, ROOM_WIDTH, ROOM_HEIGHT, fill);
        fill.setShader(glow);
        canvas.drawRect(0, 0, ROOM_WIDTH, ROOM_HEIGHT, fill);
        fill.setShader(null);
        ink(0xff392726);
        canvas.drawRect(0, 289, 1_000, 460, fill);
        for (int y : FLOOR_LINES) {
            ink(0xff4e332c);
            canvas.drawLine(0, y, ROOM_WIDTH, y, fill);
        }
        for (int offset = -600; offset <= 1_500; offset += 150) {
            ink(0x90543832);
            canvas.drawLine(508 + (offset - 508) * 0.58f, 290, offset, 460, fill);
        }
        canvas.save();
        if (!fromLeft) {
            canvas.translate(ROOM_WIDTH, 0);
            canvas.scale(-1, 1);
        }
        arch(canvas, 33, 140, 179, 325, 0xff634237);
        arch(canvas, 45, 151, 167, 325, 0xff1b171c);
        ink(0xff54382f);
        canvas.drawOval(30, 315, 187, 335, fill);
        canvas.restore();

        ink(0xff392629);
        canvas.drawRoundRect(416, 73, 612, 227, 23, 23, fill);
        ink(0xff694940);
        canvas.drawRoundRect(427, 83, 601, 216, 18, 18, fill);
        ink(0xff313342);
        canvas.drawRoundRect(436, 92, 592, 207, 14, 14, fill);
        ink(0xffe4c2a2);
        canvas.drawCircle(552, 123, 13, fill);
        ink(0xff313342);
        canvas.drawCircle(558, 117, 11, fill);
        for (int index = 0; index < 11; index++) {
            float x = 448 + (index * 31) % 130;
            float y = 105 + (index * 19) % 79;
            ink(Color.argb(95 + (int) ((Math.sin(now / 1_830.0 + index) + 1) * 60), 255, 229, 195));
            canvas.drawCircle(x, y, index % 3 == 0 ? 1.6f : 1.1f, fill);
        }
        ink(0xff694940);
        canvas.drawRect(509, 93, 516, 210, fill);
        canvas.drawRoundRect(426, 211, 602, 222, 4, 4, fill);

        ink(0x6b170f15);
        canvas.drawOval(250, 346, 777, 421, fill);
        ink(0xff724445);
        canvas.drawOval(274, 342, 755, 401, fill);
        ink(0xff98705d);
        canvas.drawOval(299, 347, 729, 395, fill);
        ink(0xff80574f);
        canvas.drawOval(322, 350, 705, 391, fill);

        ink(0xff543333);
        canvas.drawRoundRect(340, 310, 691, 377, 31, 31, fill);
        ink(0xffba766a);
        canvas.drawRoundRect(344, 304, 684, 363, 28, 28, fill);
        ink(0xffd18b77);
        canvas.drawRoundRect(354, 308, 674, 348, 20, 20, fill);
        ink(0xffe9ad91);
        for (int x = 378; x <= 646; x += 17) {
            canvas.drawLine(x, 344, x + 5, 343, fill);
        }
        ink(0xffcc967c);
        canvas.drawCircle(361, 339, 2.5f, fill);
        canvas.drawCircle(668, 339, 2.5f, fill);
    }

    private void arriving(Canvas canvas, boolean fromLeft, CatVisitLink.Trip trip, long elapsed, long now) {
        long entering = elapsed - trip.walk - trip.gap;
        int stepTime = Math.max(1, trip.arrive - SETTLE_MS);
        float progress = clamp(entering / (float) stepTime);
        float x = mix(doorway(fromLeft), PILLOW_X, easeToStop(progress));
        float floor = mix(ENTRANCE_FLOOR, PILLOW_FLOOR, progress);
        float resting = smooth(clamp((entering - stepTime) / (float) SETTLE_MS));
        long gait = Math.min(elapsed, trip.walk + trip.gap + stepTime);
        drawWalk(canvas, x, floor, mix(NEST_CAT_HEIGHT, 151, resting), fromLeft, gait, 1 - resting);
        drawSleeping(canvas, x, floor, fromLeft, now, resting);
    }

    private static float doorway(boolean fromLeft) {
        return fromLeft ? -NEST_CAT_HEIGHT * 0.44f : ROOM_WIDTH + NEST_CAT_HEIGHT * 0.44f;
    }

    private void drawWalk(Canvas canvas, float center, float floor, float height, boolean right, long elapsed, float opacity) {
        if (opacity <= 0) {
            return;
        }
        loadSprites();
        float strides = Math.max(0, elapsed) / 155f;
        int step = (int) strides % 2;
        float bounce = (float) Math.abs(Math.sin(strides * Math.PI)) * height * 0.012f;
        shadow(canvas, center, floor, height * 0.42f, opacity);
        sprite.setAlpha(Math.round(255 * opacity));
        drawSprite(canvas, visitAtlas, visitors[step], center, floor - bounce, height,
            right ? 1 : -1, 0);
        sprite.setAlpha(255);
    }

    private void drawStanding(Canvas canvas, float center, float floor, float height, boolean right, float angle,
                              boolean blink, float opacity) {
        if (opacity <= 0) {
            return;
        }
        loadSprites();
        shadow(canvas, center, floor, height * 0.37f, opacity);
        sprite.setAlpha(Math.round(255 * opacity));
        drawSprite(canvas, standingAtlas, standing[blink ? 1 : 0], center, floor,
            height, right ? 1 : -1, angle);
        sprite.setAlpha(255);
    }

    private void drawSleeping(Canvas canvas, float center, float floor, boolean right, long now, float opacity) {
        if (opacity <= 0) {
            return;
        }
        loadSprites();
        shadow(canvas, center, floor + 1, 205, opacity);
        sprite.setAlpha(Math.round(255 * opacity));
        float breath = (float) Math.sin(now / 680.0) * 2.8f;
        drawSprite(canvas, visitAtlas, visitors[2], center, floor, 158 + breath, right ? 1 : -1, 0);
        sprite.setAlpha(255);
    }

    private void loadSprites() {
        if (visitAtlas != null) {
            return;
        }
        visitAtlas = BitmapFactory.decodeResource(getResources(), R.drawable.cat_visit_frames);
        // Keep the tail and feet inside each pose while trimming the clear gutter.
        visitors = new Rect[]{ new Rect(60, 7, 638, 723), new Rect(789, 6, 1340, 705),
            new Rect(1468, 201, 2113, 651) };
        standingAtlas = BitmapFactory.decodeResource(getResources(), R.drawable.cat_atlas);
        int width = standingAtlas.getWidth() / 2;
        int height = standingAtlas.getHeight();
        standing = new Rect[]{ new Rect(0, 0, width, height), new Rect(width, 0, width * 2, height) };
    }

    private void drawSprite(Canvas canvas, Bitmap source, Rect frame, float x, float floor, float height,
                            float widthScale, float rotation) {
        float half = height * frame.width() / frame.height() * 0.5f;
        canvas.save();
        canvas.rotate(rotation, x, floor);
        canvas.scale(widthScale, 1, x, floor);
        target.set(x - half, floor - height, x + half, floor);
        canvas.drawBitmap(source, frame, target, sprite);
        canvas.restore();
    }

    private void shadow(Canvas canvas, float x, float floor, float width, float opacity) {
        ink(0x4a120b0d);
        fill.setAlpha(Math.round(74 * opacity));
        canvas.drawOval(x - width / 2, floor - width * 0.045f, x + width / 2, floor + width * 0.07f, fill);
    }

    private void tapRipple(Canvas canvas, float progress) {
        ink(Color.argb((int) (155 * (1 - progress)), 255, 204, 151));
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(2.5f);
        float width = 96 + 150 * progress;
        canvas.drawOval(PILLOW_X - width, PILLOW_Y - width * 0.15f, PILLOW_X + width, PILLOW_Y + width * 0.15f, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    private void arch(Canvas canvas, float left, float top, float right, float bottom, int color) {
        outline.reset();
        outline.moveTo(left, bottom);
        outline.lineTo(left, top + 61);
        outline.cubicTo(left, top - 4, right, top - 4, right, top + 61);
        outline.lineTo(right, bottom);
        outline.close();
        ink(color);
        canvas.drawPath(outline, fill);
    }

    private void message(Canvas canvas, String text, float x, float y, float size, int color, boolean bold) {
        ink(color);
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        fill.setTextSize(size);
        canvas.drawText(text, x, y, fill);
    }

    private void ink(int color) {
        fill.setColor(color);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float mix(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float easeToStop(float t) {
        // The cat is already moving when it enters, then slows to a stop beside the cushion.
        return t * (0.7f + t * (1.6f - 1.3f * t));
    }

    private static float clamp(float t) {
        return Math.max(0, Math.min(1, t));
    }
}
