package com.xreal.beamlink;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

/**
 * 副屏路由：把渲染窗口送到 XREAL 眼镜那块外接屏。
 *
 * 两条路径（按顺序尝试）：
 *   1) ActivityOptions.setLaunchDisplayId() —— 标准做法。AOSP 可用；
 *      但 Find N5 的 ColorOS 对第三方 App 直接抛 SecurityException（连 uid=0 的
 *      `am start --display 34` 都会被拒），所以需要兜底。
 *   2) Presentation（window 层）—— 只要拿到 SYSTEM_ALERT_WINDOW，就能往副屏加窗口，
 *      不经过 ActivityTaskManager 的 display 归属检查。
 */
public class DisplayRouter {

    private static final String TAG = "DisplayRouter";
    public static final String EXTRA_DISPLAY_ID = "display_id";

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisplayManager dm;
    private static int launchedDisplayId = -1;
    private static GlassesPresentation presentation;
    private static boolean launching;

    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            Log.i(TAG, "display added: " + displayId);
            scan();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            Log.i(TAG, "display removed: " + displayId);
            if (launchedDisplayId == displayId) {
                launchedDisplayId = -1;
                dismissPresentation();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
        }
    };

    public DisplayRouter(Activity activity) {
        this.activity = activity;
        this.dm = (DisplayManager) activity.getSystemService(Activity.DISPLAY_SERVICE);
    }

    public void start() {
        dm.registerDisplayListener(listener, handler);
        scan();
    }

    public void stop() {
        try {
            dm.unregisterDisplayListener(listener);
        } catch (Throwable ignored) {
        }
        dismissPresentation();
    }

    public void scan() {
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display target = null;
        for (Display d : displays) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            if (d.getState() == Display.STATE_OFF) continue;
            Log.i(TAG, "presentation display: id=" + d.getDisplayId() + " name=" + d.getName()
                    + " " + d.getMode().getPhysicalWidth() + "x" + d.getMode().getPhysicalHeight()
                    + " @" + d.getRefreshRate());
            if (target == null) target = d;
        }
        if (target == null) return;
        if (launching) return;
        if (target.getDisplayId() == launchedDisplayId && presentation != null && presentation.isShowing()) return;
        launchOn(target);
    }

    private void launchOn(final Display display) {
        launching = true;
        Intent intent = new Intent(activity, GlassesActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_DISPLAY_ID, display.getDisplayId());
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display.getDisplayId());
        try {
            activity.startActivity(intent, options.toBundle());
            launchedDisplayId = display.getDisplayId();
            Log.i(TAG, "launched GlassesActivity on display " + display.getDisplayId());
            return;
        } catch (SecurityException se) {
            Log.w(TAG, "setLaunchDisplayId 被拒（ColorOS 限制），改用 Presentation: " + se.getMessage());
            launching = false;
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
        }
        showPresentation(display);
    }

    private void showPresentation(final Display display) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    dismissPresentation();
                    GlassesPresentation p = new GlassesPresentation(activity, display);
                    p.show();
                    p.resume();
                    presentation = p;
                    launchedDisplayId = display.getDisplayId();
                    Session.presentationActive = true;
                    Log.i(TAG, "presentation shown on display " + display.getDisplayId());
                } catch (Throwable t) {
                    launching = false;
                    Session.presentationActive = false;
                    Session.lastError = "副屏窗口被拒: " + t.getClass().getSimpleName() + " " + t.getMessage();
                    Log.e(TAG, "presentation failed", t);
                }
            }
        });
    }

    public void dismissPresentation() {
        launching = false;
        final GlassesPresentation p = presentation;
        presentation = null;
        Session.presentationActive = false;
        if (p != null) {
            handler.post(new Runnable() {
                @Override public void run() {
                    try { p.dismiss(); } catch (Throwable ignored) {}
                }
            });
        }
    }
}
