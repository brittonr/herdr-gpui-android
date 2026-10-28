package dev.herdr.android;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * Rail container that yields a leftward swipe to its owner while keeping normal
 * vertical scrolling. The swipe is claimed only once it is clearly horizontal,
 * so scrolling the tree never closes it by accident.
 */
final class RailScrollView extends ScrollView {
    private static final float CLAIM_DP = 36f;
    private static final float RATIO = 1.5f;

    private final Runnable close;
    private final float claim;
    private float downX, downY;
    private boolean claimed;

    RailScrollView(Context context, Runnable close) {
        super(context);
        this.close = close;
        this.claim = CLAIM_DP * context.getResources().getDisplayMetrics().density;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            claimed = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (!claimed && dx < -claim && Math.abs(dx) > Math.abs(dy) * RATIO) {
                claimed = true;
                return true;
            }
            if (claimed) return true;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!claimed) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            claimed = false;
            close.run();
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            claimed = false;
        }
        return true;
    }
}
