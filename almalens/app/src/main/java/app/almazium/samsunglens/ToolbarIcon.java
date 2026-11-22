package app.almazium.samsunglens;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

final class ToolbarIcon {
    private final FrameLayout decor;
    private final float density;
    private final View button;
    private final ZoomStatusPill status;
    private final Rect closeBounds = new Rect();
    private final Rect barBounds = new Rect();
    private final Rect listBounds = new Rect();
    private final int listId, barId, closeId;
    private int targetLeft, targetTop;
    private ViewGroup reservedList;
    private int originalLeft, originalRight, appliedLeft, appliedRight;
    private boolean originalClip;

    ToolbarIcon(Activity activity, Runnable toggle, Runnable settings) {
        decor = (FrameLayout) activity.getWindow().getDecorView();
        density = activity.getResources().getDisplayMetrics().density;
        listId = id(activity, "quick_setting_main_list");
        barId = id(activity, "quick_setting_list_wrapper");
        closeId = id(activity, "quick_setting_close_button");
        button = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas canvas) {
                int save = canvas.save();
                canvas.translate(getWidth() * 0.5f, getHeight() * 0.5f);
                canvas.scale(density, density);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(1.65f);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(-1.5f, -1.5f, 5.7f, paint);
                canvas.drawLine(2.8f, 2.8f, 7.2f, 7.2f, paint);
                if (!isSelected()) {
                    canvas.drawLine(-3.3f, -3.3f, 0.3f, 0.3f, paint);
                    canvas.drawLine(-3.3f, 0.3f, 0.3f, -3.3f, paint);
                }
                canvas.restoreToCount(save);
            }
        };
        button.setFocusable(true);
        button.setOnClickListener(view -> toggle.run());
        button.setOnLongClickListener(view -> {
            settings.run();
            return true;
        });
        button.setVisibility(View.GONE);
        button.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> position());
        decor.addView(button, new FrameLayout.LayoutParams(dp(32), dp(42), Gravity.TOP | Gravity.LEFT));
        status = new ZoomStatusPill(decor);
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    void update(boolean enabled) {
        View close = find(closeId);
        View bar = find(barId);
        if (!(find(listId) instanceof ViewGroup list) || !visible(close, closeBounds)
                || !visible(list, listBounds) || !visible(bar, barBounds)) {
            hide();
            return;
        }

        int width = dp(32);
        boolean closeOnRight = closeBounds.centerX() >= barBounds.centerX();
        targetLeft = closeOnRight ? closeBounds.left - width : closeBounds.right;
        targetTop = closeBounds.centerY() - dp(42) / 2;
        if (targetLeft < barBounds.left || targetLeft + width > barBounds.right) {
            hide();
            return;
        }
        reserve(list, closeOnRight, targetLeft, width);
        position();
        setEnabled(enabled);
        button.setAlpha(Math.min(bar.getAlpha(), close.getAlpha()));
        button.setVisibility(View.VISIBLE);
    }

    private void reserve(ViewGroup list, boolean closeOnRight, int left, int width) {
        if (reservedList != list) {
            restoreList();
            reservedList = list;
            originalLeft = appliedLeft = list.getPaddingLeft();
            originalRight = appliedRight = list.getPaddingRight();
            originalClip = list.getClipToPadding();
        } else {
            if (list.getPaddingLeft() != appliedLeft) originalLeft = list.getPaddingLeft();
            if (list.getPaddingRight() != appliedRight) originalRight = list.getPaddingRight();
        }
        appliedLeft = closeOnRight ? originalLeft
                : Math.max(originalLeft, left + width - listBounds.left);
        appliedRight = closeOnRight ? Math.max(originalRight, listBounds.right - left) : originalRight;
        if (list.getPaddingLeft() != appliedLeft || list.getPaddingRight() != appliedRight) {
            list.setPadding(appliedLeft, list.getPaddingTop(), appliedRight, list.getPaddingBottom());
        }
        list.setClipToPadding(true);
    }

    private void restoreList() {
        if (reservedList == null) return;
        int left = reservedList.getPaddingLeft();
        int right = reservedList.getPaddingRight();
        reservedList.setPadding(left == appliedLeft ? originalLeft : left, reservedList.getPaddingTop(),
                right == appliedRight ? originalRight : right, reservedList.getPaddingBottom());
        reservedList.setClipToPadding(originalClip);
        reservedList = null;
    }

    private View find(int id) { return id == 0 ? null : decor.findViewById(id); }

    private static boolean visible(View view, Rect bounds) {
        return view != null && view.isShown() && view.getAlpha() > 0.1f
                && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty();
    }

    private void position() {
        button.setTranslationX(targetLeft - button.getLeft());
        button.setTranslationY(targetTop - button.getTop());
    }

    void announce(boolean enabled, View preview) {
        setEnabled(enabled);
        if (button.isShown()) status.show(enabled, barBounds, preview);
    }

    private void setEnabled(boolean enabled) {
        button.setSelected(enabled);
        button.setContentDescription(enabled ? Ui.text("Выключить зум 1000×", "Turn off 1000× zoom")
                : Ui.text("Включить зум 1000×", "Turn on 1000× zoom"));
    }

    void hide() {
        button.setVisibility(View.GONE);
        restoreList();
        status.hide();
    }

    void remove() {
        hide();
        status.remove();
        decor.removeView(button);
    }

    private int dp(int size) { return Math.round(size * density); }
}
