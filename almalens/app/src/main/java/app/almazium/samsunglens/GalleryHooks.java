package app.almazium.samsunglens;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Member;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

/**
 * Samsung Gallery: while the camera says a photo is still being processed (see ProcessingMarker), the viewer page that
 * shows it is blurred and covered with the spoiler shader. It disappears as soon as the photo is published.
 */
final class GalleryHooks {
    private static final String VIEWER = "com.samsung.android.gallery.app.ui.viewer2.";
    private static final long TIMEOUT_MS = 25_000;
    private static final long POLL_MS = 150;
    private static final int MAX_DEBUG_LINES = 12;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<View, Active> ACTIVE = new WeakHashMap<>();
    private static int debugLines;

    private static final class Active {
        final String name;
        final SpoilerDrawable drawable;
        final ViewGroup parent;
        final long since = System.currentTimeMillis();
        Runnable poll;

        Active(String name, SpoilerDrawable drawable, ViewGroup parent) {
            this.name = name;
            this.drawable = drawable;
            this.parent = parent;
        }
    }

    private GalleryHooks() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> abs = findClass(VIEWER + "contentviewer.AbsViewerHolder", loader);
            Class<?> content = findClassIfExists(VIEWER + "contentviewer.ContentViewerHolder", loader);
            Class<?> item = findClass("com.samsung.android.gallery.module.data.MediaItem", loader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        onPage(p.thisObject, p.args[0]);
                    } catch (Throwable error) {
                        XposedBridge.log("AlmaZoom gallery spoiler: " + error);
                    }
                }
            };
            int hooked = 0;
            for (Class<?> holder : new Class<?>[]{abs, content}) {
                if (holder == null) continue;
                hooked += hookIfDeclared(holder, hook, "onBind", item, int.class);
                hooked += hookIfDeclared(holder, hook, "invalidate", item, int.class, item, int.class);
            }
            XposedBridge.log("AlmaZoom gallery hooks ready (" + hooked + " methods)");
        } catch (Throwable error) {
            XposedBridge.log("AlmaZoom gallery hooks unavailable: " + error);
        }
    }

    private static int hookIfDeclared(Class<?> owner, XC_MethodHook hook, String name, Class<?>... types) {
        try {
            Member method = owner.getDeclaredMethod(name, types);
            XposedBridge.hookMethod(method, hook);
            return 1;
        } catch (NoSuchMethodException absent) {
            return 0;
        }
    }

    private static String nameOf(Object mediaItem) {
        try {
            Object path = callMethod(mediaItem, "getPath");
            String text = path == null ? "" : path.toString();
            int slash = text.lastIndexOf('/');
            if (slash >= 0) text = text.substring(slash + 1);
            if (!text.isEmpty()) return text;
        } catch (Throwable ignored) {
        }
        try {
            Object display = callMethod(mediaItem, "getDisplayName");
            return display == null ? "" : display.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void onPage(Object holder, Object mediaItem) {
        if (mediaItem == null || !(getObjectField(holder, "itemView") instanceof View page)) return;
        String name = nameOf(mediaItem);
        boolean marked = ProcessingMarker.isSet(name);
        if (debugLines < MAX_DEBUG_LINES && (marked || debugLines < 4)) {
            debugLines++;
            XposedBridge.log("AlmaZoom gallery page " + name + " marker=" + marked);
        }
        Active active = ACTIVE.get(page);
        if (active != null && !active.name.equals(name)) {
            hide(page);
            active = null;
        }
        if (marked && active == null) show(page, name);
    }

    private static void show(View page, String name) {
        if (!(page.getParent() instanceof ViewGroup parent)) return;
        SpoilerDrawable drawable = new SpoilerDrawable(page);
        drawable.setBounds(0, 0, Math.max(1, parent.getWidth()), Math.max(1, parent.getHeight()));
        Active active = new Active(name, drawable, parent);
        ACTIVE.put(page, active);
        if (Build.VERSION.SDK_INT >= 31) {
            float radius = 9f * page.getResources().getDisplayMetrics().density;
            page.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        parent.getOverlay().add(drawable);
        drawable.start();
        active.poll = new Runnable() {
            @Override public void run() {
                Active current = ACTIVE.get(page);
                if (current != active) return;
                boolean timedOut = System.currentTimeMillis() - active.since > TIMEOUT_MS;
                if (timedOut || !ProcessingMarker.isSet(active.name)) {
                    hide(page);
                } else {
                    HANDLER.postDelayed(this, POLL_MS);
                }
            }
        };
        HANDLER.postDelayed(active.poll, POLL_MS);
        XposedBridge.log("AlmaZoom gallery spoiler shown for " + name + " " + page.getWidth() + "x" + page.getHeight()
                + " shader=" + drawable.usesShader());
    }

    private static void hide(View page) {
        Active active = ACTIVE.remove(page);
        if (active == null) return;
        if (active.poll != null) HANDLER.removeCallbacks(active.poll);
        active.drawable.stop();
        active.parent.getOverlay().remove(active.drawable);
        if (Build.VERSION.SDK_INT >= 31) page.setRenderEffect(null);
        XposedBridge.log("AlmaZoom gallery spoiler hidden for " + active.name);
    }
}
