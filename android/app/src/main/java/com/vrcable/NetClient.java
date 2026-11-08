package com.vrcable;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Connects to the laptop through "adb reverse" (127.0.0.1:5599) and reads packets:
 * [type:1][length:4 big-endian][payload]
 *   0x01 CONFIG : u8 count, then per monitor u16 width, u16 height
 *   0x02 AUDIO CONFIG : u32 sampleRate
 *   0x10+i VIDEO of monitor i : one H.264 access unit (Annex B)
 *   0x20 AUDIO : float32 stereo interleaved little-endian
 */
public class NetClient {
    public static final int PORT = 5599;

    private final VRRenderer renderer;
    private volatile boolean running;
    private Thread thread;
    private AudioPlayer audio;

    public NetClient(VRRenderer renderer) {
        this.renderer = renderer;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "net");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            renderer.setStatus("Очікую підключення до ноутбука...\nЗапусти VR Cable на Windows");
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(1 << 20);
                DataInputStream in = new DataInputStream(s.getInputStream());
                renderer.setStatus("Підключено, чекаю на відео...");
                readLoop(in);
            } catch (Exception e) {
                // not connected yet, or the connection dropped
            }
            renderer.clearScreens();
            if (audio != null) { audio.stop(); audio = null; }
            try { Thread.sleep(700); } catch (InterruptedException ie) { return; }
        }
    }

    private void readLoop(DataInputStream in) throws Exception {
        byte[] head = new byte[5];
        while (running) {
            in.readFully(head);
            int type = head[0] & 0xFF;
            int len = ((head[1] & 0xFF) << 24) | ((head[2] & 0xFF) << 16) | ((head[3] & 0xFF) << 8) | (head[4] & 0xFF);
            if (len < 0 || len > 16 * 1024 * 1024) throw new java.io.IOException("bad packet");
            byte[] p = new byte[len];
            in.readFully(p);

            if (type == 0x01) {
                int n = p[0] & 0xFF;
                int[] w = new int[n], h = new int[n];
                for (int i = 0; i < n; i++) {
                    w[i] = ((p[1 + i * 4] & 0xFF) << 8) | (p[2 + i * 4] & 0xFF);
                    h[i] = ((p[3 + i * 4] & 0xFF) << 8) | (p[4 + i * 4] & 0xFF);
                }
                renderer.configureAndWait(w, h);
            } else if (type == 0x02) {
                int rate = ((p[0] & 0xFF) << 24) | ((p[1] & 0xFF) << 16) | ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
                if (audio != null) audio.stop();
                audio = new AudioPlayer(rate);
                audio.start();
            } else if (type >= 0x10 && type < 0x20) {
                renderer.feedVideo(type - 0x10, p);
            } else if (type == 0x20) {
                if (audio != null) audio.feed(p);
            }
        }
    }
}
