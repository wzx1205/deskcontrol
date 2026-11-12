package com.xreal.beamlink;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 极简手势注入服务：只做一件事——把触摸板的动作翻译成系统手势。
 * 不需要读取屏幕内容（canRetrieveWindowContent=false），只申请 canPerformGestures。
 */
public class GestureAccessibilityService extends AccessibilityService {

    private static final String TAG = "GestureA11y";
    private static volatile GestureAccessibilityService instance;

    public static boolean isReady() { return instance != null; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "connected");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    /** 在 (x,y) 点一下；displayId >= 0 时打到指定屏（眼镜屏），否则默认屏 */
    public static void tap(float x, float y) { tap(x, y, -1); }

    public static void tap(float x, float y, int displayId) {
        GestureAccessibilityService s = instance;
        if (s == null) return;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 40));
        applyDisplayId(b, displayId);
        boolean ok = s.dispatchGesture(b.build(), null, null);
        Log.i(TAG, "tap " + (int) x + "," + (int) y + " display=" + displayId + " dispatched=" + ok);
    }

    /** 从 (x1,y1) 划到 (x2,y2)，时长 durationMs */
    public static void swipe(float x1, float y1, float x2, float y2, int durationMs) {
        swipe(x1, y1, x2, y2, durationMs, -1);
    }

    public static void swipe(float x1, float y1, float x2, float y2, int durationMs, int displayId) {
        GestureAccessibilityService s = instance;
        if (s == null) return;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(20, durationMs)));
        applyDisplayId(b, displayId);
        boolean ok = s.dispatchGesture(b.build(), null, null);
        Log.i(TAG, "swipe display=" + displayId + " dispatched=" + ok);
    }

    /** DeskControl 同款：GestureDescription.Builder.setDisplayId(int)，低版本没有就退回默认屏 */
    private static void applyDisplayId(GestureDescription.Builder b, int displayId) {
        if (displayId < 0) return;
        try {
            b.getClass().getMethod("setDisplayId", int.class).invoke(b, displayId);
        } catch (Throwable t) {
            Log.w(TAG, "setDisplayId 不可用，退回默认屏: " + t);
        }
    }

    /** 返回：系统返回键 */
    public static void back() {
        GestureAccessibilityService s = instance;
        if (s == null) return;
        s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static void home() {
        GestureAccessibilityService s = instance;
        if (s == null) return;
        s.performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
