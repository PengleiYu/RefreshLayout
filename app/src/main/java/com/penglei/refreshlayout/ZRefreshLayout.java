package com.penglei.refreshlayout;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.v4.view.NestedScrollingParent;
import android.support.v4.view.NestedScrollingParentHelper;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Scroller;

/**
 * @author penglei
 */
public class ZRefreshLayout extends FrameLayout implements NestedScrollingParent {
    private final NestedScrollingParentHelper mParentHelper = new NestedScrollingParentHelper(this);
    private final Scroller mScroller = new Scroller(getContext());

    private static final float DAMPING = .5f; // 阻尼系数
    private static final int DEFAULT_THRESHOLD = -1; // 默认滑动门槛，不可滑动

    private View mHeaderView;
    private View mTailView;

    private OnRefreshListener mHeaderOnRefreshListener;
    private OnRefreshListener mTailOnRefreshListener;

    private int mThreshold = DEFAULT_THRESHOLD; // 滑动门槛，超出该值才会整体滑动
    private boolean isHorizontal = false;
    private boolean isLoading = false;
    private final Runnable revertRunnable;// 回复初始位置

    public ZRefreshLayout(@NonNull Context context) { this(context, null); }

    public ZRefreshLayout(@NonNull Context context, @android.support.annotation.Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZRefreshLayout(@NonNull Context context, @android.support.annotation.Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        revertRunnable = new Runnable() {
            @Override
            public void run() {
                if (getScrollX() == 0 && getScrollY() == 0) return;
                int scroll = getScrollX() != 0 ? getScrollX() : getScrollY();
                if (scroll > 0 && mTailOnRefreshListener != null) mTailOnRefreshListener.onEnd();
                else if (scroll < 0 && mHeaderOnRefreshListener != null)
                    mHeaderOnRefreshListener.onEnd();
                smoothBy(-getScrollX(), -getScrollY());
            }
        };

        TypedArray typedArray = context.getTheme()
                .obtainStyledAttributes(attrs, R.styleable.ZRefreshLayout, defStyleAttr, 0);
        @LayoutRes
        int headerId = typedArray.getResourceId(R.styleable.ZRefreshLayout_header, -1);
        @LayoutRes
        int tailId = typedArray.getResourceId(R.styleable.ZRefreshLayout_tail, -1);
        int orientation = typedArray.getInt(R.styleable.ZRefreshLayout_orientation, LinearLayout.VERTICAL);
        int threshold = typedArray.getDimensionPixelOffset(R.styleable.ZRefreshLayout_threshold, DEFAULT_THRESHOLD);
        View header = null, tail = null;
        if (headerId != -1) header = LayoutInflater.from(context).inflate(headerId, null);
        if (tailId != -1) tail = LayoutInflater.from(context).inflate(tailId, null);
        options(new Option(threshold, orientation, header, tail));
    }

    /**
     * 头尾View保持长或宽与父View一致
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int widthSpec = !isHorizontal ? widthMeasureSpec :
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.AT_MOST);
        int heightSpec = isHorizontal ? heightMeasureSpec :
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST);
        if (mHeaderView != null) mHeaderView.measure(widthSpec, heightSpec);
        if (mTailView != null) mTailView.measure(widthSpec, heightSpec);
    }

    /**
     * 放置头尾View
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (isHorizontal) {
            if (mHeaderView != null)
                mHeaderView.layout(left - mHeaderView.getMeasuredWidth(), top, left, bottom);
            if (mTailView != null)
                mTailView.layout(right, top, right + mTailView.getMeasuredWidth(), bottom);
        } else {
            if (mHeaderView != null)
                mHeaderView.layout(left, top - mHeaderView.getMeasuredHeight(), right, top);
            if (mTailView != null)
                mTailView.layout(left, bottom, right, bottom + mTailView.getMeasuredHeight());
        }
    }

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes) {
        return !isLoading;
    }

    @Override
    public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes) {
        mParentHelper.onNestedScrollAccepted(child, target, axes);
    }

    /**
     * 仅在用户移动child复位时才预消费
     */
    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed) {
        int index = isHorizontalScroll() ? 0 : 1;
        int delta = isHorizontalScroll() ? dx : dy;
        int scroll = isHorizontalScroll() ? getScrollX() : getScrollY();
        switch (MoveType.valueOf(delta, scroll)) {
            case BELOW_UP:
                consumed[index] = isLoading ? 0 : Math.min(delta, -scroll);
                break;
            case ABOVE_DOWN:
                consumed[index] = isLoading ? 0 : Math.max(delta, -scroll);
                break;
            default:
                consumed[index] = 0;
        }
        if (!isLoading) scrollBy(consumed[0], consumed[1]);
    }

    /**
     * child滚动到头，用户移动child偏离复位
     */
    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
        if (isLoading) return;
        int delta = isHorizontal ? dxUnconsumed : dyUnconsumed;
        int scroll = isHorizontal ? getScrollX() : getScrollY();
        MoveType moveType = MoveType.valueOf(delta, scroll);
        if ((moveType == MoveType.ABOVE_UP && mTailView == null)
                || (moveType == MoveType.BELOW_DOWN && mHeaderView == null)) return;

