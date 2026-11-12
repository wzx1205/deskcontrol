package com.xreal.beamlink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** 眼镜屏上的光标：不吃触摸、不抢焦点的小箭头，位置由触摸板相对位移驱动。 */
final class CursorOverlay {

    private static final int SIZE = 64;

    private View view;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private int displayId = -1;

    void show(Context ctx, int did) {
        if (view != null && displayId == did) return;
        dismiss();
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(did);
        if (d == null) return;
        Context dc = ctx.createDisplayContext(d);
        wm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
        view = new CursorView(dc);
        lp = new WindowManager.LayoutParams(SIZE, SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(view, lp);
        displayId = did;
        moveTo(Session.cursorX, Session.cursorY);
    }

    void moveTo(float x, float y) {
        if (wm == null || view == null) return;
        lp.x = (int) x;
        lp.y = (int) y;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
    }

    void dismiss() {
        if (wm != null && view != null) {
            try { wm.removeView(view); } catch (Throwable ignored) {}
        }
        view = null;
        wm = null;
        lp = null;
        displayId = -1;
    }

    private static final class CursorView extends View {
        CursorView(Context c) { super(c); }

        @Override protected void onDraw(Canvas canvas) {
            Path path = new Path();
            path.moveTo(6, 4);
            path.lineTo(6, 46);
            path.lineTo(18, 34);
            path.lineTo(26, 52);
            path.lineTo(34, 48);
            path.lineTo(26, 30);
            path.lineTo(42, 28);
            path.close();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5f);
            p.setColor(Color.BLACK);
            canvas.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            canvas.drawPath(path, p);
        }
    }
}
