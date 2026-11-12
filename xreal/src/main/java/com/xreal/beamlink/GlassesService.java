package com.xreal.beamlink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Presentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * 眼镜屏渲染服务（前台 Service）。
 *
 * 为什么不是 Activity：ColorOS 17 起眼镜屏的 DisplayPolicy 是
 *   standardActivity=false / allowEmbeddedActivity=false
 * 第三方连 shell 都起不了 Activity；只有**窗口**还能挂上去，所以用 Presentation。
 * 更关键的是：用户切到别的 App 时，Activity 会被 stop/destroy，窗口跟着没了；
 * 放进前台 Service 才能"手机玩别的，眼镜一直在"。
 */
public class GlassesService extends Service {

    private static final String TAG = "GlassesService";
    private static final String CHANNEL_ID = "glasses";
    private static final int NOTIFICATION_ID = 42;

    public static final String ACTION_SHOW = "com.xreal.beamlink.SHOW";
    public static final String ACTION_CAPTURE = "com.xreal.beamlink.CAPTURE";
    public static final String ACTION_STOP_CAPTURE = "com.xreal.beamlink.STOP_CAPTURE";
    public static final String ACTION_OPEN_APP = "com.xreal.beamlink.OPEN_APP";
    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager dm;
    private GlassesPresentation presentation;
    private GlassesRenderer renderer;
    private int shownDisplayId = -1;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private VirtualDisplay appDisplay;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { maybeShow(); }
        @Override public void onDisplayRemoved(int displayId) {
            if (displayId == shownDisplayId) { dismissPresentation(); }
        }
        @Override public void onDisplayChanged(int displayId) { }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Session.engineRunning = true;
        startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        dm.registerDisplayListener(displayListener, handler);
        maybeShow();
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("XREAL BeamLink")
                .setContentText("3DoF 渲染中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            // 渠道随卸载被删除；重装后若不在 startForeground 前重建，
            // 会直接抛 CannotPostForegroundServiceNotificationException（Bad notification）
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "眼镜显示", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void startForegroundCompat(int type) {
        ensureChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, type);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startForegroundCompatLegacy() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "眼镜显示", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("XREAL BeamLink")
                .setContentText("3DoF 渲染中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_CAPTURE.equals(action)) {
            startCapture(intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                    intent.getParcelableExtra(EXTRA_RESULT_DATA));
        } else if (ACTION_STOP_CAPTURE.equals(action)) {
            stopCapture();
        } else if (ACTION_OPEN_APP.equals(action)) {
            openApp(intent.getStringExtra(EXTRA_PACKAGE));
        } else {
            maybeShow();
        }
        return START_STICKY;
    }

    // ---------------- 副屏窗口 ----------------
    private void maybeShow() {
        Display target = pickDisplay();
        if (target == null) {
            Log.i(TAG, "no presentation display yet");
            return;
        }
        if (presentation != null && shownDisplayId == target.getDisplayId()) return;
        dismissPresentation();
        try {
            final GlassesPresentation p = new GlassesPresentation(this, target);
            p.show();
            p.resume();
            presentation = p;
            renderer = p.getRenderer();
            shownDisplayId = target.getDisplayId();
            Session.glassesDisplayId = shownDisplayId;
            Session.glassesWidth = target.getWidth();
            Session.glassesHeight = target.getHeight();
            Session.cursorX = Session.glassesWidth / 2f;
            Session.cursorY = Session.glassesHeight / 2f;
            Log.i(TAG, "presentation shown on display " + shownDisplayId
                    + " (" + target.getName() + ")");
        } catch (Throwable t) {
            Log.e(TAG, "presentation failed on display " + target.getDisplayId(), t);
            Session.lastError = "副屏窗口创建失败: " + t.getClass().getSimpleName();
        }
    }

