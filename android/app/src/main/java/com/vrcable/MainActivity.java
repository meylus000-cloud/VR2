package com.vrcable;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity implements SensorEventListener {
    private GLSurfaceView glView;
    private VRRenderer renderer;
    private NetClient net;
    private SensorManager sensors;
    private Sensor rotation;
    private GestureDetector gestures;

    private final float[] rIn = new float[16];
    private final float[] rOut = new float[16];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        renderer = new VRRenderer();
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(false);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(glView);

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotation == null) rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        // tap = re-centre the view, long press = switch lens distortion profile
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { renderer.recenter(); return true; }
            @Override public void onLongPress(MotionEvent e) { renderer.nextLens(); }
        });

        net = new NetClient(renderer);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        glView.onResume();
        if (rotation != null) sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_FASTEST);
        renderer.recenter();
        net.start();
    }

    @Override
    protected void onPause() {
        net.stop();
        sensors.unregisterListener(this);
        glView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    /** Volume keys move the virtual monitors closer / further. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { renderer.changeDistance(-0.2f); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { renderer.changeDistance(0.2f); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onSensorChanged(SensorEvent ev) {
        SensorManager.getRotationMatrixFromVector(rIn, ev.values);
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        if (rot == Surface.ROTATION_270) {
            SensorManager.remapCoordinateSystem(rIn, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, rOut);
        } else {
            SensorManager.remapCoordinateSystem(rIn, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rOut);
        }
        renderer.setHeadRotation(rOut);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
