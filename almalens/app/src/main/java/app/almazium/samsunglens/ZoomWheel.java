package app.almazium.samsunglens;

import android.util.SparseIntArray;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

final class ZoomWheel {
    final HorizontalScrollView scroll;
    final View ticks;
    final SparseIntArray nativePositions;
    private final ViewGroup track;
    private final List<View> extension = new ArrayList<>();
    boolean positioning;
    private long lastHaptic;

    private final boolean camera16;

    ZoomWheel(HorizontalScrollView scroll, View ticks, SparseIntArray positions, boolean camera16) {
        this.camera16 = camera16;
        this.scroll = scroll;
        this.ticks = ticks;
        nativePositions = positions;
        track = (ViewGroup) getObjectField(getObjectField(ticks, (camera16 ? "mViewBinding" : "g")), "a");
    }

    boolean install() {
        int extraTicks = ZoomMath.extensionTicks(hardwareMaximum());
        if (!extension.isEmpty() && !attached()) extension.clear();
        if (!extension.isEmpty() && extension.size() != extraTicks) remove();
        if (!extension.isEmpty() || extraTicks == 0 || track.getChildCount() == 0) return false;
        int minor = getIntField(ticks, (camera16 ? "mNormalBarResource" : "d"));
        int major = getIntField(ticks, (camera16 ? "mShortcutBarResource" : "e"));
        int spacing = Math.round(4 * scroll.getResources().getDisplayMetrics().density);
        try {
            for (int i = 1; i <= extraTicks; i++) {
                boolean marker = i % 8 == 0;
                View tick = (View) callMethod(ticks, (camera16 ? "addBarImageView" : "a"), marker ? major : minor, spacing);
                extension.add(tick);
                if (marker) {
                    if (camera16) callMethod(tick, "setType", 2);
                    else callMethod(tick, "b");
                }
            }
            invalidateHaptics();
            return true;
        } catch (Throwable error) {
            remove();
            throw error;
        }
    }

    boolean attached() {
        return !extension.isEmpty() && extension.get(0).getParent() == track;
    }

    boolean ready() {
        return attached() && nativePositions.indexOfKey(hardwareMaximum()) >= 0 && nativeEnd() >= 0
                && end() > nativeEnd();
    }

    int hardwareMaximum() {
        int size = nativePositions.size();
        return size == 0 ? 0 : nativePositions.keyAt(size - 1);
    }

    int nativeEnd() { return nativePositions.get(hardwareMaximum(), -1); }

    int end() { return Math.max(0, ticks.getWidth() - scroll.getWidth()); }

    int digitalStart() {
        int seam = Math.round(3f * scroll.getResources().getDisplayMetrics().density);
        return Math.min(end() - 1, nativeEnd() + seam);
    }

    int ratioAt(int x) {
        return ZoomMath.digitalRatio(x, digitalStart(), end(), hardwareMaximum());
    }

    int hapticTick(int x, int previous) {
        long now = SystemClock.uptimeMillis();
        int currentTick = ZoomMath.tickAt(x, digitalStart(), end(), extension.size());
        int previousTick = ZoomMath.tickAt(previous, digitalStart(), end(), extension.size());
        int tick = ZoomMath.hapticTick(currentTick, previousTick);
        if (tick == 0 || (tick == 1 && now - lastHaptic < 24)) return 0;
        lastHaptic = now;
        return tick;
    }

    int positionAt(int ratio) {
        return ZoomMath.digitalPosition(ratio, digitalStart(), end(), hardwareMaximum());
    }

    void showRatio(int ratio) {
        if (!ready() || !scroll.isShown()) return;
        int x = positionAt(ratio);
        if (x > nativeEnd() && x != scroll.getScrollX()) {
            positioning = true;
            try {
                scroll.scrollTo(x, scroll.getScrollY());
                moveHighlight(scroll.getScrollX());
            } finally {
                positioning = false;
            }
        }
    }

    void moveHighlight(int x) {
        // Camera 16 has static ticks. Its method named c is an unrelated synthetic bridge.
        if (!camera16) callMethod(ticks, "c", x);
    }

    void remove() {
        int limit = nativeEnd();
        boolean snapBack = attached() && limit >= 0 && scroll.getScrollX() > limit;
        positioning = true;
        try {
            for (View tick : extension) {
                if (tick.getParent() == track) track.removeView(tick);
            }
            extension.clear();
            if (snapBack) scroll.scrollTo(limit, scroll.getScrollY());
        } finally {
            positioning = false;
        }
        invalidateHaptics();
    }

    private void invalidateHaptics() {
        Object pairs = getObjectField(ticks, (camera16 ? "mBarRangeArray" : "b"));
        if (pairs instanceof Collection<?> collection) collection.clear();
    }
}
