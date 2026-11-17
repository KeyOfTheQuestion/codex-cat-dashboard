package local.codex.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.concurrent.ThreadLocalRandom;

final class CatView extends View {
    private static final long CHEER_TIME = 1_450;
    private static final long MIN_IDLE_GAP = 45_000;
    private static final long MAX_IDLE_GAP = 75_000;

    private final Bitmap cheer;
    private final Sequence drinking;
    private final Sequence stretching;
    private final Sequence waving;
    private final Sequence blinking;
    private final Sequence[] idleActions;
    private final RectF destination = new RectF();
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ReminderPlan.Kind reminder;
    private long reminderStartedAt;
    private long cheerStartedAt = -10_000;
    private long nextIdleAt;
    private long idleStartedAt;
    private int playingIdle = -1;
    private int previousIdle = -1;

    CatView(Context context) {
        super(context);
        Bitmap actions = BitmapFactory.decodeResource(getResources(), R.drawable.cat_actions);
        cheer = Bitmap.createBitmap(actions, actions.getWidth() / 2, actions.getHeight() / 2,
            actions.getWidth() / 2, actions.getHeight() / 2);
        drinking = new Sequence(BitmapFactory.decodeResource(getResources(), R.drawable.cat_drink_frames),
            new int[]{270, 254, 240, 271, 254, 240}, new int[]{487, 487, 487, 487, 487, 487},
            540, 1_180, 660);
        stretching = new Sequence(BitmapFactory.decodeResource(getResources(), R.drawable.cat_stretch_frames),
            new int[]{255, 255, 255, 255, 255, 256}, new int[]{481, 481, 481, 480, 480, 480},
            660, 1_230, 770);
        Frame[] wavePoses = Frame.fromAtlas(BitmapFactory.decodeResource(getResources(), R.drawable.cat_wave_frames),
            new int[]{257, 257, 258, 257, 257, 258}, new int[]{480, 480, 480, 480, 480, 479});
        Frame[] waveInBetweens = Frame.fromAtlas(
            BitmapFactory.decodeResource(getResources(), R.drawable.cat_wave_inbetweens),
            new int[]{258, 257, 258, 257, 257}, new int[]{480, 480, 480, 480, 480});
        Frame[] waveFrames = new Frame[wavePoses.length + waveInBetweens.length];
        for (int index = 0; index < waveInBetweens.length; index++) {
            waveFrames[index * 2] = wavePoses[index];
            waveFrames[index * 2 + 1] = waveInBetweens[index];
        }
        waveFrames[waveFrames.length - 1] = wavePoses[wavePoses.length - 1];
        waving = new Sequence(waveFrames, 690, 245, 630);
        blinking = new Sequence(BitmapFactory.decodeResource(getResources(), R.drawable.cat_blink_frames),
            new int[]{257, 257, 257, 257, 257, 257}, new int[]{480, 479, 479, 479, 479, 480},
            150, 110, 190);
        Sequence yawning = new Sequence(BitmapFactory.decodeResource(getResources(), R.drawable.cat_yawn_frames),
            new int[]{260, 260, 260, 260, 260, 260}, new int[]{483, 483, 483, 483, 483, 483},
            680, 540, 690);
        Sequence flashing = new Sequence(Frame.fromAtlas(
            BitmapFactory.decodeResource(getResources(), R.drawable.cat_flash_frames), 4,
            new int[]{221, 213, 196, 217, 233, 224, 221, 223},
            new int[]{417, 417, 418, 417, 417, 418, 418, 417}),
            new int[]{130, 270, 365, 545, 660, 860, 1_035, 1_260},
            new float[]{0, -12, 22, 66, 32, -51, -17, 0});
        // Use the standing floor for all frames so the preparation hop retains its height.
        Sequence kicking = new Sequence(Frame.fromAtlas(
            BitmapFactory.decodeResource(getResources(), R.drawable.cat_kick_frames), 4,
            new int[]{225, 222, 221, 216, 219, 222, 225, 227},
            new int[]{408, 408, 408, 408, 408, 408, 408, 408}),
            new int[]{140, 315, 465, 605, 790, 935, 1_100, 1_360}, null);
        Frame[] shake = Frame.fromAtlas(BitmapFactory.decodeResource(getResources(), R.drawable.cat_shake_frames),
            4, new int[]{224, 224, 223, 225, 224, 226, 226, 226},
            new int[]{419, 416, 416, 416, 417, 419, 420, 419});
        Sequence shaking = new Sequence(
            new Frame[]{shake[0], shake[1], shake[2], shake[3], shake[4], shake[5],
                shake[2], shake[3], shake[4], shake[5], shake[6], shake[7]},
            new int[]{150, 335, 445, 560, 670, 780, 895, 1_010, 1_130, 1_250, 1_460, 1_720}, null);
        idleActions = new Sequence[]{waving, blinking, yawning, flashing, kicking, shaking};
        scheduleIdle(SystemClock.uptimeMillis());
        setContentDescription("戴头盔的橘猫，轻触互动");
        setOnClickListener(view -> {
            if (reminder == null) {
                celebrate();
            } else {
                reminderStartedAt = SystemClock.uptimeMillis();
                invalidate();
            }
        });
    }

