package com.xreal.beamlink;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * XREAL Air 2 Pro 的 IMU HID 采集（Find N5 现场参数）：
 *
 *   VID 0x3318 / PID 0x0432，7 个接口：
 *     1.0~1.2 音频（snd-usb-audio）
 *     1.3 HID 64B 中断（ep_05 OUT / ep_84 IN）  ← IMU 主通道
 *     1.4 HID 64B（ep_07/0x86）                 ← MCU/亮度/按键
 *     1.5 HID 64B（ep_09/0x88）
 *     1.6 HID 3B（只有 IN）
 *   启动帧（HID 输出报文，report id 0）：
 *     00 AA C5 D1 21 42 04 00 19 01
 *
 * 为了在现场确定"哪条通道真的在吐数据"，这里会把 3/4/5/6 全部 claim 并同时排队读，
 * 日志里能看到每条通道的收包情况；只有 signature=0x01 0x02 的 64B 帧才会喂给解算器。
 */
public class XrealUsb {

    private static final String TAG = "XrealUsb";
    public static final int VID = 0x3318;
    public static final int[] PIDS = {0x0424, 0x0428, 0x0432, 0x0426};
    private static final String ACTION_USB_PERMISSION = "com.xreal.beamlink.USB_PERMISSION";

    /** 启动 IMU 推流：report id 0 + 0xAA 帧头 + CRC32 + len + cmd 0x19 + payload 0x01 */
    private static final byte[] CMD_START_IMU = {
            0x00, (byte) 0xAA, (byte) 0xC5, (byte) 0xD1, 0x21,
            0x42, 0x04, 0x00, 0x19, 0x01
    };

    /** 一条可读通道 */
    private static final class Channel {
        final UsbInterface iface;
        final UsbEndpoint in;
        final UsbEndpoint out;

        Channel(UsbInterface iface, UsbEndpoint in, UsbEndpoint out) {
            this.iface = iface;
            this.in = in;
            this.out = out;
        }
    }

    public interface Listener {
        void onDevice(UsbDevice device);
        void onPacketRate(float hz);
        void onError(String message);
    }

    private final Context context;
    private volatile Listener listener;
    private volatile PoseEngine engine;

    private volatile boolean running;
    private Thread worker;
    private UsbDeviceConnection connection;
    private final List<Channel> channels = new ArrayList<>();
    private long lastStartCmdNs;
    private long lastPacketNs;
    private long packetCount;
    private long rateWindowStartNs;
    private int debugPackets;
    private int otherDebugPackets;
    private final java.util.Map<Integer, Integer> chanCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private int imuChannelId = 3;

