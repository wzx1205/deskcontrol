package com.xreal.beamlink;

import android.app.Presentation;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * 外接屏兜底方案：用 Presentation 把 GL 窗口挂到眼镜那块屏上。
 *
 * 背景：Find N5（ColorOS）上，第三方 App 用 ActivityOptions.setLaunchDisplayId()
 * 启动 Activity 会被拒（连 uid=0 的 am start --display 34 都被拒），
 * 但 Presentation 走的是 window 层，只要有 SYSTEM_ALERT_WINDOW 就能往副屏加窗口。
 */
public class GlassesPresentation extends Presentation {

    private static final String TAG = "GlassesPresentation";
    private GLSurfaceView glView;
    private GlassesRenderer renderer;

    public GlassesPresentation(Context outer, Display display) {
        super(outer, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        renderer = new GlassesRenderer();
        glView = new GLSurfaceView(getContext());
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setPreserveEGLContextOnPause(true);
        setContentView(glView);
        Log.i(TAG, "presentation created on display " + getDisplay().getDisplayId());
    }

    public GlassesRenderer getRenderer() {
        return renderer;
    }

    public void resume() {
        if (glView != null) glView.onResume();
    }

    public void pause() {
        if (glView != null) glView.onPause();
    }
}
