package com.vrcable;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Plays interleaved float32 stereo PCM with the lowest buffer Android allows. */
public class AudioPlayer {
    private final int sampleRate;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private Thread thread;

    public AudioPlayer(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "audio-player");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public void feed(byte[] pcm) {
        while (queue.size() > 8) queue.poll();   // never let audio lag behind
        queue.offer(pcm);
    }

    private void loop() {
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT);
            // about 30 ms of audio, but never below the system minimum
            int want = sampleRate * 2 * 4 * 30 / 1000;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(Math.max(min, want))
                    .build();
            track.play();
            float[] tmp = new float[0];
            while (running) {
                byte[] pcm = queue.poll(20, TimeUnit.MILLISECONDS);
                if (pcm == null) continue;
                int n = pcm.length / 4;
                if (tmp.length < n) tmp = new float[n];
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(tmp, 0, n);
                track.write(tmp, 0, n, AudioTrack.WRITE_BLOCKING);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            android.util.Log.e("VRCable", "audio error", e);
        } finally {
            try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) {}
        }
    }
}
