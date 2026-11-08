package com.vrcable;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Stereo renderer: grey room, 1-2 virtual monitors, head tracking, lens distortion. */
public class VRRenderer implements GLSurfaceView.Renderer {

    // ---- tunables -------------------------------------------------------------------------
    /** Lens distortion profiles {k1, k2}. Long-press on the screen cycles through them. */
    static final float[][] LENS = {{0.15f, 0.05f}, {0.22f, 0.12f}, {0.34f, 0.30f}};
    static final float IPD = 0.064f;          // distance between the eyes, metres
    static final float SCREEN_W = 2.0f;       // virtual monitor width, metres
    static final float FOV = 95f;             // vertical field of view of each eye, degrees
    // ---------------------------------------------------------------------------------------

    private static class Screen {
        int tex, w, h;
        SurfaceTexture st;
        Surface surface;
        VideoDecoder dec;
        /** Number of decoded frames waiting in the SurfaceTexture queue (one per onFrameAvailable). */
        final AtomicInteger pending = new AtomicInteger(0);
        final float[] texMat = new float[16];
    }

    private volatile Screen[] screens = new Screen[0];

    // pending (network thread -> GL thread)
    private final Object cfgLock = new Object();
    private int[] pendW, pendH;
    private CountDownLatch cfgLatch;

    // status text
    private volatile String statusText = "";
    private volatile boolean statusDirty = true;
    private int statusTex;

    // head tracking
    private final Object rotLock = new Object();
    private final float[] rotSensor = new float[16];
    private volatile boolean recenterPending = true;
    private float yawOff = 0f;

    private volatile int lensIdx = 1;
    private volatile float dist = 1.6f;      // distance to the monitors, metres (volume keys change it)

    // GL objects
    private int colorProg, screenProg, textProg, distProg;
    private FloatBuffer roomVb, quadVb;
    private int fbo, fboTex, fboDepth, fboW, fboH, surfW, surfH;

    private static final float[] W_SCENE_TO_WORLD = {
            1, 0, 0, 0,
            0, 0, 1, 0,
            0, -1, 0, 0,
            0, 0, 0, 1};
    private static final float[] IDENT = new float[16];
    private static final float[][] ROOM_COLORS = {
            {0.22f, 0.22f, 0.23f},  // floor
            {0.50f, 0.50f, 0.52f},  // ceiling
            {0.38f, 0.38f, 0.40f},  // back
            {0.34f, 0.34f, 0.36f},  // front
            {0.42f, 0.42f, 0.44f},  // left
            {0.42f, 0.42f, 0.44f}   // right
    };

    public VRRenderer() {
        Matrix.setIdentityM(IDENT, 0);
        Matrix.setIdentityM(rotSensor, 0);
    }

    // ===== API used by other threads ========================================================

    public void setStatus(String s) {
        if (!s.equals(statusText)) {
            statusText = s;
            statusDirty = true;
        }
    }

    public void setHeadRotation(float[] r16) {
        synchronized (rotLock) {
            System.arraycopy(r16, 0, rotSensor, 0, 16);
        }
    }

    public void recenter() { recenterPending = true; }

    public void nextLens() { lensIdx = (lensIdx + 1) % LENS.length; }

    public void changeDistance(float d) {
        dist = Math.max(0.9f, Math.min(3.6f, dist + d));
    }

    public void clearScreens() {
        synchronized (cfgLock) {
            pendW = new int[0];
            pendH = new int[0];
        }
    }

