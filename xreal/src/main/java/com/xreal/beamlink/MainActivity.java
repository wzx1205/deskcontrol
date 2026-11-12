package com.xreal.beamlink;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * 手机主屏控制台：设备状态、锚定模式、屏幕尺寸/距离、预测补偿、轴映射、视频源。
 * 眼镜屏上的 GlassesActivity 由 DisplayRouter 自动拉起。
 */
public class MainActivity extends Activity implements XrealUsb.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private DisplayRouter router;
    private int uiTick;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            Session.refreshStats();
            updateStatus();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Session.ensureEngine();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setBackgroundColor(Color.parseColor("#101014"));

        TextView title = new TextView(this);
        title.setText("XREAL BeamLink · Air 2 Pro 3DoF 云台");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9EE7A0"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        root.addView(button("重定心（Recenter）", new Runnable() {
            @Override public void run() { Session.recenter(); }
        }));

        root.addView(button("切换锚定模式", new Runnable() {
            @Override public void run() {
                Session.mode = Session.mode.next();
                Session.pushConfig();
            }
        }));

        root.addView(button("屏幕更远 +0.5m", new Runnable() {
            @Override public void run() {
                Session.screenDistanceM = Math.min(12f, Session.screenDistanceM + 0.5f);
                Session.pushConfig();
            }
        }));

        root.addView(button("屏幕更近 -0.5m", new Runnable() {
            @Override public void run() {
                Session.screenDistanceM = Math.max(1f, Session.screenDistanceM - 0.5f);
                Session.pushConfig();
            }
        }));

        root.addView(button("屏幕更大 +0.2m", new Runnable() {
            @Override public void run() {
                Session.screenWidthM = Math.min(8f, Session.screenWidthM + 0.2f);
                Session.pushConfig();
            }
        }));

        root.addView(button("屏幕更小 -0.2m", new Runnable() {
            @Override public void run() {
                Session.screenWidthM = Math.max(0.8f, Session.screenWidthM - 0.2f);
                Session.pushConfig();
            }
        }));

        root.addView(button("预测补偿 +2ms", new Runnable() {
            @Override public void run() {
                Session.predictMs = Math.min(50f, Session.predictMs + 2f);
                Session.pushConfig();
            }
        }));

        root.addView(button("预测补偿 -2ms", new Runnable() {
            @Override public void run() {
                Session.predictMs = Math.max(0f, Session.predictMs - 2f);
                Session.pushConfig();
            }
        }));

        root.addView(button("跟随更跟手（tau -20ms）", new Runnable() {
            @Override public void run() {
                Session.followTauSec = Math.max(0.03f, Session.followTauSec - 0.02f);
                Session.pushConfig();
                Session.lastError = String.format(Locale.US, "跟随 tau = %.3f s", Session.followTauSec);
            }
        }));

        root.addView(button("跟随更平滑（tau +20ms）", new Runnable() {
            @Override public void run() {
                Session.followTauSec = Math.min(1.0f, Session.followTauSec + 0.02f);
                Session.pushConfig();
                Session.lastError = String.format(Locale.US, "跟随 tau = %.3f s", Session.followTauSec);
            }
        }));

        root.addView(button("切换轴映射（抬头自转/歪头上下跑就按它）", new Runnable() {
            @Override public void run() {
                Session.axisMapping = Session.axisMapping.next();
                PoseEngine e = Session.engine;
                if (e != null) e.reset();
                Session.pushConfig();
                Session.recenter();
                Session.lastError = "轴映射: " + Session.axisMapping.label;
            }
        }));

        root.addView(button("切换视图基 90°（抬头变成自转时按这个）", new Runnable() {
            @Override public void run() {
                Session.viewYaw90 = (Session.viewYaw90 + 1) % 4;
                Session.lastError = "viewYaw90 = " + Session.viewYaw90;
            }
        }));

        root.addView(button("左右镜像（画面左右反了用）", new Runnable() {
            @Override public void run() { Session.mirrorX = !Session.mirrorX; }
        }));

        root.addView(button("上下翻转（画面上下反了用）", new Runnable() {
            @Override public void run() { Session.flipY = !Session.flipY; }
        }));

        root.addView(button("切换 单画面 / 左右分屏", new Runnable() {
            @Override public void run() { Session.sideBySide = !Session.sideBySide; }
        }));

        root.addView(button("开 / 关 触摸板（底部覆盖层 + 伪息屏）", new Runnable() {
            @Override public void run() {
                if (Session.touchpadActive) {
                    stopService(new Intent(MainActivity.this, TouchPadService.class));
                    Session.lastError = "触摸板已关闭";
                } else {
                    if (!android.provider.Settings.canDrawOverlays(MainActivity.this)) {
                        Session.lastError = "先授予「显示在其他应用上层」";
                        return;
                    }
                    startService(new Intent(MainActivity.this, TouchPadService.class));
                    Session.lastError = GestureAccessibilityService.isReady()
                            ? "触摸板已开启" : "触摸板已开启，但无障碍未开（手势注入无效）";
                }
            }
        }));

        root.addView(button("Root 一键授权（悬浮窗 + 无障碍）", new Runnable() {
            @Override public void run() {
                RootHelper.Result r = RootHelper.grantAll(getPackageName());
                Session.lastError = (r.ok ? "Root 授权成功" : "Root 授权失败") + ": "
                        + r.output.replace("\n", " | ");
            }
        }));

        root.addView(button("打开无障碍设置（手势注入需要）", new Runnable() {
            @Override public void run() {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    Session.lastError = "打开无障碍设置失败: " + t;
                }
            }
        }));

        root.addView(button("开始 / 停止抓屏（把任意 App 放进 3DoF）", new Runnable() {
            @Override public void run() {
                if (Session.captureActive) {
                    startService(new Intent(MainActivity.this, GlassesService.class)
                            .setAction(GlassesService.ACTION_STOP_CAPTURE));
                    Session.captureActive = false;
                    Session.lastError = "抓屏已停止";
                } else {
                    android.media.projection.MediaProjectionManager mpm =
                            (android.media.projection.MediaProjectionManager)
                                    getSystemService(MEDIA_PROJECTION_SERVICE);
                    // API 34+ 允许"只抓一个应用"，避免整屏（含触摸板黑边）被抓进虚拟巨幕
                    Intent captureIntent = android.os.Build.VERSION.SDK_INT >= 34
                            ? mpm.createScreenCaptureIntent(
                                    android.media.projection.MediaProjectionConfig.createConfigForUserChoice())
                            : mpm.createScreenCaptureIntent();
                    startActivityForResult(captureIntent, 1001);
                }
            }
        }));

        root.addView(button("选择视频文件", new Runnable() {
            @Override public void run() { pickVideo(); }
        }));

        root.addView(button("清除视频（回到测试图）", new Runnable() {
            @Override public void run() {
                Session.videoUri = null;
                restartGlasses();
            }
        }));

        root.addView(button("开始 / 停止记录 IMU（抓问题用）", new Runnable() {
            @Override public void run() {
                if (Session.recorder.isRecording()) {
                    Session.recorder.stop();
                    Session.lastError = "记录已保存: " + Session.recorder.path();
                } else if (Session.recorder.start(MainActivity.this)) {
                    Session.lastError = "开始记录: " + Session.recorder.path();
                } else {
                    Session.lastError = "记录启动失败";
                }
            }
        }));

        root.addView(button("授予悬浮窗权限（副屏 Presentation 必需）", new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    Session.lastError = "无法打开悬浮窗设置: " + t.getMessage();
                }
            }
        }));

        root.addView(button("重新检测眼镜屏 / 重开渲染", new Runnable() {
            @Override public void run() {
                router.scan();
                restartGlasses();
            }
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        // 副屏渲染交给前台 Service（Activity 切后台也不会掉）
        startForegroundService(new Intent(this, GlassesService.class).setAction(GlassesService.ACTION_SHOW));
        Session.startUsb(this, this);

        // 诊断只在显式要求时跑：am start -n com.xreal.beamlink/.MainActivity --ez diag true
        // （它会往副屏拉起 Settings/DeskControl，平时自动跑会把用户的眼镜屏打乱）
        if (getIntent().getBooleanExtra("diag", false)) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { diagnosticLaunchTest(); }
            }, 1500);
            handler.postDelayed(new Runnable() {
                @Override public void run() { diagnosticVirtualDisplayTest(); }
            }, 2500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /** 依次尝试把"自己 / 其它 App"启动到眼镜屏，结果打到 logcat */
    private void diagnosticLaunchTest() {
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display target = null;
        for (android.view.Display d : dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) { target = d; break; }
        }
        if (target == null) {
            android.util.Log.i("LaunchDiag", "no external display");
            return;
        }
        int displayId = target.getDisplayId();
        String[] pkgs = {"com.android.settings", "com.deskcontrol", "com.android.launcher"};
        java.util.List<android.content.Intent> intents = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        intents.add(new android.content.Intent(this, GlassesActivity.class));
        names.add("self");
        for (String p : pkgs) {
            android.content.Intent li = getPackageManager().getLaunchIntentForPackage(p);
            if (li == null) {
                android.util.Log.i("LaunchDiag", p + " -> no launch intent (visibility?)");
                continue;
            }
            intents.add(li);
            names.add(p);
        }
        for (int i = 0; i < intents.size(); i++) {
            String c0 = names.get(i);
            android.content.Intent it = intents.get(i);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            try {
                startActivity(it, opts.toBundle());
                android.util.Log.i("LaunchDiag", c0 + " (" + it.getComponent() + ") -> OK on display " + displayId);
            } catch (Throwable e) {
                android.util.Log.i("LaunchDiag", c0 + " (" + it.getComponent() + ") -> FAIL "
                        + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }
    }

    /** 测试：App 自建 VirtualDisplay 能不能被 DisplayPolicy 放行（私有屏 owner 例外） */
    private void diagnosticVirtualDisplayTest() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.media.ImageReader reader = android.media.ImageReader.newInstance(
                    1280, 720, android.graphics.PixelFormat.RGBA_8888, 2);
            int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            android.hardware.display.VirtualDisplay vd = dm.createVirtualDisplay(
                    "beamlink-test", 1280, 720, 160, reader.getSurface(), flags, null, null);
            int vdId = vd.getDisplay().getDisplayId();
            android.util.Log.i("VdDiag", "virtual display id=" + vdId + " name=" + vd.getDisplay().getName());

            android.content.Intent it = getPackageManager().getLaunchIntentForPackage("com.android.settings");
            if (it == null) {
                android.util.Log.i("VdDiag", "no launch intent for settings");
            } else {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(vdId);
                try {
                    startActivity(it, opts.toBundle());
                    android.util.Log.i("VdDiag", "launch settings on VD -> OK");
                } catch (Throwable e) {
                    android.util.Log.i("VdDiag", "launch settings on VD -> FAIL "
                            + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
                }
            }

            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        android.media.Image img = reader.acquireLatestImage();
                        android.util.Log.i("VdDiag", "frames on VD = " + (img != null ? "YES" : "NO"));
                        if (img != null) img.close();
                    } catch (Throwable e) {
                        android.util.Log.i("VdDiag", "reader error " + e);
                    }
                }
            }, 2500);
        } catch (Throwable e) {
            android.util.Log.i("VdDiag", "create VD failed: " + e);
        }
    }

    private void restartGlasses() {
        PoseEngine e = Session.engine;
        if (e != null) e.reset();
        Session.recenter();
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, 42);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, GlassesService.class).setAction(GlassesService.ACTION_CAPTURE);
                svc.putExtra(GlassesService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(GlassesService.EXTRA_RESULT_DATA, data);
                startForegroundService(svc);
                Session.lastError = "抓屏已启动";
            } else {
                Session.lastError = "抓屏授权被拒绝";
            }
            return;
        }
        if (requestCode == 42 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(
                        data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            Session.videoUri = data.getData().toString();
            restartGlasses();
        }
    }

    private void updateStatus() {
        float[] s = Session.stats;
        StringBuilder sb = new StringBuilder();
        sb.append("眼镜: ").append(Session.deviceOpen ? Session.deviceName : "未连接").append('\n');
        sb.append(String.format(Locale.US, "IMU 帧率: %.1f Hz%n", Session.packetRateHz));
        sb.append("样本: ").append((long) s[0])
          .append("  坏帧: ").append((long) s[14])
          .append("  上电帧: ").append((long) s[15]).append('\n');
        sb.append(String.format(Locale.US, "温度: %.1f °C%n", s[3]));
        sb.append(String.format(Locale.US, "零偏: %.2f / %.2f / %.2f dps%n", s[4], s[5], s[6]));
        sb.append(String.format(Locale.US, "姿态 yaw/pitch/roll: %.1f / %.1f / %.1f °%n", s[10], s[11], s[12]));
        sb.append("初始化中: ").append(s[8] > 0.5f).append("   加计被拒: ").append(s[9] > 0.5f).append('\n');
        sb.append(String.format(Locale.US, "比例系数: gyro %.6f dps/LSB, accel %.8f g/LSB%n", s[16], s[17]));
        sb.append(String.format(Locale.US, "跟随 tau %.3fs", Session.followTauSec)).append('\n');
        sb.append("模式: ").append(Session.mode.label)
          .append(String.format(Locale.US, "   距离 %.1fm   宽 %.1fm%n", Session.screenDistanceM, Session.screenWidthM));
        sb.append(String.format(Locale.US, "预测 %.0fms", Session.predictMs))
          .append("   轴映射: ").append(Session.axisMapping.label)
          .append("   SBS: ").append(Session.sideBySide).append('\n');
        sb.append("副屏: ").append(Session.presentationActive ? "Presentation 已挂上" : "Activity 直落/未上屏").append('\n');
        sb.append("视频: ").append(Session.videoUri == null ? "测试图" : Session.videoUri);
        if (!Session.lastError.isEmpty()) sb.append('\n').append("错误: ").append(Session.lastError);

        // 固定行数，避免按钮随文本上下跳（R 行一直显示）
        float[] r = Session.lastRotation;
        if (r == null || r.length != 9) r = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        sb.append(String.format(Locale.US, "%nR = [%+.2f %+.2f %+.2f | %+.2f %+.2f %+.2f | %+.2f %+.2f %+.2f]",
                r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
        sb.append(String.format(Locale.US, "%nviewYaw90 = %d", Session.viewYaw90));
        uiTick++;
        status.setText(sb.toString());
    }

    private Button button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---- XrealUsb.Listener ----
    @Override
    public void onDevice(android.hardware.usb.UsbDevice device) {
        Session.deviceOpen = device != null;
        Session.deviceName = device == null ? ""
                : String.format("VID=%04X PID=%04X", device.getVendorId(), device.getProductId());
    }

    @Override
    public void onPacketRate(float hz) {
        Session.packetRateHz = hz;
    }

    @Override
    public void onError(String message) {
        Session.lastError = message;
    }
}