    private final UsbManager manager;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null || !ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            Log.i(TAG, "permission granted=" + granted + " pid=" + (device == null ? -1 : device.getProductId()));
            if (granted && device != null) open(device);
            else notifyError("USB 权限被拒绝");
        }
    };

    public XrealUsb(Context context) {
        this.context = context;
        this.manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setEngine(PoseEngine e) { this.engine = e; }

    public static int imuInterfaceId(int pid) { return pid == 0x0426 ? 2 : 3; }

    public UsbDevice findXreal() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() != VID) continue;
            for (int pid : PIDS) if (d.getProductId() == pid) return d;
        }
        return null;
    }

    public void start() {
        if (running) return;
        running = true;
        registerReceiver();
        UsbDevice dev = findXreal();
        if (dev == null) {
            notifyDevice(null);
            notifyError("未发现 XREAL 眼镜（VID 0x3318）");
            return;
        }
        if (manager.hasPermission(dev)) {
            open(dev);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
            manager.requestPermission(dev, pi);
        }
    }

    public void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        try {
            if (connection != null) {
                for (Channel c : channels) {
                    try { connection.releaseInterface(c.iface); } catch (Throwable ignored) {}
                }
                connection.close();
            }
        } catch (Throwable tr) {
            Log.w(TAG, "close failed", tr);
        }
        connection = null;
        channels.clear();
        try {
            context.unregisterReceiver(permissionReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        Session.devicePid = 0;
        notifyDevice(null);
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permissionReceiver, filter);
        }
    }

    private void open(UsbDevice device) {
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) {
            notifyError("openDevice 失败");
            return;
        }

        channels.clear();
        int primary = imuInterfaceId(device.getProductId());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface ui = device.getInterface(i);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int e = 0; e < ui.getEndpointCount(); e++) {
                UsbEndpoint ep = ui.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) in = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) out = ep;
            }
            if (in == null) continue;  // 音频/无中断 IN 的接口跳过
            // force=true：把内核 usbhid 从该接口上摘下来
            if (conn.claimInterface(ui, true)) {
                channels.add(new Channel(ui, in, out));
                Log.i(TAG, "claimed iface=" + ui.getId() + " in=0x" + Integer.toHexString(in.getAddress())
                        + " out=" + (out == null ? "-" : "0x" + Integer.toHexString(out.getAddress()))
                        + " maxPacket=" + in.getMaxPacketSize());
            } else {
                Log.w(TAG, "claimInterface(" + ui.getId() + ", force) 失败");
            }
        }

        if (channels.isEmpty()) {
            notifyError("没有可用的 HID 中断接口");
            conn.close();
            return;
        }

        connection = conn;
        Session.devicePid = device.getProductId();
        notifyDevice(device);


        Thread old = worker;
        if (old != null) old.interrupt();
        worker = new Thread(new ReadLoop(conn), "xreal-imu");
        worker.setPriority(Thread.MAX_PRIORITY);
        worker.start();
    }

    /** 依次尝试 feature report / output report / 直接写中断 OUT */
    private int sendStart(UsbDeviceConnection conn, Channel c) {
        byte[] padded = new byte[64];
        System.arraycopy(CMD_START_IMU, 0, padded, 0, CMD_START_IMU.length);

        // HID SET_REPORT(feature) = 0x0300
        int n = conn.controlTransfer(0x21, 0x09, 0x0300, c.iface.getId(), padded, padded.length, 500);
        Log.i(TAG, "  SET_REPORT(feature) -> " + n);
        if (n >= 0) return n;

        // HID SET_REPORT(output) = 0x0200
        n = conn.controlTransfer(0x21, 0x09, 0x0200, c.iface.getId(), padded, padded.length, 500);
        Log.i(TAG, "  SET_REPORT(output) -> " + n);
        if (n >= 0) return n;

        // 直接写中断 OUT 端点
        if (c.out != null) {
            n = conn.bulkTransfer(c.out, padded, padded.length, 500);
            Log.i(TAG, "  interrupt OUT -> " + n);
        }
        return n;
    }

    /** 组帧：AA | crc32(LE) | len16(LE) | msgid | payload —— 共 8 + dataLen 字节。
     *  注意**不要**加 hidapi 的 report-id 前缀 0x00：那是 hidapi 自己吃掉的，
     *  Android 的 bulkTransfer 是原样上线，多这一字节整帧就错位、设备直接不应答。 */
    private static byte[] buildImuCmd(int msgId, byte[] payload) {
        int plen = payload == null ? 0 : payload.length;
        int total = 8 + plen;               // AA + crc4 + len2 + msgid1 + payload
        byte[] out = new byte[total];
        out[0] = (byte) 0xAA;
        int len = 3 + plen;
        out[5] = (byte) (len & 0xFF);
        out[6] = (byte) ((len >> 8) & 0xFF);
        out[7] = (byte) msgId;
        if (plen > 0) System.arraycopy(payload, 0, out, 8, plen);
        // CRC 覆盖 len 起、长度 len 的字节
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(out, 5, len);
        long v = crc.getValue();
        out[1] = (byte) (v & 0xFF);
        out[2] = (byte) ((v >> 8) & 0xFF);
        out[3] = (byte) ((v >> 16) & 0xFF);
        out[4] = (byte) ((v >> 24) & 0xFF);
        return out;
    }

    /** 厂商顺序：GET_STATIC_ID(0x1A) → GET_CAL_DATA_LENGTH(0x14) → START_IMU_DATA(0x19,01) */
    private void handshake(UsbDeviceConnection conn, Channel c) {
        if (c.out == null) return;
        int[][] steps = {{0x1A, 0}, {0x14, 0}};
        for (int[] st : steps) {
            byte[] cmd = buildImuCmd(st[0], null);
            int n = conn.bulkTransfer(c.out, cmd, cmd.length, 300);
            Log.i(TAG, String.format("handshake cmd 0x%02X (%dB) -> %d", st[0], cmd.length, n));
            try { Thread.sleep(150); } catch (InterruptedException ignored) { return; }
        }
        byte[] start = buildImuCmd(0x19, new byte[]{0x01});
        // 先 STOP 再 START：避免设备停留在上一次会话的半开状态
        byte[] stop = buildImuCmd(0x19, new byte[]{0x00});
        conn.bulkTransfer(c.out, stop, stop.length, 300);
        try { Thread.sleep(80); } catch (InterruptedException ignored) { return; }
        int n = conn.bulkTransfer(c.out, start, start.length, 300);
        Log.i(TAG, String.format("handshake cmd 0x19 START (%dB) -> %d", start.length, n));
        // 2026-11-12 实测：SET_REPORT(feature) 返回 -1（设备不支持该请求），START 只能走中断 OUT
    }

    private final class ReadLoop implements Runnable {
        private final UsbDeviceConnection conn;

        ReadLoop(UsbDeviceConnection conn) { this.conn = conn; }

        @Override
        public void run() {
            final int perChannel = 3;
            List<UsbRequest> requests = new ArrayList<>();
            List<ByteBuffer> buffers = new ArrayList<>();
            List<Channel> owners = new ArrayList<>();

            for (Channel c : channels) {
                for (int i = 0; i < perChannel; i++) {
                    UsbRequest r = new UsbRequest();
                    if (!r.initialize(conn, c.in)) {
                        Log.w(TAG, "initialize failed on iface " + c.iface.getId());
                        continue;
                    }
                    ByteBuffer buf = ByteBuffer.allocateDirect(Math.max(64, c.in.getMaxPacketSize()));
                    buf.clear();
                    if (!r.queue(buf)) {
                        Log.w(TAG, "queue failed on iface " + c.iface.getId());
                        continue;
                    }
                    requests.add(r);
                    buffers.add(buf);
                    owners.add(c);
                }
            }
            Log.i(TAG, "queued " + requests.size() + " requests across " + channels.size() + " channels");
            // 读请求先挂上，再发握手命令，否则设备回的响应会因为没有 URB 而丢掉
            // 先清 IN 端点可能残留的 STALL：HID 设备在一次失败传输后会一直停在这里，
            // 典型症状正是"写命令成功但永远没有数据"，Android 不会自动清
            for (Channel c : channels) {
                if (c.in != null) {
                    int r = conn.controlTransfer(0x02, 0x01, 0, c.in.getAddress(), null, 0, 200);
                    Log.i(TAG, "  CLEAR_FEATURE halt ep=0x" + Integer.toHexString(c.in.getAddress()) + " -> " + r);
                }
            }
            for (Channel c : channels) {
                if (c.iface.getId() == imuInterfaceId(Session.devicePid)) handshake(conn, c);
            }

            rateWindowStartNs = SystemClock.elapsedRealtimeNanos();
            while (running) {
                UsbRequest done;
                try {
                    done = conn.requestWait();
                } catch (Throwable t) {
                    Log.w(TAG, "requestWait 异常", t);
                    break;
                }
                if (done == null) break;

                int idx = -1;
                for (int i = 0; i < requests.size(); i++) {
                    if (requests.get(i) == done) { idx = i; break; }
                }
                if (idx < 0) continue;

                ByteBuffer buf = buffers.get(idx);
                Channel ch = owners.get(idx);
                int len = buf.position();
                if (len > 0) {
                    Integer prev = chanCounts.get(ch.iface.getId());
                    chanCounts.put(ch.iface.getId(), prev == null ? 1 : prev + 1);
                    byte[] data = new byte[len];
                    buf.rewind();
                    buf.get(data, 0, len);
                    long now = SystemClock.elapsedRealtimeNanos();
                    boolean imuIface = ch.iface.getId() == imuInterfaceId(Session.devicePid)
                            && len >= 64 && data[0] == 0x01 && data[1] == 0x02;
                    if (imuIface && debugPackets < 3) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < Math.min(len, 64); k++) sb.append(String.format("%02X", data[k])).append(k % 16 == 15 ? "\n" : " ");
                        Log.i(TAG, "IMU frame#" + debugPackets + " (" + len + "B):\n" + sb);
                        debugPackets++;
                    } else if (!imuIface && otherDebugPackets < 6) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < Math.min(len, 16); k++) sb.append(String.format("%02X ", data[k]));
                        Log.i(TAG, "other pkt iface=" + ch.iface.getId() + " len=" + len + " head=" + sb);
                        otherDebugPackets++;   // 之前漏了自增，非 IMU 通道会无限刷日志，把 logcat 冲掉
                    }
                    boolean isImu = ch.iface.getId() == imuInterfaceId(Session.devicePid)
                            && len >= 64 && data[0] == 0x01 && data[1] == 0x02;
                    if (isImu) {
                        lastPacketNs = now;
                        packetCount++;
                        Session.recorder.write(now, data, len);
                        PoseEngine e = engine;
                        if (e != null) e.pushPacket(data, len, now);
                    }
                }
                buf.clear();
                if (!done.queue(buf)) break;

                long now = SystemClock.elapsedRealtimeNanos();
                if (lastPacketNs == 0L || now - lastPacketNs > 1_500_000_000L) {
                    if (now - lastStartCmdNs > 2_000_000_000L) {
                        for (Channel c : channels) {
                            if (c.iface.getId() == imuInterfaceId(Session.devicePid)) handshake(conn, c);
                        }
                        lastStartCmdNs = now;
                    }
                }
                if (now - rateWindowStartNs >= 5_000_000_000L) {
                    float hz = packetCount * 1e9f / (now - rateWindowStartNs);
                    StringBuilder cn = new StringBuilder();
                    for (java.util.Map.Entry<Integer, Integer> en : chanCounts.entrySet()) {
                        cn.append("iface").append(en.getKey()).append("=").append(en.getValue()).append(" ");
                    }
                    Log.i(TAG, String.format("IMU %.1f Hz | 各通道累计: %s", hz, cn));
                    PoseEngine pe = engine;
                    if (pe != null) {
                        float[] st = pe.stats();
                        Log.i(TAG, String.format(java.util.Locale.US,
                                "stats samples=%.0f temp=%.1fC gyroScale=%.6f accelScale=%.8f bias=(%.2f,%.2f,%.2f) ypr=(%.1f,%.1f,%.1f) init=%b",
                                st[0], st[3], st[16], st[17], st[4], st[5], st[6], st[10], st[11], st[12], st[8] > 0.5f));
                    }
                    chanCounts.clear();
                    Listener l = listener;
                    if (l != null) l.onPacketRate(hz);
                    packetCount = 0;
                    rateWindowStartNs = now;
                }
            }
        }
    }

    private void notifyDevice(UsbDevice d) {
        Listener l = listener;
        if (l != null) l.onDevice(d);
    }

    private void notifyError(String msg) {
        Log.e(TAG, msg);
        Listener l = listener;
        if (l != null) l.onError(msg);
    }
}
