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
    }

    public static synchronized void stop(Context context) {
        Context app = context.getApplicationContext();
        Session.stopUsb();
        try {
            app.stopService(new Intent(app, GlassesService.class));
        } catch (Throwable ignored) {}
        running = false;
    }

    public static boolean isRunning() { return running; }

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

    public static boolean glassesConnected() { return Session.glassesDisplayId >= 0; }

    public static String lastError() { return Session.lastError; }
}