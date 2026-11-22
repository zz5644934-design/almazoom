package app.almazium.samsunglens;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

final class ZoomGlint {
    private static final float GLINT_HALF_WIDTH = 58f;
    private static final int[] COLORS = {
            0x00b68aff, 0xff9960ff, 0xffff63bd, 0xffffba79,
            0xfffbf2b1, 0xff61eaff, 0x0061eaff
    };

    private final Runnable render = this::step;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix offset = new Matrix();
    private final RectF pill = new RectF();
    private View bar;
    private LinearGradient rainbow;
    private float density, x, goal, trail, opacity;
    private long lastFrame, lastScroll;
    private boolean animating;

    void attach(View view) {
        if (view == bar) return;
        clear();
        bar = view;
        density = view.getResources().getDisplayMetrics().density;
        rainbow = new LinearGradient(-GLINT_HALF_WIDTH * density, 0f,
                GLINT_HALF_WIDTH * density, 0f,
                COLORS, null, Shader.TileMode.CLAMP);
        paint.setShader(rainbow);
        paint.setStyle(Paint.Style.STROKE);
    }

    void duringScroll(int distance) {
        if (bar == null || !bar.isShown() || bar.getWidth() == 0) return;
        float width = bar.getWidth();
        long now = SystemClock.uptimeMillis();
        if (!animating) {
            x = goal = width * 0.5f;
            lastFrame = now;
            animating = true;
            bar.postOnAnimation(render);
        }
        lastScroll = now;
        goal = Math.max(0f, Math.min(width, goal - distance * 0.65f));
    }

    private void step() {
        if (bar == null || !bar.isShown() || bar.getAlpha() < 0.05f) {
            clear();
            return;
        }
        long now = SystemClock.uptimeMillis();
        long dt = Math.min(48, Math.max(1, now - lastFrame));
        lastFrame = now;
        float movement = (goal - x) * (1f - (float) Math.exp(-dt / 44f));
        x += movement;
        float maxTrail = 78f * density;
        float desiredTrail = Math.max(-maxTrail, Math.min(maxTrail, movement / dt * 150f));
        trail += (desiredTrail - trail) * (1f - (float) Math.exp(-dt / 100f));
        long idle = now - lastScroll;
        float targetOpacity = 1f - Math.max(0f, Math.min(1f, (idle - 55f) / 190f));
        opacity += (targetOpacity - opacity) * (1f - (float) Math.exp(-dt / 38f));
        animating = targetOpacity > 0f || opacity > 0.015f;
        if (!animating) {
            opacity = 0f;
            trail = 0f;
        }
        Drawable background = bar.getBackground();
        if (background != null) background.invalidateSelf();
        if (animating) bar.postOnAnimation(render);
    }

    void draw(Canvas canvas, Rect bounds) {
        if (bar == null || opacity <= 0f) return;
        float inset = 2.5f * density;
        if (bounds.width() <= 2f * inset || bounds.height() <= 2f * inset) return;
        pill.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        float radius = pill.height() * 0.5f;
        float scale = 1f + Math.abs(trail) / (2f * GLINT_HALF_WIDTH * density);
        offset.setScale(scale, 1f);
        offset.postTranslate(bounds.left + x - trail * 0.5f, 0f);
        rainbow.setLocalMatrix(offset);
        paint.setStrokeWidth(5f * density);
        paint.setAlpha(Math.round(88f * opacity));
        canvas.drawRoundRect(pill, radius, radius, paint);
        paint.setStrokeWidth(2.2f * density);
        paint.setAlpha(Math.round(250f * opacity));
        canvas.drawRoundRect(pill, radius, radius, paint);
    }

    void clear() {
        if (bar != null) {
            bar.removeCallbacks(render);
            bar = null;
        }
        animating = false;
        opacity = 0f;
        trail = 0f;
    }
}