    public void configureAndWait(int[] w, int[] h) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (cfgLock) {
            pendW = w;
            pendH = h;
            cfgLatch = latch;
        }
        latch.await(3, TimeUnit.SECONDS);
    }

    public void feedVideo(int idx, byte[] au) {
        Screen[] s = screens;
        if (idx < s.length && s[idx].dec != null) s[idx].dec.feed(au);
    }

    // ===== GL ===============================================================================

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        colorProg = program(
                "attribute vec4 aPos; uniform mat4 uMVP; uniform mat4 uModel; varying vec3 vWorld;" +
                "void main(){ vWorld=(uModel*aPos).xyz; gl_Position=uMVP*aPos; }",
                "precision mediump float; uniform vec3 uColor; uniform float uGrid; varying vec3 vWorld;" +
                "void main(){ vec3 c=uColor;" +
                " if(uGrid>0.5){ vec2 g=abs(fract(vWorld.xz*0.5)-0.5); float l=step(g.x,0.02)+step(g.y,0.02); c*=1.0-0.25*min(l,1.0); }" +
                " c*=1.0-0.35*clamp(length(vWorld.xz)/6.0,0.0,1.0); gl_FragColor=vec4(c,1.0); }");

        String quadVs = "attribute vec2 aPos; attribute vec2 aUV; uniform mat4 uMVP; varying vec2 vUV;" +
                "void main(){ vUV=aUV; gl_Position=uMVP*vec4(aPos,0.0,1.0); }";
        screenProg = program(quadVs,
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; uniform samplerExternalOES uTex; uniform mat4 uTexMat; varying vec2 vUV;" +
                "void main(){ gl_FragColor=texture2D(uTex,(uTexMat*vec4(vUV,0.0,1.0)).xy); }");
        textProg = program(quadVs,
                "precision mediump float; uniform sampler2D uTex; varying vec2 vUV;" +
                "void main(){ gl_FragColor=texture2D(uTex,vec2(vUV.x,1.0-vUV.y)); }");
        distProg = program(
                "attribute vec2 aPos; attribute vec2 aUV; varying vec2 vUV;" +
                "void main(){ vUV=aUV; gl_Position=vec4(aPos*2.0,0.0,1.0); }",
                "precision highp float; uniform sampler2D uTex; uniform float uK1; uniform float uK2; uniform float uAspect; varying vec2 vUV;" +
                "void main(){" +
                " float eye = vUV.x<0.5 ? 0.0 : 1.0;" +
                " vec2 local = vec2((vUV.x-eye*0.5)*2.0, vUV.y);" +
                " vec2 p = local*2.0-1.0; p.x*=uAspect;" +
                " float r2=dot(p,p); vec2 q=p*(1.0+uK1*r2+uK2*r2*r2); q.x/=uAspect;" +
                " vec2 s=q*0.5+0.5;" +
                " if(s.x<0.0||s.x>1.0||s.y<0.0||s.y>1.0){ gl_FragColor=vec4(0.0,0.0,0.0,1.0); return; }" +
                " gl_FragColor=texture2D(uTex,vec2((s.x+eye)*0.5,s.y)); }");

        buildGeometry();
        statusDirty = true;
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    private void buildGeometry() {
        float x = 4f, y0 = -1.6f, y1 = 1.4f, z = 4f;
        float[][] faces = {
                {-x, y0, -z, x, y0, -z, x, y0, z, -x, y0, z},        // floor
                {-x, y1, -z, x, y1, -z, x, y1, z, -x, y1, z},        // ceiling
                {-x, y0, -z, x, y0, -z, x, y1, -z, -x, y1, -z},      // back
                {-x, y0, z, x, y0, z, x, y1, z, -x, y1, z},          // front
                {-x, y0, -z, -x, y0, z, -x, y1, z, -x, y1, -z},      // left
                {x, y0, -z, x, y0, z, x, y1, z, x, y1, -z}           // right
        };
        int[] idx = {0, 1, 2, 0, 2, 3};
        float[] v = new float[6 * 6 * 3];
        int n = 0;
        for (float[] f : faces)
            for (int i : idx) {
                v[n++] = f[i * 3];
                v[n++] = f[i * 3 + 1];
                v[n++] = f[i * 3 + 2];
            }
        roomVb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        roomVb.put(v).position(0);

        float[] q = {
                -0.5f, -0.5f, 0f, 0f,
                0.5f, -0.5f, 1f, 0f,
                -0.5f, 0.5f, 0f, 1f,
                0.5f, 0.5f, 1f, 1f};
        quadVb = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVb.put(q).position(0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfW = width;
        surfH = height;
        fboW = width;
        fboH = height;
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
            GLES20.glDeleteRenderbuffers(1, new int[]{fboDepth}, 0);
        }
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        fboTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fboW, fboH, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        texParams(GLES20.GL_TEXTURE_2D);
        GLES20.glGenRenderbuffers(1, t, 0);
        fboDepth = t[0];
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, fboDepth);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, fboW, fboH);
        GLES20.glGenFramebuffers(1, t, 0);
        fbo = t[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, fboDepth);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private final float[] rs = new float[16], tmp = new float[16], ry = new float[16], head = new float[16];
    private final float[] tr = new float[16], view = new float[16], proj = new float[16], vp = new float[16];
    private final float[] model = new float[16], mvp = new float[16];

    @Override
    public void onDrawFrame(GL10 gl) {
        applyPendingConfig();
        Screen[] sc = screens;
        // Take EVERY queued frame out of the SurfaceTexture, so the newest one is shown and the
        // decoder is never blocked by a full buffer queue (that used to freeze the picture).
        for (Screen s : sc) {
            boolean updated = false;
            while (s.pending.get() > 0) {
                s.pending.decrementAndGet();
                try {
                    s.st.updateTexImage();
                    updated = true;
                } catch (Exception ignored) {}
            }
            if (updated) s.st.getTransformMatrix(s.texMat);
        }
        if (statusDirty) uploadStatus();

        // head orientation
        synchronized (rotLock) {
            System.arraycopy(rotSensor, 0, rs, 0, 16);
        }
        if (recenterPending) {
            yawOff = (float) Math.toDegrees(Math.atan2(-rs[2], -rs[6]));
            recenterPending = false;
        }
        Matrix.multiplyMM(tmp, 0, rs, 0, W_SCENE_TO_WORLD, 0);
        Matrix.setRotateM(ry, 0, yawOff, 0, 1, 0);
        Matrix.multiplyMM(head, 0, tmp, 0, ry, 0);

        // ---- scene into the offscreen texture, one half per eye ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, fboW, fboH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        for (int eye = 0; eye < 2; eye++) {
            GLES20.glViewport(eye * (fboW / 2), 0, fboW / 2, fboH);
            Matrix.setIdentityM(tr, 0);
            Matrix.translateM(tr, 0, eye == 0 ? IPD / 2f : -IPD / 2f, 0f, 0f);
            Matrix.multiplyMM(view, 0, tr, 0, head, 0);
            Matrix.perspectiveM(proj, 0, FOV, (fboW / 2f) / fboH, 0.1f, 30f);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
            drawRoom();
            if (sc.length == 0) drawStatus(); else drawScreens(sc);
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        // ---- lens distortion to the real screen ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfW, surfH);
        GLES20.glUseProgram(distProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(distProg, "uTex"), 0);
        float[] k = LENS[lensIdx];
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distProg, "uK1"), k[0]);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distProg, "uK2"), k[1]);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distProg, "uAspect"), (surfW / 2f) / surfH);
        bindQuad(distProg);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawRoom() {
        GLES20.glUseProgram(colorProg);
        int aPos = GLES20.glGetAttribLocation(colorProg, "aPos");
        GLES20.glEnableVertexAttribArray(aPos);
        roomVb.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, roomVb);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, vp, 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uModel"), 1, false, IDENT, 0);
        int uColor = GLES20.glGetUniformLocation(colorProg, "uColor");
        int uGrid = GLES20.glGetUniformLocation(colorProg, "uGrid");
        for (int i = 0; i < 6; i++) {
            GLES20.glUniform3fv(uColor, 1, ROOM_COLORS[i], 0);
            GLES20.glUniform1f(uGrid, i == 0 ? 1f : 0f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, i * 6, 6);
        }
    }

    private void drawScreens(Screen[] sc) {
        float d = dist;
        int n = sc.length;
        // angular position of every monitor; left monitor gets a positive angle
        float half = (float) Math.toDegrees(Math.atan2(SCREEN_W / 2f, d));
        float step = 2f * half + 1.5f;
        for (int i = 0; i < n; i++) {
            float angle = (n == 1) ? 0f : ((n - 1) / 2f - i) * step;
            float w = SCREEN_W;
            float h = SCREEN_W * sc[i].h / (float) Math.max(1, sc[i].w);

            // dark frame behind the picture
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, angle, 0, 1, 0);
            Matrix.translateM(model, 0, 0, 0, -d - 0.01f);
            Matrix.scaleM(model, 0, w + 0.08f, h + 0.08f, 1f);
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
            GLES20.glUseProgram(colorProg);
            int aPos = GLES20.glGetAttribLocation(colorProg, "aPos");
            GLES20.glEnableVertexAttribArray(aPos);
            quadVb.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadVb);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uModel"), 1, false, model, 0);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(colorProg, "uColor"), 0.03f, 0.03f, 0.03f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(colorProg, "uGrid"), 0f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // the picture
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, angle, 0, 1, 0);
            Matrix.translateM(model, 0, 0, 0, -d);
            Matrix.scaleM(model, 0, w, h, 1f);
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
            GLES20.glUseProgram(screenProg);
            bindQuad(screenProg);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProg, "uMVP"), 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProg, "uTexMat"), 1, false, sc[i].texMat, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sc[i].tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(screenProg, "uTex"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }

    private void drawStatus() {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 0, 0, -dist);
        Matrix.scaleM(model, 0, 1.6f, 0.4f, 1f);
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUseProgram(textProg);
        bindQuad(textProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(textProg, "uMVP"), 1, false, mvp, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, statusTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(textProg, "uTex"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void bindQuad(int prog) {
        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        int aUV = GLES20.glGetAttribLocation(prog, "aUV");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUV);
        quadVb.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadVb);
        quadVb.position(2);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quadVb);
    }

    // ===== screens / status =================================================================

    private void applyPendingConfig() {
        int[] w, h;
        CountDownLatch latch;
        synchronized (cfgLock) {
            w = pendW;
            h = pendH;
            latch = cfgLatch;
            pendW = null;
            pendH = null;
            cfgLatch = null;
        }
        if (w == null) return;

        Screen[] old = screens;
        screens = new Screen[0];
        for (Screen s : old) destroy(s);

        Screen[] fresh = new Screen[w.length];
        for (int i = 0; i < w.length; i++) {
            Screen s = new Screen();
            s.w = w[i];
            s.h = h[i];
            Matrix.setIdentityM(s.texMat, 0);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            s.tex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.tex);
            texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            s.st = new SurfaceTexture(s.tex);
            s.st.setDefaultBufferSize(s.w, s.h);
            s.st.setOnFrameAvailableListener(st -> s.pending.incrementAndGet());
            s.surface = new Surface(s.st);
            s.dec = new VideoDecoder(s.surface, s.w, s.h);
            s.dec.start();
            fresh[i] = s;
        }
        screens = fresh;
        if (latch != null) latch.countDown();
    }

    private void destroy(Screen s) {
        if (s.dec != null) s.dec.stop();
        if (s.surface != null) s.surface.release();
        if (s.st != null) s.st.release();
        GLES20.glDeleteTextures(1, new int[]{s.tex}, 0);
    }

    private void uploadStatus() {
        statusDirty = false;
        if (statusTex == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            statusTex = t[0];
        }
        Bitmap bmp = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF181818);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(46f);
        p.setTextAlign(Paint.Align.CENTER);
        String[] lines = statusText.split("\n");
        float y = 128f - (lines.length - 1) * 30f + 16f;
        for (String line : lines) {
            c.drawText(line, 512f, y, p);
            y += 60f;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, statusTex);
        texParams(GLES20.GL_TEXTURE_2D);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }

    // ===== helpers ==========================================================================

    private static void texParams(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) android.util.Log.e("VRCable", "shader error: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
