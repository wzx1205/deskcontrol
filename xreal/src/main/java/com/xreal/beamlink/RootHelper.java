package com.xreal.beamlink;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * root 兜底：用 su 执行系统命令（打开无障碍 / 授权悬浮窗）。
 * 比 Shizuku 稳，不需要额外依赖；没有 root 时会被拒绝，返回失败原因。
 */
public final class RootHelper {

    private static final String TAG = "RootHelper";

    private RootHelper() {}

    public static final class Result {
        public final boolean ok;
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }
    }

    public static Result run(String command) {
        StringBuilder out = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = e.readLine()) != null) out.append(line).append('\n');
            int code = p.waitFor();
            String text = out.toString().trim();
            boolean ok = code == 0 && text.contains("OK");
            Log.i(TAG, "su exit=" + code + " out=" + text);
            return new Result(ok, text.isEmpty() ? ("exit=" + code) : text);
        } catch (Throwable t) {
            Log.e(TAG, "su failed", t);
            return new Result(false, "su 不可用: " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    public static Result grantAll(String pkg) {
        String a11y = pkg + "/" + GestureAccessibilityService.class.getName();
        return run("settings put secure enabled_accessibility_services " + a11y
                + "; settings put secure accessibility_enabled 1"
                + "; appops set " + pkg + " SYSTEM_ALERT_WINDOW allow"
                + "; echo OK");
    }
}