    void setReminder(ReminderPlan.Kind kind, boolean newReminder) {
        if (reminder != kind || newReminder) {
            reminder = kind;
            reminderStartedAt = SystemClock.uptimeMillis();
            scheduleIdle(reminderStartedAt);
            invalidate();
        }
    }

    void celebrate() {
        cheerStartedAt = SystemClock.uptimeMillis();
        reminderStartedAt = cheerStartedAt + CHEER_TIME;
        scheduleIdle(reminderStartedAt);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        long sinceCheer = now - cheerStartedAt;
        long sinceReminder = Math.max(0, now - reminderStartedAt);
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

        boolean moving = celebrating;
        if (celebrating) {
            canvas.drawBitmap(cheer, null, destination, spritePaint);
            drawStars(canvas, center, top, size, hop);
        } else if (reminder == ReminderPlan.Kind.WATER || reminder == ReminderPlan.Kind.STRETCH) {
            Sequence sequence = reminder == ReminderPlan.Kind.WATER ? drinking : stretching;
            moving = sequence.draw(canvas, destination, spritePaint, sinceReminder);
        } else if (reminder == ReminderPlan.Kind.TASK) {
            moving = waving.draw(canvas, destination, spritePaint, sinceReminder);
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

    private void scheduleIdle(long after) {
        playingIdle = -1;
        nextIdleAt = after + ThreadLocalRandom.current().nextLong(MIN_IDLE_GAP, MAX_IDLE_GAP + 1);
    }

    private boolean drawIdle(Canvas canvas, long now) {
        if (playingIdle >= 0 && now - idleStartedAt >= idleActions[playingIdle].duration()) {
            scheduleIdle(now);
        }
        if (playingIdle < 0 && now >= nextIdleAt) {
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
            return idleActions[playingIdle].draw(canvas, destination, spritePaint, now - idleStartedAt);
        }
        blinking.drawFrame(canvas, destination, spritePaint, 0);
        return false;
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

        Sequence(Bitmap atlas, int[] centers, int[] feet, long rising, long holding, long falling) {
            this(Frame.fromAtlas(atlas, centers, feet), rising, holding, falling);
        }

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

        Frame(Bitmap atlas, Rect source, int center, int feet) {
            this.atlas = atlas;
            this.source = source;
            this.center = center;
            this.feet = feet;
        }

        static Frame[] fromAtlas(Bitmap atlas, int[] centers, int[] feet) {
            return fromAtlas(atlas, 3, centers, feet);
        }

        static Frame[] fromAtlas(Bitmap atlas, int columns, int[] centers, int[] feet) {
            int width = atlas.getWidth() / columns;
            int height = atlas.getHeight() / 2;
            Frame[] frames = new Frame[centers.length];
            for (int index = 0; index < frames.length; index++) {
                int x = index % columns * width;
                int y = index / columns * height;
                frames[index] = new Frame(atlas, new Rect(x, y, x + width, y + height),
                    centers[index], feet[index]);
            }
            return frames;
        }
    }
}
