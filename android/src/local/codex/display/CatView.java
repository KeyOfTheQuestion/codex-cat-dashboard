package local.codex.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.concurrent.ThreadLocalRandom;

final class CatView extends View {
    private static final long CHEER_TIME = 1_450;
    private static final long BLINK_INTERVAL = 6_000;
    private static final long MIN_IDLE_GAP = 45_000;
    private static final long MAX_IDLE_GAP = 75_000;
    private static final long POSE_FADE = 155;
    private static final long YAWN_GAP = 200;

    private final Bitmap cheer;
    private final ColorFilter cheerColor;
    private final Sequence drinking;
    private final Sequence stretching;
    private final Sequence yawning;
    private final Sequence waving;
    private final Sequence blinking;
    private final Sequence[] idleActions;
    private final RectF destination = new RectF();
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ReminderPlan.Kind reminder;
    private long reminderStartedAt;
    private long reminderUntil;
    private long cheerStartedAt = -10_000;
    private long nextIdleAt;
    private long nextBlinkAt;
    private long blinkStartedAt = -BLINK_INTERVAL;
    private long idleStartedAt;
    private int playingIdle = -1;
    private int previousIdle = -1;

    CatView(Context context) {
        super(context);
        Bitmap actions = BitmapFactory.decodeResource(getResources(), R.drawable.cat_actions);
        cheer = Bitmap.createBitmap(actions, actions.getWidth() / 2, actions.getHeight() / 2,
            actions.getWidth() / 2, actions.getHeight() / 2);
        cheerColor = spriteColor(R.drawable.cat_actions);
        drinking = new Sequence(loadFrames(R.drawable.cat_drink_frames,
            new int[]{270, 254, 240, 271, 254, 240}, new int[]{487, 487, 487, 487, 487, 487}, 3),
            540, 1_180, 660);
        stretching = new Sequence(loadFrames(R.drawable.cat_stretch_frames,
            new int[]{255, 255, 255, 255, 255, 256}, new int[]{481, 481, 481, 480, 480, 480}, 3),
            660, 1_230, 770);
        Frame[] wavePoses = loadFrames(R.drawable.cat_wave_frames,
            new int[]{257, 257, 258, 257, 257, 258}, new int[]{480, 480, 480, 480, 480, 479}, 3);
        Frame[] waveInBetweens = loadFrames(R.drawable.cat_wave_inbetweens,
            new int[]{258, 257, 258, 257, 257}, new int[]{480, 480, 480, 480, 480}, 3);
        Frame[] waveFrames = new Frame[wavePoses.length + waveInBetweens.length];
        for (int index = 0; index < waveInBetweens.length; index++) {
            waveFrames[index * 2] = wavePoses[index];
            waveFrames[index * 2 + 1] = waveInBetweens[index];
        }
        waveFrames[waveFrames.length - 1] = wavePoses[wavePoses.length - 1];
        waving = new Sequence(waveFrames, 690, 245, 630);
        blinking = new Sequence(loadFrames(R.drawable.cat_blink_frames,
            new int[]{257, 257, 257, 257, 257, 257}, new int[]{480, 479, 479, 479, 479, 480}, 3),
            150, 110, 190);
        yawning = new Sequence(loadFrames(R.drawable.cat_yawn_frames,
            new int[]{260, 260, 260, 260, 260, 260}, new int[]{483, 483, 483, 483, 483, 483}, 3),
            680, 540, 690);
        Sequence flashing = new Sequence(loadFrames(R.drawable.cat_flash_frames,
            new int[]{221, 213, 196, 217, 233, 224, 221, 223},
            new int[]{417, 417, 418, 417, 417, 418, 418, 417}, 4),
            new int[]{130, 270, 365, 545, 660, 860, 1_035, 1_260},
            new float[]{0, -12, 22, 66, 32, -51, -17, 0});
        // Use the standing floor for all frames so the preparation hop retains its height.
        Sequence kicking = new Sequence(loadFrames(R.drawable.cat_kick_frames,
            new int[]{225, 222, 221, 216, 219, 222, 225, 227},
            new int[]{408, 408, 408, 408, 408, 408, 408, 408}, 4),
            new int[]{140, 315, 465, 605, 790, 935, 1_100, 1_360}, null);
        Frame[] shake = loadFrames(R.drawable.cat_shake_frames,
            new int[]{224, 224, 223, 225, 224, 226, 226, 226},
            new int[]{419, 416, 416, 416, 417, 419, 420, 419}, 4);
        Sequence shaking = new Sequence(
            new Frame[]{shake[0], shake[1], shake[2], shake[3], shake[4], shake[5],
                shake[2], shake[3], shake[4], shake[5], shake[6], shake[7]},
            new int[]{150, 335, 445, 560, 670, 780, 895, 1_010, 1_130, 1_250, 1_460, 1_720}, null);
        idleActions = new Sequence[]{waving, yawning, flashing, kicking, shaking};
        scheduleIdle(SystemClock.uptimeMillis());
        setContentDescription("戴头盔的橘猫，轻触互动");
        setOnClickListener(view -> {
            if (reminder == null) {
                celebrate();
            } else {
                startReminder(SystemClock.uptimeMillis());
            }
        });
    }

