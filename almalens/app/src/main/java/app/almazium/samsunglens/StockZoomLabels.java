package app.almazium.samsunglens;

import android.graphics.Paint;
import android.text.Editable;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Map;

import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

final class StockZoomLabels {
    private final Map<TextView, Label> active = new IdentityHashMap<>();

    void show(Object zoomBinding, int nativeZoom, String ratio, String compact, boolean camera16) {
        Map<TextView, String> targets = new IdentityHashMap<>();
        Object display = field(zoomBinding, camera16 ? "l" : "m");
        TextView readout = text(field(display, camera16 ? "mViewBinding" : "i"), "b");
        if (readout != null) targets.put(readout, camera16 ? ratio : ratio + " ×");
        int maximumPreset = Math.round(nativeZoom / 1000f) * 1000;
        collect(targets, field(zoomBinding, camera16 ? "d" : "e"), maximumPreset, compact, camera16);
        collect(targets, field(zoomBinding, "c"), maximumPreset, compact, camera16);
        collect(targets, field(zoomBinding, "j"), maximumPreset, compact, camera16);

        active.entrySet().removeIf(entry -> {
            if (targets.containsKey(entry.getKey())) return false;
            entry.getValue().restore();
            return true;
        });
        for (Map.Entry<TextView, String> target : targets.entrySet()) {
            TextView view = target.getKey();
            Label label = active.get(view);
            if (label == null) {
                label = new Label(view);
                active.put(view, label);
            }
            label.show(target.getValue(), view != readout);
        }
    }

    void restore() {
        for (Label label : active.values()) label.restore();
        active.clear();
    }

    private static Object field(Object owner, String field) {
        if (owner == null) return null;
        try {
            return getObjectField(owner, field);
        } catch (NoSuchFieldError unavailable) {
            return null;
        }
    }

    private static int zoomValue(View view, String field) {
        try {
            return getIntField(view, field);
        } catch (NoSuchFieldError unavailable) {
            return -1;
        }
    }

    private static TextView text(Object binding, String field) {
        if (binding == null) return null;
        Object value = field(binding, field);
        return value instanceof TextView view ? view : null;
    }

    private static void collect(Map<TextView, String> targets, Object child, int maximum, String ratio, boolean camera16) {
        if (!(child instanceof View view)) return;
        switch (view.getClass().getSimpleName()) {
            case "ZoomShortcutButton" -> {
                if (zoomValue(view, camera16 ? "mValue" : "b") != maximum) return;
                if (camera16 && view instanceof TextView button) {
                    add(targets, button, ratio);
                } else if (!camera16) {
                    add(targets, text(field(view, "a"), "a"), ratio);
                }
            }
            case "ZoomLensShortcutButton" -> {
                if (zoomValue(view, camera16 ? "mZoomValue" : "e") == maximum) addLensLabels(targets, field(view, camera16 ? "mViewBinding" : "a"), ratio);
            }
            case "ZoomLensTextButton" -> {
                if (zoomValue(view, camera16 ? "mDisplayZoomValue" : "q") == maximum) addLensLabels(targets, field(view, camera16 ? "mViewBinding" : "k"), ratio);
            }
            default -> {
                if (view instanceof ViewGroup group) {
                    for (int i = 0; i < group.getChildCount(); i++) {
                        collect(targets, group.getChildAt(i), maximum, ratio, camera16);
                    }
                }
            }
        }
    }

    private static void addLensLabels(Map<TextView, String> targets, Object binding, String ratio) {
        add(targets, text(binding, "c"), ratio);
        add(targets, text(binding, "g"), ratio);
    }

    private static void add(Map<TextView, String> targets, TextView view, String ratio) {
        if (view != null) targets.put(view, ratio);
    }

    private static final class Label implements TextWatcher {
        /** A label is never shrunk below this fraction of its original size. */
        private static final float MIN_SCALE = 0.55f;
        final TextView view;
        final float baseSize;   // px
        CharSequence original;
        String replacement;
        boolean changing;
        boolean fit;

        Label(TextView view) {
            this.view = view;
            baseSize = view.getTextSize();
            original = new SpannedString(view.getText());
            view.addTextChangedListener(this);
        }

        void show(String value, boolean fit) {
            replacement = value;
            this.fit = fit;
            render();
        }

        private void render() {
            if (fit) {
                int width = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
                if (width > 0) {
                    Paint probe = new Paint(view.getPaint());
                    probe.setTextSize(baseSize);
                    float measured = probe.measureText(replacement);
                    float scale = measured > width ? Math.max(MIN_SCALE, width / measured) : 1f;
                    float target = baseSize * scale;
                    if (Math.abs(view.getTextSize() - target) > 0.5f) {
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, target);
                    }
                }
            }
            if (TextUtils.equals(view.getText(), replacement)) return;
            changing = true;
            try {
                view.setText(replacement);
            } finally {
                changing = false;
            }
        }

        @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}

        @Override public void afterTextChanged(Editable text) {
            if (changing || replacement == null) return;
            original = new SpannedString(text);
            render();
        }

        void restore() {
            view.removeTextChangedListener(this);
            if (fit) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSize);
            view.setText(original);
        }
    }
}
