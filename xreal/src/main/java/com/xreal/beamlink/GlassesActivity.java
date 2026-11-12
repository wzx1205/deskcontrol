package com.xreal.beamlink;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

/**
 * 跑在眼镜那块 DP 屏（Find N5 上 displayId=34，1920x1080@60）上的 Activity：
 * 全屏 GL 渲染虚拟巨幕。由 DisplayRouter 用 ActivityOptions.setLaunchDisplayId 启动。
 */
public class GlassesActivity extends Activity implements XrealUsb.Listener {

    private static final String TAG = "GlassesActivity";

    private GLSurfaceView glView;
    private GlassesRenderer renderer;
    private MediaPlayer mediaPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();

        Session.ensureEngine();

        renderer = new GlassesRenderer();
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setPreserveEGLContextOnPause(true);
        setContentView(glView);

        Session.startUsb(this, this);

        if (getIntent() != null && getIntent().getBooleanExtra("recenter", false)) {
            Session.recenter();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        startVideoWhenReady(0);
    }

    @Override
    protected void onPause() {
        stopVideo();
        glView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---- 视频 ----
    private void startVideoWhenReady(final int attempt) {
        final String uriString = Session.videoUri;
        if (uriString == null || uriString.isEmpty()) {
            renderer.hasVideo = false;
            return;
        }
        final SurfaceTexture st = renderer.getSurfaceTexture();
        if (st == null) {
            if (attempt < 30) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { startVideoWhenReady(attempt + 1); }
                }, 100);
            }
            return;
        }
        try {
            stopVideo();
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(this, Uri.parse(uriString));
            mp.setSurface(new Surface(st));
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    if (w > 0 && h > 0) renderer.setVideoAspect((float) w / (float) h);
                }
            });
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    renderer.hasVideo = true;
                    m.start();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                    renderer.hasVideo = false;
                    return true;
                }
            });
            mp.prepareAsync();
            mediaPlayer = mp;
            Log.i(TAG, "video prepare: " + uriString);
        } catch (Throwable t) {
            Log.e(TAG, "video setup failed", t);
            renderer.hasVideo = false;
        }
    }

    private void stopVideo() {
        MediaPlayer mp = mediaPlayer;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
        }
        mediaPlayer = null;
        if (renderer != null) renderer.hasVideo = false;
    }

    // ---- XrealUsb.Listener ----
    @Override
    public void onDevice(android.hardware.usb.UsbDevice device) {
        Session.deviceOpen = device != null;
        Session.deviceName = device == null ? ""
                : String.format("VID=%04X PID=%04X", device.getVendorId(), device.getProductId());
        if (device != null) {
            PoseEngine e = Session.engine;
            if (e != null) e.reset();
            Session.recenter();
        }
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
