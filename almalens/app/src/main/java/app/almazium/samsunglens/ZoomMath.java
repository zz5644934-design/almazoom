package app.almazium.samsunglens;

final class ZoomMath {
    static float centeredOffset(int size, float factor) {
        return size * (1f - factor) / 2f;
    }

    static final int LIMIT = 1_000_000;

    private static final int MIN_EXTENSION_TICKS = 24;
    private static final int MAX_EXTENSION_TICKS = 96;

    private ZoomMath() {}

    static int extensionTicks(int nativeZoom) {
        if (nativeZoom <= 0 || nativeZoom >= LIMIT) return 0;
        int steps = (int) Math.round(6 * Math.log10((double) LIMIT / nativeZoom));
        return Math.max(MIN_EXTENSION_TICKS, Math.min(MAX_EXTENSION_TICKS, steps * 8));
    }

    static int digitalRatio(int x, int start, int end, int base) {
        if (base <= 0 || base >= LIMIT || end <= start || x <= start) return base;
        if (x >= end) return LIMIT;
        double progress = (double) (x - start) / (end - start);
        double ratio = base * Math.pow((double) LIMIT / base, progress);
        return Math.min(LIMIT, Math.max(base, (int) Math.round(ratio / 100.0) * 100));
    }

    static int digitalPosition(int ratio, int start, int end, int base) {
        if (base <= 0 || base >= LIMIT || end <= start || ratio <= base) return start;
        if (ratio >= LIMIT) return end;
        double progress = Math.log((double) ratio / base) / Math.log((double) LIMIT / base);
        return start + (int) Math.round((end - start) * progress);
    }

    static float cropFactor(int requested, int nativeZoom) {
        return nativeZoom <= 0 ? 1f : Math.max(1f, (float) Math.min(requested, LIMIT) / nativeZoom);
    }

    static int tickAt(int x, int start, int end, int count) {
        if (x <= start || end <= start) return 0;
        return Math.min(count, (int) ((long) (x - start) * count / (end - start)));
    }

    static int hapticTick(int current, int previous) {
        if (current == previous) return 0;
        int low = Math.min(current, previous);
        int high = Math.max(current, previous);
        return high / 8 != low / 8 ? 2 : 1;
    }

    static int cropSize(int size, float factor) {
        return Math.max(1, Math.min(size, Math.round(size / factor)));
    }
}
