package com.xreal.beamlink;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 按需注入：只有 DeskControl 开启 3DoF 时才让 system_server 放行"往眼镜屏起 Activity"。
 *
 * 实现在 KernelSU 模块 `banmds_frida` 里（frida-inject + hook 脚本），App 只负责 su 调脚本：
 *   - 开启：幂等注入，注入器常驻（不用 -e，注入器活着 hook 才活着）
 *   - 关闭：不 kill（实测 kill -9 会让 system_server 崩溃重启），hook 保留到重启
 *
 * 没有 root 时静默失败：引擎仍走 Presentation 路径，只是 App 不能原生直落眼镜屏。
 */
final class XrealHooks {

    private static final String TAG = "XrealHooks";
    private static final String MODULE_DIR = "/data/adb/modules/banmds_frida";

    private XrealHooks() {}

    static void enableAsync(final Context ctx) {
        new Thread(new Runnable() {
            @Override public void run() { runScript("hook_on.sh"); }
        }, "xreal-hook-on").start();
    }

    static void disableAsync(final Context ctx) {
        new Thread(new Runnable() {
            @Override public void run() { runScript("hook_off.sh"); }
        }, "xreal-hook-off").start();
    }

    private static void runScript(String script) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "sh " + MODULE_DIR + "/" + script)
                    .redirectErrorStream(true)
                    .start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) Log.i(TAG, script + ": " + line);
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                Log.w(TAG, script + " 超时");
                p.destroy();
            }
        } catch (Throwable t) {
            Log.w(TAG, script + " 失败（没有 root 或未授权？）: " + t);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }
}