/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.app.Notification;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.UserHandle;
import android.provider.Settings;
import android.os.Handler;
import android.os.UserHandle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.android.systemui.cm.UserContentObserver;
import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.notification.NotificationHelper;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.util.ArrayList;

public class HeadsUpNotificationView extends FrameLayout implements SwipeHelper.Callback, ExpandHelper.Callback,
        ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = "HeadsUpNotificationView";
    private static final boolean DEBUG = false;
    private static final boolean SPEW = DEBUG;

    Rect mTmpRect = new Rect();
    int[] mTmpTwoArray = new int[2];

    private final int mTouchSensitivityDelay;
    private final float mMaxAlpha = 1f;
    private final ArrayMap<String, Long> mSnoozedPackages;

    private SwipeHelper mSwipeHelper;
    private EdgeSwipeHelper mEdgeSwipeHelper;

    private PhoneStatusBar mBar;

    private long mStartTouchTime;
    private ViewGroup mContentHolder;
    private int mSnoozeLengthMs;
    private boolean mAttached = false;
    private SettingsObserver mSettingsObserver;

    private NotificationData.Entry mHeadsUp;
    private int mUser;
    private String mMostRecentPackageName;

    private boolean mTouchOutside;

    private static int sRoundedRectCornerRadius = 0;

    // Notification helper
    protected NotificationHelper mNotificationHelper;

    public HeadsUpNotificationView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpNotificationView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Resources resources = context.getResources();
        mTouchSensitivityDelay = resources.getInteger(R.integer.heads_up_sensitivity_delay);
        if (DEBUG) Log.v(TAG, "create() " + mTouchSensitivityDelay);
        sRoundedRectCornerRadius = context.getResources().getDimensionPixelSize(
                R.dimen.notification_material_rounded_rect_radius);
        mSnoozedPackages = new ArrayMap<>();
    }

    class SettingsObserver extends UserContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();

            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_NOTIFICATION_SNOOZE),
                    false, this, UserHandle.USER_ALL);
            update();
        }

        @Override
        protected void unobserve() {
            super.unobserve();
            ContentResolver resolver = mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void update() {
            ContentResolver resolver = mContext.getContentResolver();

            mSnoozeLengthMs = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.HEADS_UP_NOTIFICATION_SNOOZE,
                    mContext.getResources().getInteger(
                    R.integer.heads_up_default_snooze_length_ms),
                    UserHandle.USER_CURRENT);
        }
    }

    public void updateResources() {
        if (mContentHolder != null) {
            final LayoutParams lp = (LayoutParams) mContentHolder.getLayoutParams();
            lp.width = getResources().getDimensionPixelSize(R.dimen.notification_panel_width);
            lp.gravity = getResources().getInteger(R.integer.notification_panel_layout_gravity);
            mContentHolder.setLayoutParams(lp);
        }
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public void setNotificationHelper(NotificationHelper notificationHelper) {
		mNotificationHelper = notificationHelper;
    }

    public ViewGroup getHolder() {
        return mContentHolder;
    }

    public boolean showNotification(NotificationData.Entry isHeadsUp) {
        if (mHeadsUp != null && isHeadsUp != null && !mHeadsUp.key.equals(isHeadsUp.key)) {
            // bump any previous heads up back to the shade
            release();
        }

        mHeadsUp = isHeadsUp; // set new entry

        if (mContentHolder != null) {
            mContentHolder.removeAllViews();
        }

        mTouchOutside = false;

        if (mHeadsUp != null) {
            mMostRecentPackageName = mHeadsUp.notification.getPackageName();
            mHeadsUp.row.setSystemExpanded(true);
            mHeadsUp.row.setSensitive(false);
            mHeadsUp.row.setHeadsUp(true);
            mHeadsUp.row.setHideSensitive(
                    false, false /* animated */, 0 /* delay */, 0 /* duration */);
            mHeadsUp.expanded.setOnClickListener(mNotificationHelper.getNotificationClickListener(isHeadsUp, true));
            if (mContentHolder == null) {
                // too soon!
                return false;
            }
            mContentHolder.setX(0);
            mContentHolder.setVisibility(View.VISIBLE);
            mContentHolder.setAlpha(mMaxAlpha);
            mContentHolder.addView(mHeadsUp.row);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);

            mSwipeHelper.snapChild(mContentHolder, 1f);
            mStartTouchTime = SystemClock.elapsedRealtime() + mTouchSensitivityDelay;

            mHeadsUp.setInterruption();

            // 2. Animate mHeadsUpNotificationView in
            mBar.scheduleHeadsUpOpen(TextUtils.equals(
                    mHeadsUp.notification.getNotification().category, Notification.CATEGORY_CALL));

            // 3. Set alarm to age the notification off
            mBar.resetHeadsUpDecayTimer();
        }
        return true;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView.getVisibility() == VISIBLE) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
    }

    public boolean isShowing(String key) {
        return mHeadsUp != null && mHeadsUp.key.equals(key);
    }

    /** Discard the Heads Up notification. */
    public void clear() {
        mHeadsUp = null;
        mBar.scheduleHeadsUpClose();
    }

    /** Respond to dismissal of the Heads Up window. */
    public void dismiss() {
        if (mHeadsUp == null) return;
        if (mHeadsUp.notification.isClearable()) {
            mBar.onNotificationClear(mHeadsUp.notification);
        } else {
            release();
        }
        mHeadsUp = null;
        mBar.scheduleHeadsUpClose();
    }

    /** Push any current Heads Up notification down into the shade. */
    public void release() {
        if (mHeadsUp != null) {
            mBar.displayNotificationFromHeadsUp(mHeadsUp.notification);
        }
        mHeadsUp = null;
    }

    public boolean isSnoozed(String packageName) {
        final String key = snoozeKey(packageName, mUser);
        Long snoozedUntil = mSnoozedPackages.get(key);
        if (snoozedUntil != null) {
            if (snoozedUntil > SystemClock.elapsedRealtime()) {
                if (DEBUG) Log.v(TAG, key + " snoozed");
                return true;
            }
            mSnoozedPackages.remove(packageName);
        }
        return false;
    }

    private void snooze() {
        if (mMostRecentPackageName != null) {
            mSnoozedPackages.put(snoozeKey(mMostRecentPackageName, mUser),
                    SystemClock.elapsedRealtime() + mSnoozeLengthMs);
            if (mSnoozeLengthMs != 0) {
                Toast.makeText(mContext,
                        mContext.getString(R.string.heads_up_snooze_message,
                        mSnoozeLengthMs / 60 / 1000), Toast.LENGTH_LONG).show();
            }
        }
        releaseAndClose();
    }

    private static String snoozeKey(String packageName, int user) {
        return user + "," + packageName;
    }

    public void releaseAndClose() {
        release();
        mBar.scheduleHeadsUpClose();
    }

    public NotificationData.Entry getEntry() {
        return mHeadsUp;
    }

    public boolean isClearable() {
        return mHeadsUp == null || mHeadsUp.notification.isClearable();
    }

    // ViewGroup methods

    private static final ViewOutlineProvider CONTENT_HOLDER_OUTLINE_PROVIDER =
            new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int outlineLeft = view.getPaddingLeft();
            int outlineTop = view.getPaddingTop();

            // Apply padding to shadow.
            outline.setRoundRect(outlineLeft, outlineTop,
                    view.getWidth() - outlineLeft - view.getPaddingRight(),
                    view.getHeight() - outlineTop - view.getPaddingBottom(),
                    sRoundedRectCornerRadius);
        }
    };

    @Override
    public void onAttachedToWindow() {
        if (!mAttached) {
            mAttached = true;

            final ViewConfiguration viewConfiguration = ViewConfiguration.get(getContext());
            float touchSlop = viewConfiguration.getScaledTouchSlop();
            mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, getContext());
            mSwipeHelper.setMaxSwipeProgress(mMaxAlpha);
            mEdgeSwipeHelper = new EdgeSwipeHelper(touchSlop);

            int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
            int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_max_height);

            mContentHolder = (ViewGroup) findViewById(R.id.content_holder);
            mContentHolder.setOutlineProvider(CONTENT_HOLDER_OUTLINE_PROVIDER);

            if (mSettingsObserver == null) {
                mSettingsObserver = new SettingsObserver(new Handler());
            }
            mSettingsObserver.observe();

            if (DEBUG) Log.v(TAG, "mSnoozeLengthMs = " + mSnoozeLengthMs);

            if (mHeadsUp != null) {
                // whoops, we're on already!
                showNotification(mHeadsUp);
            }

            getViewTreeObserver().addOnComputeInternalInsetsListener(this);

            mTouchOutside = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAttached) {
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            mAttached = false;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        if (SystemClock.elapsedRealtime() < mStartTouchTime) {
            return true;
        }
        return mEdgeSwipeHelper.onInterceptTouchEvent(ev)
                || mSwipeHelper.onInterceptTouchEvent(ev)
                || super.onInterceptTouchEvent(ev);
    }

    // View methods

    @Override
    public void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        if (DEBUG) {
            //Log.d(TAG, "onDraw: canvas height: " + c.getHeight() + "px; measured height: "
            //        + getMeasuredHeight() + "px");
            c.save();
            c.clipRect(6, 6, c.getWidth() - 6, getMeasuredHeight() - 6,
                    android.graphics.Region.Op.DIFFERENCE);
            c.drawColor(0xFFcc00cc);
            c.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (SystemClock.elapsedRealtime() < mStartTouchTime) {
            return false;
        }
        switch (ev.getAction()) {
            case MotionEvent.ACTION_OUTSIDE:
                if (mTouchOutside) return true;
                // Hide headsup, after 1 sec.
                mBar.getHandler().postDelayed(new Runnable() {
                    public void run() {
                        mBar.scheduleHeadsUpClose();
                    }
                }, 1000);
                mTouchOutside = true;
                return true;
            default:
                mBar.resetHeadsUpDecayTimer();
                return mEdgeSwipeHelper.onTouchEvent(ev)
                        || mSwipeHelper.onTouchEvent(ev)
                        || super.onTouchEvent(ev);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    // ExpandHelper.Callback methods

    @Override
    public ExpandableView getChildAtRawPosition(float x, float y) {
        return getChildAtPosition(x, y);
    }

    @Override
    public ExpandableView getChildAtPosition(float x, float y) {
        return mHeadsUp == null ? null : mHeadsUp.row;
    }

    @Override
    public boolean canChildBeExpanded(View v) {
        return mHeadsUp != null && mHeadsUp.row == v && mHeadsUp.row.isExpandable();
    }

    @Override
    public void setUserExpandedChild(View v, boolean userExpanded) {
        if (mHeadsUp != null && mHeadsUp.row == v) {
            mHeadsUp.row.setUserExpanded(userExpanded);
        }
    }

    @Override
    public void setUserLockedChild(View v, boolean userLocked) {
        if (mHeadsUp != null && mHeadsUp.row == v) {
            mHeadsUp.row.setUserLocked(userLocked);
        }
    }

    @Override
    public void expansionStateChanged(boolean isExpanding) {

    }

    // SwipeHelper.Callback methods

    @Override
    public boolean canChildBeDismissed(View v) {
        return true;
    }

    @Override
    public boolean isAntiFalsingNeeded() {
        return false;
    }

    @Override
    public float getFalsingThresholdFactor() {
        return 1.0f;
    }

    public void onChildDismissed(View v, boolean direction) {
        if (DEBUG)  Log.v(TAG, "User swiped heads up to dismiss");
        mBar.onHeadsUpDismissed(direction);
    }

    @Override
    public void onChildTriggered(View v) {
    }

    @Override
    public void onBeginDrag(View v) {
        // Prevent any surrounding View from intercepting us now.
        requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public void onDragCancelled(View v) {
        mContentHolder.setAlpha(mMaxAlpha); // sometimes this isn't quite reset
    }

    @Override
    public void onChildSnappedBack(View animView) {
    }

    @Override
    public boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress) {
        getBackground().setAlpha((int) (255 * swipeProgress));
        return false;
    }

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        return mContentHolder;
    }

    @Override
    public View getChildContentView(View v) {
        return mContentHolder;
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        mContentHolder.getLocationOnScreen(mTmpTwoArray);

        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(mTmpTwoArray[0], mTmpTwoArray[1],
                mTmpTwoArray[0] + mContentHolder.getWidth(),
                mTmpTwoArray[1] + mContentHolder.getHeight());
    }

    public void escalate() {
        mBar.scheduleHeadsUpEscalation();
    }

    public String getKey() {
        return mHeadsUp == null ? null : mHeadsUp.notification.getKey();
    }

    public void setUser(int user) {
        mUser = user;
    }

    private class EdgeSwipeHelper implements Gefingerpoken {
        private static final boolean DEBUG_EDGE_SWIPE = false;
        private final float mTouchSlop;
        private boolean mConsuming;
        private float mFirstY;
        private float mFirstX;

        public EdgeSwipeHelper(float touchSlop) {
            mTouchSlop = touchSlop;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (DEBUG_EDGE_SWIPE) Log.d(TAG, "action down " + ev.getY());
                    mFirstX = ev.getX();
                    mFirstY = ev.getY();
                    mConsuming = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (DEBUG_EDGE_SWIPE) Log.d(TAG, "action move " + ev.getY());
                    final float dY = ev.getY() - mFirstY;
                    final float daX = Math.abs(ev.getX() - mFirstX);
                    final float daY = Math.abs(dY);
                    if (!mConsuming && daX < daY && daY > mTouchSlop) {
                        if (dY < 0) {
                            snooze();
                        }
                        if (dY > 0) {
                            if (DEBUG_EDGE_SWIPE) Log.d(TAG, "found an open");
                            mBar.animateExpandNotificationsPanel();
                            mBar.onHeadsUpDismissed(true);
                        }
                        mConsuming = true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (DEBUG_EDGE_SWIPE) Log.d(TAG, "action done" );
                    mConsuming = false;
                    break;
            }
            return mConsuming;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return mConsuming;
        }
    }
}
