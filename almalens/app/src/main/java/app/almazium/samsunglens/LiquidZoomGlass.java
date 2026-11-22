package app.almazium.samsunglens;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

@TargetApi(33)
final class LiquidZoomGlass {
    private final Handler handler;
    private final ZoomGlint glint;
    private final Runnable capture = this::capture;
    private final Bitmap[] buffers = new Bitmap[3];
    private final Matrix mapping = new Matrix();
    private final Matrix inverse = new Matrix();
    private final RectF covered = new RectF();
    private final RectF source = new RectF();
    private final RectF clipped = new RectF();
    private final float[] transform = new float[9];
    private final RuntimeShader shader;
    private View bar, preview;
    private GlassBackground background;
    private int generation, nextBuffer, failures;
    private boolean inFlight;

    LiquidZoomGlass(Handler handler, ZoomGlint glint) {
        this.handler = handler;
        this.glint = glint;
        RuntimeShader compiled;
        try {
            compiled = new RuntimeShader(GLASS);
        } catch (RuntimeException error) {
            compiled = null;
        }
        shader = compiled;
    }

    void show(View target, View cameraPreview) {
        if (target != bar || cameraPreview != preview) {
            stop();
            bar = target;
            preview = cameraPreview;
            if (preview != null && shader != null) handler.post(capture);
        }
        ensureBackground();
    }

    void stop() {
        handler.removeCallbacks(capture);
        generation++;
        if (bar != null && background != null && bar.getBackground() == background) {
            bar.setBackground(background.original);
        }
        background = null;
        glint.clear();
        bar = null;
        preview = null;
        failures = 0;
    }

    private void capture() {
        if (bar == null || preview == null) return;
        if (!bar.isShown() || !preview.isShown()) {
            stop();
            return;
        }
        boolean texture = preview instanceof TextureView;
        handler.postDelayed(capture, texture ? 120 : 58);
        if (inFlight) return;
        if (!mapToPreview()) { failed(); return; }

        try {
            if (preview instanceof SurfaceView surface) captureSurface(surface);
            else if (preview instanceof TextureView view) captureTexture(view);
        } catch (RuntimeException error) {
            failed();
        }
    }

    private boolean mapToPreview() {
        int width = bar.getWidth(), height = bar.getHeight();
        int cameraWidth = preview.getWidth(), cameraHeight = preview.getHeight();
        if (width < 4 || height < 4 || cameraWidth < 4 || cameraHeight < 4) return false;
        mapping.reset();
        bar.transformMatrixToGlobal(mapping);
        preview.transformMatrixToLocal(mapping);
        mapping.getValues(transform);
        if (transform[Matrix.MSCALE_X] <= 0f || transform[Matrix.MSCALE_Y] <= 0f
                || Math.abs(transform[Matrix.MSKEW_X]) > 0.001f
                || Math.abs(transform[Matrix.MSKEW_Y]) > 0.001f || !mapping.invert(inverse)) return false;

        covered.set(0, 0, width, height);
        mapping.mapRect(covered);
        clipped.set(covered);
        if (!clipped.intersect(0, 0, cameraWidth, cameraHeight)
                || clipped.width() * clipped.height() < covered.width() * covered.height() * 0.75f) {
            if (background != null) background.clear();
            return false;
        }
        return true;
    }

    private void captureSurface(SurfaceView view) {
        if (!view.getHolder().getSurface().isValid()) { failed(); return; }
        Rect frame = view.getHolder().getSurfaceFrame();
        if (frame.isEmpty()) { failed(); return; }

        float margin = bar.getResources().getDisplayMetrics().density * 42f;
        source.set(-margin, -margin, bar.getWidth() + margin, bar.getHeight() + margin);
        mapping.mapRect(source);
        if (!source.intersect(0, 0, view.getWidth(), view.getHeight())) return;
        float sx = frame.width() / (float) view.getWidth();
        float sy = frame.height() / (float) view.getHeight();
        Rect region = new Rect(
                Math.max(0, (int) Math.floor(source.left * sx)),
                Math.max(0, (int) Math.floor(source.top * sy)),
                Math.min(frame.width(), (int) Math.ceil(source.right * sx)),
                Math.min(frame.height(), (int) Math.ceil(source.bottom * sy)));
        if (region.isEmpty()) return;
        RectF destination = new RectF(region.left / sx, region.top / sy, region.right / sx, region.bottom / sy);
        inverse.mapRect(destination);
        float scale = Math.min(0.85f, Math.min(1024f / destination.width(), 352f / destination.height()));
        int width = Math.max(2, Math.round(destination.width() * scale));
        int height = Math.max(2, Math.round(destination.height() * scale));
        Bitmap pixels = drawBuffer(width, height);
        int ticket = generation;
        inFlight = true;
        try {
            PixelCopy.request(view, region, pixels, result -> {
                inFlight = false;
                if (ticket != generation || bar == null) return;
                if (result == PixelCopy.SUCCESS) publish(pixels, destination);
                else failed();
            }, handler);
        } catch (RuntimeException error) {
            inFlight = false;
            throw error;
        }
    }

    private void captureTexture(TextureView view) {
        if (!view.isAvailable()) { failed(); return; }
        float scale = Math.min(1f, Math.min(672f / view.getWidth(), 1120f / view.getHeight()));
        Bitmap pixels = drawBuffer(Math.max(2, Math.round(view.getWidth() * scale)),
                Math.max(2, Math.round(view.getHeight() * scale)));
        if (view.getBitmap(pixels) == null) { failed(); return; }
        RectF destination = new RectF(0, 0, view.getWidth(), view.getHeight());
        inverse.mapRect(destination);
        publish(pixels, destination);
    }

