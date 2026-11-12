package com.xreal.beamlink;

import android.content.Context;

/**
 * 进程内会话：Activity / 渲染线程 / USB 线程共享同一份配置与引擎。
 */
public final class Session {

    private Session() {}

    public static volatile PoseEngine engine;
    public static volatile XrealUsb usb;
    public static final ImuRecorder recorder = new ImuRecorder();

    public static volatile boolean deviceOpen;
    public static volatile String deviceName = "";
    public static volatile int devicePid;
    public static volatile float packetRateHz;
    public static volatile String lastError = "";

    /** UI 触发、渲染线程消费 */
    public static volatile boolean recenterRequested;

    /** 最近一次统计（18 个 float，顺序见 xr_jni.cpp） */
    public static volatile float[] stats = new float[18];

    /** 最近一帧姿态（行主序 3x3） */
    public static volatile float[] lastRotation = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};

    public static volatile PoseEngine.AnchorMode mode = PoseEngine.AnchorMode.FOLLOW;
    public static volatile float screenDistanceM = 3.0f;
    public static volatile float screenWidthM = 2.6f;
    public static volatile float predictMs = 18.0f;
    public static volatile float followTauSec = 0.12f;
    public static volatile float recenterBlendSec = 0.35f;
    public static volatile boolean yawDriftComp = true;
    public static volatile PoseEngine.AxisMapping axisMapping = PoseEngine.AxisMapping.ROT_Z270;

    /** 视频源（content:// 或 file://）；为空则渲染内置测试图 */
    public static volatile String videoUri;

    /** 副屏 Presentation 是否已成功挂上 */
    public static volatile boolean presentationActive;

    /** 视图基切换：绕视线主轴 90° 步进（0..3），用于纠正陀螺轴与外壳轴不一致 */
    public static volatile int viewYaw90;

    /** 触摸板覆盖层状态 */
    public static volatile boolean touchpadActive;
    public static volatile float cropBottom;
    public static volatile int screenW = 1080;
    public static volatile int screenH = 2400;

    /** 抓屏（MediaProjection）状态 */
    public static volatile boolean captureActive;
    public static volatile android.graphics.SurfaceTexture captureSurfaceTexture;
    public static volatile int captureWidth = 1080;
    public static volatile int captureHeight = 2400;
    public static volatile int captureRotation;
    public static volatile boolean captureRequested;
    /** 单应用抓屏：内容尺寸小于整屏，触摸板黑边不会进画面，渲染侧不再裁剪 */
    public static volatile boolean captureSingleApp;

    /** 眼镜屏与光标 */
    public static volatile int glassesDisplayId = -1;
    /** 引擎服务真实运行状态（由 GlassesService 生命周期置位，供 DeskControl 判断是否走 VD 路由） */
    public static volatile boolean engineRunning;
    /** 引擎自建、承载 App 的 VirtualDisplay（openApp 后才有） */
    public static volatile int appDisplayId = -1;
    public static volatile int glassesWidth = 1920;
    public static volatile int glassesHeight = 1080;
    public static volatile float cursorX = 960f;
    public static volatile float cursorY = 540f;

    /** 渲染侧微调：左右镜像 / 上下翻转 */
    public static volatile boolean mirrorX;
    public static volatile boolean flipY;

    /** true = 左右分屏（双眼面板，如 3840x1080），false = 单画面 */
    public static volatile boolean sideBySide = false;

    public static synchronized PoseEngine ensureEngine() {
        PoseEngine e = engine;
        if (e == null) {
            e = new PoseEngine();
            engine = e;
        }
        pushConfig(e);
        return e;
    }

    public static synchronized void pushConfig() {
        PoseEngine e = engine;
        if (e == null) return;
        pushConfig(e);
    }

    private static void pushConfig(PoseEngine e) {
        e.mode = mode;
        e.screenDistanceM = screenDistanceM;
        e.screenWidthM = screenWidthM;
        e.predictMs = predictMs;
        e.followTauSec = followTauSec;
        e.recenterBlendSec = recenterBlendSec;
        e.yawDriftComp = yawDriftComp;
        e.axisMapping = axisMapping;
        e.applyConfig();
    }

    public static void refreshStats() {
        PoseEngine e = engine;
        if (e != null) stats = e.stats().clone();
    }

    /** 全进程只允许一条 USB 读流；重复调用只替换监听者 */
    public static synchronized XrealUsb startUsb(Context context, XrealUsb.Listener listener) {
        XrealUsb existing = usb;
        if (existing != null) {
            existing.setListener(listener);
            return existing;
        }
        XrealUsb u = new XrealUsb(context.getApplicationContext());
        u.setEngine(ensureEngine());
        u.setListener(listener);
        usb = u;
        u.start();
        return u;
    }

    public static synchronized void stopUsb() {
        XrealUsb u = usb;
        if (u != null) u.stop();
        usb = null;
    }

    public static void recenter() {
        recenterRequested = true;
    }
}
