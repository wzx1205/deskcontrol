package com.xreal.beamlink;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;

/**
 * DeskControl 里唯一的对外入口：起/停「XREAL 3DoF 巨幕」。
 *
 * 这里只提供引擎能力（IMU 采集 → 姿态 → 眼镜屏渲染），不自带任何 UI、触摸板和光标：
 * 那些由 DeskControl 自己的 TouchpadActivity / CursorOverlayView / ControlAccessibilityService 负责。
 */
public final class XrealEngine {

    private XrealEngine() {}

    private static volatile boolean running;

    /** 起前台渲染服务 + IMU 采集；眼镜没插也能起，插上后自动找到外接屏 */
    public static synchronized void start(Context context) {
        final Context app = context.getApplicationContext();
        app.startForegroundService(new Intent(app, GlassesService.class)
                .setAction(GlassesService.ACTION_SHOW));
        Session.startUsb(app, new XrealUsb.Listener() {
            @Override public void onDevice(UsbDevice device) {
                Session.deviceOpen = true;
                try { Session.deviceName = device.getProductName(); } catch (Throwable ignored) {}
            }
            @Override public void onPacketRate(float hz) { Session.packetRateHz = hz; }
            @Override public void onError(String message) { Session.lastError = message; }
        });
        running = true;
        XrealHooks.enableAsync(app);
    }

    public static synchronized void stop(Context context) {
        Context app = context.getApplicationContext();
        XrealHooks.disableAsync(app);
        Session.stopUsb();
        try {
            app.stopService(new Intent(app, GlassesService.class));
        } catch (Throwable ignored) {}
        running = false;
    }

    public static boolean isRunning() { return Session.engineRunning; }

    /** 重定心：把当前朝向设为巨幕正前方 */
    public static void recenter() { Session.recenter(); }

    public static void setMode(PoseEngine.AnchorMode mode) {
        Session.mode = mode;
        Session.pushConfig();
    }

    public static void setScreenSize(float distanceM, float widthM) {
        Session.screenDistanceM = distanceM;
        Session.screenWidthM = widthM;
        Session.pushConfig();
    }

    public static void setPredictMs(float ms) {
        Session.predictMs = ms;
        Session.pushConfig();
    }

    /** 运行状态：{yaw, pitch, roll, imuHz, ...}，顺序见 PoseEngine.stats() */
    public static float[] stats() {
        Session.refreshStats();
        return Session.stats;
    }

    /** 把某个 App 直接起在引擎自己的 1920x1080 VirtualDisplay 上（不经过手机抓屏）。
     *  hook 生效时才能成；失败会在 logcat 里留 VdApp 记录。 */
    public static void openApp(Context context, String pkg) {
        Context app = context.getApplicationContext();
        app.startForegroundService(new Intent(app, GlassesService.class)
                .setAction(GlassesService.ACTION_OPEN_APP)
                .putExtra(GlassesService.EXTRA_PACKAGE, pkg));
    }

    public static boolean glassesConnected() { return Session.glassesDisplayId >= 0; }

    /** 引擎自建、承载 App 的 VirtualDisplay id；没调用过 openApp 时是 -1 */
    public static int appDisplayId() { return Session.appDisplayId; }

    public static String lastError() { return Session.lastError; }
}