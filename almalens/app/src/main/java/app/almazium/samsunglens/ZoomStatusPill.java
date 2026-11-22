package app.almazium.samsunglens;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

final class ZoomStatusPill {
    private final FrameLayout decor;
    private final FrameLayout pill;
    private final ImageView backdrop;
    private final TextView label;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final Runnable fade = () -> fadeOut();
    private int generation, centerX, bottomY;

    ZoomStatusPill(FrameLayout decor) {
        this.decor = decor;
        density = decor.getResources().getDisplayMetrics().density;
        pill = new FrameLayout(decor.getContext());
        GradientDrawable shell = new GradientDrawable();
        shell.setColor(0x7a26252b);
        shell.setCornerRadius(dp(18));
        pill.setBackground(shell);
        pill.setClipToOutline(true);
        pill.setVisibility(View.GONE);
        pill.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> position());

        backdrop = new ImageView(decor.getContext());
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        if (Build.VERSION.SDK_INT >= 31) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(8), dp(8), Shader.TileMode.CLAMP));
        }
        pill.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        label = new TextView(decor.getContext());
        label.setGravity(Gravity.CENTER);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setSingleLine(true);
        label.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(0x9c242329);
        tint.setCornerRadius(dp(18));
        tint.setStroke(Math.max(1, dp(1)), 0x26ffffff);
        label.setBackground(tint);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pill.addView(label, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));
        decor.addView(pill, new FrameLayout.LayoutParams(-2, dp(34), Gravity.TOP | Gravity.LEFT));
    }

    void show(boolean enabled, Rect bar, View preview) {
        int request = ++generation;
        handler.removeCallbacks(fade);
        backdrop.setImageDrawable(null);
        label.setText(enabled ? Ui.text("1000× включён", "1000× on") : Ui.text("1000× выключен", "1000× off"));
        centerX = bar.centerX();
        bottomY = bar.top - dp(8);
        pill.animate().cancel();
        pill.setAlpha(0f);
        pill.setVisibility(View.VISIBLE);
        position();
        pill.animate().alpha(1f).setDuration(100).start();
        if (preview != null && Build.VERSION.SDK_INT >= 31) {
            pill.postOnAnimation(() -> copyBackdrop(preview, request));
        }
        handler.postDelayed(fade, 1350);
    }

    private void position() {
        pill.setTranslationX(centerX - pill.getWidth() / 2f - pill.getLeft());
        pill.setTranslationY(bottomY - pill.getHeight() - pill.getTop());
    }

    private void copyBackdrop(View preview, int request) {
        if (request != generation || !pill.isShown() || !preview.isShown()
                || pill.getWidth() == 0 || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        try {
            Matrix map = new Matrix();
            pill.transformMatrixToGlobal(map);
            preview.transformMatrixToLocal(map);
            RectF sample = new RectF(0f, 0f, pill.getWidth(), pill.getHeight());
            map.mapRect(sample);
            if (sample.left < 0 || sample.top < 0
                    || sample.right > preview.getWidth() || sample.bottom > preview.getHeight()) return;
            if (preview instanceof SurfaceView surface) {
                Rect frame = surface.getHolder().getSurfaceFrame();
                if (frame.isEmpty() || !surface.getHolder().getSurface().isValid()) return;
                Rect crop = scaledCrop(sample, frame.width(), frame.height(), preview);
                if (crop.isEmpty()) return;
                Bitmap pixels = Bitmap.createBitmap(Math.max(2, pill.getWidth() / 2),
                        Math.max(2, pill.getHeight() / 2), Bitmap.Config.ARGB_8888);
                PixelCopy.request(surface, crop, pixels, result -> {
                    if (result == PixelCopy.SUCCESS && request == generation) backdrop.setImageBitmap(pixels);
                }, handler);
            } else if (preview instanceof TextureView texture && texture.isAvailable()) {
                float scale = Math.min(1f, Math.min(384f / texture.getWidth(), 640f / texture.getHeight()));
                Bitmap frame = texture.getBitmap(Math.max(1, (int) (texture.getWidth() * scale)),
                        Math.max(1, (int) (texture.getHeight() * scale)));
                if (frame == null) return;
                Rect crop = scaledCrop(sample, frame.getWidth(), frame.getHeight(), texture);
                if (!crop.isEmpty()) backdrop.setImageBitmap(
                        Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height()));
            }
        } catch (RuntimeException unavailable) {
            // The translucent plate is also the fallback when a preview cannot be sampled.
        }
    }

    private static Rect scaledCrop(RectF rect, int width, int height, View preview) {
        float sx = width / (float) preview.getWidth();
        float sy = height / (float) preview.getHeight();
        return new Rect(Math.max(0, (int) (rect.left * sx)),
                Math.max(0, (int) (rect.top * sy)),
                Math.min(width, (int) Math.ceil(rect.right * sx)),
                Math.min(height, (int) Math.ceil(rect.bottom * sy)));
    }

    private void fadeOut() {
        int request = generation;
        pill.animate().alpha(0f).setDuration(170).withEndAction(() -> {
            if (request == generation) hide();
        }).start();
    }

    void hide() {
        generation++;
        handler.removeCallbacks(fade);
        pill.animate().cancel();
        pill.setVisibility(View.GONE);
        backdrop.setImageDrawable(null);
    }

    void remove() {
        hide();
        decor.removeView(pill);
    }

    private int dp(int size) { return Math.round(size * density); }
}
