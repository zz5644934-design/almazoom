package app.almazium.samsunglens;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Random;

import de.robv.android.xposed.XposedBridge;

/**
 * While a zoomed photo is still being processed, the round gallery thumbnail of the camera is blurred and covered
 * with a "spoiler": tiny particles that drift and twinkle, with a soft highlight sweeping across (an AGSL shader).
 * It disappears when the final photo is ready.
 */
final class ProcessingOverlay {
    private static final long TIMEOUT_MS = 25_000;
    private static final String[] TARGET_IDS = {"gallery_button", "gallery_icon", "gallery_layout"};

    private final Activity activity;
    private final FrameLayout decor;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Spoiler spoiler;
    private final Runnable timeout = this::hide;
    private View target;
    private boolean blurred;
    private boolean loggedCandidates;

    ProcessingOverlay(Activity activity) {
        this.activity = activity;
        decor = (FrameLayout) activity.getWindow().getDecorView();
        density = activity.getResources().getDisplayMetrics().density;
        spoiler = new Spoiler(activity, density);
        spoiler.setVisibility(View.GONE);
        spoiler.setClickable(false);
        spoiler.setFocusable(false);
        decor.addView(spoiler, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.LEFT));
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private View findTarget() {
        for (String name : TARGET_IDS) {
            int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            if (id == 0) continue;
            View view = decor.findViewById(id);
            if (view != null && view.isShown() && view.getWidth() >= dp(24)) return view;
        }
        return null;
    }

