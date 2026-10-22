package se.radiocast;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

/**
 * The frame around a sheet that slides up from the bottom (cast picker, now playing):
 * a close button, drag down to close, never taller than 85 % of the screen (so the dimmed area
 * above it can always be tapped to close it), and the content scrolls when it is longer.
 */
public final class SheetFrame extends FrameLayout {

    /** Inflates a sheet layout into the dialog with all of the above. */
    public static void install(Dialog d, int layout) {
        Context c = d.getContext();
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ViewGroup root = (ViewGroup) LayoutInflater.from(c).inflate(layout, null, false);
        addCloseButton(d, root);
        SheetFrame frame = new SheetFrame(c, d);
        frame.addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        d.setContentView(frame);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
    }

    /** Puts a ✕ in the sheet's top row, next to the drag handle (the first view in the content). */
    private static void addCloseButton(Dialog d, ViewGroup root) {
        Context c = d.getContext();
        float dp = c.getResources().getDisplayMetrics().density;
        if (root.getChildCount() == 0 || !(root.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout inner = (LinearLayout) root.getChildAt(0);
        if (inner.getChildCount() == 0) return;
        View handle = inner.getChildAt(0);
        ViewGroup.LayoutParams hp = handle.getLayoutParams();
        int bottom = hp instanceof MarginLayoutParams ? ((MarginLayoutParams) hp).bottomMargin : 0;
        inner.removeViewAt(0);

        FrameLayout header = new FrameLayout(c);
        LayoutParams handleLp = new LayoutParams(hp.width, hp.height, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        handleLp.topMargin = Math.round(2 * dp);
        header.addView(handle, handleLp);

        ImageButton close = new ImageButton(c);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(c.getColor(R.color.muted)));
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            close.setBackgroundResource(tv.resourceId);
        } else {
            close.setBackground(null);
        }
        close.setContentDescription("Stäng");
        close.setOnClickListener(v -> d.dismiss());
        int size = Math.round(44 * dp);
        LayoutParams closeLp = new LayoutParams(size, size, Gravity.END | Gravity.TOP);
        closeLp.topMargin = Math.round(-6 * dp);
        closeLp.setMarginEnd(Math.round(-8 * dp));
        header.addView(close, closeLp);

        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = Math.max(0, bottom - Math.round(12 * dp));
        header.setMinimumHeight(Math.round(32 * dp));
        inner.addView(header, 0, headerLp);
    }

    private final Dialog dialog;
    private final int slop;
    private float startX, startY;
    private boolean dragging;
    private VelocityTracker velocity;

    private SheetFrame(Context c, Dialog dialog) {
        super(c);
        this.dialog = dialog;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.85f);
        int size = MeasureSpec.getSize(heightSpec);
        int mode = MeasureSpec.getMode(heightSpec);
        if (mode == MeasureSpec.UNSPECIFIED || size > max) heightSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec, heightSpec);
    }

    /** The content can scroll up (then a downward drag scrolls it instead of closing the sheet). */
    private boolean contentScrolled() {
        return getChildCount() > 0 && getChildAt(0).canScrollVertically(-1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = e.getRawX();
                startY = e.getRawY();
                dragging = false;
                track(e, true);
                return false;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                float dy = e.getRawY() - startY, dx = e.getRawX() - startX;
                if (dy > slop && dy > Math.abs(dx) * 1.5f && !contentScrolled()) {
                    dragging = true;
                    startY = e.getRawY();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Nothing inside took the touch (e.g. the handle): this may become a drag.
                startX = e.getRawX();
                startY = e.getRawY();
                track(e, true);
                return true;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                float dy = e.getRawY() - startY;
                if (!dragging && dy > slop) { dragging = true; startY = e.getRawY(); dy = 0; }
                if (dragging) setTranslationY(Math.max(0, dy));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                track(e, false);
                if (dragging) settle();
                dragging = false;
                if (velocity != null) { velocity.recycle(); velocity = null; }
                return true;
            default:
                return true;
        }
    }

    private void track(MotionEvent e, boolean reset) {
        if (reset || velocity == null) {
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
        }
        MotionEvent copy = MotionEvent.obtain(e);
        copy.setLocation(e.getRawX(), e.getRawY());
        velocity.addMovement(copy);
        copy.recycle();
    }

    /** Closes when dragged far enough or flung down; otherwise springs back. */
    private void settle() {
        float speed = 0;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            speed = velocity.getYVelocity() / getResources().getDisplayMetrics().density; // dp per second
        }
        boolean close = getTranslationY() > getHeight() * 0.25f || speed > 900;
        float target = close ? getHeight() : 0;
        ValueAnimator a = ValueAnimator.ofFloat(getTranslationY(), target);
        a.setDuration(close ? 160 : 200);
        a.addUpdateListener(v -> setTranslationY((float) v.getAnimatedValue()));
        if (close) {
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { dialog.dismiss(); }
            });
        }
        a.start();
    }
}
