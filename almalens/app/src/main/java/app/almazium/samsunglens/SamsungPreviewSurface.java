package app.almazium.samsunglens;

import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.SurfaceView;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static de.robv.android.xposed.XposedHelpers.getBooleanField;

final class SamsungPreviewSurface {
    private final Method refreshSurface;
    private volatile Crop active;
    private volatile Crop submitted;

    private record Crop(SurfaceView view, SurfaceControl layer, Rect normal, Rect source) {}

    SamsungPreviewSurface() throws ReflectiveOperationException {
        refreshSurface = SurfaceView.class.getDeclaredMethod("requestUpdateSurfacePositionAndScale");
        refreshSurface.setAccessible(true);
        Method position = SurfaceView.class.getDeclaredMethod("onSetSurfacePositionAndScale",
                SurfaceControl.Transaction.class, SurfaceControl.class, int.class, int.class,
                float.class, float.class);
        XposedBridge.hookMethod(position, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Crop crop = active;
                if (crop == null || p.thisObject != crop.view || p.args[1] != crop.layer
                        || p.hasThrowable()) return;
                SurfaceControl.Transaction transaction = (SurfaceControl.Transaction) p.args[0];
                float nativeX = (Float) p.args[4];
                float nativeY = (Float) p.args[5];
                if (nativeX <= 0 || nativeY <= 0) return;
                Rect normal = crop.normal;
                Rect source = crop.source;
                float x = nativeX * normal.width() / source.width();
                float y = nativeY * normal.height() / source.height();
                float left = (Integer) p.args[2] + nativeX * normal.left - x * source.left;
                float top = (Integer) p.args[3] + nativeY * normal.top - y * source.top;
                transaction.setCrop(crop.layer, source);
                transaction.setScale(crop.layer, x, y);
                transaction.setPosition(crop.layer, left, top);
                submitted = crop;
            }
        });
    }

    boolean supports(SurfaceView surface) {
        SurfaceControl layer = surface.getSurfaceControl();
        return layer != null && layer.isValid() && !surface.getHolder().getSurfaceFrame().isEmpty();
    }

    boolean apply(SurfaceView surface, float factor) throws ReflectiveOperationException {
        SurfaceControl layer = surface.getSurfaceControl();
        Rect frame = surface.getHolder().getSurfaceFrame();
        int width = frame.width();
        int height = frame.height();
        if (layer == null || !layer.isValid() || width <= 0 || height <= 0) return false;
        Crop previous = active;
        if (previous != null && (previous.layer != layer || previous.view != surface)) restore();
        Rect normal = new Rect(0, 0, width, height);
        Rect stockClip = surface.getClipBounds();
        if (stockClip != null && getBooleanField(surface, "mClipSurfaceToBounds")) {
            if (!normal.intersect(stockClip)) return false;
        }
        int w = ZoomMath.cropSize(normal.width(), factor);
        int h = ZoomMath.cropSize(normal.height(), factor);
        int left = normal.left + (normal.width() - w) / 2;
        int top = normal.top + (normal.height() - h) / 2;
        Crop crop = new Crop(surface, layer, normal, new Rect(left, top, left + w, top + h));
        if (crop.equals(previous) && previous == submitted) return true;
        active = crop;
        submitted = null;
        refreshSurface.invoke(surface);
        if (submitted != crop) {
            restore();
            throw new IllegalStateException("PREVIEW_SURFACE_POSITION_CALLBACK_MISSING");
        }
        if (previous == null) {
            XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " PREVIEW_SURFACE_CROP_SUBMITTED "
                    + width + "x" + height + " source=" + w + "x" + h + " factor=" + factor);
        }
        return true;
    }

    void restore() throws ReflectiveOperationException {
        Crop crop = active;
        active = null;
        submitted = null;
        if (crop == null || !crop.layer.isValid() || crop.view.getSurfaceControl() != crop.layer) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setCrop(crop.layer, crop.normal);
            transaction.apply();
        }
        refreshSurface.invoke(crop.view);
    }
}
