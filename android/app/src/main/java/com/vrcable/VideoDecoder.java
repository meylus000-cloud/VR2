package com.vrcable;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** H.264 decoder in low-latency mode that renders straight to a Surface. Restarts itself after errors. */
public class VideoDecoder {
    private final Surface surface;
    private final int width, height;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private volatile boolean needKey = true;
    private Thread thread;

    public VideoDecoder(Surface surface, int width, int height) {
        this.surface = surface;
        this.width = width;
        this.height = height;
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "video-decoder");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(300); } catch (InterruptedException ignored) {}
        }
    }

    /** Called from the network thread with one complete access unit. */
    public void feed(byte[] au) {
        if (queue.size() > 4) {          // decoder is behind: drop everything, wait for next keyframe
            queue.clear();
            needKey = true;
        }
        if (needKey) {
            if (!hasNal(au, 5)) return;   // only start again from an IDR frame
            needKey = false;
        }
        queue.offer(au);
    }

    private static boolean hasNal(byte[] d, int type) {
        for (int i = 0; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1 && (d[i + 3] & 0x1F) == type) return true;
        }
        return false;
    }

    /** Keeps a codec alive; if it fails for any reason a new one is created and we resync on the next IDR. */
    private void run() {
        while (running) {
            try {
                decode();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                android.util.Log.e("VRCable", "decoder error, restarting", e);
            }
            if (!running) return;
            queue.clear();
            needKey = true;
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }
    }

    private void decode() throws Exception {
        MediaCodec codec = null;
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 120);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);   // big keyframes must fit
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(fmt, surface, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                byte[] au = queue.poll(4, TimeUnit.MILLISECONDS);
                if (au != null) {
                    int in = codec.dequeueInputBuffer(5000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        b.clear();
                        if (b.remaining() >= au.length) {
                            b.put(au);
                            codec.queueInputBuffer(in, 0, au.length, System.nanoTime() / 1000, 0);
                        } else {
                            codec.queueInputBuffer(in, 0, 0, 0, 0);   // frame too big: skip it, resync later
                            needKey = true;
                        }
                    } else {
                        needKey = true;   // could not queue: resync on next IDR
                    }
                }
                int out;
                while ((out = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    codec.releaseOutputBuffer(out, true);   // render immediately
                }
            }
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
        }
    }
}
