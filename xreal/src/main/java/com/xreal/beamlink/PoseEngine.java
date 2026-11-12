package com.xreal.beamlink;

/**
 * 3DoF 内核的 JNI 封装（native 实现在 src/main/cpp/xr_jni.cpp，
 * 内核源码在仓库根目录 core/，由 CMake/NDK 直接编译，不复制代码）。
 */
public class PoseEngine implements AutoCloseable {

    public enum AnchorMode {
        OFF(0, "0DoF 贴脸"),
        WORLD(1, "3DoF 世界锁定"),
        YAW_ONLY(2, "仅偏航（保持水平）"),
        FOLLOW(3, "软跟随（稳过的 0DoF）");

        public final int value;
        public final String label;

        AnchorMode(int value, String label) {
            this.value = value;
            this.label = label;
        }

        public AnchorMode next() {
            switch (this) {
                case WORLD: return YAW_ONLY;
                case YAW_ONLY: return FOLLOW;
                case FOLLOW: return OFF;
                default: return WORLD;
            }
        }
    }

    public enum AxisMapping {
        VENDOR_DEFAULT(0, "厂商默认 (-y,-x,-z)"),
        PASS_THROUGH(1, "直通"),
        ROT_Z90(2, "绕上轴 +90° (y,-x,z)"),
        ROT_Z180(3, "绕上轴 180° (-x,-y,z)"),
        ROT_Z270(4, "绕上轴 -90° (-y,x,z)");

        public final int value;
        public final String label;

        AxisMapping(int value, String label) {
            this.value = value;
            this.label = label;
        }

        public AxisMapping next() {
            AxisMapping[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private long handle = nativeCreate();

    public AnchorMode mode = AnchorMode.WORLD;
    public float screenDistanceM = 3.0f;
    public float screenWidthM = 2.6f;
    public float predictMs = 18.0f;
    public float followTauSec = 0.12f;
    public float recenterBlendSec = 0.35f;
    public boolean yawDriftComp = true;
    public AxisMapping axisMapping = AxisMapping.ROT_Z270;

    private final float[] view = new float[9];
    private final float[] statsBuf = new float[18];

    public void applyConfig() {
        nativeConfigure(handle, mode.value, screenDistanceM, screenWidthM, predictMs,
                followTauSec, recenterBlendSec, yawDriftComp, axisMapping.value);
    }

    /** @return true 表示该帧是有效 IMU 样本 */
    public boolean pushPacket(byte[] data, int len, long hostNs) {
        return nativePushPacket(handle, data, len, hostNs);
    }

    /** 返回 3x3 锚点系→相机系旋转（GL 轴系，行主序） */
    public float[] viewRotation(long nowNs) {
        nativeGetView(handle, nowNs, view);
        return view;
    }

    public void recenter() {
        nativeRecenter(handle);
    }

    public void reset() {
        nativeReset(handle);
    }

    public float[] stats() {
        nativeStats(handle, statsBuf);
        return statsBuf;
    }

    @Override
    public void close() {
        if (handle != 0L) {
            nativeDestroy(handle);
            handle = 0L;
        }
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long h);
    private static native void nativeConfigure(long h, int mode, float distM, float widthM,
                                               float predictMs, float followTau, float recenterBlend,
                                               boolean yawDriftComp, int axisMap);
    private static native boolean nativePushPacket(long h, byte[] data, int len, long hostNs);
    private static native void nativeGetView(long h, long nowNs, float[] out9);
    private static native void nativeRecenter(long h);
    private static native void nativeReset(long h);
    private static native void nativeStats(long h, float[] out18);

    static {
        System.loadLibrary("xrealbeamlink");
    }
}
