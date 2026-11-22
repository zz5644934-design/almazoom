package app.almazium.samsunglens;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import de.robv.android.xposed.XposedBridge;

/**
 * A "spoiler" laid over one page of the Samsung Gallery viewer: a dim veil with twinkling particles and a soft
 * highlight, drawn by the same AGSL shader as the camera thumbnail. It follows the page it covers.
 */
final class SpoilerDrawable extends Drawable {
    private final View page;
    private final Paint scrim = new Paint();
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable tick = this::invalidateSelf;
    private RuntimeShader shader;
    private boolean running;
    private int lastWidth, lastHeight;

    SpoilerDrawable(View page) {
        this.page = page;
        scrim.setColor(0x38000000);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = new RuntimeShader(ProcessingOverlay.Spoiler.AGSL);
                shader.setFloatUniform("density", page.getResources().getDisplayMetrics().density);
                shade.setShader(shader);
            } catch (RuntimeException | LinkageError error) {
                shader = null;
                XposedBridge.log("AlmaLens gallery spoiler shader unavailable: " + error);
            }
        }
    }

    boolean usesShader() {
        return shader != null;
    }

    void start() {
        running = true;
        page.postOnAnimation(tick);
    }

    void stop() {
        running = false;
        page.removeCallbacks(tick);
    }

    @Override public void draw(Canvas canvas) {
        int w = page.getWidth(), h = page.getHeight();
        if (w <= 0 || h <= 0 || page.getVisibility() != View.VISIBLE) {
            if (running) page.postOnAnimation(tick);
            return;
        }
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w;
            lastHeight = h;
            if (shader != null) shader.setFloatUniform("size", w, h);
        }
        int save = canvas.save();
        canvas.translate(page.getX(), page.getY());
        canvas.clipRect(0, 0, w, h);
        canvas.drawRect(0, 0, w, h, scrim);
        if (shader != null) {
            shader.setFloatUniform("time", (SystemClock.uptimeMillis() % 600_000L) / 1000f);
            canvas.drawRect(0, 0, w, h, shade);
        }
        canvas.restoreToCount(save);
        if (running) page.postOnAnimation(tick);
    }

    @Override public void setAlpha(int alpha) {}

    @Override public void setColorFilter(ColorFilter colorFilter) {}

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
