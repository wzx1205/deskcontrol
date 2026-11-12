package com.xreal.beamlink;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 虚拟巨幕渲染器。
 *
 * 三种纹理来源，优先级从高到低：
 *   1) 抓屏（MediaProjection → SurfaceTexture）—— 显示任意 App 的画面
 *   2) 本地视频（MediaPlayer → SurfaceTexture）
 *   3) 内置测试图（程序生成）
 *
 * 坐标约定：锚点系 → 相机系 = R(qRel)，MVP = P · R · T(0,0,-d) · S(w/2,h/2,1)
 */
public class GlassesRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "GlassesRenderer";
    private static final float FOV_Y_DEG = 46f;
    private static final float NEAR = 0.05f;
    private static final float FAR = 100f;

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec2 aPos;\n" +
            "uniform mat4 uMvp;\n" +
            "out vec2 vUv;\n" +
            "void main() {\n" +
            "  vUv = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_2D =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "in vec2 vUv;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uUvOffset;\n" +
            "uniform float uUvScale;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "  vec2 uv = vec2(vUv.x * uUvScale + uUvOffset, 1.0 - vUv.y);\n" +
            "  fragColor = texture(uTex, uv);\n" +
            "}\n";

    private static final String FRAGMENT_OES =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "in vec2 vUv;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform float uUvOffset;\n" +
            "uniform float uUvScale;\n" +
            "uniform float uFlipV;\n" +
            "uniform float uCropBottom;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "  vec2 uv = vec2(vUv.x * uUvScale + uUvOffset, uFlipV > 0.5 ? vUv.y : 1.0 - vUv.y);\n" +
            "  uv.y *= (1.0 - uCropBottom);\n" +
            "  fragColor = texture(uTex, uv);\n" +
            "}\n";

    private int program2D;
    private int programOES;
    private final FloatBuffer quad;

    private int textureId;      // 内置测试图
    private int textureOes;     // 本地视频
    private int captureTex;     // 抓屏

    private SurfaceTexture surfaceTexture;
    private SurfaceTexture captureSurface;

    private volatile boolean frameAvailable;
    private volatile boolean captureFrameAvailable;

    public volatile float videoAspect = 16f / 9f;
    public volatile boolean hasVideo;

    private int[] viewport = {0, 0, 1920, 1080};

    public GlassesRenderer() {
        FloatBuffer b = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
        b.position(0);
        quad = b;
    }

    public SurfaceTexture getSurfaceTexture() { return surfaceTexture; }

    public void setVideoAspect(float aspect) {
        if (aspect > 0.1f && aspect < 10f) videoAspect = aspect;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program2D = buildProgram(VERTEX_SHADER, FRAGMENT_2D);
        programOES = buildProgram(VERTEX_SHADER, FRAGMENT_OES);
        textureId = createFallbackTexture();

        textureOes = createOesTexture();
        surfaceTexture = new SurfaceTexture(textureOes);
        surfaceTexture.setOnFrameAvailableListener(this);

        captureTex = createOesTexture();
        captureSurface = new SurfaceTexture(captureTex);
        captureSurface.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture st) {
                captureFrameAvailable = true;
            }
        });
        Session.captureSurfaceTexture = captureSurface;

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        Log.i(TAG, "GL ready: " + GLES30.glGetString(GLES30.GL_VERSION));
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewport = new int[]{0, 0, width, height};
        GLES30.glViewport(0, 0, width, height);
        Log.i(TAG, "surface " + width + "x" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        PoseEngine engine = Session.engine;
        if (Session.recenterRequested) {
            if (engine != null) engine.recenter();
            Session.recenterRequested = false;
        }

        long now = SystemClock.elapsedRealtimeNanos();
        if (engine != null) {
            float[] r = engine.viewRotation(now);
            if (r != null && r.length >= 9) {
                Session.lastRotation = r.clone();
            }
        }
        float[] rot = Session.lastRotation;

        if (frameAvailable) {
            SurfaceTexture st = surfaceTexture;
            if (st != null) st.updateTexImage();
            frameAvailable = false;
        }
        if (captureFrameAvailable) {
            SurfaceTexture st = captureSurface;
            if (st != null) {
                st.updateTexImage();
                noteCaptureAge(st.getTimestamp());
            }
            captureFrameAvailable = false;
        }

        GLES30.glClearColor(0.02f, 0.02f, 0.03f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        // 纹理源与内容宽高比
        int texKind;         // 0=测试图 1=视频 2=抓屏
        float aspect;
        float cropBottom = 0f;   // 抓屏底部裁掉的比例（触摸板黑边）
        float extraRotation = 0f;   // 绕视线轴的额外旋转（抓屏方向修正）
        boolean mplayerFlip = true;

        if (Session.captureActive) {
            texKind = 2;
            int w = Math.max(1, Session.captureWidth);
            int h = Math.max(1, Session.captureHeight);
            int r = Session.captureRotation;
            cropBottom = Session.captureSingleApp
                    ? 0f : Math.max(0f, Math.min(0.6f, Session.cropBottom));
            float visW = w;
            float visH = h * (1f - cropBottom);
            if (r == 90 || r == 270) {
                aspect = visH / visW;
                extraRotation = (r == 90) ? -90f : 90f;
            } else {
                aspect = visW / visH;
                extraRotation = (r == 180) ? 180f : 0f;
            }
            mplayerFlip = false;
        } else if (hasVideo) {
            texKind = 1;
            aspect = videoAspect;
        } else {
            texKind = 0;
            aspect = videoAspect;
        }

        float d = Session.screenDistanceM;
        float w = Session.screenWidthM;
        float h = w / aspect;

        float[] model = MatrixUtil.multiply(
                MatrixUtil.translate(0f, 0f, -d),
                MatrixUtil.scale((Session.mirrorX ? -1f : 1f) * w / 2f,
                        (Session.flipY ? -1f : 1f) * h / 2f, 1f));
        if (extraRotation != 0f) {
            model = MatrixUtil.multiply(MatrixUtil.translate(0f, 0f, -d),
                    MatrixUtil.multiply(MatrixUtil.rotationZ(extraRotation),
                            MatrixUtil.scale(w / 2f, h / 2f, 1f)));
        }

        float[] view = MatrixUtil.fromRotation3(rot);
        if (Session.viewYaw90 != 0) {
            view = MatrixUtil.multiply(MatrixUtil.rotationY(Session.viewYaw90 * 90f), view);
        }
        float[] mv = MatrixUtil.multiply(view, model);

        if (Session.sideBySide) {
            drawEye(mv, 0, 2, 0f, 0.5f, texKind, mplayerFlip, cropBottom);
            drawEye(mv, 1, 2, 0.5f, 0.5f, texKind, mplayerFlip, cropBottom);
        } else {
            drawEye(mv, 0, 1, 0f, 1f, texKind, mplayerFlip, cropBottom);
        }
    }

    private void drawEye(float[] mv, int eye, int eyeCount, float uvOffset, float uvScale,
                         int texKind, boolean flipV, float cropBottom) {
        float aspect = videoAspect / eyeCount;
        float[] proj = MatrixUtil.perspective(FOV_Y_DEG, aspect, NEAR, FAR);
        float[] mvp = MatrixUtil.multiply(proj, mv);

        int eyeW = viewport[2] / eyeCount;
        int x = viewport[0] + eye * eyeW;
        GLES30.glViewport(x, viewport[1], eyeW, viewport[3]);
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
        GLES30.glScissor(x, viewport[1], eyeW, viewport[3]);

        boolean oes = texKind != 0;
        int prog = oes ? programOES : program2D;
        GLES30.glUseProgram(prog);

        int aPos = GLES30.glGetAttribLocation(prog, "aPos");
        GLES30.glEnableVertexAttribArray(aPos);
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad);

        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uUvOffset"), uvOffset);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uUvScale"), uvScale);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0);
        if (oes) {
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFlipV"), flipV ? 1f : 0f);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uCropBottom"), cropBottom);
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        if (texKind == 2) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, captureTex);
        } else if (texKind == 1) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOes);
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glBindTexture(oes ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES30.GL_TEXTURE_2D, 0);
        GLES30.glDisableVertexAttribArray(aPos);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameAvailable = true;
    }

    // 抓屏帧龄：生产者时间戳 → 消费时刻，1 Hz 汇总一次，用来校准 Session.predictMs
    private long ageMinNs = Long.MAX_VALUE;
    private long ageMaxNs;
    private long ageSumNs;
    private int ageCount;
    private long ageWindowStartNs;

    private void noteCaptureAge(long frameTsNs) {
        long now = SystemClock.elapsedRealtimeNanos();
        long age = now - frameTsNs;
        if (age < 0 || age > 500_000_000L) return;   // 时钟源不一致，丢弃
        if (age < ageMinNs) ageMinNs = age;
        if (age > ageMaxNs) ageMaxNs = age;
        ageSumNs += age;
        ageCount++;
        if (ageWindowStartNs == 0) ageWindowStartNs = now;
        if (now - ageWindowStartNs < 1_000_000_000L) return;
        Log.i(TAG, String.format(Locale.US, "CaptureLatency n=%d min=%.1fms avg=%.1fms max=%.1fms",
                ageCount, ageMinNs / 1e6, ageSumNs / (double) ageCount / 1e6, ageMaxNs / 1e6));
        ageMinNs = Long.MAX_VALUE;
        ageMaxNs = 0;
        ageSumNs = 0;
        ageCount = 0;
        ageWindowStartNs = now;
    }

    private int buildProgram(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] status = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            throw new RuntimeException("program link failed: " + GLES30.glGetProgramInfoLog(p));
        }
        return p;
    }

    private int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            throw new RuntimeException("shader compile failed: " + GLES30.glGetShaderInfoLog(s));
        }
        return s;
    }

    private int createOesTexture() {
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0]);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return ids[0];
    }

    /** 内置测试图：8 段色条 + 网格 + 左上角白块 */
    private int createFallbackTexture() {
        int w = 512;
        int h = 288;
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int bar = (x * 8) / w;
                int r = 0, g = 0, b = 0;
                switch (bar) {
                    case 0: r = 200; g = 200; b = 200; break;
                    case 1: r = 200; g = 200; b = 0; break;
                    case 2: r = 0; g = 200; b = 200; break;
                    case 3: r = 0; g = 200; b = 0; break;
                    case 4: r = 200; g = 0; b = 200; break;
                    case 5: r = 200; g = 0; b = 0; break;
                    case 6: r = 0; g = 0; b = 200; break;
                    default: r = 30; g = 30; b = 30; break;
                }
                if (x % 32 == 0 || y % 32 == 0) {
                    r = (int) (r * 0.6f);
                    g = (int) (g * 0.6f);
                    b = (int) (b * 0.6f);
                }
                if (x >= 8 && x <= 72 && y >= 8 && y <= 72) {
                    r = 255; g = 255; b = 255;
                }
                buf.put((byte) r);
                buf.put((byte) g);
                buf.put((byte) b);
                buf.put((byte) 255);
            }
        }
        buf.position(0);
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        return ids[0];
    }
}