    private Bitmap drawBuffer(int width, int height) {
        int i = nextBuffer;
        nextBuffer = (nextBuffer + 1) % buffers.length;
        Bitmap slot = buffers[i];
        if (slot == null || slot.getWidth() != width || slot.getHeight() != height) {
            slot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            buffers[i] = slot;
        }
        return slot;
    }

    private void publish(Bitmap pixels, RectF destination) {
        if (!bar.isShown()) return;
        failures = 0;
        try {
            ensureBackground();
            background.publish(pixels, destination);
        } catch (RuntimeException error) {
            if (background != null) background.clear();
            handler.removeCallbacks(capture);
        }
    }

    private void ensureBackground() {
        glint.attach(bar);
        Drawable present = bar.getBackground();
        if (background == null || present != background) {
            background = new GlassBackground(present, shader, glint,
                    bar.getResources().getDisplayMetrics().density);
            bar.setBackground(background);
        }
    }

    private void failed() {
        if (++failures >= 3 && background != null) background.clear();
    }

    private static final class GlassBackground extends Drawable {
        final Drawable original;
        private final RuntimeShader shader;
        private final ZoomGlint glint;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix map = new Matrix();
        private final RectF bitmapBounds = new RectF();
        private final Path clip = new Path();
        private final RenderNode frosted = new RenderNode("Zoom glass");
        private final int padding;
        private boolean hasFrame, contentDirty;

        GlassBackground(Drawable original, RuntimeShader shader, ZoomGlint glint, float density) {
            this.original = original;
            this.shader = shader;
            this.glint = glint;
            padding = Math.round(32f * density);
            paint.setShader(shader);
            float blur = 13f * density;
            frosted.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
            if (shader != null) shader.setFloatUniform("density", density);
        }

        void publish(Bitmap pixels, RectF destination) {
            bitmapBounds.set(0, 0, pixels.getWidth(), pixels.getHeight());
            map.setRectToRect(bitmapBounds, destination, Matrix.ScaleToFit.FILL);
            BitmapShader scene = new BitmapShader(pixels, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            scene.setLocalMatrix(map);
            scene.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("scene", scene);
            hasFrame = true;
            contentDirty = true;
            invalidateSelf();
        }

        void clear() {
            if (hasFrame) {
                hasFrame = false;
                invalidateSelf();
            }
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (!hasFrame || b.isEmpty() || !canvas.isHardwareAccelerated()) {
                if (original != null) original.draw(canvas);
            } else {
                if (contentDirty) recordGlass(b.width(), b.height());
                int save = canvas.save();
                try {
                    canvas.clipPath(clip);
                    canvas.drawRenderNode(frosted);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            glint.draw(canvas, b);
        }

        private void recordGlass(int width, int height) {
            Canvas recording = frosted.beginRecording(width + 2 * padding, height + 2 * padding);
            try {
                recording.translate(padding, padding);
                recording.drawRect(-padding, -padding, width + padding, height + padding, paint);
            } finally {
                frosted.endRecording();
            }
            contentDirty = false;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            if (original != null) original.setBounds(bounds);
            if (shader != null) shader.setFloatUniform("size", bounds.width(), bounds.height());
            float radius = Math.min(bounds.width(), bounds.height()) * 0.5f;
            clip.rewind();
            clip.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, radius, radius, Path.Direction.CW);
            frosted.setPosition(bounds.left - padding, bounds.top - padding,
                    bounds.right + padding, bounds.bottom + padding);
            contentDirty = true;
        }

        @Override public boolean getPadding(Rect padding) {
            return original != null && original.getPadding(padding);
        }

        @Override public int getMinimumWidth() { return original == null ? 0 : original.getMinimumWidth(); }
        @Override public int getMinimumHeight() { return original == null ? 0 : original.getMinimumHeight(); }

        @Override public void getOutline(Outline outline) {
            if (original != null) original.getOutline(outline);
            else super.getOutline(outline);
        }

        @Override public void setAlpha(int alpha) { frosted.setAlpha(alpha / 255f); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter color) {
            paint.setColorFilter(color);
            contentDirty = true;
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final String GLASS = """
            uniform shader scene;
            uniform float2 size;
            uniform float density;

            half4 main(float2 p) {
                float radius = min(size.x, size.y) * 0.5;
                float2 core = max(size * 0.5 - float2(radius), float2(0.0));
                float2 q = p - size * 0.5;
                float2 direction = q - clamp(q, -core, core);
                float edgeDist = radius - length(direction);
                float2 normal = direction / max(length(direction), 0.001);
                float lip = 1.0 - smoothstep(0.0, 11.0 * density, edgeDist);
                float2 bent = p - normal * (8.0 * lip * lip) * density;
                half3 color = scene.eval(bent).rgb;

                float highlight = max(0.0, dot(normal, float2(-0.43, -0.90)));
                float reflection = exp(-abs(edgeDist) / (4.0 * density))
                        * (0.07 + highlight * 0.26);
                float height = clamp(p.y / size.y, 0.0, 1.0);
                float softLight = (1.0 - smoothstep(0.0, 0.75, height)) * 0.042;
                half luminance = dot(color, half3(0.2126, 0.7152, 0.0722));
                color = mix(half3(luminance), color, 0.69);
                color = color * 0.31 + half3(0.10, 0.111, 0.132) + softLight;
                color = mix(color, half3(0.92, 0.95, 1.0), reflection);
                return half4(color, 1.0);
            }
            """;
}
