package com.xreal.beamlink;

/** 4x4 列主序矩阵工具（直接喂给 GL） */
public final class MatrixUtil {

    private MatrixUtil() {}

    public static float[] identity() {
        return new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
    }

    public static float[] perspective(float fovYDeg, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovYDeg / 2.0)));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
        return m;
    }

    public static float[] translate(float x, float y, float z) {
        float[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    public static float[] scale(float x, float y, float z) {
        float[] m = identity();
        m[0] = x;
        m[5] = y;
        m[10] = z;
        return m;
    }

    public static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    /** 绕 Y 轴（相机上方向）旋转 deg 度，4x4 列主序 */
    public static float[] rotationY(float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r);
        float s = (float) Math.sin(r);
        float[] m = identity();
        m[0] = c;  m[2] = s;
        m[8] = -s; m[10] = c;
        return m;
    }

    /** 绕 Z 轴（视线轴）旋转 deg 度，4x4 列主序 */
    public static float[] rotationZ(float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r);
        float s = (float) Math.sin(r);
        float[] m = identity();
        m[0] = c;  m[1] = s;
        m[4] = -s; m[5] = c;
        return m;
    }

    /** 3x3 行主序（锚点系→相机系）转 4x4 列主序 */
    public static float[] fromRotation3(float[] r) {
        float[] m = identity();
        m[0] = r[0]; m[4] = r[1]; m[8] = r[2];
        m[1] = r[3]; m[5] = r[4]; m[9] = r[5];
        m[2] = r[6]; m[6] = r[7]; m[10] = r[8];
        return m;
    }
}
