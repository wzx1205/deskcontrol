package com.xreal.beamlink;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 触摸板覆盖层（DeskControl 那套思路）：
 *
 *   · 底部一块可见区域，覆盖在正在播放的 App 上（App 保持前台/焦点，抓屏才有画面）
 *   · 单指滑动 → 移动眼镜屏上的光标；单击 → 在光标处点击
 *   · 双指拖动 → 滚动；双指双击 → 返回
 *
 *  两个关键属性：
 *    FLAG_SECURE        —— 这一块不会出现在 MediaProjection 的抓屏里（避免虚拟屏底部被挡）
 *    screenBrightness   —— 压暗但保持可见，屏幕逻辑上仍然点亮，抓屏持续有画面（伪息屏）
 *
 *  手势打到哪块屏：
 *    抓屏模式（眼镜里是手机画面）→ 映射回手机屏幕坐标，注入默认屏；
 *    原生 Activity 模式          → 直接注入眼镜屏（GestureDescription.setDisplayId）。
 */
public class TouchPadService extends Service {

    private static final String TAG = "TouchPad";
    private WindowManager wm;
    private View pad;
    private WindowManager.LayoutParams lp;
    private int padHeight;
    private final CursorOverlay cursor = new CursorOverlay();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable cursorSync = new Runnable() {
        @Override public void run() {
            int gd = Session.glassesDisplayId;
            if (gd >= 0) cursor.show(TouchPadService.this, gd);
            else cursor.dismiss();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        padHeight = (int) (sh * 0.30f);

        pad = new PadView();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, padHeight,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        // 伪息屏但不能全黑：否则用户连触摸板边框都看不见（2026-11-12 反馈）
        lp.screenBrightness = 0.25f;
        wm.addView(pad, lp);

        Session.touchpadActive = true;
        Session.cropBottom = (float) padHeight / (float) sh;
        Session.screenW = sw;
        Session.screenH = sh;
        handler.post(cursorSync);
        Log.i(TAG, "pad added h=" + padHeight + " screen=" + sw + "x" + sh);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(cursorSync);
        cursor.dismiss();
        Session.touchpadActive = false;
        Session.cropBottom = 0f;
        if (pad != null) {
            try { wm.removeView(pad); } catch (Throwable ignored) {}
            pad = null;
        }
        super.onDestroy();
    }

    private void moveCursorBy(float dx, float dy) {
        // 触摸板到眼镜屏按面积放大，手感接近 DeskControl 的相对触控板
        float k = 1.8f;
        float x = Math.max(0f, Math.min(Session.glassesWidth - 8f, Session.cursorX + dx * k));
        float y = Math.max(0f, Math.min(Session.glassesHeight - 8f, Session.cursorY + dy * k));
        Session.cursorX = x;
        Session.cursorY = y;
        cursor.moveTo(x, y);
    }

    private void clickAtCursor() {
        int gd = Session.glassesDisplayId;
        float x = Session.cursorX;
        float y = Session.cursorY;
        if (Session.captureActive || gd < 0) {
            // 抓屏模式：眼镜里放的是手机画面，把眼镜上的位置等比映射回手机
            float tx = x / Session.glassesWidth * Session.screenW;
            float ty = y / Session.glassesHeight * Session.screenH;
            GestureAccessibilityService.tap(tx, ty);
        } else {
            GestureAccessibilityService.tap(x, y, gd);
        }
    }

    private void doScroll(float dy) {
        int gd = (Session.captureActive || Session.glassesDisplayId < 0) ? -1 : Session.glassesDisplayId;
        float cx = (gd >= 0 ? Session.glassesWidth : Session.screenW) / 2f;
        float cy = (gd >= 0 ? Session.glassesHeight : Session.screenH) / 2f;
        // 手指上划 = 内容上滚 = 向下注入滑动
        GestureAccessibilityService.swipe(cx, cy - dy, cx, cy + dy, 220, gd);
    }

    private final class PadView extends View {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        private float lastX, lastY;
        private long downMs;
        private int activePointers;

        PadView() {
            super(TouchPadService.this);
            bar.setColor(0x99000000);
            border.setColor(0xCCFFFFFF);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(3f);
            text.setColor(0xEEFFFFFF);
            text.setTextSize(30f);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            RectF r = new RectF(20, 20, getWidth() - 20, getHeight() - 20);
            c.drawRoundRect(r, 36, 36, bar);
            c.drawRoundRect(r, 36, 36, border);
            c.drawText("单指移动光标 · 单击=确定 · 双指拖动=滚动 · 双指双击=返回", 48, 78, text);
            c.drawText("光标 " + (int) Session.cursorX + "," + (int) Session.cursorY
                    + "   目标屏 " + (Session.captureActive ? "手机(抓屏)" : "眼镜"), 48, 128, text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = e.getX();
                    downY = lastY = e.getY();
                    downMs = System.currentTimeMillis();
                    activePointers = 1;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    activePointers = e.getPointerCount();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - lastX;
                    float dy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    if (activePointers <= 1) moveCursorBy(dx, dy);
                    else doScroll(dy);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    long dt = System.currentTimeMillis() - downMs;
                    float dist = (float) Math.hypot(dx, dy);
                    if (activePointers >= 2 && dist < 60f && dt < 400) {
                        GestureAccessibilityService.back();
                    } else if (activePointers == 1 && dist < 40f && dt < 400) {
                        clickAtCursor();
                    }
                    activePointers = 0;
                    invalidate();
                    return true;
                }
            }
            return true;
        }
    }
}
