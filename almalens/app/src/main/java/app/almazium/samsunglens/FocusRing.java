package app.almazium.samsunglens;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

final class FocusRing extends Drawable {
    private static final int[] PALETTE = {
            0xfff765b5, 0xffffaa75, 0xffffe99d, 0xff9ef2b1,
            0xff72e6ff, 0xff8b9fff, 0xffc08aff, 0xfff765b5
    };
    private static final long REVOLUTION_MS = 1800;

    private final View view;
    private final Drawable original;
    private final Matrix rotate = new Matrix();
    private final SweepGradient rainbow = new SweepGradient(0f, 0f, PALETTE, null);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RenderNode glow;

    FocusRing(View view, Drawable original) {
        this.view = view;
        this.original = original;
        tint.setBlendMode(BlendMode.SRC_ATOP);
        tint.setShader(rainbow);
        glow = Build.VERSION.SDK_INT >= 31 ? createGlow(view) : null;
    }

    private static RenderNode createGlow(View view) {
        float blur = 2.0f * view.getResources().getDisplayMetrics().density;
        RenderNode node = new RenderNode("Focus glow");
        node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL));
        node.setAlpha(0.38f);
        return node;
    }

    void restore() {
        unscheduleSelf(nextFrame);
        if (view.getBackground() == this) view.setBackground(original);
    }

    @Override public void draw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        Rect bounds = getBounds();
        if (bounds.isEmpty() || !view.isShown()) return;

        float x = bounds.exactCenterX(), y = bounds.exactCenterY();
        rotate.setRotate((now % REVOLUTION_MS) * (360f / REVOLUTION_MS));
        rotate.postTranslate(x, y);
        rainbow.setLocalMatrix(rotate);

        if (glow != null && canvas.isHardwareAccelerated()) {
            glow.setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom);
            Canvas glowCanvas = glow.beginRecording(bounds.width(), bounds.height());
            glowCanvas.translate(-bounds.left, -bounds.top);
            drawTinted(glowCanvas, bounds);
            glow.endRecording();
            canvas.drawRenderNode(glow);
        }
        drawTinted(canvas, bounds);
        scheduleSelf(nextFrame, now + 16);
    }

    private void drawTinted(Canvas canvas, Rect bounds) {
        int layer = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);
        original.draw(canvas);
        canvas.drawRect(bounds, tint);
        canvas.restoreToCount(layer);
    }

    private final Runnable nextFrame = this::invalidateSelf;

    @Override protected void onBoundsChange(Rect bounds) { original.setBounds(bounds); }
    @Override protected boolean onStateChange(int[] state) { return original.setState(state); }
    @Override protected boolean onLevelChange(int level) { return original.setLevel(level); }
    @Override public boolean isStateful() { return original.isStateful(); }
    @Override public boolean getPadding(Rect padding) { return original.getPadding(padding); }
    @Override public void getOutline(Outline outline) { original.getOutline(outline); }
    @Override public int getMinimumWidth() { return original.getMinimumWidth(); }
    @Override public int getMinimumHeight() { return original.getMinimumHeight(); }
    @Override public void setAlpha(int alpha) { original.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { original.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