        int dx = isHorizontalScroll() ? dxUnconsumed : 0;
        int dy = isHorizontalScroll() ? 0 : dyUnconsumed;
        scrollBy((int) (dx * DAMPING), (int) (dy * DAMPING));
    }

    @Override
    public void onStopNestedScroll(@NonNull View target) {
        int scroll = isHorizontalScroll() ? getScrollX() : getScrollY();
        if (-scroll >= getHeaderSize(isHorizontal)) {
            refreshHeader(true);
        } else if (scroll >= getTailSize(isHorizontal)) {
            refreshTail(true);
        } else if (!isLoading) {
            post(revertRunnable);
        }
        mParentHelper.onStopNestedScroll(target);
    }

    @Override
    public boolean onNestedPreFling(@NonNull View target, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onNestedFling(@NonNull View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }

    @Override
    public int getNestedScrollAxes() { return mParentHelper.getNestedScrollAxes(); }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        boolean horizontal = l != oldl;
        int scroll = (horizontal) ? l : t;
        if (getNestedScrollAxes() != ViewCompat.SCROLL_AXIS_NONE) notifyListener(scroll);
        if (scroll == 0) isLoading = false;
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
            invalidate();
        }
    }

    /**
     * 开始滑动
     *
     * @param delta        滑动偏移量
     * @param isHorizontal 滑动方向
     */
    private void smoothBy(int delta, boolean isHorizontal) {
        smoothBy(isHorizontal ? delta : 0, isHorizontal ? 0 : delta);
    }

    /**
     * 开始滑动
     */
    private void smoothBy(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        mScroller.forceFinished(true);
        mScroller.startScroll(getScrollX(), getScrollY(), dx, dy);
        invalidate();
    }

    private boolean isHorizontalScroll() { return getNestedScrollAxes() == ViewCompat.SCROLL_AXIS_HORIZONTAL; }

    private void notifyListener(int offset) {
        if (offset > 0 && mTailOnRefreshListener != null) mTailOnRefreshListener.onStart(offset);
        else if (offset < 0 && mHeaderOnRefreshListener != null) mHeaderOnRefreshListener.onStart(-offset);
    }

    private int getHeaderSize(boolean isHorizontal) {
        if (mThreshold >= 0) return mThreshold;
        if (mHeaderView == null) return 0;
        return isHorizontal ? mHeaderView.getWidth() : mHeaderView.getHeight();
    }

    private int getTailSize(boolean isHorizontal) {
        if (mThreshold >= 0) return mThreshold;
        if (mTailView == null) return 0;
        return isHorizontal ? mTailView.getWidth() : mTailView.getHeight();
    }

    /**
     * 刷新顶部
     */
    public void refreshHeader(boolean isRefresh) {
        isLoading = isRefresh;
        if (mHeaderView == null) return;
        if (isRefresh) refresh(true);
        else refreshCancel();
    }

    /**
     * 刷新底部
     */
    public void refreshTail(boolean isRefresh) {
        isLoading = isRefresh;
        if (mTailView == null) return;
        if (isRefresh) refresh(false);
        else refreshCancel();
    }

    /**
     * 开始刷新
     */
    private void refresh(boolean isHeader) {
        int target = isHeader ? -getHeaderSize(isHorizontal) : getTailSize(isHorizontal);
        int current = isHorizontal ? getScrollX() : getScrollY();
        isLoading = true;
        smoothBy(target - current, isHorizontal);
        OnRefreshListener onRefreshListener = isHeader ? mHeaderOnRefreshListener : mTailOnRefreshListener;
        if (onRefreshListener != null) onRefreshListener.onRefresh();
    }

    /**
     * 取消刷新
     */
    public void refreshCancel() { post(revertRunnable); }

    /**
     * 设置配置项
     */
    public ZRefreshLayout options(Option option) {
        removeView(mHeaderView);
        mHeaderView = option.header;
        addView(mHeaderView, 0);

        removeView(mTailView);
        mTailView = option.tail;
        addView(mTailView, getChildCount());

        isHorizontal = option.orientation == LinearLayout.HORIZONTAL;
        mThreshold = option.threshold;
        return this;
    }

    /**
     * 顶部刷新监听
     */
    public ZRefreshLayout setHeaderOnRefreshListener(OnRefreshListener headerOnRefreshListener) {
        mHeaderOnRefreshListener = headerOnRefreshListener;
        return this;
    }

    /**
     * 底部刷新监听
     */
    public ZRefreshLayout setTailOnRefreshListener(OnRefreshListener tailOnRefreshListener) {
        mTailOnRefreshListener = tailOnRefreshListener;
        return this;
    }

    public interface OnRefreshListener {
        /**
         * 开始上拉或下拉
         *
         * @param offset 距初始位置偏移量
         */
        void onStart(int offset);

        /**
         * 开始刷新
         */
        void onRefresh();

        /**
         * 刷新结束
         */
        void onEnd();
    }

    private enum MoveType {
        ABOVE_UP,//处于上方且向上移动
        ABOVE_DOWN,//处于上方且向下移动
        BELOW_UP,//处于下方且向上移动
        BELOW_DOWN,//处于下方且向下移动
        NO;//未移动

        static MoveType valueOf(int delta, int offset) {
            if (offset >= 0 && delta > 0) return ABOVE_UP;
            else if (offset > 0 && delta < 0) return ABOVE_DOWN;
            else if (offset < 0 && delta > 0) return BELOW_UP;
            else if (offset <= 0 && delta < 0) return BELOW_DOWN;
            else return NO;
        }
    }

    public static class Option {
        private int threshold;
        private int orientation;
        private View header;
        private View tail;

        private Option() {}

        private Option(int threshold, int orientation, View header, View tail) {
            this.threshold = threshold;
            this.orientation = orientation;
            this.header = header;
            this.tail = tail;
        }

        public static Option create() {
            return new Option();
        }

        public Option threshold(int threshold) {
            this.threshold = threshold;
            return this;
        }

        public Option orientation(int orientation) {
            this.orientation = orientation;
            return this;
        }

        public Option header(View header) {
            this.header = header;
            return this;
        }

        public Option tail(View tail) {
            this.tail = tail;
            return this;
        }
    }
}