    private Display pickDisplay() {
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : ds) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getState() != Display.STATE_OFF) return d;
        }
        // 有些 ROM 不给 PRESENTATION 分类，退回全量扫描
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getState() != Display.STATE_OFF) return d;
        }
        return null;
    }

    private void dismissPresentation() {
        GlassesPresentation p = presentation;
        presentation = null;
        shownDisplayId = -1;
        Session.glassesDisplayId = -1;
        if (p != null) {
            try { p.pause(); } catch (Throwable ignored) {}
            try { p.dismiss(); } catch (Throwable ignored) {}
        }
        renderer = null;
    }

    // ---------------- 抓屏 ----------------
    private void startCapture(int resultCode, Intent data) {
        if (data == null) {
            Log.e(TAG, "capture: no data");
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mgr.getMediaProjection(resultCode, data);
        } catch (Throwable t) {
            Log.e(TAG, "getMediaProjection failed", t);
            return;
        }
        if (projection == null) {
            Log.e(TAG, "projection null");
            return;
        }
        // 拿到 projection token 之后才能把 FGS 类型升级成 mediaProjection
        startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                Log.i(TAG, "projection stopped");
                stopCapture();
            }

            @Override public void onCapturedContentResize(int width, int height) {
                // API 34：整屏抓屏给屏幕尺寸；单应用抓屏给被选窗口的尺寸
                android.graphics.Point size = new android.graphics.Point();
                dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size);
                boolean single = width * (long) height < size.x * (long) size.y;
                Session.captureSingleApp = single;
                Log.i(TAG, "captured content " + width + "x" + height
                        + (single ? " (single app, 不裁剪黑边)" : " (full screen)"));
            }
        }, handler);
        if (!ensureCaptureSurface()) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (createVirtualDisplay()) Session.captureActive = true; }
            }, 800);
        } else {
            createVirtualDisplay();
        }
    }

    private boolean ensureCaptureSurface() {
        return Session.captureSurfaceTexture != null;
    }

    private boolean createVirtualDisplay() {
        SurfaceTexture st = Session.captureSurfaceTexture;
        if (st == null || projection == null) {
            Log.e(TAG, "capture surface not ready");
            return false;
        }
        Display def = dm.getDisplay(Display.DEFAULT_DISPLAY);
        android.graphics.Point size = new android.graphics.Point();
        def.getRealSize(size);
        int w = size.x, h = size.y;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        st.setDefaultBufferSize(w, h);
        Surface surface = new Surface(st);
        stopVirtualDisplayOnly();
        virtualDisplay = projection.createVirtualDisplay(
                "xreal-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, handler);
        Session.captureWidth = w;
        Session.captureHeight = h;
        Session.captureRotation = def.getRotation();
        Session.captureSingleApp = false;
        Session.captureActive = virtualDisplay != null;
        Log.i(TAG, "virtual display " + w + "x" + h + " rot=" + Session.captureRotation
                + " ok=" + Session.captureActive);
        return Session.captureActive;
    }

    private void stopVirtualDisplayOnly() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        Session.captureActive = false;
    }

    private void stopCapture() {
        stopVirtualDisplayOnly();
        stopAppDisplay();
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
    }

    /** 把某个 App 直接启动到引擎自己的 1920x1080 VirtualDisplay 上，Surface 喂给 3DoF 渲染器。
     *  不再经过 MediaProjection / 手机屏幕抓取（hook 解除限制后这条才成立）。 */
    private void openApp(final String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        SurfaceTexture st = Session.captureSurfaceTexture;
        if (st == null) {
            // 渲染器还没把外部纹理建好，稍后再试
            handler.postDelayed(new Runnable() {
                @Override public void run() { openApp(pkg); }
            }, 300);
            return;
        }
        stopCapture();
        final int w = 1920, h = 1080;
        st.setDefaultBufferSize(w, h);
        try {
            appDisplay = dm.createVirtualDisplay("xreal-app", w, h, 320,
                    new android.view.Surface(st),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null, handler);
        } catch (Throwable t) {
            Log.e(TAG, "create app display failed", t);
            return;
        }
        Session.captureWidth = w;
        Session.captureHeight = h;
        Session.captureRotation = 0;
        Session.captureSingleApp = false;
        Session.captureActive = true;
        Session.appDisplayId = appDisplay.getDisplay().getDisplayId();
        // createVirtualDisplay 是异步注册的；马上 startActivity 会找不到这块 display
        handler.postDelayed(new Runnable() {
            @Override public void run() { launchOnAppDisplay(pkg); }
        }, 300);
    }

    private void launchOnAppDisplay(String pkg) {
        if (appDisplay == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.e(TAG, "no launch intent for " + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(Session.appDisplayId);
        try {
            startActivity(launch, opts.toBundle());
            Log.i("VdApp", "launched " + pkg + " on display " + Session.appDisplayId);
        } catch (Throwable t) {
            Log.e("VdApp", "launch failed: " + pkg, t);
        }
    }

    private void stopAppDisplay() {
        Session.appDisplayId = -1;
        if (appDisplay != null) {
            try { appDisplay.release(); } catch (Throwable ignored) {}
            appDisplay = null;
        }
    }

    @Override
    public void onDestroy() {
        Session.engineRunning = false;
        stopCapture();
        dismissPresentation();
        try { dm.unregisterDisplayListener(displayListener); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
