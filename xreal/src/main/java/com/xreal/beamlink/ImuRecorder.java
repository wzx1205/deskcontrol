package com.xreal.beamlink;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

/**
 * IMU 原始帧落盘：每行 "hostNs,devTsNs,hex64bytes"
 * 用途：把"晃头会飘"的实际数据抓回来，在 PC 上用同一套 C++ 内核离线复算。
 */
public final class ImuRecorder {

    private static final String TAG = "ImuRecorder";

    private BufferedWriter writer;
    private File file;
    private long rows;

    public synchronized boolean start(Context ctx) {
        if (writer != null) return true;
        try {
            File dir = ctx.getExternalFilesDir("logs");
            if (dir == null) dir = ctx.getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            file = new File(dir, "imu_" + System.currentTimeMillis() + ".csv");
            writer = new BufferedWriter(new FileWriter(file), 1 << 16);
            writer.write("host_ns,dev_ns,hex\n");
            rows = 0;
            Log.i(TAG, "recording -> " + file.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            writer = null;
            return false;
        }
    }

    public synchronized void stop() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (Throwable ignored) {
        }
        writer = null;
        Log.i(TAG, "stopped, rows=" + rows + " file=" + (file == null ? "-" : file.getAbsolutePath()));
    }

    public synchronized boolean isRecording() { return writer != null; }

    public String path() { return file == null ? "" : file.getAbsolutePath(); }

    /** ReadLoop 里调用；只写 IMU 帧（64B，01 02 开头） */
    public synchronized void write(long hostNs, byte[] data, int len) {
        if (writer == null || len < 64) return;
        try {
            long devTs = 0;
            for (int i = 0; i < 8; i++) devTs |= ((long) (data[4 + i] & 0xFF)) << (8 * i);
            StringBuilder sb = new StringBuilder(160);
            sb.append(hostNs).append(',').append(devTs).append(',');
            for (int i = 0; i < 64; i++) {
                sb.append(String.format(Locale.US, "%02x", data[i]));
            }
            sb.append('\n');
            writer.write(sb.toString());
            rows++;
            if (rows % 2000 == 0) writer.flush();
        } catch (Throwable t) {
            Log.e(TAG, "write failed", t);
        }
    }
}