    void setReminder(ReminderPlan.Kind kind, boolean newReminder, long playbackLeft) {
        long now = SystemClock.uptimeMillis();
        reminderUntil = now + playbackLeft;
        if (reminder != kind || newReminder) {
            reminder = kind;
            startReminder(now);
        }
    }

    void celebrate() {
        cheerStartedAt = SystemClock.uptimeMillis();
        startReminder(cheerStartedAt + CHEER_TIME);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && reminder != null && SystemClock.uptimeMillis() < reminderUntil) {
            startReminder(Math.max(SystemClock.uptimeMillis(), cheerStartedAt + CHEER_TIME));
        }
    }

    private void startReminder(long start) {
        reminderStartedAt = start;
        scheduleIdle(start);
        invalidate();
    }

    private Sequence reminderSequence() {
        if (reminder == null) {
            return null;
        }
        switch (reminder) {
            case WATER:
                return drinking;
            case STRETCH:
                return stretching;
            case TASK:
                return waving;
            default:
                return null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        if (reminder != null && now >= reminderUntil) {
            reminder = null;
            scheduleIdle(now);
        }
        long sinceCheer = now - cheerStartedAt;
        boolean celebrating = sinceCheer < CHEER_TIME;
        float hop = celebrating ? (float) Math.sin(Math.PI * sinceCheer / CHEER_TIME) : 0;
        float size = Math.min(getWidth(), getHeight() * 0.91f);
        float center = getWidth() / 2f;
        float ground = getHeight() * 0.96f;
        float top = ground - size * 0.965f;
        float breath = (float) Math.sin(now / 840.0) * size * 0.003f;
        float offset = breath - hop * size * 0.035f;
        float shadow = size * (0.28f - hop * 0.03f);

        accentPaint.setColor(0x8015130f);
        canvas.drawOval(center - shadow, ground - size * 0.008f,
            center + shadow, ground + size * 0.024f, accentPaint);
        destination.set(center - size / 2, top + offset, center + size / 2, top + offset + size);

        boolean moving;
        if (celebrating) {
            int layer = startPoseFade(canvas, poseBlend(sinceCheer, CHEER_TIME));
            spritePaint.setColorFilter(cheerColor);
            canvas.drawBitmap(cheer, null, destination, spritePaint);
            endPoseFade(canvas, layer);
            drawStars(canvas, center, top, size, hop);
            moving = true;
        } else if (reminder != null) {
            moving = drawReminder(canvas, now);
        } else {
            moving = drawIdle(canvas, now);
        }

        if (isShown() && getWindowVisibility() == VISIBLE) {
            if (moving) {
                postInvalidateOnAnimation();
            } else {
                postInvalidateDelayed(65);
            }
        }
    }

    private boolean drawReminder(Canvas canvas, long now) {
        Sequence action = reminderSequence();
        boolean withYawn = reminder == ReminderPlan.Kind.STRETCH;
        long yawnAt = action.duration() + YAWN_GAP;
        long duration = withYawn ? yawnAt + yawning.duration() : action.duration();
        long phase = ReminderPlayback.phase(now - reminderStartedAt, duration);
        float timeLeft = smooth((reminderUntil - now) / (float) POSE_FADE);
        if (phase < action.duration()) {
            return drawAction(canvas, action, phase, timeLeft);
        }
        if (withYawn && phase >= yawnAt && phase < duration) {
            return drawAction(canvas, yawning, phase - yawnAt, timeLeft);
        }
        return drawIdle(canvas, now);
    }

    private boolean drawAction(Canvas canvas, Sequence action, long elapsed, float visibility) {
        int layer = startPoseFade(canvas, Math.min(visibility, poseBlend(elapsed, action.duration())));
        boolean moving = action.draw(canvas, destination, spritePaint, elapsed);
        endPoseFade(canvas, layer);
        return moving || layer >= 0;
    }

    private int startPoseFade(Canvas canvas, float weight) {
        if (weight >= 1) {
            return -1;
        }
        int layer = canvas.saveLayer(null, null);
        spritePaint.setAlpha(Math.round(255 * (1 - weight)));
        blinking.drawFrame(canvas, destination, spritePaint, 0);
        spritePaint.setAlpha(Math.round(255 * weight));
        spritePaint.setBlendMode(BlendMode.PLUS);
        return layer;
    }

    private void endPoseFade(Canvas canvas, int layer) {
        if (layer >= 0) {
            canvas.restoreToCount(layer);
            spritePaint.setBlendMode(null);
            spritePaint.setAlpha(255);
        }
    }

    private static float poseBlend(long elapsed, long duration) {
        return smooth(Math.min(elapsed, duration - elapsed) / (float) POSE_FADE);
    }

    private static float smooth(float value) {
        float t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    private void scheduleIdle(long after) {
        playingIdle = -1;
        blinkStartedAt = -BLINK_INTERVAL;
        nextBlinkAt = after + BLINK_INTERVAL;
        nextIdleAt = after + ThreadLocalRandom.current().nextLong(MIN_IDLE_GAP, MAX_IDLE_GAP + 1);
    }

    private boolean drawIdle(Canvas canvas, long now) {
        if (playingIdle >= 0 && now - idleStartedAt >= idleActions[playingIdle].duration()) {
            scheduleIdle(now);
        }
        long blinkElapsed = now - blinkStartedAt + Sequence.PAUSE;
        if (blinkElapsed < blinking.duration()) {
            return blinking.draw(canvas, destination, spritePaint, blinkElapsed);
        }
        if (reminder == null && playingIdle < 0 && now >= nextIdleAt) {
            int choices = previousIdle < 0 ? idleActions.length : idleActions.length - 1;
            int picked = ThreadLocalRandom.current().nextInt(choices);
            if (previousIdle >= 0 && picked >= previousIdle) {
                picked++;
            }
            playingIdle = picked;
            previousIdle = picked;
            idleStartedAt = now;
        }
        if (playingIdle >= 0) {
            return drawAction(canvas, idleActions[playingIdle], now - idleStartedAt, 1);
        }
        if (now >= nextBlinkAt) {
            blinkStartedAt = now;
            nextBlinkAt = now + BLINK_INTERVAL;
            return blinking.draw(canvas, destination, spritePaint, Sequence.PAUSE);
        }
        blinking.drawFrame(canvas, destination, spritePaint, 0);
        return false;
    }

    private Frame[] loadFrames(int resource, int[] centers, int[] feet, int columns) {
        int helmetLift;
        switch (resource) {
            case R.drawable.cat_drink_frames:
                helmetLift = 35;
                break;
            case R.drawable.cat_stretch_frames:
                helmetLift = 23;
                break;
            case R.drawable.cat_yawn_frames:
                helmetLift = 20;
                break;
            case R.drawable.cat_wave_frames:
                helmetLift = 16;
                break;
            case R.drawable.cat_wave_inbetweens:
                helmetLift = 12;
                break;
            default:
                helmetLift = 0;
                break;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = helmetLift > 0;
        Bitmap atlas = BitmapFactory.decodeResource(getResources(), resource, options);
        Frame[] frames = Frame.fromAtlas(atlas, columns, centers, feet, spriteColor(resource));
        if (helmetLift > 0) {
            for (Frame frame : frames) {
                matchHelmet(frame, helmetLift);
            }
        }
        return frames;
    }

    // Lift the gray crown to the neutral pose while leaving the amber fur and deep seams intact.
    private static void matchHelmet(Frame frame, int lift) {
        Rect cell = frame.source;
        int tile = cell.width();
        int center = cell.left + frame.center;
        int floor = cell.top + frame.feet;
        int left = Math.max(cell.left, center - Math.round(tile * 0.23f));
        int right = Math.min(cell.right, center + Math.round(tile * 0.23f));
        int top = Math.max(cell.top, floor - Math.round(tile * 0.95f));
        int bottom = floor - Math.round(tile * 0.56f);
        int[] row = new int[right - left];
        for (int y = top; y < bottom; y++) {
            float height = (floor - y) / (float) tile;
            float crown = smooth((height - 0.56f) / 0.20f);
            frame.atlas.getPixels(row, 0, row.length, left, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                int pixel = row[x];
                int alpha = pixel >>> 24;
                if (alpha < 168) {
                    continue;
                }
                int red = pixel >> 16 & 255;
                int green = pixel >> 8 & 255;
                int blue = pixel & 255;
                int spread = Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
                float gray = 1 - smooth((spread - 8) / 27f);
                float light = smooth(green / 58f) * (1 - smooth((green - 126) / 95f));
                float edge = smooth((alpha - 168) / 72f);
                int bump = Math.round(lift * crown * gray * light * edge);
                row[x] = alpha << 24 | Math.min(255, red + bump) << 16
                    | Math.min(255, green + bump) << 8 | Math.min(255, blue + bump);
            }
            frame.atlas.setPixels(row, 0, row.length, left, y, row.length, 1);
        }
    }

    // Match opaque helmet, muzzle, vest and fur samples to the neutral blink atlas.
    private static ColorFilter spriteColor(int resource) {
        switch (resource) {
            case R.drawable.cat_wave_frames:
                return tone(1.001f, 6.5f, 1.001f, 7.5f, 1.000f, 8.7f);
            case R.drawable.cat_wave_inbetweens:
                return tone(1.015f, 4.8f, 1.008f, 6.4f, 1.006f, 7.6f);
            case R.drawable.cat_yawn_frames:
                return tone(0.995f, 9.9f, 1.001f, 10.3f, 1.013f, 10.4f);
            case R.drawable.cat_stretch_frames:
                return tone(0.993f, 12.1f, 0.989f, 12.2f, 0.996f, 12.1f);
            case R.drawable.cat_drink_frames:
                return tone(0.975f, 12.3f, 0.978f, 11.9f, 0.980f, 11.8f);
            case R.drawable.cat_flash_frames:
                return tone(1.011f, 3.3f, 1.015f, 5.6f, 1.020f, 7.2f);
            case R.drawable.cat_kick_frames:
                return tone(0.984f, 1.9f, 0.984f, 4.6f, 0.993f, 5.9f);
            case R.drawable.cat_shake_frames:
                return tone(1.022f, -0.6f, 1.028f, 1.7f, 1.024f, 5.0f);
            case R.drawable.cat_actions:
                return tone(1.017f, 7.8f, 1.012f, 9.5f, 0.999f, 10.7f);
            default:
                return null;
        }
    }

    private static ColorFilter tone(float red, float redOffset, float green, float greenOffset,
                                    float blue, float blueOffset) {
        return new ColorMatrixColorFilter(new float[]{
            red, 0, 0, 0, redOffset,
            0, green, 0, 0, greenOffset,
            0, 0, blue, 0, blueOffset,
            0, 0, 0, 1, 0
        });
    }

    private void drawStars(Canvas canvas, float center, float top, float size, float hop) {
        accentPaint.setColor(0xffffcc82);
        accentPaint.setAlpha(Math.round(220 * hop));
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setStrokeWidth(Math.max(1, size * 0.005f));
        spark(canvas, center + size * 0.39f, top + size * (0.27f - hop * 0.07f), size * 0.025f);
        spark(canvas, center - size * 0.37f, top + size * (0.36f - hop * 0.07f), size * 0.018f);
    }

    private void spark(Canvas canvas, float x, float y, float radius) {
        canvas.drawLine(x - radius, y, x + radius, y, accentPaint);
        canvas.drawLine(x, y - radius, x, y + radius, accentPaint);
    }

    private static final class Sequence {
        private static final long PAUSE = 360;

        private final Frame[] frames;
        private final RectF aligned = new RectF();
        private final long rising;
        private final long holding;
        private final long falling;
        private final int[] frameEnds;
        private final float[] slide;

        Sequence(Frame[] frames, long rising, long holding, long falling) {
            this.frames = frames;
            this.rising = rising;
            this.holding = holding;
            this.falling = falling;
            frameEnds = null;
            slide = null;
        }

        Sequence(Frame[] frames, int[] frameEnds, float[] slide) {
            this.frames = frames;
            this.frameEnds = frameEnds;
            this.slide = slide;
            rising = 0;
            holding = 0;
            falling = 0;
        }

        boolean draw(Canvas canvas, RectF destination, Paint paint, long elapsed) {
            if (frameEnds != null) {
                int index = 0;
                while (index < frameEnds.length - 1 && elapsed >= frameEnds[index]) {
                    index++;
                }
                float shift = 0;
                if (slide != null) {
                    shift = slide[index];
                    if (index + 1 < slide.length) {
                        long frameStart = index == 0 ? 0 : frameEnds[index - 1];
                        float part = (elapsed - frameStart) / (float) (frameEnds[index] - frameStart);
                        shift += (slide[index + 1] - shift) * ease(part);
                    }
                }
                drawFrame(canvas, destination, paint, index, shift);
                return elapsed < duration();
            }
            long motion = elapsed - PAUSE;
            int last = frames.length - 1;
            int index = 0;
            if (motion >= 0 && motion < rising) {
                index = frameAt(motion, rising);
            } else if (motion >= rising && motion < rising + holding) {
                index = last;
            } else if (motion >= rising + holding && motion < rising + holding + falling) {
                index = last - frameAt(motion - rising - holding, falling);
            }

            drawFrame(canvas, destination, paint, index);
            return motion >= 0 && elapsed < duration();
        }

        long duration() {
            return frameEnds == null ? PAUSE + rising + holding + falling : frameEnds[frameEnds.length - 1];
        }

        void drawFrame(Canvas canvas, RectF destination, Paint paint, int index) {
            drawFrame(canvas, destination, paint, index, 0);
        }

        private void drawFrame(Canvas canvas, RectF destination, Paint paint, int index, float shift) {
            Frame frame = frames[index];
            int tile = frame.source.width();
            float scale = destination.width() / tile;
            aligned.set(destination);
            aligned.offset((tile / 2f - frame.center + shift) * scale, (tile * 0.95f - frame.feet) * scale);
            // Each cell is a complete pose. Register the helmet and feet before drawing it.
            paint.setColorFilter(frame.color);
            canvas.drawBitmap(frame.atlas, frame.source, aligned, paint);
        }

        private int frameAt(long elapsed, long duration) {
            float fraction = elapsed / (float) duration;
            return Math.round((frames.length - 1) * ease(fraction));
        }

        private static float ease(float fraction) {
            return fraction * fraction * (3 - 2 * fraction);
        }
    }

    private static final class Frame {
        final Bitmap atlas;
        final Rect source;
        final int center;
        final int feet;
        final ColorFilter color;

        Frame(Bitmap atlas, Rect source, int center, int feet, ColorFilter color) {
            this.atlas = atlas;
            this.source = source;
            this.center = center;
            this.feet = feet;
            this.color = color;
        }

        static Frame[] fromAtlas(Bitmap atlas, int columns, int[] centers, int[] feet, ColorFilter color) {
            int width = atlas.getWidth() / columns;
            int height = atlas.getHeight() / 2;
            Frame[] frames = new Frame[centers.length];
            for (int index = 0; index < frames.length; index++) {
                int x = index % columns * width;
                int y = index / columns * height;
                frames[index] = new Frame(atlas, new Rect(x, y, x + width, y + height),
                    centers[index], feet[index], color);
            }
            return frames;
        }
    }
}