    void show() {
        View view = findTarget();
        Rect bounds = new Rect();
        if (view == null || !view.getGlobalVisibleRect(bounds) || bounds.isEmpty()) {
            XposedBridge.log("AlmaLens spoiler: gallery thumbnail not found or not visible");
            logCandidates();
            return;
        }
        restoreTarget();
        target = view;
        if (Build.VERSION.SDK_INT >= 31) {
            float radius = dp(4);
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            blurred = true;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) spoiler.getLayoutParams();
        if (params.width != bounds.width() || params.height != bounds.height()) {
            params.width = bounds.width();
            params.height = bounds.height();
            spoiler.setLayoutParams(params);
        }
        spoiler.setTranslationX(bounds.left - spoiler.getLeft());
        spoiler.setTranslationY(bounds.top - spoiler.getTop());
        spoiler.setVisibility(View.VISIBLE);
        spoiler.bringToFront();
        spoiler.start();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, TIMEOUT_MS);
        XposedBridge.log("AlmaLens spoiler shown on " + bounds.width() + "x" + bounds.height()
                + " shader=" + spoiler.usesShader());
    }

    /** Once per session: which views of the camera screen could be the round thumbnail. */
    private void logCandidates() {
        if (loggedCandidates) return;
        loggedCandidates = true;
        StringBuilder text = new StringBuilder("AlmaLens spoiler candidates:");
        collect(decor, text, new int[1]);
        XposedBridge.log(text.toString());
    }

    private void collect(View view, StringBuilder text, int[] count) {
        if (count[0] >= 30) return;
        String name = "";
        try {
            if (view.getId() != View.NO_ID) name = activity.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException ignored) {
        }
        if (name.matches("(?i).*(gallery|thumb|quick|review|recent|last|snapshot).*")) {
            count[0]++;
            text.append("\n  ").append(name).append(' ').append(view.getClass().getSimpleName()).append(' ')
                    .append(view.getWidth()).append('x').append(view.getHeight())
                    .append(" shown=").append(view.isShown());
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), text, count);
        }
    }

    void hide() {
        handler.removeCallbacks(timeout);
        spoiler.stop();
        spoiler.setVisibility(View.GONE);
        restoreTarget();
    }

    void remove() {
        hide();
        decor.removeView(spoiler);
    }

    private void restoreTarget() {
        if (target != null && blurred && Build.VERSION.SDK_INT >= 31) target.setRenderEffect(null);
        blurred = false;
        target = null;
    }

    static final class Spoiler extends View {
        /** Particles on a jittered grid, each one drifting and twinkling; a faint highlight sweeps over them. */
        static final String AGSL = """
                uniform float2 size;
                uniform float time;
                uniform float density;

                float hash(float2 p) {
                    float3 p3 = fract(float3(p.xyx) * 0.1031);
                    p3 += dot(p3, p3.yzx + 33.33);
                    return fract((p3.x + p3.y) * p3.z);
                }

                half4 main(float2 fc) {
                    float cell = 7.0 * density;
                    float2 g = fc / cell;
                    float2 id = floor(g);
                    float2 f = g - id;
                    float acc = 0.0;
                    for (int j = -1; j <= 1; j++) {
                        for (int i = -1; i <= 1; i++) {
                            float2 o = float2(float(i), float(j));
                            float2 cid = id + o;
                            float h1 = hash(cid);
                            float h2 = hash(cid + 17.31);
                            float h3 = hash(cid + 91.73);
                            if (h1 > 0.40) {
                                float2 drift = 0.38 * float2(sin(time * (0.5 + h1) + h2 * 6.2831),
                                                             cos(time * (0.45 + h2) + h3 * 6.2831));
                                float2 pos = o + float2(h2, h3) + drift - f;
                                float d = length(pos) * cell;
                                float r = (0.55 + 0.95 * h3) * density;
                                float tw = 0.5 + 0.5 * sin(time * (2.0 + 5.0 * h1) + h2 * 6.2831);
                                acc += (1.0 - smoothstep(r * 0.25, r, d)) * (0.20 + 0.80 * tw);
                            }
                        }
                    }
                    float2 uv = fc / size;
                    float sweep = 0.5 + 0.5 * sin(uv.x * 3.2 - uv.y * 2.1 - time * 1.1);
                    half3 tint = mix(half3(0.74, 0.84, 1.0), half3(1.0, 0.90, 0.97), half(sweep));
                    float a = clamp(acc * (0.75 + 0.45 * sweep), 0.0, 1.0);
                    return half4(tint * half(a), half(a));
                }
                """;

        private static final int COUNT = 34;
        private final float density;
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private RuntimeShader shader;
        private final float[] x = new float[COUNT], y = new float[COUNT], vx = new float[COUNT], vy = new float[COUNT];
        private final float[] size = new float[COUNT], phase = new float[COUNT], speed = new float[COUNT];
        private long last;
        private boolean running;
        private final Runnable tick = this::invalidate;

        Spoiler(Activity activity, float density) {
            super(activity);
            this.density = density;
            scrim.setColor(0x5a000000);
            dot.setColor(0xffffffff);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, density));
            rim.setColor(0x33ffffff);
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    shader = new RuntimeShader(AGSL);
                    shader.setFloatUniform("density", density);
                    shade.setShader(shader);
                } catch (RuntimeException | LinkageError error) {
                    shader = null;
                    XposedBridge.log("AlmaLens spoiler shader unavailable, using plain dots: " + error);
                }
            }
            Random random = new Random(7);
            for (int i = 0; i < COUNT; i++) {
                x[i] = random.nextFloat();
                y[i] = random.nextFloat();
                float angle = random.nextFloat() * 6.2831855f;
                float velocity = 0.04f + random.nextFloat() * 0.10f;
                vx[i] = (float) Math.cos(angle) * velocity;
                vy[i] = (float) Math.sin(angle) * velocity;
                size[i] = 0.8f + random.nextFloat() * 1.0f;
                phase[i] = random.nextFloat() * 6.2831855f;
                speed[i] = 2.0f + random.nextFloat() * 3.0f;
            }
        }

        boolean usesShader() {
            return shader != null;
        }

        void start() {
            running = true;
            last = SystemClock.uptimeMillis();
            postOnAnimation(tick);
        }

        void stop() {
            running = false;
            removeCallbacks(tick);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            clip.rewind();
            float radius = Math.min(w, h) / 2f;
            clip.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
            if (shader != null) shader.setFloatUniform("size", w, h);
        }

        @Override protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, Math.max(0f, (now - last) / 1000f));
            last = now;
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            int save = canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(0, 0, w, h, scrim);
            float t = (now % 600_000L) / 1000f;
            if (shader != null) {
                shader.setFloatUniform("time", t);
                canvas.drawRect(0, 0, w, h, shade);
            } else {
                for (int i = 0; i < COUNT; i++) {
                    x[i] += vx[i] * dt;
                    y[i] += vy[i] * dt;
                    if (x[i] < 0f) x[i] += 1f; else if (x[i] > 1f) x[i] -= 1f;
                    if (y[i] < 0f) y[i] += 1f; else if (y[i] > 1f) y[i] -= 1f;
                    float twinkle = 0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]);
                    dot.setAlpha(Math.round(70 + 185 * twinkle));
                    canvas.drawCircle(x[i] * w, y[i] * h, size[i] * density, dot);
                }
            }
            canvas.restoreToCount(save);
            float radius = Math.min(w, h) / 2f - rim.getStrokeWidth() / 2f;
            canvas.drawCircle(w / 2f, h / 2f, radius, rim);
            if (running) postOnAnimation(tick);
        }
    }
}
